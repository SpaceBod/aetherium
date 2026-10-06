package dev.spacebod.aetherium.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spacebod.aetherium.Aetherium;
import dev.spacebod.aetherium.client.gpu.VulkanAccess;
import dev.spacebod.aetherium.shaders.gui.ShaderPackScreen;
import dev.spacebod.aetherium.shaders.gui.Theme;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Aetherium's own settings, opened from a button in vanilla's Video Settings (nothing vanilla already offers is
 * repeated here). Pages on the left (optimisations, shaders, advanced); on the right the page's settings
 * in titled groups, a description panel for the hovered one (what it does, its cost, whether it needs a restart, its
 * default) and Apply / Undo / Done.
 * <ul>
 *   <li>changes are staged (outlined) until Apply; Done and Esc apply too, so nothing typed is lost;</li>
 *   <li>search across every page; a marker on settings changed from their default, middle-click resets one;</li>
 *   <li>settings that need a restart are listed in a banner once applied;</li>
 *   <li>click / right-click / Shift+scroll step values, sliders drag, hold Tab to look at the world behind.</li>
 * </ul>
 */
public final class AetheriumSettingsScreen extends Screen implements SettingsCatalog.Host {
	private static final int MARGIN = 10;
	private static final int ROW = 22;
	private static final int GAP = 3;
	private static final int HEADER = 18;
	private static int lastPage;

	private final @Nullable Screen parent;
	private final SettingsCatalog catalog;
	private final List<Setting.Page> pages;
	private int page;
	private final Map<Setting<?>, Object> pending = new IdentityHashMap<>();
	private @Nullable EditBox search;
	private double scroll;
	private double contentHeight;
	private boolean peek;
	private @Nullable String toast;
	private int toastColor = Theme.TEXT;
	private long toastUntil;
	private final Map<Setting<?>, Float> knobs = new IdentityHashMap<>();
	private @Nullable Setting<?> dragging;
	private int dragX0;
	private int dragX1;
	private float ui = 1;
	private int vw;
	private int vh;

