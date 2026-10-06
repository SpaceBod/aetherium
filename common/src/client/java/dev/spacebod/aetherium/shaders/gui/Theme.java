package dev.spacebod.aetherium.shaders.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Util;

/** Colours and drawing helpers of the Aetherium Shaders screens: dark glass panels, one accent, soft corners. */
public final class Theme {
	public static final int PANEL = 0xE6101217;
	public static final int PANEL_RAISED = 0xF01A1D24;
	/** Panels drawn over the game (the HUD's corner panels): the world shows through. */
	public static final int OVERLAY = 0x9E101217;
	public static final int CARD = 0x22FFFFFF;
	public static final int CARD_HOVER = 0x33FFFFFF;
	public static final int BORDER = 0x26FFFFFF;
	public static final int TEXT = 0xFFE9EBF1;
	public static final int TEXT_DIM = 0xFFA3A9B7;
	public static final int TEXT_MUTED = 0xFF6D7382;
	public static final int ACCENT = 0xFF8B7CFF;
	public static final int ACCENT_SOFT = 0x558B7CFF;
	public static final int ACCENT_TEXT = 0xFFB8AEFF;
	public static final int TRACK = 0xFF343844;
	public static final int GOOD = 0xFF5BD69B;
	public static final int WARN = 0xFFF2B35B;
	public static final int BAD = 0xFFFF6B6B;

	private Theme() {
	}

	/** A filled rectangle with 1-pixel cut corners (reads as rounded at GUI scale). */
	public static void round(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		if (x1 - x0 < 3 || y1 - y0 < 3) {
			g.fill(x0, y0, x1, y1, color);
			return;
		}
		g.fill(x0 + 1, y0, x1 - 1, y0 + 1, color);
		g.fill(x0, y0 + 1, x1, y1 - 1, color);
		g.fill(x0 + 1, y1 - 1, x1 - 1, y1, color);
	}

	/** A rounded outline. */
	public static void roundOutline(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0 + 1, y0, x1 - 1, y0 + 1, color);
		g.fill(x0 + 1, y1 - 1, x1 - 1, y1, color);
		g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
		g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
	}

	public static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		round(g, x0, y0, x1, y1, PANEL);
		roundOutline(g, x0, y0, x1, y1, BORDER);
	}

	/** Text cut to {@code width} pixels with an ellipsis. */
	public static String fit(Font font, String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		String cut = font.plainSubstrByWidth(text, Math.max(0, width - font.width("…")));
		return cut + "…";
	}

	/** {@code text} word-wrapped to {@code width}, at most {@code maxLines} lines; the last kept line ends in an ellipsis if text was cut. */
	public static List<String> wrap(Font font, String text, int width, int maxLines) {
		List<String> lines = new ArrayList<>();
		for (FormattedText part : font.getSplitter().splitLines(text, Math.max(1, width), Style.EMPTY)) {
			lines.add(part.getString());
		}
		if (lines.size() <= maxLines) {
			return lines;
		}
		List<String> kept = new ArrayList<>(lines.subList(0, Math.max(0, maxLines)));
		if (!kept.isEmpty()) {
			int last = kept.size() - 1;
			String cut = fit(font, kept.get(last) + " " + lines.get(maxLines), width);
			kept.set(last, cut.endsWith("…") ? cut : fit(font, kept.get(last) + "…", width));
		}
		return kept;
	}

	/** A ring of eight dots around ({@code cx}, {@code cy}), the bright one going round (one turn per 0.8 s). */
	public static void spinner(GuiGraphicsExtractor g, int cx, int cy, int radius, int color) {
		int head = (int) (Util.getMillis() / 100 % 8);
		for (int i = 0; i < 8; i++) {
			double angle = Math.PI * 2 * i / 8 - Math.PI / 2;
			int x = cx + (int) Math.round(Math.cos(angle) * radius);
			int y = cy + (int) Math.round(Math.sin(angle) * radius);
			int behind = Math.floorMod(head - i, 8);
			int alpha = Math.max(0x30, 0xFF - behind * 0x28);
			g.fill(x - 1, y - 1, x + 1, y + 1, (color & 0x00FFFFFF) | alpha << 24);
		}
	}

	/** A bar filled to {@code fraction} (0..1); below 0, a segment sliding back and forth (no known progress). */
	public static void progress(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float fraction) {
		g.fill(x0, y0, x1, y1, TRACK);
		int width = x1 - x0;
		if (fraction >= 0) {
			g.fill(x0, y0, x0 + Math.round(width * Math.min(1, fraction)), y1, ACCENT);
			return;
		}
		int segment = Math.max(8, width / 4);
		double t = (Math.sin(Util.getMillis() / 450.0) + 1) / 2;
		int start = x0 + (int) ((width - segment) * t);
		g.fill(start, y0, start + segment, y1, ACCENT);
	}

	public static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
		int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
		return (int) (aa + (ba - aa) * t) << 24 | (int) (ar + (br - ar) * t) << 16 | (int) (ag + (bg - ag) * t) << 8 | (int) (ab + (bb - ab) * t);
	}
}
