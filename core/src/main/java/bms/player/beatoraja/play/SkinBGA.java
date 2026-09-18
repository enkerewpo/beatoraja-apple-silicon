package bms.player.beatoraja.play;

import bms.player.beatoraja.Config;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.BMSPlayerMode;
import bms.player.beatoraja.PlayerResource;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;
import bms.player.beatoraja.skin.SkinObject;
import bms.player.beatoraja.skin.StretchType;

import static bms.player.beatoraja.skin.SkinProperty.*;

/**
 * BGAオブジェクト
 * 
 * @author exch
 */
public class SkinBGA extends SkinObject {
	
	private BMSPlayer player;
	private long time;

	public SkinBGA(int bgaExpand) {
		switch (bgaExpand) {
		case Config.BGAEXPAND_FULL:
			setStretch(StretchType.STRETCH);
			break;
		case Config.BGAEXPAND_KEEP_ASPECT_RATIO:
			setStretch(StretchType.KEEP_ASPECT_RATIO_FIT_INNER);
			break;
		case Config.BGAEXPAND_OFF:
			setStretch(StretchType.KEEP_ASPECT_RATIO_NO_EXPANDING);
			break;
		}
	}
	
	@Override
	public void prepare(long time, MainState state) {
		if(player == null) {
			player = (BMSPlayer)state;
		}
		this.time = time;
		super.prepare(time, state);
		// 练习模式：本层还要负责画参数面板，所以即使图层条件不成立也要画。
		// （dst 上通常挂着 op = {41} OPTION_BGAON，而 BGA_AUTO 模式下
		//   BMSResource 只在 AUTOPLAY/REPLAY 时把 bgaon 置真 —— 练习与普通游玩都会是假，
		//   面板就会整块消失，表现为"练习模式里看不到参数调整"。）
		if (player.resource.getPlayMode().mode == BMSPlayerMode.Mode.PRACTICE) {
			draw = true;
		}
		if(draw && player.resource.getBGAManager() != null) {
			final int s = player.getState();
			player.resource.getBGAManager().prepareBGA(
					s == BMSPlayer.STATE_PRELOAD || s == BMSPlayer.STATE_PRACTICE || s == BMSPlayer.STATE_READY ? -1
							: player.timer.getNowTime(TIMER_PLAY));
		}
	}

	public void draw(SkinObjectRenderer sprite) {
		final PlayerResource resource = player.resource;
		if (resource.getPlayMode().mode == BMSPlayerMode.Mode.PRACTICE) {
			// 练习模式的参数面板（START TIME / GAUGE / ... 与 note 密度图）画在本层位置：
			// BGA 之上、游玩层（lane / notes / keybeam / judge）之下。
			// 触摸版皮肤例外：它的轨道背景是一整块不透明矩形，画在这里会被整块盖住，
			// 改由 BMSPlayer#drawPracticeOverlay 叠加到最上层（50% 透明 + 不画密度图）。
			if (!player.getPracticeConfiguration().isOverlayOnTop(player.getSkin())) {
				player.getPracticeConfiguration().draw(region, sprite, time, player);
			}
		} else if (resource.getBGAManager() != null) {
			resource.getBGAManager().drawBGA(this,sprite,region);
		}		
	}

	@Override
	public void dispose() {

	}
}