	private record Hotspot(int x0, int y0, int x1, int y1, Runnable left, @Nullable Runnable right, @Nullable Runnable middle, @Nullable Setting<?> tag) {
		boolean contains(double x, double y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	/** One line of the list: a group title or a setting (with the page it lives on, shown while searching). */
	private record Line(@Nullable String header, @Nullable Setting<?> setting, @Nullable String where) {
	}

	private final List<Hotspot> hotspots = new ArrayList<>();
	private @Nullable Setting<?> hovered;
	private int listY0;
	private int listY1;
	private int sideX1;

	public AetheriumSettingsScreen(@Nullable Screen parent) {
		super(Component.literal("Aetherium Settings"));
		this.parent = parent;
		this.catalog = new SettingsCatalog(this);
		this.pages = catalog.pages();
		this.page = Math.min(lastPage, pages.size() - 1);
	}

	@Override
	protected void init() {
		String query = search == null ? "" : search.getValue();
		search = new EditBox(minecraft.font, 0, 0, 120, 14, Component.literal("Search settings"));
		search.setBordered(false);
		search.setMaxLength(64);
		search.setHint(Component.literal("Search settings…").withStyle(s -> s.withColor(Theme.TEXT_MUTED)));
		search.setValue(query);
		search.setResponder(q -> scroll = 0);
		addRenderableWidget(search);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// In a world, no blur: changes are visible behind the menu.
		if (minecraft.level == null) {
			super.extractBackground(g, mouseX, mouseY, a);
		}
	}

	// ================================================================ host

	@Override
	public Object value(Setting<?> setting) {
		return pending.containsKey(setting) ? pending.get(setting) : setting.applied();
	}

	@Override
	public void stage(Setting<?> setting, Object value) {
		if (setting.unavailableReason() != null && !Objects.equals(value, setting.applied())) {
			showToast(setting.unavailableReason(), Theme.WARN);
			return;
		}
		if (setting.isImmediate()) {
			pending.remove(setting);
			commit(List.of(Map.entry(setting, value)));
			showToast(setting.name + ": " + setting.label(setting.applied()), Theme.TEXT_DIM);
			return;
		}
		if (Objects.equals(value, setting.applied())) {
			pending.remove(setting);
		} else {
			pending.put(setting, value);
		}
	}

	@Override
	public void openShaderPacks() {
		minecraft.gui.setScreen(new ShaderPackScreen(this));
	}

	@Override
	public void toast(String text, int color) {
		showToast(text, color);
	}

	// ================================================================ rendering

	/** Same sizing as the shader pack screen: one menu scale step per 360 window pixels, never above the GUI scale. */
	private void updateScale() {
		var window = minecraft.getWindow();
		float guiScale = (float) window.getGuiScale();
		int target = Math.max(1, Math.min((int) guiScale, window.getHeight() / 360));
		ui = target / guiScale;
		vw = Math.round(width / ui);
		vh = Math.round(height / ui);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		hotspots.clear();
		hovered = null;
		pending.entrySet().removeIf(e -> Objects.equals(e.getValue(), e.getKey().applied()));
		Font font = minecraft.font;
		updateScale();
		int mx = (int) (mouseX / ui), my = (int) (mouseY / ui);
		g.pose().pushMatrix();
		g.pose().scale(ui, ui);
		if (peek) {
			String hint = Theme.fit(font, "Previewing — release Tab to return", vw - 40);
			int w = font.width(hint) + 20;
			Theme.round(g, (vw - w) / 2, vh - 30, (vw + w) / 2, vh - 12, Theme.PANEL);
			g.centeredText(font, hint, vw / 2, vh - 25, Theme.TEXT_DIM);
			g.pose().popMatrix();
			return;
		}
		int sideWidth = vw < 480 ? 112 : Math.max(130, Math.min(180, vw / 5));
		sideX1 = MARGIN + sideWidth;
		drawSidebar(g, font, MARGIN, MARGIN, sideX1, vh - MARGIN, mx, my);
		drawMain(g, font, sideX1 + 8, MARGIN, vw - MARGIN, vh - MARGIN, mx, my);
		super.extractRenderState(g, mx, my, a); // the search box
		drawToast(g, font);
		g.pose().popMatrix();
	}

	private void drawSidebar(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, int mx, int my) {
		Theme.panel(g, x0, y0, x1, y1);
		int inner = x1 - x0 - 24;
		boolean big = font.width("Aetherium") * 1.25f <= inner;
		g.pose().pushMatrix();
		g.pose().translate(x0 + 12, y0 + 12);
		if (big) {
			g.pose().scale(1.25f, 1.25f);
		}
		g.text(font, Theme.fit(font, "Aetherium", inner), 0, 0, Theme.TEXT, false);
		g.pose().popMatrix();
		g.text(font, "Settings", x0 + 12, y0 + 27, Theme.ACCENT_TEXT, false);

		boolean searching = searching();
		int y = y0 + 46;
		for (int i = 0; i < pages.size(); i++) {
			Setting.Page p = pages.get(i);
			int cy0 = y, cy1 = y + 28;
			y += 31;
			if (cy1 > y1 - 34) {
				break;
			}
			boolean selected = i == page && !searching;
			boolean hover = mx >= x0 + 6 && mx < x1 - 6 && my >= cy0 && my < cy1;
			Theme.round(g, x0 + 6, cy0, x1 - 6, cy1, selected ? Theme.ACCENT_SOFT : hover ? Theme.CARD_HOVER : Theme.CARD);
			if (selected) {
				g.fill(x0 + 6, cy0 + 4, x0 + 8, cy1 - 4, Theme.ACCENT);
			}
			g.text(font, p.icon(), x0 + 13, cy0 + 10, selected ? Theme.ACCENT_TEXT : Theme.TEXT_MUTED, false);
			int textX = x0 + 26;
			int changes = pendingOn(p);
			int badge = changes > 0 ? font.width(Integer.toString(changes)) + 8 : 0;
			g.text(font, Theme.fit(font, p.title(), x1 - textX - 10 - badge), textX, cy0 + 5, Theme.TEXT, false);
			g.text(font, Theme.fit(font, p.subtitle(), x1 - textX - 10), textX, cy0 + 16, Theme.TEXT_MUTED, false);
			if (changes > 0) {
				int bx1 = x1 - 10;
				Theme.round(g, bx1 - badge, cy0 + 4, bx1, cy0 + 14, Theme.ACCENT);
				g.centeredText(font, Integer.toString(changes), bx1 - badge / 2, cy0 + 5, 0xFFFFFFFF);
			}
			int index = i;
			hotspots.add(new Hotspot(x0 + 6, cy0, x1 - 6, cy1, () -> selectPage(index), null, null, null));
		}
		String footer = "Running on " + (VulkanAccess.device() != null ? "Vulkan" : "OpenGL");
		g.text(font, Theme.fit(font, footer, x1 - x0 - 24), x0 + 12, y1 - 16, Theme.TEXT_MUTED, false);
	}

	private void drawMain(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, int mx, int my) {
		Theme.panel(g, x0, y0, x1, y1);
		int pad = 12;
		int inner = x1 - x0 - 2 * pad;
		boolean wide = inner >= 420;
		int searchWidth = wide ? Math.min(180, inner / 3) : inner;
		int titleWidth = wide ? inner - searchWidth - pad : inner;
		Setting.Page p = pages.get(page);
		String title = searching() ? "Search" : p.title();
		String subtitle = searching() ? resultCount() + " matching settings" : p.subtitle();
		boolean bigTitle = font.width(title) * 1.25f <= titleWidth;
		g.pose().pushMatrix();
		g.pose().translate(x0 + pad, y0 + 11);
		if (bigTitle) {
			g.pose().scale(1.25f, 1.25f);
		}
		g.text(font, Theme.fit(font, title, titleWidth), 0, 0, Theme.TEXT, false);
		g.pose().popMatrix();
		g.text(font, Theme.fit(font, subtitle, titleWidth), x0 + pad, y0 + 26, Theme.TEXT_DIM, false);

		int sx0 = wide ? x1 - pad - searchWidth : x0 + pad;
		int sy0 = wide ? y0 + 10 : y0 + 40;
		Theme.round(g, sx0, sy0, sx0 + searchWidth, sy0 + 18, search.isFocused() ? Theme.CARD_HOVER : Theme.CARD);
		if (search.isFocused()) {
			Theme.roundOutline(g, sx0, sy0, sx0 + searchWidth, sy0 + 18, Theme.ACCENT_SOFT);
		}
		search.setX(sx0 + 6);
		search.setY(sy0 + 5);
		search.setWidth(searchWidth - 12);

		int contentTop = wide ? y0 + 42 : y0 + 64;
		int footerTop = y1 - 30;
		int descTop = footerTop - 62;
		g.fill(x0 + pad, contentTop - 4, x1 - pad, contentTop - 3, Theme.BORDER);
		for (String banner : banners()) {
			banner(g, font, x0 + pad, contentTop, x1 - pad, banner, Theme.WARN);
			contentTop += 20;
		}
		drawList(g, font, x0 + pad, contentTop, x1 - pad, descTop - 6, mx, my);
		drawDescription(g, font, x0 + pad, descTop, x1 - pad, footerTop - 6);
		drawFooter(g, font, x0 + pad, footerTop, x1 - pad, y1 - 8, mx, my);
	}

	private void drawList(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, int mx, int my) {
		listY0 = y0;
		listY1 = y1;
		List<Line> lines = lines();
		double total = 0;
		for (Line line : lines) {
			total += line.header() != null ? HEADER : ROW + GAP;
		}
		contentHeight = total;
		scroll = clampScroll(scroll, contentHeight, y1 - y0);
		g.enableScissor(x0, y0, x1 + 6, y1);
		int y = y0 - (int) scroll;
		boolean first = true;
		for (Line line : lines) {
			if (line.header() != null) {
				if (y + HEADER > y0 && y < y1) {
					int ty = y + (first ? 3 : 7);
					g.text(font, Theme.fit(font, line.header(), x1 - x0), x0 + 2, ty, Theme.ACCENT_TEXT, false);
					int lx = x0 + 8 + font.width(Theme.fit(font, line.header(), x1 - x0));
					if (lx < x1) {
						g.fill(lx, ty + 4, x1, ty + 5, Theme.BORDER);
					}
				}
				y += HEADER;
			} else {
				if (y + ROW > y0 && y < y1) {
					drawRow(g, font, line, x0, y, x1, y + ROW, mx, my);
				}
				y += ROW + GAP;
			}
			first = false;
		}
		g.disableScissor();
		if (lines.isEmpty()) {
			g.text(font, "Nothing matches “" + search.getValue().trim() + "”", x0 + 4, y0 + 8, Theme.TEXT_MUTED, false);
		}
		double view = y1 - y0;
		if (contentHeight > view) {
			int barH = Math.max(20, (int) (view * view / contentHeight));
			int barY = y0 + (int) ((view - barH) * scroll / (contentHeight - view));
			g.fill(x1 + 3, barY, x1 + 5, barY + barH, Theme.BORDER);
		}
	}

	private void drawRow(GuiGraphicsExtractor g, Font font, Line line, int x0, int y0, int x1, int y1, int mx, int my) {
		Setting<?> s = Objects.requireNonNull(line.setting());
		boolean hover = mx >= x0 && mx < x1 && my >= Math.max(y0, listY0) && my < Math.min(y1, listY1);
		if (hover) {
			hovered = s;
		}
		String unavailable = s.unavailableReason();
		boolean usable = unavailable == null;
		Theme.round(g, x0, y0, x1, y1, hover ? Theme.CARD_HOVER : Theme.CARD);
		int textY = y0 + (y1 - y0 - 8) / 2;
		Object value = value(s);
		Object def = s.defaultValue();
		boolean changed = def != null && !Objects.equals(value, def) && (s.kind == Setting.Kind.TOGGLE || s.kind == Setting.Kind.CHOICE || s.kind == Setting.Kind.SLIDER);
		boolean isPending = pending.containsKey(s);
		if (changed) {
			g.fill(x0, y0 + 4, x0 + 2, y1 - 4, Theme.ACCENT);
		}
		int controlWidth = switch (s.kind) {
			case TOGGLE -> 30;
			case ACTION -> Math.max(56, font.width(s.actionLabel()) + 20);
			case INFO -> Math.max(64, Math.min(220, (x1 - x0) / 2));
			default -> Math.max(70, Math.min(150, (x1 - x0) * 2 / 5));
		};
		int controlX0 = x1 - 6 - controlWidth;
		boolean restart = s.restartPending();
		int labelRight = controlX0 - (changed && hover ? 20 : 6) - (restart ? 12 : 0);
		int nameColor = usable ? Theme.TEXT : Theme.TEXT_MUTED;
		if (line.where() != null) {
			int nameWidth = Math.min(font.width(s.name), (labelRight - x0 - 8) * 2 / 3);
			g.text(font, Theme.fit(font, s.name, nameWidth), x0 + 8, textY, nameColor, false);
			g.text(font, Theme.fit(font, line.where(), labelRight - x0 - 16 - nameWidth), x0 + 14 + nameWidth, textY, Theme.TEXT_MUTED, false);
		} else {
			g.text(font, Theme.fit(font, s.name, labelRight - x0 - 8), x0 + 8, textY, nameColor, false);
		}
		if (restart) {
			g.text(font, "⟳", labelRight + 2, textY, Theme.WARN, false);
		}
		Runnable unavailableToast = () -> showToast(unavailable, Theme.WARN);
		Runnable reset = def == null ? null : () -> stage(s, def);
		if (changed && hover && usable && reset != null) {
			int rx = controlX0 - 16;
			boolean overReset = mx >= rx && mx < rx + 12 && my >= y0 && my < y1;
			g.text(font, "↺", rx + 2, textY, overReset ? Theme.ACCENT_TEXT : Theme.TEXT_MUTED, false);
			hot(rx, y0, rx + 12, y1, reset, null, null, s);
		}
		switch (s.kind) {
			case TOGGLE -> {
				boolean on = Boolean.TRUE.equals(value);
				float knob = knobs.getOrDefault(s, on ? 1f : 0f);
				knob += ((on ? 1f : 0f) - knob) * 0.35f;
				knobs.put(s, knob);
				int tx0 = x1 - 6 - 26, ty0 = y0 + (y1 - y0 - 12) / 2;
				int track = Theme.mix(Theme.TRACK, Theme.ACCENT, knob);
				Theme.round(g, tx0, ty0, tx0 + 26, ty0 + 12, usable ? track : Theme.mix(track, Theme.PANEL, 0.5f));
				int kx = tx0 + 2 + (int) (knob * 12);
				Theme.round(g, kx, ty0 + 2, kx + 10, ty0 + 10, usable ? 0xFFF4F5F8 : 0xFF8A8F9C);
				if (isPending) {
					Theme.roundOutline(g, tx0 - 2, ty0 - 2, tx0 + 28, ty0 + 14, Theme.ACCENT_SOFT);
				}
				Runnable flip = usable ? () -> stage(s, !on) : unavailableToast;
				hot(x0, y0, x1, y1, flip, flip, reset, s);
			}
			case CHOICE -> {
				pill(g, font, controlX0, y0 + 4, x1 - 6, y1 - 4, s.label(value), isPending, hover, usable);
				boolean overLeft = hover && mx >= controlX0 && mx < controlX0 + 12;
				boolean overRight = hover && mx >= x1 - 18 && mx < x1 - 6;
				g.text(font, "‹", controlX0 + 4, textY, overLeft ? Theme.ACCENT_TEXT : Theme.TEXT_MUTED, false);
				g.text(font, "›", x1 - 13, textY, overRight ? Theme.ACCENT_TEXT : Theme.TEXT_MUTED, false);
				Runnable back = usable ? () -> stage(s, s.step(value(s), -1)) : unavailableToast;
				Runnable forward = usable ? () -> stage(s, s.step(value(s), 1)) : unavailableToast;
				hot(controlX0, y0, controlX0 + 12, y1, back, null, reset, s);
				hot(x0, y0, x1, y1, forward, back, reset, s);
			}
			case SLIDER -> {
				double t = s.sliderPosition(value);
				int trackY = y0 + (y1 - y0) / 2 + 4;
				int tx1 = x1 - 8;
				Theme.round(g, controlX0, trackY, tx1, trackY + 3, Theme.TRACK);
				int fillX = controlX0 + (int) ((tx1 - controlX0) * t);
				Theme.round(g, controlX0, trackY, Math.max(controlX0 + 3, fillX), trackY + 3, usable ? Theme.ACCENT : Theme.TEXT_MUTED);
				Theme.round(g, fillX - 3, trackY - 2, fillX + 3, trackY + 5, usable ? 0xFFF4F5F8 : 0xFF8A8F9C);
				g.text(font, Theme.fit(font, s.label(value), tx1 - controlX0), controlX0, y0 + 3, isPending ? Theme.ACCENT_TEXT : Theme.TEXT_DIM, false);
				Runnable back = usable ? () -> stage(s, s.step(value(s), -1)) : unavailableToast;
				Runnable forward = usable ? () -> stage(s, s.step(value(s), 1)) : unavailableToast;
				int sx0 = controlX0;
				hot(controlX0 - 4, y0, x1, y1, usable ? () -> {
					dragging = s;
					dragX0 = sx0;
					dragX1 = tx1;
					dragTo(mouseXNow());
				} : unavailableToast, back, reset, s);
				hot(x0, y0, controlX0 - 4, y1, forward, back, reset, s);
			}
			case ACTION -> {
				int bx0 = controlX0, by0 = y0 + 3, bx1 = x1 - 4, by1 = y1 - 3;
				boolean overButton = mx >= bx0 && mx < bx1 && my >= by0 && my < by1;
				Theme.round(g, bx0, by0, bx1, by1, overButton ? Theme.mix(Theme.ACCENT, 0xFFFFFFFF, 0.15f) : hover ? Theme.ACCENT : Theme.ACCENT_SOFT);
				g.centeredText(font, Theme.fit(font, s.actionLabel(), bx1 - bx0 - 8), (bx0 + bx1) / 2, by0 + (by1 - by0 - 8) / 2, 0xFFFFFFFF);
				Runnable run = usable ? s::runAction : unavailableToast;
				hot(x0, y0, x1, y1, run, null, null, s);
			}
			case INFO -> {
				String text = Theme.fit(font, s.label(value), controlWidth);
				g.text(font, text, x1 - 8 - font.width(text), textY, Theme.TEXT_DIM, false);
				hot(x0, y0, x1, y1, () -> {
				}, null, null, s);
			}
		}
	}

	private void drawDescription(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1) {
		Theme.round(g, x0, y0, x1, y1, Theme.PANEL_RAISED);
		Setting<?> s = hovered;
		if (s == null) {
			String help = searching()
					? "Results from every page. Esc clears the search."
					: "Click to change · right-click or Shift+scroll to go back · middle-click resets · Enter applies · hold Tab to look at the world";
			List<String> lines = Theme.wrap(font, help, x1 - x0 - 16, Math.max(1, (y1 - y0 - 8) / 10));
			int ty = y0 + (y1 - y0 - lines.size() * 10) / 2 + 1;
			for (String line : lines) {
				g.text(font, line, x0 + 8, ty, Theme.TEXT_MUTED, false);
				ty += 10;
			}
			return;
		}
		int nameWidth = Math.min(font.width(s.name), x1 - x0 - 16);
		g.text(font, Theme.fit(font, s.name, nameWidth), x0 + 8, y0 + 6, Theme.TEXT, false);
		// Tags after the name, as many as fit.
		int tx = x0 + 14 + nameWidth;
		List<Setting.Tag> tags = new ArrayList<>(s.tags());
		if (s.unavailableReason() != null) {
			tags.add(0, new Setting.Tag("Unavailable", Theme.BAD));
		}
		for (Setting.Tag tag : tags) {
			int w = font.width(tag.label()) + 8;
			if (tx + w > x1 - 6) {
				break;
			}
			Theme.round(g, tx, y0 + 4, tx + w, y0 + 15, (tag.color() & 0x00FFFFFF) | 0x2A000000);
			g.text(font, tag.label(), tx + 4, y0 + 6, tag.color(), false);
			tx += w + 4;
		}
		String meta = meta(s);
		int textY = y0 + 19;
		int bottom = meta != null ? y1 - 13 : y1 - 3;
		String body = s.unavailableReason() != null ? s.unavailableReason() + " " + s.description : s.description;
		for (String line : Theme.wrap(font, body, x1 - x0 - 16, Math.max(1, (bottom - textY) / 10))) {
			g.text(font, line, x0 + 8, textY, Theme.TEXT_DIM, false);
			textY += 10;
		}
		if (meta != null) {
			g.text(font, Theme.fit(font, meta, x1 - x0 - 16), x0 + 8, y1 - 11, s.restartPending() ? Theme.WARN : Theme.TEXT_MUTED, false);
		}
	}

	private @Nullable String meta(Setting<?> s) {
		if (s.kind == Setting.Kind.ACTION || s.kind == Setting.Kind.INFO) {
			return null;
		}
		StringBuilder b = new StringBuilder();
		Object value = value(s);
		b.append("Value: ").append(s.label(value));
		Object def = s.defaultValue();
		if (def != null) {
			b.append("  ·  Default: ").append(s.label(def));
		}
		if (pending.containsKey(s)) {
			b.append("  ·  Not applied yet (now ").append(s.label(s.applied())).append(')');
		} else if (s.restartPending()) {
			b.append("  ·  Restart the game to apply");
		}
		return b.toString();
	}

	private void drawFooter(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, int mx, int my) {
		int count = pending.size();
		int bx = x1;
		bx = footerButton(g, font, bx, y0, y1, "Done", true, mx, my, this::onClose);
		bx = footerButton(g, font, bx, y0, y1, "Apply", count > 0, mx, my, this::apply);
		if (count > 0) {
			bx = footerButton(g, font, bx, y0, y1, "Undo", false, mx, my, () -> {
				pending.clear();
				showToast("Changes discarded", Theme.TEXT_DIM);
			});
		}
		if (!searching()) {
			bx = footerButton(g, font, bx, y0, y1, "Reset page", false, mx, my, this::resetPage);
		}
		String left = count > 0 ? count + " change" + (count == 1 ? "" : "s") + " not applied" : "Up to date";
		if (bx - x0 - 8 > font.width("…") + 4) {
			g.text(font, Theme.fit(font, left, bx - x0 - 8), x0, y0 + 7, count > 0 ? Theme.ACCENT_TEXT : Theme.TEXT_MUTED, false);
		}
	}

	private int footerButton(GuiGraphicsExtractor g, Font font, int right, int y0, int y1, String label, boolean primary, int mx, int my, Runnable action) {
		int w = Math.max(56, font.width(label) + 20);
		int x0 = right - w;
		boolean hover = mx >= x0 && mx < right && my >= y0 && my < y1;
		int color = primary ? (hover ? Theme.mix(Theme.ACCENT, 0xFFFFFFFF, 0.15f) : Theme.ACCENT) : hover ? Theme.CARD_HOVER : Theme.CARD;
		Theme.round(g, x0, y0, right, y1, color);
		g.centeredText(font, Theme.fit(font, label, w - 8), (x0 + right) / 2, y0 + (y1 - y0 - 8) / 2, primary ? 0xFFFFFFFF : Theme.TEXT);
		hotspots.add(new Hotspot(x0, y0, right, y1, action, null, null, null));
		return x0 - 4;
	}

	private void pill(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, String text, boolean isPending, boolean hover, boolean usable) {
		Theme.round(g, x0, y0, x1, y1, hover ? 0x40000000 : 0x30000000);
		if (isPending) {
			Theme.roundOutline(g, x0, y0, x1, y1, Theme.ACCENT_SOFT);
		}
		String fitted = Theme.fit(font, text, x1 - x0 - 26);
		g.centeredText(font, fitted, (x0 + x1) / 2, y0 + (y1 - y0 - 8) / 2, !usable ? Theme.TEXT_MUTED : isPending ? Theme.ACCENT_TEXT : Theme.TEXT);
	}

	private void banner(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, String text, int color) {
		Theme.round(g, x0, y0, x1, y0 + 16, (color & 0x00FFFFFF) | 0x26000000);
		g.text(font, Theme.fit(font, text, x1 - x0 - 12), x0 + 6, y0 + 4, color, false);
	}

	private void drawToast(GuiGraphicsExtractor g, Font font) {
		if (toast == null || Util.getMillis() > toastUntil) {
			return;
		}
		String text = Theme.fit(font, toast, vw - 60);
		int w = font.width(text) + 20;
		int x0 = (vw - w) / 2, y0 = MARGIN + 6;
		Theme.round(g, x0, y0, x0 + w, y0 + 18, Theme.PANEL_RAISED);
		Theme.roundOutline(g, x0, y0, x0 + w, y0 + 18, toastColor & 0x80FFFFFF);
		g.centeredText(font, text, vw / 2, y0 + 5, toastColor);
	}

	private void hot(int x0, int y0, int x1, int y1, Runnable left, @Nullable Runnable right, @Nullable Runnable middle, @Nullable Setting<?> tag) {
		int cy0 = Math.max(y0, listY0), cy1 = Math.min(y1, listY1);
		if (cy1 > cy0) {
			hotspots.add(new Hotspot(x0, cy0, x1, cy1, left, right, middle, tag));
		}
	}

	// ================================================================ state

	private boolean searching() {
		return search != null && !search.getValue().isBlank();
	}

	private List<Line> lines() {
		List<Line> lines = new ArrayList<>();
		if (!searching()) {
			for (Setting.Section section : pages.get(page).sections().get()) {
				lines.add(new Line(section.title(), null, null));
				for (Setting<?> s : section.settings()) {
					lines.add(new Line(null, s, null));
				}
			}
			return lines;
		}
		String query = search.getValue().trim();
		for (Setting.Page p : pages) {
			List<Line> hits = new ArrayList<>();
			for (Setting.Section section : p.sections().get()) {
				for (Setting<?> s : section.settings()) {
					if (s.matches(query) || section.title().toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))) {
						hits.add(new Line(null, s, section.title()));
					}
				}
			}
			if (!hits.isEmpty()) {
				lines.add(new Line(p.title(), null, null));
				lines.addAll(hits);
			}
		}
		return lines;
	}

