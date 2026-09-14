# Song 数据库：修改谱面后 update 出现重复条目

## 现象

在一个歌曲文件夹里修改了某个谱面文件，进游戏执行 update song 后，
该谱面在选曲列表里出现**两个一模一样的条目**。反复修改会继续堆积。

唯一的恢复办法是：把整个歌曲文件夹删掉 → update（这一步是整目录全量删除）→
重新放入 → 再 update。之后才恢复正常。

## 根因

`song` 表的主键是 **`sha256`**，不是 `path`：

```sql
CREATE TABLE song (md5 TEXT, sha256 TEXT, ..., path TEXT, ..., PRIMARY KEY (sha256))
```

而 `sha256` 是**谱面文件内容的哈希**。修改谱面 → 内容变 → `sha256` 变。

写入用的是：

```java
db.insertWithOnConflict("song", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
```

`CONFLICT_REPLACE` 只在**主键冲突**（即 sha256 相同）时替换。sha256 变了就命中不了，
于是直接 **INSERT 一条新行**。而同 `path` 的旧行（旧 sha256）谁也不会去删：

- `processBmsFileParallel` / `processBmsFile` 的增量检查用 `SELECT date FROM song WHERE path = ?`，
  只用来决定"要不要重新解码"，没有清理语义；
- 扫描后的 **Deletion Sync** 判据是"磁盘上这个文件还在不在"——
  文件当然还在，`path` 也被 `seenThisScan` 标记过，所以旧行被判定为"仍然有效"。

结果：同一份谱面（同一个 path）在库里留下两条记录，选曲列表就显示出两个。

### 为什么"删掉整个文件夹再 update"能恢复

删掉文件夹后，两条记录的 `path` 都指向不存在的文件 → Deletion Sync 把它们**一起删掉**。
重新放入再 update 时库里没有旧记录，自然只有一条。

这正好反过来印证了根因：问题不在"写入了两条"，而在"旧的那条删不掉"。

## 修复

文件：`android/src/main/java/bms/player/beatoraja/song/AndroidSQLiteSongDatabaseAccessor.java`

### 1. 写入前按 path 清理陈旧记录（治本）

新增 `deleteStaleSongByPath(db, path, sha256)`，在两个写入点插入前调用：

```java
deleteStaleSongByPath(db, pathName, songData.getSha256());   // processBmsFile（串行扫描）
deleteStaleSongByPath(db, songData.getPath(), songData.getSha256()); // insertSongData（并行扫描）
```

删除条件是 `path = ? AND sha256 <> ?`，保证**一个 path 恒定只对应一条记录**，
谱面怎么改都不会堆积。删除与插入在同一个事务里（串行路径按文件夹开事务、
并行路径按 100 条批量开事务），不会出现中间态。

`setSongDatas()`（favorite/tag 更新）不用改——它按 sha256 覆写，不改变 path 归属。

### 2. 扫描结束后按 path 去重（清理存量）

只改写入点救不了**已经进库**的重复条目，所以在 `updateSongDatas` 末尾加了
`dedupeSongByPath(db)`：

```sql
SELECT path FROM song WHERE path IS NOT NULL AND path <> '' GROUP BY path HAVING COUNT(*) > 1
```

对每个重复 path 只保留 `adddate` 最大的一条（同秒取 `rowid` 最大的，即最后写入的、
对应磁盘当前内容的那条），其余删除。

两个实现细节：

- **不能在 DELETE 的 WHERE 里对 `song` 表做子查询**。SQLite 边删边求值，
  保留行一旦先被删掉，后续子查询会返回别的值，可能把整个 path 删光。
  所以先 `SELECT rowid ... LIMIT 1` 取出要保留的行，再 `DELETE ... WHERE rowid <> ?`。
- 用 `rowid` 而不是 `sha256` 做排除：`sha256` 是 TEXT 主键，理论上允许 NULL，
  `NULL <> 'x'` 求值为 NULL，那一行删不掉。

去重是**全库**的，不局限于本次扫描路径，所以任何时候 update 都会顺带把历史重复清干净。

### 3. song.path 索引

```sql
CREATE INDEX IF NOT EXISTS idx_song_path ON song(path)
```

增量检查每个文件都要 `WHERE path = ?` 查一次，没有索引就是全表扫描；
按 path 的清理和去重同样依赖它。语句幂等，放在 `updateSongDatas` 建表之后。

## 已知副作用

谱面改动后 sha256 必然变化，`score` 表里旧 sha256 的成绩不再关联到新记录。
这是 beatoraja 的既有语义（改谱即视为新谱面，成绩需要重打），本次未改动。

## 验证

1. 选一个文件夹，改动其中一个 `.bms`（哪怕只改一个音符或标题）→ update song
   → 选曲列表里该谱面应只有**一个**条目。
2. 再改一次 → update → 仍然只有一个。
3. 对已有重复的库执行一次 update → logcat 出现
   `dedupeSongByPath: removed N stale records from M duplicated paths`，
   N 为清理掉的重复条目数，选曲列表重复项消失。
4. update 耗时应不高于改动前（path 索引会加速增量检查）。
