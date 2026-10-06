package dev.spacebod.aetherium.shaders.gui;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.config.ShaderPackSettings;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.shaderpack.option.StringOption;
import dev.spacebod.aetherium.shaders.shaderpack.option.menu.OptionMenuBooleanOptionElement;
import dev.spacebod.aetherium.shaders.shaderpack.option.menu.OptionMenuElement;
import dev.spacebod.aetherium.shaders.shaderpack.option.menu.OptionMenuElementScreen;
import dev.spacebod.aetherium.shaders.shaderpack.option.menu.OptionMenuLinkElement;
import dev.spacebod.aetherium.shaders.shaderpack.option.menu.OptionMenuOptionElement;
import dev.spacebod.aetherium.shaders.shaderpack.option.menu.OptionMenuProfileElement;
import dev.spacebod.aetherium.shaders.shaderpack.option.menu.OptionMenuStringOptionElement;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Aetherium Shaders settings. A top bar with the pack switcher (it opens the pack library; the packs folder is
 * watched, so packs added or removed appear at once), the search box, the Changed filter and a menu; the pack's
 * profiles and sections on the left, the open section's options in one column (the screens it links to laid out under
 * headings), the details of the hovered option on the right, and the apply bar at the bottom. Also:
 * <ul>
 *   <li>search across every option of every sub-screen, results showing where the option lives;</li>
 *   <li>a "Changed" filter listing every option that differs from the pack's default;</li>
 *   <li>a details panel for the hovered or focused option (the pack's text, its value and default, a reset button)
 *       instead of tooltips;</li>
 *   <li>per-option reset, a marker on options changed from default, and pending (not yet applied) values outlined;</li>
 *   <li>Shift+scroll to step a value, sliders you can drag (the value is taken on release), profiles as chips;</li>
 *   <li>keyboard: Tab (tapped) or the up and down arrows move between options, left and right change the value, Enter
 *       toggles or opens;</li>
 *   <li>Apply without leaving the menu: the world keeps rendering behind it (the game is not paused), and holding
 *       <b>Tab</b> hides the menu to look at the result; applying reports its time or the error;</li>
 *   <li>in the menu: Reload (the pack read again from disk), import of a settings file (a dialog, or dropped onto the
 *       window), export and Reset all;</li>
 *   <li>packs installed by dropping zips onto the window; leaving with changes not applied asks whether to apply or
 *       discard them, and Reset all asks first.</li>
 * </ul>
 * Every text is a translation key under {@code aetherium.shaders.screen.}.
 */
public final class ShaderPackScreen extends Screen {
	private static final int MARGIN = 10;
	private static final int ROW = 20;
	private static final int GAP = 3;
	/** Height of the top and bottom bars and of everything on them. */
	private static final int BAR = 20;
	/** Tab held longer than this hides the menu; a shorter tap moves the focus. */
	private static final long PEEK_DELAY_MS = 250;
	private static final String KEYS = "aetherium.shaders.screen.";
	private static final Component TITLE = Component.translatable(KEYS + "title");

	private final @Nullable Screen parent;
	private final ShaderPackEngine engine = ShaderPackEngine.get();
	private List<String> packs = new ArrayList<>();
	/** The pack chosen in the list (null = off); becomes the active pack on apply. */
	private @Nullable String selected;
	private @Nullable String active;
	private @Nullable PackOptions options;
	private @Nullable String loadError;
	/** The selected pack's options being read in the background ({@link PackOptions#request}). */
	private @Nullable CompletableFuture<PackOptions> pendingOptions;
	private @Nullable String pendingFor;
	/** The read follows an apply: the shown options stay until it arrives, then only applied values leave the queue. */
	private boolean refreshing;
	/** Packs whose options were read ahead (hovered in the list), once per screen. */
	private final Set<String> prefetched = new HashSet<>();
	private final Deque<String> path = new ArrayDeque<>();
	private @Nullable EditBox search;
	private boolean changedOnly;
	private double optionScroll;
	private double navScroll;
	private double navContentHeight;
	private int navX1;
	/** The pack library over the screen, and its scroll and list area (x0, y0, x1, y1). */
	private boolean library;
	private double libraryScroll;
	private double libraryContentHeight;
	private int[] libraryBounds = new int[4];
	/** The menu under the top bar's "⋯" button. */
	private boolean moreMenu;
	private boolean peek;
	/** When Tab went down (-1 = up), and whether Shift was held: a tap moves the focus, holding it peeks. */
	private long tabDownAt = -1;
	private boolean tabBack;
	private @Nullable String toast;
	private int toastColor = Theme.TEXT;
	private long toastUntil;
	private long applyStarted = -1;
	private final Map<String, Float> knobs = new HashMap<>();
	private @Nullable OptionMenuStringOptionElement dragging;
	/** The value under the slider being dragged; set as the option's pending value on release. */
	private @Nullable String dragValue;
	private int dragX0;
	private int dragX1;
	/** Menu scale relative to the GUI scale, and the layout size in menu units. */
	private float ui = 1;
	private int vw;
	private int vh;
	/** The packs folder, watched while the screen is open (null when it cannot be). */
	private @Nullable PackFolderWatch watch;
	/** A file dialog for Import, while it is open. */
	private @Nullable CompletableFuture<Optional<Path>> pendingImport;
	/** A question over the screen (unapplied changes on leaving, Reset all); null = none. */
	private @Nullable Confirm confirm;

	/** Keyboard focus: an index into the option list drawn last frame (-1 = none). */
	private int focus = -1;
	private boolean scrollToFocus;
	private List<Row> lastRows = List.of();

	/** Clickable areas of the last frame (immediate-mode UI). */
	private record Hotspot(int x0, int y0, int x1, int y1, Runnable left, @Nullable Runnable right, @Nullable Runnable middle, Object tag) {
		boolean contains(double x, double y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	private record Choice(String label, boolean primary, Runnable action) {
	}

	private record Confirm(String message, List<Choice> choices) {
	}

	private final List<Hotspot> hotspots = new ArrayList<>();
	private @Nullable Object hoveredTag;
	private int listX0;
	private int listY0;
	private int listX1;
	private int listY1;
	private double listContentHeight;

	public ShaderPackScreen(@Nullable Screen parent) {
		super(TITLE);
		this.parent = parent;
		this.active = ShaderPackSettings.activePack().orElse(null);
		this.selected = active;
	}

	/** {@code key} under this screen's prefix, translated, with {@code args}. */
	private static String t(String key, Object... args) {
		return Component.translatable(KEYS + key, args).getString();
	}

	/** One of two keys by count ({@code key.one} for 1, {@code key.many} otherwise), with the count as first argument. */
	private static String plural(String key, int count) {
		return t(key + (count == 1 ? ".one" : ".many"), count);
	}

	@Override
	protected void init() {
		packs = ShaderPackSettings.listPacks();
		if (watch == null) {
			watch = PackFolderWatch.start(ShaderPackSettings.packsDirectory());
		}
		if (options == null && pendingOptions == null && selected != null && loadError == null) {
			openPack(selected);
		}
		String query = search == null ? "" : search.getValue();
		search = new EditBox(minecraft.font, 0, 0, 120, 14, Component.translatable(KEYS + "search"));
		search.setBordered(false);
		search.setMaxLength(64);
		search.setHint(Component.translatable(KEYS + "search_hint").withStyle(s -> s.withColor(Theme.TEXT_MUTED)));
		search.setValue(query);
		search.setResponder(q -> resetList());
		addRenderableWidget(search);
	}

	@Override
	public void removed() {
		if (watch != null) {
			watch.close();
			watch = null;
		}
		super.removed();
	}

	/** The option list changed (another screen, search, filter or pack): back to its top, no focus. */
	private void resetList() {
		optionScroll = 0;
		focus = -1;
	}

	/** Shows {@code pack}'s options: at once when read before, else once read in the background (a spinner meanwhile). */
	private void openPack(@Nullable String pack) {
		options = null;
		loadError = null;
		pendingOptions = null;
		refreshing = false;
		path.clear();
		resetList();
		AetheriumShaders.getShaderPackOptionQueue().clear();
		if (pack != null) {
			readOptions(pack, false);
		}
	}

	private void readOptions(String pack, boolean refresh) {
		pendingOptions = PackOptions.request(pack);
		pendingFor = pack;
		refreshing = refresh;
		pollOptions();
	}

	/** Takes the options read in the background once they are there. */
	private void pollOptions() {
		var future = pendingOptions;
		if (future == null || !future.isDone()) {
			return;
		}
		pendingOptions = null;
		boolean refresh = refreshing;
		refreshing = false;
		if (!Objects.equals(pendingFor, selected)) {
			return;
		}
		try {
			PackOptions loaded = future.join();
			Map<String, String> queue = AetheriumShaders.getShaderPackOptionQueue();
			if (refresh) {
				// Edits made while it was read stay pending; those now saved are applied values.
				queue.entrySet().removeIf(e -> e.getValue().equals(loaded.appliedValue(e.getKey())));
			} else {
				queue.clear();
			}
			options = loaded;
		} catch (CompletionException | CancellationException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			AetheriumShaders.logger.error("could not read pack {}", pendingFor, cause);
			if (!refresh) {
				loadError = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
			}
		}
	}

	/** Packs added, removed or changed in the folder since the last frame: the list again, and the shown pack re-read. */
	private void pollFolder() {
		if (watch == null) {
			return;
		}
		Set<String> changed = watch.drain();
		if (changed.isEmpty()) {
			return;
		}
		packs = ShaderPackSettings.listPacks();
		prefetched.clear();
		if (selected != null && options != null && pendingOptions == null
				&& (changed.contains(selected) || changed.contains(selected + ".txt") || changed.contains(""))) {
			// Re-read only when the pack or its saved options changed (the request is answered from memory otherwise).
			readOptions(selected, true);
		}
	}

	@Override
	public boolean isPauseScreen() {
		// The world keeps rendering (and animating) behind the menu: applied changes are visible right away.
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (minecraft.level == null) {
			super.extractBackground(g, mouseX, mouseY, a);
		}
	}

	// ================================================================ rendering

	/**
	 * The menu's own scale: one step per 360 window pixels (3 at 1080p, 4 at 1440p), never above the GUI scale, so the
	 * layout keeps the same room whatever GUI scale the game uses. Drawing and input both go through {@link #ui}.
	 */
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
		hoveredTag = null;
		Font font = minecraft.font;
		pollApply();
		pollOptions();
		pollFolder();
		pollImport();
		updateScale();
		if (tabDownAt >= 0 && !peek && confirm == null && Util.getMillis() - tabDownAt > PEEK_DELAY_MS) {
			peek = true;
		}
		int mx = (int) (mouseX / ui), my = (int) (mouseY / ui);
		g.pose().pushMatrix();
		g.pose().scale(ui, ui);
		if (peek) {
			String hint = Theme.fit(font, t("previewing"), vw - 40);
			int w = font.width(hint) + 20;
			Theme.round(g, (vw - w) / 2, vh - 30, (vw + w) / 2, vh - 12, Theme.PANEL);
			g.centeredText(font, hint, vw / 2, vh - 25, Theme.TEXT_DIM);
			g.pose().popMatrix();
			return;
		}
		// Under a question, the pack library or the menu, nothing below reacts to the mouse.
		boolean covered = confirm != null || library || moreMenu;
		int ux = covered ? Integer.MIN_VALUE : mx, uy = covered ? Integer.MIN_VALUE : my;
		int top = MARGIN, bottom = vh - MARGIN;
		drawTopBar(g, font, MARGIN, top, vw - MARGIN, top + BAR, ux, uy);
		// With the three columns the status sits under the sections and the buttons under the details; otherwise both share
		// a bar along the bottom.
		if (columns(vw - 2 * MARGIN)) {
			drawBody(g, font, MARGIN, top + BAR + 6, vw - MARGIN, bottom, ux, uy);
		} else {
			drawBody(g, font, MARGIN, top + BAR + 6, vw - MARGIN, bottom - BAR - 6, ux, uy);
			drawBottomBar(g, font, MARGIN, bottom - BAR, vw - MARGIN, bottom, ux, uy);
		}
		super.extractRenderState(g, ux, uy, a); // the search box
		if (moreMenu && confirm == null) {
			drawMoreMenu(g, font, mx, my);
		}
		if (library && confirm == null) {
			drawLibrary(g, font, mx, my);
		}
		if (confirm != null) {
			drawConfirm(g, font, confirm, mx, my);
		}
		drawToast(g, font);
		g.pose().popMatrix();
	}

	// ---------------------------------------------------------------- top bar

	/** The pack switcher (opens the library), the search box, the Changed filter and the menu, one row at one height. */
	private void drawTopBar(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, int mx, int my) {
		int switchX1 = x0 + Math.min(230, Math.max(140, (x1 - x0) / 3));
		boolean hover = inside(mx, my, x0, y0, switchX1, y1);
		Theme.round(g, x0, y0, switchX1, y1, hover ? Theme.PANEL_RAISED : Theme.PANEL);
		Theme.roundOutline(g, x0, y0, switchX1, y1, hover ? Theme.ACCENT_SOFT : Theme.BORDER);
		int cy = (y0 + y1) / 2;
		boolean activePack = selected != null && Objects.equals(selected, active);
		if (activePack && engine.loadProgress() != null) {
			Theme.spinner(g, x0 + 10, cy, 3, Theme.ACCENT_TEXT);
		} else {
			int dot = selected == null ? Theme.TEXT_MUTED : !activePack ? Theme.WARN : engine.lastError() != null ? Theme.BAD
					: engine.running() ? Theme.GOOD : Theme.TEXT_DIM;
			g.fill(x0 + 8, cy - 2, x0 + 12, cy + 2, dot);
		}
		String status = selected == null ? (active == null ? t("pack_vanilla") : t("apply_to_switch"))
				: activePack ? statusText() : t("apply_to_switch");
		int statusWidth = Math.min(font.width(status), (switchX1 - x0) / 2 - 10);
		String name = selected == null ? t("shaders_off_title") : ShaderPackSettings.displayName(selected);
		int nameRight = switchX1 - 14 - statusWidth - 8;
		g.text(font, Theme.fit(font, name, nameRight - x0 - 18), x0 + 18, cy - 4, Theme.TEXT, false);
		g.text(font, Theme.fit(font, status, statusWidth), switchX1 - 14 - statusWidth, cy - 4, activePack ? statusColor() : Theme.TEXT_MUTED, false);
		g.text(font, "▾", switchX1 - 10, cy - 4, hover ? Theme.ACCENT_TEXT : Theme.TEXT_DIM, false);
		hotspots.add(new Hotspot(x0, y0, switchX1, y1, this::openLibrary, null, null, "switcher"));

		// Right to left: the menu, the Changed filter, then the search box takes the rest.
		int moreX0 = x1 - BAR;
		button(g, font, moreX0, y0, x1, y1, "⋯", moreMenu, mx, my, () -> moreMenu = !moreMenu);
		moreButtonX0 = moreX0;
		search.visible = options != null;
		if (options == null) {
			return;
		}
		String chipLabel = changedOnly ? t("changed_on") : t("changed");
		int chipX1 = moreX0 - 6;
		int chipX0 = chipX1 - (font.width(chipLabel) + 16);
		chip(g, font, chipX0, y0, chipX1, y1, chipLabel, changedOnly, mx, my, () -> {
			changedOnly = !changedOnly;
			resetList();
		});
		int sx0 = switchX1 + 8, sx1 = chipX0 - 6;
		Theme.round(g, sx0, y0, sx1, y1, search.isFocused() ? Theme.CARD_HOVER : Theme.CARD);
		if (search.isFocused()) {
			Theme.roundOutline(g, sx0, y0, sx1, y1, Theme.ACCENT_SOFT);
		}
		search.setX(sx0 + 7);
		search.setY(cy - 4);
		search.setWidth(Math.max(20, sx1 - sx0 - 14));
	}

	/** The menu button's left edge (the menu opens below it, right-aligned). */
	private int moreButtonX0;

	private void openLibrary() {
		library = true;
		moreMenu = false;
		search.setFocused(false);
	}

	// ---------------------------------------------------------------- body

	private void drawBody(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, int mx, int my) {
		if (selected == null) {
			drawMessage(g, font, x0, y0, x1, y1, t("shaders_off_title"), t("pick_pack"), Theme.TEXT_DIM, t("choose_pack"), mx, my);
			return;
		}
		if (options == null) {
			if (pendingOptions != null) {
				Theme.panel(g, x0, y0, x1, y1);
				drawReading(g, font, x0 + 12, y0 + 12, x1 - 12, y1 - 12);
			} else {
				drawMessage(g, font, x0, y0, x1, y1, ShaderPackSettings.displayName(selected),
						t("read_failed", Objects.requireNonNullElse(loadError, t("unknown_error"))), Theme.BAD, t("choose_pack"), mx, my);
			}
			return;
		}
		int width = x1 - x0;
		boolean side = columns(width);
		boolean nav = width >= 380;
		int navX1 = nav ? x0 + (side ? 150 : 124) : x0;
		int detailX0 = side ? x1 - 172 : x1;
		if (nav) {
			drawSections(g, font, x0, y0, navX1, y1, side, mx, my);
		}
		int cx0 = nav ? navX1 + 6 : x0;
		int cx1 = side ? detailX0 - 6 : x1;
		int listBottom = side ? y1 : y1 - 62;
		drawContent(g, font, cx0, y0, cx1, listBottom, mx, my);
		boolean profiles = !options.profileNames().isEmpty();
		if (side) {
			// The profile selector heads the details column and the buttons end it.
			int dy0 = profiles ? drawProfiles(g, font, detailX0, y0, x1, mx, my) + 6 : y0;
			int actionsY0 = y1 - ACTIONS;
			drawDescription(g, font, detailX0, dy0, x1, actionsY0 - 6, mx, my);
			drawActions(g, font, detailX0, actionsY0, x1, y1, mx, my);
		} else if (profiles) {
			// Narrow: the profiles share the strip under the list, to the right of the details.
			int px0 = Math.max(cx0 + 120, x1 - 150);
			drawProfiles(g, font, px0, listBottom + 6, x1, mx, my);
			drawDescription(g, font, cx0, listBottom + 6, px0 - 6, y1, mx, my);
		} else {
			drawDescription(g, font, cx0, listBottom + 6, x1, y1, mx, my);
		}
	}

	/** Height of the panel with Discard, Apply and Done under the details. */
	private static final int ACTIONS = BAR + 8;

	/** Whether the body has room for the three columns (sections, options, details) at {@code width}. */
	private boolean columns(int width) {
		return selected != null && options != null && width >= 560;
	}

	/** Discard (with something to discard), Apply and Done, sharing the panel's width. */
	private void drawActions(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, int mx, int my) {
		Theme.panel(g, x0, y0, x1, y1);
		int pending = options.pendingCount();
		boolean packChange = !Objects.equals(selected, active);
		List<Choice> buttons = new ArrayList<>();
		if (pending > 0) {
			buttons.add(new Choice(t("discard"), false, () -> options.discardPending()));
		}
		buttons.add(new Choice(t("apply"), pending > 0 || packChange, this::apply));
		buttons.add(new Choice(t("done"), false, () -> {
			apply();
			close();
		}));
		int pad = 4, gap = 4;
		float w = (x1 - x0 - 2 * pad - gap * (buttons.size() - 1)) / (float) buttons.size();
		for (int i = 0; i < buttons.size(); i++) {
			Choice c = buttons.get(i);
			int bx0 = x0 + pad + Math.round(i * (w + gap));
			int bx1 = x0 + pad + Math.round(i * (w + gap) + w);
			button(g, font, bx0, y0 + pad, bx1, y1 - pad, c.label(), c.primary(), mx, my, c.action());
		}
	}

	/** What is not applied yet (or that everything is), and its colour. */
	private String footerStatus() {
		int pending = options == null ? 0 : options.pendingCount();
		boolean packChange = !Objects.equals(selected, active);
		if (pending > 0 || packChange) {
			String count = plural("footer_options", pending);
			String what = packChange && pending > 0 ? t("footer_both", t("footer_pack_change"), count) : packChange ? t("footer_pack_change") : count;
			return t("footer_not_applied", what);
		}
		return exportedTo != null ? t("exported_to", exportedTo) : t("up_to_date");
	}

	private int footerStatusColor() {
		boolean unapplied = (options != null && options.pendingCount() > 0) || !Objects.equals(selected, active);
		return unapplied ? Theme.ACCENT_TEXT : Theme.TEXT_MUTED;
	}

	/** One profile chip, placed: {@code profile} null is Custom (shown while the options match no profile). */
	private record ProfileChip(@Nullable String profile, String label, boolean on, int x0, int y0, int x1) {
	}

	/** The profile chips laid out left to right from ({@code x0}, {@code y}), wrapping before {@code x1}. */
	private List<ProfileChip> layoutProfiles(Font font, int x0, int y, int x1) {
		Optional<String> current = options.currentProfile();
		List<@Nullable String> names = new ArrayList<>(options.profileNames());
		if (current.isEmpty()) {
			names.add(null);
		}
		List<ProfileChip> out = new ArrayList<>();
		int cx = x0;
		for (String p : names) {
			String label = Theme.fit(font, p == null ? t("custom") : options.profileLabel(p), x1 - x0 - 10);
			int w = font.width(label) + 10;
			if (cx + w > x1 && cx > x0) {
				cx = x0;
				y += 16;
			}
			out.add(new ProfileChip(p, label, p == null || current.isPresent() && current.get().equals(p), cx, y, cx + w));
			cx += w + 3;
		}
		return out;
	}

	/** The profile selector: a panel at ({@code x0}, {@code y0}) as tall as its chips need; returns its bottom. */
	private int drawProfiles(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int mx, int my) {
		int pad = 7;
		List<ProfileChip> chips = layoutProfiles(font, x0 + pad, y0 + pad + 11, x1 - pad);
		int y1 = (chips.isEmpty() ? y0 + pad + 11 : chips.getLast().y0() + 13) + pad;
		Theme.panel(g, x0, y0, x1, y1);
		g.text(font, t("profile"), x0 + pad, y0 + pad, Theme.TEXT_MUTED, false);
		for (ProfileChip c : chips) {
			String p = c.profile();
			chip(g, font, c.x0(), c.y0(), c.x1(), c.y0() + 13, c.label(), c.on(), mx, my, p == null ? () -> {
			} : () -> options.applyProfile(p));
		}
		return y1;
	}

	/** A panel with a title, a line of text and one button that opens the pack library. */
	private void drawMessage(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, String title, String body, int bodyColor, String action,
			int mx, int my) {
		Theme.panel(g, x0, y0, x1, y1);
		int w = Math.min(320, x1 - x0 - 40);
		List<String> lines = Theme.wrap(font, body, w, 4);
		int h = 14 + lines.size() * 10 + 30;
		int cx = (x0 + x1) / 2;
		int ty = (y0 + y1 - h) / 2;
		g.centeredText(font, Theme.fit(font, title, w), cx, ty, Theme.TEXT);
		ty += 14;
		for (String line : lines) {
			g.centeredText(font, line, cx, ty, bodyColor);
			ty += 10;
		}
		int bw = font.width(action) + 24;
		button(g, font, cx - bw / 2, ty + 8, cx + bw / 2, ty + 26, action, true, mx, my, this::openLibrary);
	}

	/**
	 * The pack's name and what is known about it, then its sections: the overview (its main screen), every screen the
	 * main screen links to, and under the open one the screens it links to. With {@code footer}, what is not applied yet
	 * at the bottom.
	 */
	private void drawSections(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, boolean footer, int mx, int my) {
		Theme.panel(g, x0, y0, x1, y1);
		int pad = 7;
		int width = x1 - x0 - 2 * pad;
		int y = y0 + pad + 1;
		// Title, then version and kind, then how many options it has and how many differ from its defaults.
		String[] name = splitVersion(ShaderPackSettings.displayName(selected));
		for (String line : Theme.wrap(font, name[0], width, 2)) {
			g.text(font, line, x0 + pad, y, Theme.TEXT, false);
			y += 10;
		}
		String kind = selected.toLowerCase(Locale.ROOT).endsWith(".zip") ? t("pack_zip") : t("pack_folder");
		String subtitle = name[1] == null ? kind : name[1] + "  ·  " + kind;
		g.text(font, Theme.fit(font, subtitle, width), x0 + pad, y + 1, Theme.TEXT_DIM, false);
		y += 12;
		Set<String> ids = options.allOptionIds();
		int changed = 0;
		for (String id : ids) {
			if (!options.isDefault(id)) {
				changed++;
			}
		}
		String meta = plural("footer_options", ids.size()) + (changed > 0 ? "  ·  " + t("changed_count", changed) : "");
		g.text(font, Theme.fit(font, meta, width), x0 + pad, y, Theme.TEXT_MUTED, false);
		y += 13;
		g.fill(x0 + pad, y, x1 - pad, y + 1, Theme.BORDER);
		y += 7;
		g.text(font, t("sections"), x0 + pad, y, Theme.TEXT_MUTED, false);
		y += 12;

		List<Section> sections = sections();
		int listTop = y, listBottom = y1 - pad;
		if (footer) {
			// What is not applied yet (or that everything is), under a rule at the foot of the panel.
			listBottom = y1 - 21;
			g.fill(x0 + pad, y1 - 20, x1 - pad, y1 - 19, Theme.BORDER);
			g.text(font, Theme.fit(font, footerStatus(), x1 - x0 - 2 * pad), x0 + pad, y1 - 13, footerStatusColor(), false);
		}
		navContentHeight = sections.size() * 17.0;
		navScroll = clampScroll(navScroll, navContentHeight, listBottom - listTop);
		navX1 = x1;
		g.enableScissor(x0 + 1, listTop, x1 - 1, listBottom);
		int ry = listTop - (int) navScroll;
		boolean filtering = !search.getValue().isBlank() || changedOnly;
		for (Section s : sections) {
			int ry0 = ry, ry1 = ry + 15;
			ry += 17;
			if (ry1 < listTop || ry0 > listBottom) {
				continue;
			}
			boolean current = !filtering && s.current();
			boolean hover = inside(mx, my, x0 + 3, Math.max(ry0, listTop), x1 - 3, Math.min(ry1, listBottom));
			if (current || hover) {
				Theme.round(g, x0 + 3, ry0, x1 - 3, ry1, current ? Theme.ACCENT_SOFT : Theme.CARD_HOVER);
			}
			if (current) {
				g.fill(x0 + 3, ry0 + 3, x0 + 5, ry1 - 3, Theme.ACCENT);
			}
			int indent = s.child() ? 14 : 8;
			int color = current ? Theme.TEXT : s.child() ? Theme.TEXT_DIM : filtering ? Theme.TEXT_DIM : Theme.TEXT;
			g.text(font, Theme.fit(font, s.label(), x1 - x0 - indent - 6), x0 + indent, ry0 + 4, color, false);
			if (ry1 > listTop && ry0 < listBottom) {
				hotspots.add(new Hotspot(x0 + 3, Math.max(ry0, listTop), x1 - 3, Math.min(ry1, listBottom), () -> navigate(s.top(), s.screen()), null, null,
						"section"));
			}
		}
		g.disableScissor();
	}

	/** A trailing version, "1.2", "v1.2.3b" or "r5.5.1", after a space or dash. */
	private static final Pattern VERSION = Pattern.compile("^(.+?)[\\s-]+([vr]?\\d+(?:\\.\\d+)+[\\w.+-]*)$",
			Pattern.CASE_INSENSITIVE);

	/** A pack's display name as its title and version (null when it carries none). */
	private static @Nullable String[] splitVersion(String name) {
		Matcher m = VERSION.matcher(name.strip());
		return m.matches() ? new String[] {m.group(1).strip(), m.group(2)} : new String[] {name.strip(), null};
	}

	/** One entry of the section list: {@code screen} null is the overview, {@code top} the top-level section it is under. */
	private record Section(String label, @Nullable String top, @Nullable String screen, boolean child, boolean current) {
	}

	private List<Section> sections() {
		List<Section> out = new ArrayList<>();
		List<String> stack = new ArrayList<>(path);
		Collections.reverse(stack); // bottom (top-level section) first
		String openTop = stack.isEmpty() ? null : stack.getFirst();
		String openChild = stack.size() > 1 ? stack.get(1) : null;
		out.add(new Section(t("overview"), null, null, false, stack.isEmpty()));
		for (OptionMenuElement e : options.screen(null).elements) {
			if (!(e instanceof OptionMenuLinkElement link)) {
				continue;
			}
			String id = link.targetScreenId;
			boolean open = id.equals(openTop);
			out.add(new Section(options.screenLabel(id), id, id, false, open && openChild == null));
			if (!open) {
				continue;
			}
			for (OptionMenuElement c : options.screen(id).elements) {
				if (c instanceof OptionMenuLinkElement childLink && !childLink.targetScreenId.equals(id)) {
					String cid = childLink.targetScreenId;
					out.add(new Section(options.screenLabel(cid), id, cid, true, cid.equals(openChild)));
				}
			}
		}
		return out;
	}

	/** Shows {@code screen} (null = the top-level section itself, or the overview when {@code top} is null too). */
	private void navigate(@Nullable String top, @Nullable String screen) {
		path.clear();
		if (top != null) {
			path.push(top);
			if (screen != null && !screen.equals(top)) {
				path.push(screen);
			}
		}
		if (search != null) {
			search.setValue("");
			search.setFocused(false);
		}
		changedOnly = false;
		resetList();
	}

	/** The open screen's title (or the search or filter heading) over its option list. */
	private void drawContent(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, int mx, int my) {
		Theme.panel(g, x0, y0, x1, y1);
		int pad = 8;
		boolean searching = !search.getValue().isBlank();
		String title;
		if (searching) {
			title = t("search_results");
		} else if (changedOnly) {
			title = t("changed_heading");
		} else {
			title = path.isEmpty() ? t("overview") : options.screenLabel(path.peek());
		}
		int tx = x0 + pad;
		if (!searching && !changedOnly && path.size() > 1) {
			// Deeper than the section list shows: back one screen.
			boolean over = inside(mx, my, tx - 2, y0 + 4, tx + 10, y0 + 18);
			g.text(font, "‹", tx, y0 + 8, over ? Theme.ACCENT_TEXT : Theme.TEXT_DIM, false);
			hotspots.add(new Hotspot(tx - 2, y0 + 4, tx + 10, y0 + 18, () -> {
				path.pop();
				resetList();
			}, null, null, "back"));
			tx += 12;
		}
		g.text(font, Theme.fit(font, title, x1 - tx - pad), tx, y0 + 8, Theme.TEXT, false);
		ShaderPackEngine.LoadProgress progress = engine.loadProgress();
		if (progress != null && Objects.equals(selected, active)) {
			// The pack loads in the background (the world keeps vanilla visuals meanwhile).
			Theme.progress(g, x0 + pad, y0 + 20, x1 - pad, y0 + 21, progress.overall());
		} else {
			g.fill(x0 + pad, y0 + 20, x1 - pad, y0 + 21, Theme.BORDER);
		}
		drawOptions(g, font, x0 + pad, y0 + 25, x1 - pad, y1 - pad, mx, my);
	}

	/** While the selected pack's options are read: a spinner, and placeholder rows where the options will be. */
	private void drawReading(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1) {
		String label = t("reading", ShaderPackSettings.displayName(Objects.requireNonNull(selected)));
		Theme.spinner(g, x0 + 6, y0 + 7, 4, Theme.ACCENT_TEXT);
		g.text(font, Theme.fit(font, label, x1 - x0 - 20), x0 + 18, y0 + 3, Theme.TEXT_DIM, false);
		// Placeholder rows, gently pulsing.
		float pulse = (float) (Math.sin(Util.getMillis() / 300.0) + 1) / 2;
		int card = Theme.mix(0x14FFFFFF, 0x24FFFFFF, pulse);
		int columns = Math.max(1, Math.min(2, (x1 - x0) / 250));
		int cellWidth = (x1 - x0 - GAP * (columns - 1)) / columns;
		int y = y0 + 20;
		for (int row = 0; y + ROW <= y1; row++, y += ROW + GAP) {
			for (int col = 0; col < columns; col++) {
				int cx0 = x0 + col * (cellWidth + GAP);
				Theme.round(g, cx0, y, cx0 + cellWidth, y + ROW, card);
				int labelWidth = Math.max(30, cellWidth * (35 + (row * 7 + col * 13) % 30) / 100);
				g.fill(cx0 + 8, y + 9, cx0 + 8 + labelWidth, y + 13, card);
			}
		}
	}

	/** A heading ({@code item} null) or an option, screen link or profile ({@code header} null) of the option list. */
	private record Row(PackOptions.@Nullable Located item, @Nullable String header) {
		int height() {
			return item == null ? HEADER : ROW + GAP;
		}
	}

	private static final int HEADER = 16;

	/**
	 * The list for the open screen: its options in one column, and on a section's screen each screen it links to laid
	 * out under a heading of its own (deeper links stay links). Searching or the Changed filter lists matches instead,
	 * each with where it lives. The pack's empty cells (spacers for its own grid) are left out.
	 */
	private List<Row> rows() {
		List<Row> rows = new ArrayList<>();
		if (!search.getValue().isBlank() || changedOnly) {
			for (PackOptions.Located item : visibleItems()) {
				rows.add(new Row(item, null));
			}
			return rows;
		}
		OptionMenuElementScreen screen = currentScreen();
		boolean expand = !path.isEmpty();
		for (OptionMenuElement e : screen.elements) {
			if (e == OptionMenuElement.EMPTY) {
				continue;
			}
			if (expand && e instanceof OptionMenuLinkElement link && !path.contains(link.targetScreenId)) {
				OptionMenuElementScreen sub = options.menu().subScreens.get(link.targetScreenId);
				if (sub != null) {
					rows.add(new Row(null, options.screenLabel(link.targetScreenId)));
					for (OptionMenuElement s : sub.elements) {
						if (s != OptionMenuElement.EMPTY) {
							rows.add(new Row(new PackOptions.Located(s, List.of()), null));
						}
					}
					continue;
				}
			}
			rows.add(new Row(new PackOptions.Located(e, List.of()), null));
		}
		return rows;
	}

	private void drawOptions(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, int mx, int my) {
		listX0 = x0;
		listY0 = y0;
		listX1 = x1;
		listY1 = y1;
		List<Row> rows = rows();
		boolean flat = !search.getValue().isBlank() || changedOnly;
		lastRows = rows;
		if (focus >= rows.size()) {
			focus = -1;
		}
		int[] tops = new int[rows.size()];
		int content = 0;
		for (int i = 0; i < rows.size(); i++) {
			tops[i] = content;
			content += rows.get(i).height();
		}
		listContentHeight = content;
		if (scrollToFocus && focus >= 0) {
			// Keep the focused option in view.
			double top = tops[focus];
			if (top < optionScroll) {
				optionScroll = top;
			} else if (top + ROW > optionScroll + (y1 - y0)) {
				optionScroll = top + ROW - (y1 - y0);
			}
		}
		scrollToFocus = false;
		optionScroll = clampScroll(optionScroll, listContentHeight, y1 - y0);
		if (rows.isEmpty()) {
			g.text(font, flat ? t("nothing_matches") : t("no_options"), x0, y0 + 4, Theme.TEXT_MUTED, false);
			return;
		}
		int right = listContentHeight > y1 - y0 ? x1 - 6 : x1;
		g.enableScissor(x0, y0, x1, y1);
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int ry0 = y0 + tops[i] - (int) optionScroll;
			if (ry0 + row.height() < y0 || ry0 > y1) {
				continue;
			}
			if (row.item() == null) {
				g.text(font, Theme.fit(font, row.header(), right - x0 - 2), x0 + 2, ry0 + 6, Theme.ACCENT_TEXT, false);
				continue;
			}
			drawElement(g, font, row.item(), x0, ry0, right, ry0 + ROW, mx, my, flat, i == focus);
		}
		g.disableScissor();
		if (listContentHeight > y1 - y0) {
			double view = y1 - y0;
			int barH = Math.max(20, (int) (view * view / listContentHeight));
			int barY = y0 + (int) ((view - barH) * optionScroll / (listContentHeight - view));
			g.fill(x1 - 2, barY, x1, barY + barH, Theme.BORDER);
		}
	}

	private void drawElement(GuiGraphicsExtractor g, Font font, PackOptions.Located item, int x0, int y0, int x1, int y1, int mx, int my, boolean flat,
			boolean focused) {
		OptionMenuElement element = item.element();
		if (element == OptionMenuElement.EMPTY) {
			return;
		}
		boolean hover = mx >= x0 && mx < x1 && my >= Math.max(y0, listY0) && my < Math.min(y1, listY1);
		if (hover) {
			hoveredTag = item;
		}
		Theme.round(g, x0, y0, x1, y1, hover || focused ? Theme.CARD_HOVER : Theme.CARD);
		if (focused) {
			Theme.roundOutline(g, x0, y0, x1, y1, Theme.ACCENT);
		}
		int textY = y0 + (y1 - y0 - 8) / 2;
		if (element instanceof OptionMenuLinkElement link) {
			g.text(font, Theme.fit(font, options.screenLabel(link.targetScreenId), x1 - x0 - 26), x0 + 8, textY, Theme.TEXT, false);
			g.text(font, "›", x1 - 12, textY, hover || focused ? Theme.ACCENT_TEXT : Theme.TEXT_MUTED, false);
			hot(x0, y0, x1, y1, () -> openScreen(link.targetScreenId), null, null, item);
			return;
		}
		if (element instanceof OptionMenuProfileElement) {
			Optional<String> current = options.currentProfile();
			String value = current.map(options::profileLabel).orElse(t("custom"));
			g.text(font, t("profile"), x0 + 8, textY, Theme.TEXT, false);
			pill(g, font, x1 - 6 - Math.max(64, Math.min(150, (x1 - x0) * 2 / 5)), y0 + 4, x1 - 6, y1 - 4, value, false, hover);
			List<String> names = options.profileNames();
			hot(x0, y0, x1, y1, () -> cycleProfile(names, current, 1), () -> cycleProfile(names, current, -1), null, item);
			return;
		}
		if (!(element instanceof OptionMenuOptionElement option)) {
			return;
		}
		String id = option.optionId;
		boolean changed = !options.isDefault(id);
		boolean pending = options.isPending(id);
		if (changed) {
			g.fill(x0, y0 + 4, x0 + 2, y1 - 4, Theme.ACCENT);
		}
		int controlWidth = element instanceof OptionMenuBooleanOptionElement ? 30 : Math.max(64, Math.min(150, (x1 - x0) * 2 / 5));
		int controlX0 = x1 - 6 - controlWidth;
		// A changed option has a Reset button between its name and its control.
		String resetLabel = t("reset");
		int resetX1 = controlX0 - 6;
		int resetX0 = resetX1 - (font.width(resetLabel) + 12);
		int labelRight = changed ? resetX0 - 6 : controlX0 - 6;
		String label = options.optionLabel(id);
		if (flat && !item.path().isEmpty()) {
			String where = String.join(" › ", item.path());
			int labelWidth = Math.min(font.width(label), (labelRight - x0 - 8) * 2 / 3);
			g.text(font, Theme.fit(font, label, labelWidth), x0 + 8, textY, Theme.TEXT, false);
			g.text(font, Theme.fit(font, where, labelRight - x0 - 16 - labelWidth), x0 + 14 + labelWidth, textY, Theme.TEXT_MUTED, false);
		} else {
			g.text(font, Theme.fit(font, label, labelRight - x0 - 8), x0 + 8, textY, Theme.TEXT, false);
		}
		Runnable reset = () -> options.set(id, options.defaultValue(id));
		// Clicks go to the last hotspot added under the mouse, so each control below adds the whole row first and its own
		// parts (arrows, then the Reset button at the end) after it.
		if (element instanceof OptionMenuBooleanOptionElement) {
			boolean on = Boolean.parseBoolean(options.value(id));
			float knob = knobs.getOrDefault(id, on ? 1f : 0f);
			knob += ((on ? 1f : 0f) - knob) * 0.35f;
			knobs.put(id, knob);
			int tx0 = x1 - 6 - 26, ty0 = y0 + (y1 - y0 - 12) / 2;
			Theme.round(g, tx0, ty0, tx0 + 26, ty0 + 12, Theme.mix(Theme.TRACK, Theme.ACCENT, knob));
			int kx = tx0 + 2 + (int) (knob * 12);
			Theme.round(g, kx, ty0 + 2, kx + 10, ty0 + 10, 0xFFF4F5F8);
			if (pending) {
				Theme.roundOutline(g, tx0 - 2, ty0 - 2, tx0 + 28, ty0 + 14, Theme.ACCENT_SOFT);
			}
			hot(x0, y0, x1, y1, () -> options.set(id, Boolean.toString(!on)), () -> options.set(id, Boolean.toString(!on)), reset, item);
		} else if (element instanceof OptionMenuStringOptionElement s) {
			StringOption opt = s.option;
			boolean draggingThis = dragging == s && dragValue != null;
			String value = draggingThis ? dragValue : options.value(id);
			String shown = options.valueLabel(id, value);
			boolean marked = pending || draggingThis;
			if (option.slider) {
				List<String> values = opt.getAllowedValues();
				int index = Math.max(0, values.indexOf(value));
				float t = values.size() <= 1 ? 1 : (float) index / (values.size() - 1);
				int trackY = y0 + (y1 - y0) / 2 + 4;
				Theme.round(g, controlX0, trackY, x1 - 8, trackY + 3, Theme.TRACK);
				int fillX = controlX0 + (int) ((x1 - 8 - controlX0) * t);
				Theme.round(g, controlX0, trackY, Math.max(controlX0 + 3, fillX), trackY + 3, Theme.ACCENT);
				Theme.round(g, fillX - 3, trackY - 2, fillX + 3, trackY + 5, 0xFFF4F5F8);
				g.text(font, Theme.fit(font, shown, x1 - 8 - controlX0), controlX0, y0 + 3, marked ? Theme.ACCENT_TEXT : Theme.TEXT_DIM, false);
				int sx0 = controlX0, sx1 = x1 - 8;
				hot(controlX0, y0, x1, y1, () -> {
					dragging = s;
					dragValue = null;
					dragX0 = sx0;
					dragX1 = sx1;
					dragTo(mouseXNow());
				}, () -> options.set(id, options.step(opt, value, -1)), reset, item);
				hot(x0, y0, controlX0, y1, () -> options.set(id, options.step(opt, value, 1)), () -> options.set(id, options.step(opt, value, -1)), reset, item);
			} else {
				pill(g, font, controlX0, y0 + 4, x1 - 6, y1 - 4, shown, pending, hover);
				// The arrows' targets are the pill's ends, wider than the glyphs so they are easy to hit.
				int arrow = 16;
				boolean overLeft = hover && mx >= controlX0 && mx < controlX0 + arrow;
				boolean overRight = hover && mx >= x1 - 6 - arrow && mx < x1 - 6;
				g.text(font, "‹", controlX0 + 5, textY, overLeft ? Theme.ACCENT_TEXT : Theme.TEXT_MUTED, false);
				g.text(font, "›", x1 - 14, textY, overRight ? Theme.ACCENT_TEXT : Theme.TEXT_MUTED, false);
				hot(x0, y0, x1, y1, () -> options.set(id, options.step(opt, value, 1)), () -> options.set(id, options.step(opt, value, -1)), reset, item);
				hot(controlX0, y0, controlX0 + arrow, y1, () -> options.set(id, options.step(opt, value, -1)), () -> options.set(id, options.step(opt, value, -1)),
						reset, item);
				hot(x1 - 6 - arrow, y0, x1, y1, () -> options.set(id, options.step(opt, value, 1)), () -> options.set(id, options.step(opt, value, 1)), reset,
						item);
			}
		}
		if (changed) {
			boolean overReset = mx >= resetX0 && mx < resetX1 && my >= Math.max(y0 + 4, listY0) && my < Math.min(y1 - 4, listY1);
			Theme.round(g, resetX0, y0 + 4, resetX1, y1 - 4, overReset ? Theme.ACCENT_SOFT : 0x30000000);
			g.centeredText(font, resetLabel, (resetX0 + resetX1) / 2, textY, overReset ? 0xFFFFFFFF : Theme.TEXT_DIM);
			hot(resetX0, y0 + 4, resetX1, y1 - 4, reset, reset, reset, item);
		}
	}

	private String onOff(boolean on) {
		return on ? t("value_on") : t("value_off");
	}

	/**
	 * The hovered option, else the one the keyboard is on: its name, the pack's text for it, its value and default, and
	 * (with room) its id. With nothing to describe, how the menu is used.
	 */
	private void drawDescription(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, int mx, int my) {
		Theme.panel(g, x0, y0, x1, y1);
		int pad = 8;
		int width = x1 - x0 - 2 * pad;
		boolean tall = y1 - y0 > 90;
		Object described = hoveredTag != null ? hoveredTag : focusedItem();
		if (described == null) {
			described = lastDescribed;
		}
		String title;
		Optional<String> body;
		String id = null;
		OptionMenuElement element = described instanceof PackOptions.Located item ? item.element() : null;
		if (element instanceof OptionMenuOptionElement o) {
			title = options.optionLabel(o.optionId);
			body = options.optionComment(o.optionId);
			id = o.optionId;
		} else if (element instanceof OptionMenuLinkElement link) {
			title = options.screenLabel(link.targetScreenId);
			body = options.screenComment(link.targetScreenId);
		} else if (element instanceof OptionMenuProfileElement) {
			title = t("profile");
			body = options.profileComment().or(() -> Optional.of(t("profile_help")));
		} else {
			List<String> lines = new ArrayList<>(Theme.wrap(font, t("help"), width, Math.max(1, (y1 - y0 - 2 * pad) / 10)));
			int room = (y1 - y0 - 2 * pad) / 10 - lines.size() - 1;
			if (room > 0) {
				lines.add("");
				lines.addAll(Theme.wrap(font, t("help_keys"), width, room));
			}
			int ty = y0 + pad + 1;
			for (String line : lines) {
				g.text(font, line, x0 + pad, ty, Theme.TEXT_MUTED, false);
				ty += 10;
			}
			return;
		}
		lastDescribed = described;
		int ty = y0 + pad + 1;
		for (String line : Theme.wrap(font, title, width, 2)) {
			g.text(font, line, x0 + pad, ty, Theme.TEXT, false);
			ty += 10;
		}
		ty += 3;
		// Values and the reset button go at the bottom; the text takes what is left above them.
		int footer = id == null ? 0 : tall ? 34 : 12;
		if (body.isPresent()) {
			int lines = Math.max(1, (y1 - pad - footer - ty) / 10);
			for (String line : Theme.wrap(font, body.get(), width, lines)) {
				g.text(font, line, x0 + pad, ty, Theme.TEXT_DIM, false);
				ty += 10;
			}
		}
		if (id == null) {
			return;
		}
		boolean bool = element instanceof OptionMenuBooleanOptionElement;
		String def = options.defaultValue(id);
		String now = options.value(id);
		String nowShown = bool ? onOff(Boolean.parseBoolean(now)) : options.valueLabel(id, now);
		String defShown = bool ? onOff(Boolean.parseBoolean(def)) : options.valueLabel(id, def);
		boolean pending = options.isPending(id);
		if (!tall) {
			String meta = t("meta", id, nowShown, defShown) + (pending ? t("meta_not_applied") : "");
			g.text(font, Theme.fit(font, meta, width), x0 + pad, y1 - pad - 8, Theme.TEXT_MUTED, false);
			return;
		}
		int by = y1 - pad - 34;
		g.text(font, Theme.fit(font, t("value_now", nowShown) + (pending ? " · " + t("not_applied") : ""), width), x0 + pad, by, pending ? Theme.ACCENT_TEXT : Theme.TEXT_DIM,
				false);
		g.text(font, Theme.fit(font, t("value_default", defShown), width), x0 + pad, by + 10, Theme.TEXT_MUTED, false);
		g.text(font, Theme.fit(font, id, width), x0 + pad, y1 - pad - 8, Theme.TEXT_MUTED, false);
	}

	/** What the details panel showed last, kept while the mouse is between rows. */
	private @Nullable Object lastDescribed;

	/** What is not applied yet, then Discard, Apply and Done. */
	private void drawBottomBar(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, int mx, int my) {
		Theme.panel(g, x0, y0, x1, y1);
		int pending = options == null ? 0 : options.pendingCount();
		boolean packChange = !Objects.equals(selected, active);
		int bx = x1 - 3;
		bx = barButton(g, font, bx, y0 + 3, y1 - 3, t("done"), false, mx, my, () -> {
			apply();
			close();
		});
		bx = barButton(g, font, bx, y0 + 3, y1 - 3, t("apply"), pending > 0 || packChange, mx, my, this::apply);
		if (pending > 0) {
			bx = barButton(g, font, bx, y0 + 3, y1 - 3, t("discard"), false, mx, my, () -> options.discardPending());
		}
		if (bx - x0 - 16 > font.width("…") + 4) {
			g.text(font, Theme.fit(font, footerStatus(), bx - x0 - 16), x0 + 8, (y0 + y1) / 2 - 4, footerStatusColor(), false);
		}
	}

	/** A bar button ending at {@code right}; returns the x where the next one (to its left) ends. */
	private int barButton(GuiGraphicsExtractor g, Font font, int right, int y0, int y1, String label, boolean primary, int mx, int my, Runnable action) {
		int w = Math.max(52, font.width(label) + 20);
		button(g, font, right - w, y0, right, y1, label, primary, mx, my, action);
		return right - w - 4;
	}

	/** The menu under the "⋯" button: import, export, reset, reload, the packs folder. A click outside closes it. */
	private void drawMoreMenu(GuiGraphicsExtractor g, Font font, int mx, int my) {
		hotspots.clear();
		hotspots.add(new Hotspot(0, 0, vw, vh, () -> moreMenu = false, null, null, "outside"));
		List<Choice> items = new ArrayList<>();
		if (options != null) {
			items.add(new Choice(t("import"), false, this::chooseImport));
			items.add(new Choice(t("export"), false, this::exportSettings));
			items.add(new Choice(t("reset_all"), false, this::askResetAll));
		}
		items.add(new Choice(t("reload"), false, this::reloadFromDisk));
		items.add(new Choice(t("open_folder"), false, () -> Blaze3D.openPath(ShaderPackSettings.packsDirectory())));
		int w = 0;
		for (Choice c : items) {
			w = Math.max(w, font.width(c.label()));
		}
		w += 24;
		int x1 = vw - MARGIN, x0 = x1 - w;
		int y0 = MARGIN + BAR + 3;
		int y1 = y0 + 6 + items.size() * 16;
		Theme.round(g, x0, y0, x1, y1, Theme.PANEL_RAISED);
		Theme.roundOutline(g, x0, y0, x1, y1, Theme.BORDER);
		int y = y0 + 3;
		for (Choice c : items) {
			boolean hover = inside(mx, my, x0 + 3, y, x1 - 3, y + 15);
			if (hover) {
				Theme.round(g, x0 + 3, y, x1 - 3, y + 15, Theme.CARD_HOVER);
			}
			g.text(font, c.label(), x0 + 10, y + 4, hover ? Theme.TEXT : Theme.TEXT_DIM, false);
			hotspots.add(new Hotspot(x0 + 3, y, x1 - 3, y + 15, () -> {
				moreMenu = false;
				c.action().run();
			}, null, null, "menu"));
			y += 16;
		}
	}

	/**
	 * Every pack in the folder as a card (shaders off first): click one to show its options. The folder is watched, so
	 * packs added or removed appear at once; zips dropped onto the window are installed. A click outside closes it.
	 */
	private void drawLibrary(GuiGraphicsExtractor g, Font font, int mx, int my) {
		hotspots.clear();
		g.fill(0, 0, vw, vh, 0x88000000);
		int w = Math.min(560, vw - 2 * MARGIN), h = Math.min(400, vh - 2 * MARGIN);
		int x0 = (vw - w) / 2, y0 = (vh - h) / 2, x1 = x0 + w, y1 = y0 + h;
		hotspots.add(new Hotspot(0, 0, vw, vh, () -> library = false, null, null, "outside"));
		hotspots.add(new Hotspot(x0, y0, x1, y1, () -> {
		}, null, null, "library"));
		Theme.panel(g, x0, y0, x1, y1);
		int pad = 12;
		g.text(font, t("library_title"), x0 + pad, y0 + pad, Theme.TEXT, false);
		g.text(font, Theme.fit(font, t("library_hint"), w - 2 * pad), x0 + pad, y0 + pad + 12, Theme.TEXT_MUTED, false);

		List<@Nullable String> entries = new ArrayList<>();
		entries.add(null);
		entries.addAll(packs);
		int columns = Math.max(1, (w - 2 * pad + 6) / 166);
		int cardW = (w - 2 * pad - 6 * (columns - 1)) / columns, cardH = 30;
		int listTop = y0 + pad + 28, listBottom = y1 - pad - 26;
		int rowsCount = (entries.size() + columns - 1) / columns;
		libraryContentHeight = rowsCount * (cardH + 6.0);
		libraryScroll = clampScroll(libraryScroll, libraryContentHeight, listBottom - listTop);
		libraryBounds = new int[]{x0, listTop, x1, listBottom};
		g.enableScissor(x0 + 1, listTop, x1 - 1, listBottom);
		for (int i = 0; i < entries.size(); i++) {
			String pack = entries.get(i);
			int cx0 = x0 + pad + (i % columns) * (cardW + 6);
			int cy0 = listTop + (i / columns) * (cardH + 6) - (int) libraryScroll;
			int cx1 = cx0 + cardW, cy1 = cy0 + cardH;
			if (cy1 < listTop || cy0 > listBottom) {
				continue;
			}
			boolean isSelected = Objects.equals(pack, selected);
			boolean hover = inside(mx, my, cx0, Math.max(cy0, listTop), cx1, Math.min(cy1, listBottom));
			Theme.round(g, cx0, cy0, cx1, cy1, isSelected ? Theme.ACCENT_SOFT : hover ? Theme.CARD_HOVER : Theme.CARD);
			if (isSelected) {
				Theme.roundOutline(g, cx0, cy0, cx1, cy1, Theme.ACCENT);
			}
			String name = pack == null ? t("pack_off") : ShaderPackSettings.displayName(pack);
			g.text(font, Theme.fit(font, name, cardW - 26), cx0 + 8, cy0 + 6, Theme.TEXT, false);
			boolean isActive = Objects.equals(pack, active);
			String sub = pack == null ? t("pack_vanilla") : isActive ? statusText()
					: pack.toLowerCase(Locale.ROOT).endsWith(".zip") ? t("pack_zip") : t("pack_folder");
			g.text(font, Theme.fit(font, sub, cardW - 26), cx0 + 8, cy0 + 17, isActive && pack != null ? statusColor() : Theme.TEXT_MUTED, false);
			if (pack != null && isActive && engine.loadProgress() != null) {
				Theme.spinner(g, cx1 - 11, (cy0 + cy1) / 2, 3, Theme.ACCENT_TEXT);
			} else if (isActive) {
				g.fill(cx1 - 13, (cy0 + cy1) / 2 - 2, cx1 - 9, (cy0 + cy1) / 2 + 2, pack == null ? Theme.TEXT_DIM : Theme.GOOD);
			}
			if (hover && pack != null && !isSelected && prefetched.add(pack)) {
				// Read ahead: by the time it is clicked its options are usually there.
				PackOptions.request(pack);
			}
			hotspots.add(new Hotspot(cx0, Math.max(cy0, listTop), cx1, Math.min(cy1, listBottom), () -> {
				select(pack);
				library = false;
			}, null, null, "pack"));
		}
		g.disableScissor();
		if (packs.isEmpty()) {
			g.textWithWordWrap(font, Component.translatable(KEYS + "no_packs"), x0 + pad, listTop + cardH + 12, w - 2 * pad, Theme.TEXT_MUTED, false);
		}
		int by0 = y1 - pad - 18, by1 = y1 - pad;
		int fw = font.width(t("open_folder")) + 20;
		button(g, font, x0 + pad, by0, x0 + pad + fw, by1, t("open_folder"), false, mx, my, () -> Blaze3D.openPath(ShaderPackSettings.packsDirectory()));
		int rw = font.width(t("reload")) + 20;
		button(g, font, x0 + pad + fw + 4, by0, x0 + pad + fw + 4 + rw, by1, t("reload"), false, mx, my, this::reloadFromDisk);
		int closeW = Math.max(52, font.width(t("close")) + 20);
		button(g, font, x1 - pad - closeW, by0, x1 - pad, by1, t("close"), false, mx, my, () -> library = false);
	}

	private static boolean inside(int mx, int my, int x0, int y0, int x1, int y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}

	/** A question over the dimmed screen: its text and a row of buttons (the primary one also answers Enter). */
	private void drawConfirm(GuiGraphicsExtractor g, Font font, Confirm question, int mx, int my) {
		// Only the question's buttons can be clicked.
		hotspots.clear();
		g.fill(0, 0, vw, vh, 0x99000000);
		int w = Math.min(380, vw - 40);
		List<String> lines = Theme.wrap(font, question.message(), w - 24, 6);
		int h = 20 + lines.size() * 10 + 34;
		int x0 = (vw - w) / 2, y0 = (vh - h) / 2;
		Theme.panel(g, x0, y0, x0 + w, y0 + h);
		int ty = y0 + 12;
		for (String line : lines) {
			g.text(font, line, x0 + 12, ty, Theme.TEXT, false);
			ty += 10;
		}
		int bx = x0 + w - 12;
		for (Choice choice : question.choices()) {
			int bw = Math.max(56, font.width(choice.label()) + 20);
			button(g, font, bx - bw, y0 + h - 30, bx, y0 + h - 10, choice.label(), choice.primary(), mx, my, () -> answer(choice));
			bx -= bw + 4;
		}
	}

	private void answer(Choice choice) {
		confirm = null;
		choice.action().run();
	}

	/**
	 * Saves the selected pack's settings (pending changes included, defaults left out) to
	 * {@code shaderpacks/<pack> settings <time>.txt}, in the {@code .txt} form packs folders use (renamed to
	 * {@code <pack>.txt} next to the pack, it loads as its settings), and shows the folder.
	 */
	private void exportSettings() {
		if (options == null || selected == null) {
			return;
		}
		String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"));
		Path file = ShaderPackSettings.packsDirectory().resolve(selected + " settings " + time + ".txt");
		try {
			ShaderPackSettings.writeOptionsTo(file, options.changesToSave());
			exportedTo = file.getFileName().toString();
			Blaze3D.openPath(ShaderPackSettings.packsDirectory());
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.error("could not export settings to {}", file, e);
			exportedTo = null;
		}
	}

	/** File name of the last export (shown in the footer). */
	private @Nullable String exportedTo;

	/** Import: the system's file dialog, opened at the pack's settings file; the answer is taken on a later frame. */
	private void chooseImport() {
		if (options == null || selected == null || pendingImport != null) {
			return;
		}
		pendingImport = FileDialog.open(t("import_title"), ShaderPackSettings.packsDirectory().resolve(selected + ".txt"));
	}

	private void pollImport() {
		var future = pendingImport;
		if (future == null || !future.isDone()) {
			return;
		}
		pendingImport = null;
		try {
			future.join().ifPresent(this::importSettings);
		} catch (CompletionException | CancellationException e) {
			AetheriumShaders.logger.warn("no file dialog for importing settings", e.getCause() == null ? e : e.getCause());
			showToast(t("import_no_dialog"), Theme.WARN);
		}
	}

	/**
	 * A settings file ({@code .txt}, the form the packs folder and Export use) for the shown pack: its values become the
	 * pending values, every option it does not name goes back to its default. Nothing changes until Apply.
	 */
	private void importSettings(Path file) {
		if (options == null) {
			return;
		}
		Map<String, String> values = new HashMap<>();
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
			Properties properties = new Properties();
			properties.load(r);
			properties.forEach((k, v) -> values.put(k.toString(), v.toString()));
		} catch (IOException | IllegalArgumentException e) {
			AetheriumShaders.logger.error("could not import settings from {}", file, e);
			showToast(t("import_failed", file.getFileName().toString()), Theme.BAD);
			return;
		}
		int known = options.importValues(values);
		showToast(t("imported", plural("settings_count", known), file.getFileName().toString()), known == 0 ? Theme.WARN : Theme.GOOD);
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

	// ---------------------------------------------------------------- widgets

	private void button(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, String label, boolean primary, int mx, int my, Runnable action) {
		boolean hover = mx >= x0 && mx < x1 && my >= y0 && my < y1;
		int color = primary ? (hover ? Theme.mix(Theme.ACCENT, 0xFFFFFFFF, 0.15f) : Theme.ACCENT) : hover ? Theme.CARD_HOVER : Theme.CARD;
		Theme.round(g, x0, y0, x1, y1, color);
		g.centeredText(font, Theme.fit(font, label, x1 - x0 - 8), (x0 + x1) / 2, y0 + (y1 - y0 - 8) / 2, primary ? 0xFFFFFFFF : Theme.TEXT);
		hotspots.add(new Hotspot(x0, y0, x1, y1, action, null, null, "button"));
	}

	private void chip(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, String label, boolean on, int mx, int my, Runnable action) {
		boolean hover = mx >= x0 && mx < x1 && my >= y0 && my < y1;
		Theme.round(g, x0, y0, x1, y1, on ? Theme.ACCENT_SOFT : hover ? Theme.CARD_HOVER : Theme.CARD);
		if (on) {
			Theme.roundOutline(g, x0, y0, x1, y1, Theme.ACCENT);
		}
		g.centeredText(font, Theme.fit(font, label, x1 - x0 - 6), (x0 + x1) / 2, y0 + (y1 - y0 - 8) / 2, on ? 0xFFFFFFFF : Theme.TEXT_DIM);
		hotspots.add(new Hotspot(x0, y0, x1, y1, action, null, null, "chip"));
	}

	private void pill(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, String text, boolean pending, boolean hover) {
		Theme.round(g, x0, y0, x1, y1, hover ? 0x40000000 : 0x30000000);
		if (pending) {
			Theme.roundOutline(g, x0, y0, x1, y1, Theme.ACCENT_SOFT);
		}
		String fitted = Theme.fit(font, text, x1 - x0 - 34);
		g.centeredText(font, fitted, (x0 + x1) / 2, y0 + (y1 - y0 - 8) / 2, pending ? Theme.ACCENT_TEXT : Theme.TEXT);
	}




	private void hot(int x0, int y0, int x1, int y1, Runnable left, @Nullable Runnable right, @Nullable Runnable middle, Object tag) {
		int cy0 = Math.max(y0, listY0), cy1 = Math.min(y1, listY1);
		if (cy1 > cy0) {
			hotspots.add(new Hotspot(x0, cy0, x1, cy1, left, right, middle, tag));
		}
	}

	// ---------------------------------------------------------------- state

	private OptionMenuElementScreen currentScreen() {
		return options.screen(path.peek());
	}

	private void openScreen(String id) {
		path.push(id);
		resetList();
	}

	private List<PackOptions.Located> visibleItems() {
		String query = search.getValue().trim();
		if (!query.isEmpty()) {
			List<PackOptions.Located> hits = options.search(query);
			if (changedOnly) {
				hits.removeIf(h -> h.element() instanceof OptionMenuOptionElement o && options.isDefault(o.optionId));
			}
			return hits;
		}
		if (changedOnly) {
			List<PackOptions.Located> changed = options.search("");
			changed.removeIf(h -> !(h.element() instanceof OptionMenuOptionElement o) || options.isDefault(o.optionId));
			return changed;
		}
		List<PackOptions.Located> items = new ArrayList<>();
		for (OptionMenuElement e : currentScreen().elements) {
			items.add(new PackOptions.Located(e, List.of()));
		}
		return items;
	}

	private void select(@Nullable String pack) {
		if (Objects.equals(pack, selected)) {
			return;
		}
		selected = pack;
		openPack(pack);
		if (search != null) {
			search.setValue("");
		}
		changedOnly = false;
	}

	private void cycleProfile(List<String> names, Optional<String> current, int delta) {
		if (names.isEmpty()) {
			return;
		}
		int i = current.map(names::indexOf).orElse(delta > 0 ? -1 : 0);
		options.applyProfile(names.get(Math.floorMod(i + delta, names.size())));
	}

	private String statusText() {
		if (engine.lastError() != null) {
			return t("status_failed");
		}
		ShaderPackEngine.LoadProgress progress = engine.loadProgress();
		if (progress != null) {
			return t("status_loading_percent", Math.round(progress.overall() * 100));
		}
		if (applyStarted >= 0) {
			return t("status_loading");
		}
		return engine.running() ? t("status_active") : minecraft.level == null ? t("status_active_in_worlds") : t("status_loading");
	}

	private int statusColor() {
		if (engine.lastError() != null) {
			return Theme.BAD;
		}
		if (engine.loadProgress() != null) {
			return Theme.ACCENT_TEXT;
		}
		return engine.running() ? Theme.GOOD : Theme.TEXT_DIM;
	}

	private void showToast(String text, int color) {
		toast = text;
		toastColor = color;
		toastUntil = Util.getMillis() + 3500;
	}

	/** Saves pending options, switches pack if needed, and reloads the engine (loads on the next frame). */
	private void apply() {
		boolean packChange = !Objects.equals(selected, active);
		boolean optionChange = options != null && options.pendingCount() > 0;
		if (!packChange && !optionChange) {
			return;
		}
		if (optionChange) {
			ShaderPackSettings.writeOptions(options.packName(), options.changesToSave());
		}
		if (packChange) {
			ShaderPackSettings.setActivePack(selected);
			active = selected;
		}
		engine.reload();
		if (optionChange && selected != null) {
			// Re-read in the background so "applied" values match what was saved (the shown values stay meanwhile).
			readOptions(selected, true);
		}
		if (selected == null) {
			showToast(t("toast_off"), Theme.TEXT_DIM);
		} else if (minecraft.level == null) {
			showToast(t("toast_saved"), Theme.TEXT_DIM);
		} else {
			applyStarted = Util.getMillis();
		}
	}

	/**
	 * The packs folder and the shown pack read again from disk (edits made to a pack's files show up), and the active
	 * pack reloaded. Pending changes stay pending.
	 */
	private void reloadFromDisk() {
		PackOptions.forgetAll();
		prefetched.clear();
		packs = ShaderPackSettings.listPacks();
		if (selected != null) {
			if (options != null) {
				readOptions(selected, true);
			} else {
				openPack(selected);
			}
		}
		if (active != null) {
			engine.reload();
			if (minecraft.level != null) {
				applyStarted = Util.getMillis();
			}
		}
		showToast(t("reloaded", plural("packs_count", packs.size())), Theme.TEXT_DIM);
	}

	/** After apply: report when the engine has loaded the pack (next frame) or failed. */
	private void pollApply() {
		if (applyStarted < 0) {
			return;
		}
		if (engine.lastError() != null) {
			showToast(t("toast_failed"), Theme.BAD);
			applyStarted = -1;
		} else if (engine.running()) {
			long ms = Util.getMillis() - applyStarted;
			showToast(t("toast_applied", ms < 1000 ? t("duration_ms", ms) : t("duration_s", String.format(Locale.ROOT, "%.1f", ms / 1000.0))), Theme.GOOD);
			applyStarted = -1;
		} else if (!engine.loading() && Util.getMillis() - applyStarted > 15000) {
			applyStarted = -1;
		}
	}

	private boolean unapplied() {
		return options != null && options.pendingCount() > 0 || !Objects.equals(selected, active);
	}

	private void askResetAll() {
		if (options == null) {
			return;
		}
		PackOptions shown = options;
		confirm = new Confirm(t("confirm_reset", ShaderPackSettings.displayName(shown.packName())), List.of(
				new Choice(t("reset_all"), true, shown::resetAll),
				new Choice(t("cancel"), false, () -> {
				})));
	}

	// ================================================================ keyboard focus

	private PackOptions.@Nullable Located focusedItem() {
		return focus >= 0 && focus < lastRows.size() ? lastRows.get(focus).item() : null;
	}

	/**
	 * Moves the focus {@code delta} rows on (skipping headings and empty cells), wrapping around the ends with {@code wrap}; the
	 * first or last option when none had it.
	 */
	private void moveFocus(int delta, boolean wrap) {
		List<Row> items = lastRows;
		if (items.isEmpty() || delta == 0) {
			return;
		}
		int i;
		if (focus < 0) {
			i = delta > 0 ? 0 : items.size() - 1;
		} else {
			i = focus + delta;
			if (i < 0 || i >= items.size()) {
				if (!wrap) {
					return;
				}
				i = Math.floorMod(i, items.size());
			}
		}
		int step = delta > 0 ? 1 : -1;
		while (i >= 0 && i < items.size() && (items.get(i).item() == null || items.get(i).item().element() == OptionMenuElement.EMPTY)) {
			i += step;
		}
		if (i >= 0 && i < items.size()) {
			focus = i;
			scrollToFocus = true;
		}
	}

	/** Enter on an option: toggles a switch, steps a value forward, opens a screen, or moves to the next profile. */
	private void activate(PackOptions.Located item) {
		change(item, 1, true);
	}

	/**
	 * Left/right on an option: steps its value ({@code direction} -1 or +1); a switch is toggled either way. Screen
	 * links open with {@code open} (Enter, right arrow).
	 */
	private void change(PackOptions.Located item, int direction, boolean open) {
		OptionMenuElement element = item.element();
		if (element instanceof OptionMenuLinkElement link) {
			if (open) {
				openScreen(link.targetScreenId);
			}
		} else if (element instanceof OptionMenuProfileElement) {
			cycleProfile(options.profileNames(), options.currentProfile(), direction);
		} else if (element instanceof OptionMenuBooleanOptionElement b) {
			options.set(b.optionId, Boolean.toString(!Boolean.parseBoolean(options.value(b.optionId))));
		} else if (element instanceof OptionMenuStringOptionElement s) {
			options.set(s.optionId, options.step(s.option, options.value(s.optionId), direction));
		} else {
			return;
		}
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.25f));
	}

	// ================================================================ input

	private double mouseXNow() {
		return minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()) / ui;
	}

	/** {@code event} in menu units. */
	private MouseButtonEvent scaled(MouseButtonEvent event) {
		return new MouseButtonEvent(event.x() / ui, event.y() / ui, event.buttonInfo());
	}

	/** The value under the mouse on the slider being dragged (taken as the option's value on release). */
	private void dragTo(double mouseX) {
		if (dragging == null) {
			return;
		}
		List<String> values = dragging.option.getAllowedValues();
		if (values.isEmpty()) {
			return;
		}
		double t = (mouseX - dragX0) / Math.max(1, dragX1 - dragX0);
		int index = (int) Math.round(Math.max(0, Math.min(1, t)) * (values.size() - 1));
		dragValue = values.get(index);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent raw, boolean doubleClick) {
		MouseButtonEvent event = scaled(raw);
		if (peek) {
			return true;
		}
		if (confirm == null && !library && !moreMenu && super.mouseClicked(event, doubleClick)) {
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
				if (h.tag() instanceof PackOptions.Located) {
					// The mouse takes over from the keyboard focus.
					focus = -1;
				}
				action.run();
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.25f));
			}
			return true;
		}
		return confirm != null;
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
		if (dragging != null && dragValue != null && options != null) {
			// One change per drag: the value the slider was let go at.
			options.set(dragging.optionId, dragValue);
		}
		dragging = null;
		dragValue = null;
		return super.mouseReleased(scaled(event));
	}

	@Override
	public boolean mouseScrolled(double rawX, double rawY, double scrollX, double scrollY) {
		if (confirm != null) {
			return true;
		}
		double x = rawX / ui, y = rawY / ui;
		if (library) {
			libraryScroll -= scrollY * 36;
			return true;
		}
		if (moreMenu) {
			return true;
		}
		if (x < navX1) {
			navScroll -= scrollY * 34;
			return true;
		}
		if (minecraft.hasShiftDown() && hoveredStringOption(x, y) instanceof OptionMenuStringOptionElement s) {
			options.set(s.optionId, options.step(s.option, options.value(s.optionId), scrollY > 0 ? -1 : 1));
			return true;
		}
		if (minecraft.hasShiftDown() && hoveredOption(x, y) instanceof OptionMenuBooleanOptionElement b) {
			options.set(b.optionId, Boolean.toString(!Boolean.parseBoolean(options.value(b.optionId))));
			return true;
		}
		optionScroll -= scrollY * (ROW + GAP) * 2;
		return true;
	}

	private @Nullable OptionMenuElement hoveredOption(double x, double y) {
		for (int i = hotspots.size() - 1; i >= 0; i--) {
			Hotspot h = hotspots.get(i);
			if (h.contains(x, y) && h.tag() instanceof PackOptions.Located located) {
				return located.element();
			}
		}
		return null;
	}

	private @Nullable OptionMenuStringOptionElement hoveredStringOption(double x, double y) {
		return hoveredOption(x, y) instanceof OptionMenuStringOptionElement s ? s : null;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		boolean enter = key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER;
		if (confirm != null) {
			// A question is open: Escape keeps editing (the last choice), Enter takes the primary one.
			Confirm question = confirm;
			if (key == InputConstants.KEY_ESCAPE) {
				answer(question.choices().getLast());
			} else if (enter) {
				question.choices().stream().filter(Choice::primary).findFirst().ifPresent(this::answer);
			}
			return true;
		}
		if (key == InputConstants.KEY_TAB) {
			// Held: preview (see extractRenderState); tapped: next option (on release).
			if (tabDownAt < 0) {
				tabDownAt = Util.getMillis();
				tabBack = event.hasShiftDown();
			}
			return true;
		}
		boolean ctrl = event.hasControlDown();
		if (ctrl && key == InputConstants.KEY_F && options != null) {
			setFocused(search);
			search.setFocused(true);
			focus = -1;
			return true;
		}
		if (ctrl && key == InputConstants.KEY_S) {
			apply();
			return true;
		}
		if (options != null && search.isFocused() && key == InputConstants.KEY_DOWN) {
			// From the search box down into the results.
			search.setFocused(false);
			setFocused(null);
			focus = -1;
			moveFocus(1, false);
			return true;
		}
		if (options != null && !search.isFocused()) {
			PackOptions.Located target = focusedItem();
			if (target == null && hoveredTag instanceof PackOptions.Located hovered) {
				target = hovered;
			}
			switch (key) {
				case InputConstants.KEY_UP -> {
					moveFocus(-1, false);
					return true;
				}
				case InputConstants.KEY_DOWN -> {
					moveFocus(1, false);
					return true;
				}
				case InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT -> {
					if (target != null) {
						change(target, key == InputConstants.KEY_RIGHT ? 1 : -1, key == InputConstants.KEY_RIGHT);
					}
					return true;
				}
				case InputConstants.KEY_SPACE -> {
					if (focusedItem() != null) {
						activate(focusedItem());
						return true;
					}
				}
				default -> {
				}
			}
			if (enter && focusedItem() != null) {
				activate(focusedItem());
				return true;
			}
		}
		if (enter && !search.isFocused()) {
			apply();
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE && (library || moreMenu)) {
			library = false;
			moreMenu = false;
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE) {
			if (search.isFocused() || !search.getValue().isEmpty()) {
				search.setValue("");
				search.setFocused(false);
				return true;
			}
			if (changedOnly) {
				changedOnly = false;
				resetList();
				return true;
			}
			if (!path.isEmpty()) {
				path.pop();
				resetList();
				return true;
			}
		}
		if (key == InputConstants.KEY_BACKSPACE && !search.isFocused() && !path.isEmpty()) {
			path.pop();
			resetList();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (event.key() == InputConstants.KEY_TAB) {
			boolean peeked = peek;
			peek = false;
			boolean down = tabDownAt >= 0;
			tabDownAt = -1;
			if (down && !peeked && confirm == null && options != null) {
				search.setFocused(false);
				setFocused(null);
				moveFocus(tabBack ? -1 : 1, true);
			}
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public void onFilesDrop(List<Path> files) {
		// A settings file dropped while a pack's options are shown is imported; packs are installed.
		Path settings = files.stream().filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt") && Files.isRegularFile(f))
				.findFirst().orElse(null);
		if (settings != null) {
			if (options != null) {
				importSettings(settings);
			} else {
				showToast(t("import_pick_pack"), Theme.WARN);
			}
			if (files.size() == 1) {
				return;
			}
		}
		int added = 0;
		for (Path file : files) {
			String name = file.getFileName().toString();
			boolean zip = name.toLowerCase(Locale.ROOT).endsWith(".zip");
			if (!zip && !Files.isDirectory(file.resolve("shaders"))) {
				continue;
			}
			try {
				Path target = ShaderPackSettings.packsDirectory().resolve(name);
				Files.createDirectories(target.getParent());
				if (zip) {
					Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
				} else {
					copyTree(file, target);
				}
				added++;
			} catch (IOException e) {
				AetheriumShaders.logger.error("could not copy {}", file, e);
			}
		}
		packs = ShaderPackSettings.listPacks();
		if (watch == null) {
			// The folder may have been created by the copy: watch it from now on.
			watch = PackFolderWatch.start(ShaderPackSettings.packsDirectory());
		}
		if (settings == null || added > 0) {
			showToast(added == 0 ? t("dropped_nothing") : t("dropped_added", plural("packs_count", added)), added == 0 ? Theme.WARN : Theme.GOOD);
		}
	}

	private static void copyTree(Path from, Path to) throws IOException {
		try (var stream = Files.walk(from)) {
			for (Path p : (Iterable<Path>) stream::iterator) {
				Path dest = to.resolve(from.relativize(p).toString());
				if (Files.isDirectory(p)) {
					Files.createDirectories(dest);
				} else {
					Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	/** Escape: leaves at once when everything is applied, else asks whether to apply or discard (or keep editing). */
	@Override
	public void onClose() {
		if (!unapplied()) {
			close();
			return;
		}
		int pending = options == null ? 0 : options.pendingCount();
		confirm = new Confirm(t("confirm_leave", pending == 0 ? t("footer_pack_change") : plural("footer_options", pending)), List.of(
				new Choice(t("apply"), true, () -> {
					apply();
					close();
				}),
				new Choice(t("discard"), false, () -> {
					if (options != null) {
						options.discardPending();
					}
					selected = active;
					close();
				}),
				new Choice(t("keep_editing"), false, () -> {
				})));
	}

	private void close() {
		minecraft.gui.setScreen(parent);
	}

	private static double clampScroll(double scroll, double content, double view) {
		return Math.max(0, Math.min(scroll, Math.max(0, content - view)));
	}
}