	private int resultCount() {
		int n = 0;
		for (Line line : lines()) {
			if (line.setting() != null) {
				n++;
			}
		}
		return n;
	}

	private int pendingOn(Setting.Page p) {
		int n = 0;
		for (Setting.Section section : p.sections().get()) {
			for (Setting<?> s : section.settings()) {
				if (pending.containsKey(s)) {
					n++;
				}
			}
		}
		return n;
	}

	/** Warnings above the list: settings waiting for a restart, and the renderer when it is not Vulkan. */
	private List<String> banners() {
		List<String> banners = new ArrayList<>();
		Set<String> restart = new LinkedHashSet<>();
		for (Setting.Page p : pages) {
			for (Setting.Section section : p.sections().get()) {
				for (Setting<?> s : section.settings()) {
					if (s.restartPending()) {
						restart.add(s.name);
					}
				}
			}
		}
		if (!restart.isEmpty()) {
			banners.add("⟳ Restart the game to finish applying: " + String.join(", ", restart));
		}
		if (VulkanAccess.device() == null) {
			banners.add("Running on OpenGL: shader packs and GPU optimisations need Vulkan (Video Settings › Graphics API).");
		}
		return banners;
	}

	/** Shows page {@code index} (wrapping); for scripted checks of every page. */
	public void showPage(int index) {
		selectPage(Math.floorMod(index, pages.size()));
	}

