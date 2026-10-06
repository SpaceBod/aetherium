package dev.spacebod.aetherium.shaders.gui;

import dev.spacebod.aetherium.shaders.config.ShaderPackSettings;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * A shader pack loading in the background, shown in game as a small glass pill in the top-right corner: the settings
 * screen's spinner, the pack's name and how far the load has got as a percentage. It fades in when
 * a load starts and, once the pack is ready, shows a green dot for a moment before fading out (red when it failed). It
 * is hidden with the HUD and while the shader screen, which shows the load itself, is open. Stacked with the other
 * corner panels by {@code HudCorner}.
 */
public final class PackLoadIndicator {
	private static final int MARGIN = 6;
	private static final int HEIGHT = 16;
	private static final long FADE_MS = 220;
	private static final long DONE_MS = 1600;
	private static final long FAILED_MS = 4000;

	private enum State { HIDDEN, LOADING, DONE, FAILED }

	private static State state = State.HIDDEN;
	private static long since;
	private static @Nullable String pack;
	private static float progress;

	private PackLoadIndicator() {
	}

	/** End of the HUD, with its top at {@code top} (right-aligned); returns the y below it, or {@code top} when hidden. */
	public static int draw(GuiGraphicsExtractor g, int top) {
		Minecraft mc = Minecraft.getInstance();
		update(ShaderPackEngine.get());
		if (state == State.HIDDEN || mc.gui.hud.isHidden() || mc.gui.screen() instanceof ShaderPackScreen || pack == null) {
			return top;
		}
		long now = Util.getMillis();
		long age = now - since;
		float alpha = switch (state) {
			case LOADING -> Math.min(1.0f, age / (float) FADE_MS);
			case DONE -> fadeOut(age, DONE_MS);
			case FAILED -> fadeOut(age, FAILED_MS);
			default -> 0.0f;
		};
		if (alpha <= 0.0f) {
			if (state != State.LOADING) {
				state = State.HIDDEN;
			}
			return top;
		}
		Font font = mc.font;
		String name = Theme.fit(font, ShaderPackSettings.displayName(pack), 140);
		// While loading, the percentage after the name, in a slot as wide as "100%" so the pill does not twitch.
		boolean loading = state == State.LOADING;
		int percentSlot = loading ? font.width("100%") + 6 : 0;
		int width = 8 + 9 + 5 + font.width(name) + percentSlot + 8;
		int x1 = mc.getWindow().getGuiScaledWidth() - MARGIN;
		int x0 = x1 - width;
		int y0 = top;
		int y1 = y0 + HEIGHT;
		Theme.round(g, x0, y0, x1, y1, faded(Theme.OVERLAY, alpha));
		Theme.roundOutline(g, x0, y0, x1, y1, faded(Theme.BORDER, alpha));
		int cx = x0 + 8 + 4;
		int cy = y0 + HEIGHT / 2;
		switch (state) {
			case LOADING -> {
				Theme.spinner(g, cx, cy, 3, faded(Theme.ACCENT_TEXT, alpha));
				String percent = Math.round(Math.max(0.0f, Math.min(1.0f, progress)) * 100) + "%";
				g.text(font, percent, x1 - 8 - font.width(percent), y0 + (HEIGHT - 8) / 2 + 1, faded(Theme.ACCENT_TEXT, alpha), false);
			}
			case DONE -> dot(g, cx, cy, faded(Theme.GOOD, alpha));
			case FAILED -> dot(g, cx, cy, faded(Theme.BAD, alpha));
			default -> {
			}
		}
		g.text(font, name, x0 + 8 + 9 + 5, y0 + (HEIGHT - 8) / 2 + 1, faded(state == State.FAILED ? Theme.BAD : Theme.TEXT_DIM, alpha), false);
		return y1 + 4;
	}

	/** Follows the engine: a load starting, progressing, ending ready or failed. */
	private static void update(ShaderPackEngine engine) {
		ShaderPackEngine.LoadProgress load = engine.loadProgress();
		long now = Util.getMillis();
		if (load != null) {
			if (state != State.LOADING) {
				state = State.LOADING;
				since = now;
			}
			pack = load.pack();
			progress = (float) load.overall();
		} else if (state == State.LOADING) {
			state = engine.running() ? State.DONE : State.FAILED;
			since = now;
		}
	}

	private static float fadeOut(long age, long shown) {
		if (age < shown) {
			return 1.0f;
		}
		return Math.max(0.0f, 1.0f - (age - shown) / (float) FADE_MS);
	}

	private static void dot(GuiGraphicsExtractor g, int cx, int cy, int color) {
		g.fill(cx - 2, cy - 3, cx + 2, cy + 3, color);
		g.fill(cx - 3, cy - 2, cx + 3, cy + 2, color);
	}

	private static int faded(int color, float alpha) {
		int a = Math.round((color >>> 24) * alpha);
		return a << 24 | color & 0x00FFFFFF;
	}
}
