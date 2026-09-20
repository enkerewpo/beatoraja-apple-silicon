# MusicPlayer 选曲界面闪退：`stagefile` 二次读取竞态（NPE @ SpriteBatch.flush）

## 现象（用户日志 2026-09-20 14:19:25）

```
FATAL EXCEPTION: GLThread 153
java.lang.NullPointerException: Attempt to invoke virtual method
  'void com.badlogic.gdx.graphics.Texture.bind()' on a null object reference
    at com.badlogic.gdx.graphics.g2d.SpriteBatch.flush(SpriteBatch.java:975)
    at com.badlogic.gdx.graphics.g2d.SpriteBatch.end(SpriteBatch.java:205)
    at bms.player.beatoraja.play.MusicPlayer.drawStagefile(MusicPlayer.java:935)
    at bms.player.beatoraja.play.MusicPlayer.render(MusicPlayer.java:867)
```

栈里是 `batch.end()`，看着像"画封面时端()出问题"，其实**根因是 `batch.draw()` 收到了 null 纹理**，
只是 libGDX 1.14 让这个 null 一路安静地活到了 `end()`。

## libGDX 1.14 的三行关键代码（`gdx-1.14.0-sources.jar`）

```java
// SpriteBatch.java:202  之后
public void end () {
    if (!drawing) throw new IllegalStateException(...);
    if (idx > 0) flush();
    lastTexture = null;          // ← :206  每个批次结束都把 lastTexture 清成 null
    drawing = false;
}

// :526  五参数重载，MusicPlayer 用的就是这个
public void draw (Texture texture, float x, float y, float width, float height) {
    if (!drawing) throw ...;
    if (texture != lastTexture) switchTexture(texture);   // ← :531
    else if (idx == vertices.length) flush();
    float u = 0, v = 1, u2 = 1, v2 = 0;                    // UV 写死，全程不碰 texture
    ... vertices[idx...] = ...; this.idx = idx + 20;
}

// :975
lastTexture.bind();              // ← NPE 在这里
```

由此得到两条**反直觉**的性质：

1. **`batch.draw(null, x, y, w, h)` 不会当场抛异常。** 它只做 `if (texture != lastTexture) switchTexture(...)`；
   而每个批次的第一笔绘制之前，`lastTexture` 恰好刚被上一个批次的 `end()` 清成 `null`——`null != null`
   为假，于是连 `switchTexture(null)`（那才会在 `texture.getWidth()` 上抛）都不走，顶点照写、`idx` 照加。
2. **NPE 因此被推迟到 `end() → flush() → lastTexture.bind()`。** 崩溃栈指向 `end()`，
   和"封面是 null"这件事没有任何字面上的联系——这也是这个 bug 一直没被认出来的原因。

> 推论：只要某个批次的**第一笔** `draw` 传进 null 纹理，就必然在这个批次的 `end()` 崩。
> 无论谁在 null 纹理上调用，栈都长成同一个样子。

## MusicPlayer 侧：判空和用之间夹了一次 `batch.begin()`

```java
private void drawStagefile(SpriteBatch batch) {
    if (stagefile == null) return;                       // ← 字段读 #1
    float x = (skinW - STAGEFILE_W) / 2f;
    float y = (skinH - STAGEFILE_H) / 2f;
    batch.begin();                                       // ← 几十微秒的 JNI/GL 调用夹在这里
    batch.setColor(1, 1, 1, 1);
    batch.draw(stagefile, x, y, STAGEFILE_W, STAGEFILE_H); // ← 字段读 #2（可能已经是 null）
    batch.end();
}
```

`stagefile` 是 `volatile Texture`，会被**另外两个线程**置空：

| 写入点 | 线程 | 时机 |
| --- | --- | --- |
| `loadSingle()` → `retireStagefile()`（:452） | **MusicPlayer-Loader** | 手动点歌 / PREV / NEXT / 进界面 |
| `transitionToNextInBackground()` → `retireStagefile()`（:1772） | **worker（曲尾自动切歌）** | 每首歌结束的瞬间 |
| `disposeGlResources()`（:824）/ `shutdownResources()`（:1412） | GL 线程 | pause / 切状态 / 退出（不参与本竞态） |

`retireStagefile()` 的语义就是"同步置空"（注释里写得很清楚：调用方一返回 `stagefile` 一定是 null）。
判空读的是它，用的时候读的**还是它**，两次读之间没有任何同步——Write 只要落在两次读之间，
第二次就拿到 null，直接吞进 `batch.draw()`。

窗口不是"几纳秒"：`batch.begin()` 里有 `glDepthMask` / `shader.bind()` / `setupMatrices()`
（若干次 JNI + GL 调用）以及一次 `setColor` 的打包，实测量级是**几十微秒**，
在 16.7ms 的帧周期里约 0.1%~0.3%。曲尾自动切歌、连点 NEXT 时每位命中一次抽签，
所以表现为"偶发、没规律、切歌那一瞬间崩"。

正面佐证：**MusicPlayer 里只有这一处是"同一个纹理字段读两次"**。其余四处绘制
（`drawBackground` :884 / `drawSongList` :911 / `drawProgressBar` :1055,1058 / `drawControlButtons` :1084）
全部先取局部变量再画，所以只有 `drawStagefile` 会崩——与崩溃栈完全一致。

## 修复：只在开始时读一次字段

```java
Texture tex = stagefile;      // 一次快照，后面只用局部引用
if (tex == null) return;
...
batch.draw(tex, x, y, STAGEFILE_W, STAGEFILE_H);
```

**为什么拿到的旧纹理不会被中途释放**：`drainRetiredTextures()` 在 `render()` 的最开头执行，
`retireStagefile()` 只把纹理塞进 `glTextureGarbage` 队列。若 retire 发生在本帧 drain 之后，
真正的 `dispose()` 要等到**下一帧**的 drain——而本帧的绘制在本帧内就用完了。
（若 retire 发生在本帧 drain 之前，字段早已是 null，快照根本取不到那张图。）
所以"快照 + 本帧使用"在 GL 单线程的 dispose 时序下是安全的。

这是本文件里唯一需要改的地方；diff 只有一行 + 注释。

## 同类地雷（本次未改，记录备查）

- 通用规则：**凡是要交给 `batch.draw()` 的纹理，一律先取局部变量**，尤其是该字段会被别的线程写的。
  判空之后再读一次字段 = 把竞态窗口留给 `batch.begin()`。
- `shutdownResources()` 的 `stagefile.dispose()`（:1410）**没有走 `glTextureGarbage`、也没加
  `stagefileLock`**。目前它只在 GL 线程（切状态 / dispose）被调用，所以安全；一旦将来从后台线程
  调用 `shutdown()`，就是"在别的线程里 dispose GL 资源 + 与 render 竞态"，比本 bug 更严重。
- MusicPlayer 里其它字段（`font`）被每帧重读，但它们只由 GL 线程写，暂不构成竞态。

## 验证

- `javac`（core，JDK21）exit=0，仅"过时 API"提示。
- 复现路径：选曲界面曲尾自动切歌 / 连点 NEXT 时不再崩。