	private void selectPage(int index) {
		if (searching()) {
			search.setValue("");
		}
		if (index != page) {
			page = index;
			lastPage = index;
			scroll = 0;
		}
	}

	private void resetPage() {
		int n = 0;
		for (Setting.Section section : pages.get(page).sections().get()) {
			for (Setting<?> s : section.settings()) {
				Object def = s.defaultValue();
				if (def != null && !s.isImmediate() && s.unavailableReason() == null && !Objects.equals(def, value(s))) {
					stage(s, def);
					n++;
				}
			}
		}
		showToast(n == 0 ? "Everything on this page is at its default" : n + " setting" + (n == 1 ? "" : "s") + " reset · Apply to keep",
				n == 0 ? Theme.TEXT_DIM : Theme.ACCENT_TEXT);
	}

	private void showToast(@Nullable String text, int color) {
		if (text == null) {
			return;
		}
		toast = text;
		toastColor = color;
		toastUntil = Util.getMillis() + 3500;
	}

	private void apply() {
		if (pending.isEmpty()) {
			return;
		}
		List<Map.Entry<Setting<?>, Object>> changes = new ArrayList<>(pending.entrySet());
		pending.clear();
		int done = commit(changes);
		boolean restart = banners().stream().anyMatch(b -> b.startsWith("⟳"));
		showToast("Applied " + done + " change" + (done == 1 ? "" : "s") + (restart ? " · some need a restart" : ""), restart ? Theme.WARN : Theme.GOOD);
	}

