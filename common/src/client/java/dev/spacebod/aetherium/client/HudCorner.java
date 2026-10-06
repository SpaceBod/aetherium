package dev.spacebod.aetherium.client;

import dev.spacebod.aetherium.client.zoom.ZoomHud;
import dev.spacebod.aetherium.shaders.gui.PackLoadIndicator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Aetherium's panels in the top-right corner of the HUD, stacked top to bottom (the shader pack loading, then the zoom
 * readout when it is placed there), below the status effect icons the game draws in the same corner.
 */
public final class HudCorner {
	private static final int MARGIN = 6;

	private HudCorner() {
	}

	/** End of the HUD: every corner panel, over everything else. */
	public static void draw(GuiGraphicsExtractor g) {
		int rows = effectRows(Minecraft.getInstance());
		int start = MARGIN + rows * 26;
		int y = PackLoadIndicator.draw(g, start);
		// The zoom readout goes just under the top edge unless something is above it.
		ZoomHud.draw(g, rows == 0 && y == start ? 0 : y);
	}

	/** Rows of status effect icons the game draws in the corner (beneficial, then harmful). */
	private static int effectRows(Minecraft mc) {
		if (mc.player == null) {
			return 0;
		}
		boolean good = false;
		boolean bad = false;
		for (MobEffectInstance effect : mc.player.getActiveEffects()) {
			if (!effect.showIcon()) {
				continue;
			}
			if (effect.getEffect().value().isBeneficial()) {
				good = true;
			} else {
				bad = true;
			}
		}
		return (good ? 1 : 0) + (bad ? 1 : 0);
	}
}