	/** Commits in each setting's order, runs the shared after-apply steps once, saves both option files. */
	private int commit(List<Map.Entry<Setting<?>, Object>> changes) {
		changes.sort((a, b) -> Integer.compare(a.getKey().order(), b.getKey().order()));
		Set<Runnable> after = new LinkedHashSet<>();
		int done = 0;
		Map<String, String> failed = new LinkedHashMap<>();
		for (Map.Entry<Setting<?>, Object> e : changes) {
			Setting<?> s = e.getKey();
			try {
				s.commit(e.getValue());
				done++;
				if (s.afterApply() != null) {
					after.add(s.afterApply());
				}
			} catch (RuntimeException ex) {
				Aetherium.LOG.error("Could not apply setting {}", s.id, ex);
				failed.put(s.id, s.name);
			}
		}
		for (Runnable r : after) {
			try {
				r.run();
			} catch (RuntimeException ex) {
				Aetherium.LOG.error("Settings: after-apply step failed", ex);
			}
		}
		catalog.store.saveIfDirty();
		if (!failed.isEmpty()) {
			showToast("Could not apply " + String.join(", ", failed.values()) + " · details in the log", Theme.BAD);
		}
		return done;
	}

	// ================================================================ input

	private double mouseXNow() {
		return minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()) / ui;
	}

	private MouseButtonEvent scaled(MouseButtonEvent event) {
		return new MouseButtonEvent(event.x() / ui, event.y() / ui, event.buttonInfo());
	}

	private void dragTo(double mouseX) {
		if (dragging == null) {
			return;
		}
		double t = (mouseX - dragX0) / Math.max(1, dragX1 - dragX0);
		stage(dragging, dragging.fromSlider(t));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent raw, boolean doubleClick) {
		MouseButtonEvent event = scaled(raw);
		if (peek) {
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		search.setFocused(false);
		for (int i = hotspots.size() - 1; i >= 0; i--) {
			Hotspot h = hotspots.get(i);
			if (!h.contains(event.x(), event.y())) {
				continue;
			}
			Runnable action = switch (event.button()) {
				case InputConstants.MOUSE_BUTTON_LEFT -> event.hasShiftDown() && h.right() != null ? h.right() : h.left();
				case InputConstants.MOUSE_BUTTON_RIGHT -> h.right();
				case InputConstants.MOUSE_BUTTON_MIDDLE -> h.middle();
				default -> null;
			};
			if (action != null) {
				action.run();
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.25f));
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent raw, double dx, double dy) {
		MouseButtonEvent event = scaled(raw);
		if (dragging != null) {
			dragTo(event.x());
			return true;
		}
		return super.mouseDragged(event, dx / ui, dy / ui);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = null;
		return super.mouseReleased(scaled(event));
	}

	@Override
	public boolean mouseScrolled(double rawX, double rawY, double scrollX, double scrollY) {
		double x = rawX / ui, y = rawY / ui;
		if (x < sideX1) {
			int next = Math.floorMod(page + (scrollY > 0 ? -1 : 1), pages.size());
			selectPage(next);
			return true;
		}
		if (minecraft.hasShiftDown()) {
			Setting<?> s = hoveredAt(x, y);
			if (s != null && s.unavailableReason() == null && (s.kind == Setting.Kind.TOGGLE || s.kind == Setting.Kind.CHOICE || s.kind == Setting.Kind.SLIDER)) {
				stage(s, s.step(value(s), scrollY > 0 ? -1 : 1));
				return true;
			}
		}
		scroll -= scrollY * (ROW + GAP) * 2;
		return true;
	}

	private @Nullable Setting<?> hoveredAt(double x, double y) {
		for (int i = hotspots.size() - 1; i >= 0; i--) {
			Hotspot h = hotspots.get(i);
			if (h.contains(x, y) && h.tag() != null) {
				return h.tag();
			}
		}
		return null;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == InputConstants.KEY_TAB && !search.isFocused()) {
			peek = true;
			return true;
		}
		boolean ctrl = event.hasControlDown();
		if (ctrl && key == InputConstants.KEY_F) {
			setFocused(search);
			search.setFocused(true);
			return true;
		}
		if (ctrl && key == InputConstants.KEY_Z && !search.isFocused()) {
			pending.clear();
			showToast("Changes discarded", Theme.TEXT_DIM);
			return true;
		}
		if (ctrl && key == InputConstants.KEY_S || key == InputConstants.KEY_RETURN && !search.isFocused()) {
			apply();
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE && (search.isFocused() || searching())) {
			search.setValue("");
			search.setFocused(false);
			return true;
		}
		if (!search.isFocused() && (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN)) {
			selectPage(Math.floorMod(page + (key == InputConstants.KEY_UP ? -1 : 1), pages.size()));
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (event.key() == InputConstants.KEY_TAB) {
			peek = false;
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public void onClose() {
		// Closing keeps the edits, as vanilla's options screens do.
		apply();
		minecraft.gui.setScreen(parent);
	}

	private static double clampScroll(double scroll, double content, double view) {
		return Math.max(0, Math.min(scroll, Math.max(0, content - view)));
	}
}
