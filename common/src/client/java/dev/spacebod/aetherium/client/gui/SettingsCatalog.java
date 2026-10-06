package dev.spacebod.aetherium.client.gui;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.spacebod.aetherium.client.gpu.VulkanAccess;
import dev.spacebod.aetherium.client.Crosshair;
import dev.spacebod.aetherium.client.gui.Setting.Tag;
import dev.spacebod.aetherium.client.zoom.Zoom;
import dev.spacebod.aetherium.client.zoom.ZoomHud;
import dev.spacebod.aetherium.shaders.gui.Theme;
import dev.spacebod.aetherium.core.AetheriumConfig;
import dev.spacebod.aetherium.core.Optimisations;
import dev.spacebod.aetherium.shaders.compat.lod.LodCompat;
import dev.spacebod.aetherium.shaders.config.ShaderPackSettings;
import dev.spacebod.aetherium.shaders.engine.RenderScale;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.gui.ShaderKeys;
import dev.spacebod.aetherium.shaders.shaderpack.ShadowOverrides;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * Every page of the settings screen: only what vanilla's own settings do not have. Aetherium's optimisations and
 * engine switches, the shader pack entry (with the LOD mod's status when it is installed), and the developer settings.
 */
final class SettingsCatalog {
	/** What the catalog needs from the screen: pending values (for settings that depend on others) and navigation. */
	interface Host {
		Object value(Setting<?> setting);

		void stage(Setting<?> setting, Object value);

		void openShaderPacks();

		void toast(String text, int color);
	}

	/** The user-facing face of one optimisation. */
	private record Meta(String group, String name, String description, List<Tag> tags) {
	}

	private static final String MEMORY = "Memory";
	private static final String SHADER_ENGINE = "Shader engine";
	private static final String EXPERIMENTAL = "Experimental";

	private static final Map<String, Meta> META = new LinkedHashMap<>();

	static {
		meta("mem.state_cache_dedup", MEMORY, "Shared block shapes",
				"Block states share identical collision shapes and face data instead of each keeping its own copy (6,000 shape objects become about 330).",
				Tag.LOSSLESS, Tag.SAVES_MEMORY);
		meta("mem.component_patch", MEMORY, "Shared empty item data",
				"Item data that has been emptied again shares one empty table instead of keeping an empty copy per item.",
				Tag.LOSSLESS, Tag.SAVES_MEMORY);
		meta("mem.palette_lock", MEMORY, "Lightweight chunk data checks",
				"Chunk storage detects cross-thread misuse with a single number instead of a lock object per section (about 13 MB at 16 chunks render distance).",
				Tag.LOSSLESS, Tag.SAVES_MEMORY);
		meta("shaders.lean_frame", SHADER_ENGINE, "Lean frame",
				"Skips the depth copies, conversions and clears that the loaded pack never reads. About 3% faster with packs; the picture is identical.",
				Tag.LOSSLESS, Tag.SPEEDS_UP_GPU);
		meta("shaders.shadow_cache", SHADER_ENGINE, "Shadow cache",
				"Keeps the terrain part of the shadow map between frames and redraws it only when the sun has moved a fiftieth of a degree, you move to another spot or a chunk changes, so terrain shadows can trail the sun by that much; mobs and players still cast shadows every frame. Up to 16% faster with shadow-heavy packs.",
				Tag.NEAR_LOSSLESS, Tag.SPEEDS_UP_GPU, Tag.SPEEDS_UP_CPU);
		meta("shaders.early_hand", SHADER_ENGINE, "Lit first-person hand",
				"Draws your hand and held items together with the world, so packs that light the scene afterwards light them too. Off: the hand stays unlit (full bright) in those packs.",
				List.of());
		meta("shaders.nan_preserve", SHADER_ENGINE, "Exact NaN handling",
				"Keeps packs' checks for invalid pixel values working on Vulkan, as they do on OpenGL. Without it some packs (FastPBR) turn the screen black. Applies when a pack loads.",
				List.of());
		meta("shaders.inline_compute", SHADER_ENGINE, "Inline compute",
				"Records packs' compute programs into the frame's own command list instead of splitting it for each group of them. The picture is identical.",
				Tag.LOSSLESS, Tag.SPEEDS_UP_CPU);
		meta("gpu.pipeline_cache", EXPERIMENTAL, "Pipeline cache",
				"Keeps compiled GPU pipelines on disk (aetherium/pipeline-cache.bin) so the next launch and shader pack loads can start faster. Ignored automatically after a driver or GPU change. How much time it saves is not measured yet.",
				Tag.LOSSLESS, Tag.EXPERIMENTAL);
		meta("shaders.reach_snapshots", SHADER_ENGINE, "Targeted snapshots",
				"Each world pass copies only the pack buffers that the shaders able to draw in it can actually read, instead of every buffer any of them mentions. The picture is identical.",
				Tag.LOSSLESS, Tag.SPEEDS_UP_GPU);
		meta("shaders.settled_bindings", SHADER_ENGINE, "Settled texture bindings",
				"Works out each pack shader's textures once per render pass and re-binds only what changed; per draw only the texture's own material maps follow it. Helps most with many mobs on screen. The picture is identical.",
				Tag.LOSSLESS, Tag.SPEEDS_UP_CPU);
		meta("shaders.frozen_fullscreen", SHADER_ENGINE, "Prepared screen passes",
				"The pack's full-screen passes keep their texture plan from load and upload their settings in one go per stage instead of one at a time. The picture is identical.",
				Tag.LOSSLESS, Tag.SPEEDS_UP_CPU);
		meta("shaders.cpu_matrices", SHADER_ENGINE, "Precomputed terrain matrices",
				"Terrain shaders read the frame's normal and inverse view matrices instead of working them out for every vertex and pixel. Applies when a pack loads.",
				Tag.LOSSLESS, Tag.SPEEDS_UP_GPU);
		meta("shaders.hw_shadow_compare", SHADER_ENGINE, "Hardware shadow filtering",
				"Packs' shadow-map comparisons use the GPU's built-in depth-comparison filtering (one lookup) instead of four lookups and a blend in the shader, as OpenGL does it. Faster with shadow-heavy packs; shadows look the same.",
				Tag.LOSSLESS, Tag.SPEEDS_UP_GPU);
		meta("shaders.blit_mips", SHADER_ENGINE, "Blitted mipmaps",
				"Fills packs' blurred copies of their buffers (bloom, exposure) and mipmapped shadow maps with direct image copies instead of one draw pass per level, and only when the buffer changed. On wide screens the chains now go down to a single pixel as on OpenGL.",
				Tag.VULKAN, Tag.SPEEDS_UP_GPU);
		meta("shaders.loadop_clears", SHADER_ENGINE, "Clears on load",
				"Packs' per-frame buffer clears happen as the first pass that draws into each buffer starts, and the rest together in one go, instead of one separate clear (and GPU stall) per buffer. The picture is identical.",
				Tag.LOSSLESS, Tag.SPEEDS_UP_GPU);
		meta("shaders.narrow_barrier", SHADER_ENGINE, "Narrow pass barriers",
				"After each of the shader engine's own passes the GPU waits only for what that pass wrote, instead of for all of its memory. The game's own passes are unchanged. The picture is identical.",
				Tag.LOSSLESS, Tag.VULKAN, Tag.SPEEDS_UP_GPU);
		meta("shaders.flip_only_doubles", SHADER_ENGINE, "Single buffers where possible",
				"Gives a pack's buffer its second (ping-pong) copy only when a pass writes or swaps it. Saves video memory; the amount is in the log. Applies when a pack loads or the window is resized.",
				Tag.LOSSLESS, Tag.SAVES_MEMORY);
		meta("shaders.shadow_cache_bind", SHADER_ENGINE, "Direct shadow cache",
				"When the shadow cache is reused and nothing is drawn on top of it, packs read the cached shadow map directly instead of copying it back first. The picture is identical.",
				Tag.LOSSLESS, Tag.SPEEDS_UP_GPU);
		meta("shaders.zero_locals", SHADER_ENGINE, "Zeroed shader variables",
				"Pack shader variables that are read before they are written start at zero, as OpenGL drivers give them. Without it some packs show black faces or flickering lines on Vulkan. Only the variables that need it are touched.",
				List.of());
		meta("shaders.transform_cache", SHADER_ENGINE, "Shader transform cache",
				"Keeps each pack program's converted shader code on disk (at most 256 MiB), so loading the same pack again skips most of the conversion work. Anything that changes the result (pack options, settings, an Aetherium update) makes a new entry. Tools has a button to clear it.",
				Tag.LOSSLESS, Tag.SPEEDS_UP_CPU);
	}

	private static void meta(String id, String group, String name, String description, Tag... tags) {
		META.put(id, new Meta(group, name, description, List.of(tags)));
	}

	private static void meta(String id, String group, String name, String description, List<Tag> tags) {
		META.put(id, new Meta(group, name, description, tags));
	}

	private final Minecraft minecraft = Minecraft.getInstance();
	private final Host host;
	final AetheriumStore store = new AetheriumStore();
	private final Map<String, Setting<Boolean>> optimisations = new LinkedHashMap<>();

	SettingsCatalog(Host host) {
		this.host = host;
		for (Optimisations.Optimisation o : Optimisations.ALL) {
			optimisations.put(o.id(), optimisation(o));
		}
	}

	/** The pages; each page's settings are built once per screen, so staged values stay attached to them. */
	List<Setting.Page> pages() {
		List<Setting.Page> pages = new ArrayList<>();
		pages.add(page("performance", "Performance", "Aetherium's optimisations", "⚡", this::performance));
		pages.add(page("shaders", "Shaders", "Shader packs and the shader engine", "☀", this::shaders));
		pages.add(page("hud", "HUD", "Zoom, its readout and the crosshair", "⌕", this::hud));
		pages.add(page("advanced", "Advanced", "Profile, tools and system", "⚙", this::advanced));
		return pages;
	}

	private final Map<String, List<Setting.Section>> built = new LinkedHashMap<>();

	private Setting.Page page(String id, String title, String subtitle, String icon, java.util.function.Supplier<List<Setting.Section>> sections) {
		return new Setting.Page(id, title, subtitle, icon, () -> built.computeIfAbsent(id, k -> sections.get()));
	}

	Iterable<Setting<Boolean>> allOptimisations() {
		return optimisations.values();
	}

	// ================================================================ pages

	private List<Setting.Section> performance() {
		Setting<String> recommended = Setting.action("aetherium.preset.recommended", "Recommended",
				"Every optimisation at its recommended value: experiments off, everything else on.", "Use",
				() -> preset(true));
		Setting<String> allOff = Setting.action("aetherium.preset.off", "All off",
				"Turns every optimisation off: plain vanilla behaviour, for comparing or for tracking down a problem.", "Use",
				() -> preset(false));
		Setting.Sections sections = new Setting.Sections()
				.add("Presets", List.of(recommended, allOff));
		for (String group : List.of(MEMORY, EXPERIMENTAL)) {
			sections.add(group, group(group));
		}
		return sections.build();
	}

	private List<Setting.Section> shaders() {
		ShaderPackEngine engine = ShaderPackEngine.get();
		Setting<String> packs = Setting.action("aetherium.shader_packs", "Shader packs",
				"Choose a shader pack, change its options and see the result live. Drop pack zips onto that screen to install them.",
				"Open…", host::openShaderPacks).keywords("shaderpack pack");
		Setting<String> active = Setting.info("aetherium.shader_active", "Active pack", "The pack in use and its state.", () -> {
			String pack = ShaderPackSettings.activePack().orElse(null);
			if (pack == null) {
				return "Off";
			}
			String name = ShaderPackSettings.displayName(pack);
			if (engine.lastError() != null) {
				return name + " · failed to load";
			}
			return name + (engine.running() ? " · active" : minecraft.level == null ? " · loads in a world" : " · loading");
		});
		Setting<String> key = Setting.info("aetherium.shader_key", "Shortcuts",
				"Keys in game: open the shader pack screen, turn shaders on and off, reload the pack from disk (unbound at first). "
						+ "Change them under Controls › Key Binds.",
				() -> "Open " + ShaderKeys.OPEN_SETTINGS.getTranslatedKeyMessage().getString() + " · Toggle "
						+ ShaderKeys.TOGGLE.getTranslatedKeyMessage().getString()
						+ (ShaderKeys.RELOAD.isUnbound() ? "" : " · Reload " + ShaderKeys.RELOAD.getTranslatedKeyMessage().getString()));
		Setting<Integer> renderScale = renderScale();
		List<Setting<?>> pack = new ArrayList<>(List.of(packs, active, key, shadowDistance(), shadowMapScale(), renderScale,
				upscaleSharpness(renderScale)));
		if (LodCompat.installed()) {
			pack.add(Setting.info("aetherium.lod.status", "Far terrain (LOD mod)",
					"How the LOD mod's far terrain is drawn with the current pack. Its own options are in its own menu.",
					() -> !LodCompat.hasRenderingEnabled() ? "Off in the LOD mod"
							: engine.running() ? (LodCompat.packDrawsLods() ? "Drawn by the pack" : "Hidden: the pack has no LOD support")
							: "Drawn by the LOD mod").keywords("lod distant terrain"));
		}
		return new Setting.Sections()
				.add("Shader pack", pack)
				.add(SHADER_ENGINE, group(SHADER_ENGINE))
				.build();
	}

	private List<Setting.Section> hud() {
		java.util.function.Function<Integer, String> times = v -> String.format(Locale.ROOT, "%.1f×", v / 10.0);
		Setting<String> key = Setting.info("aetherium.zoom.key", "Zoom key",
				"Hold it in game to zoom; the scroll wheel sets how far while held. Change it under Controls › Key Binds.",
				() -> Zoom.KEY.getTranslatedKeyMessage().getString()).keywords("zoom spyglass key");
		Setting<Integer> sensitivity = Setting.intSlider("aetherium.zoom.sensitivity", "Zoomed sensitivity",
						"How fast the mouse turns the view while zoomed. At 100% a mouse movement moves the picture as far across the screen at any zoom as it does unzoomed; lower is slower and steadier.",
						Zoom.MIN_SENSITIVITY, Zoom.MAX_SENSITIVITY, 5, Zoom::sensitivity, Zoom::setSensitivity, v -> v + "%")
				.defaults(() -> Zoom.DEFAULT_SENSITIVITY).keywords("zoom mouse sensitivity speed aim");
		Setting<Boolean> cinematic = Setting.toggle("aetherium.zoom.cinematic", "Cinematic camera",
						"Smooths the mouse while zoomed, as the game's cinematic camera does: gliding turns, slower to aim. Off: the view follows the mouse directly.",
						Zoom::cinematic, Zoom::setCinematic)
				.defaults(() -> true).keywords("zoom cinematic smooth camera");
		Setting<Integer> nominal = Setting.intSlider("aetherium.zoom.nominal", "Starting zoom",
						"The magnification every zoom starts at; scrolling changes it until the key is let go.",
						Zoom.LOWEST, Zoom.HIGHEST, 5, Zoom::nominal, Zoom::setNominal, times)
				.defaults(() -> Zoom.DEFAULT_NOMINAL).keywords("zoom nominal default start magnification");
		Setting<Integer> min = Setting.intSlider("aetherium.zoom.min", "Least zoom",
						"How far out the scroll wheel goes while zoomed. 1.0× is the normal view.",
						Zoom.LOWEST, Zoom.HIGHEST, 5, Zoom::min, Zoom::setMin, times)
				.defaults(() -> Zoom.DEFAULT_MIN).keywords("zoom minimum min magnification");
		Setting<Integer> max = Setting.intSlider("aetherium.zoom.max", "Most zoom",
						"How far in the scroll wheel goes while zoomed. Below the least zoom, the least zoom is used.",
						Zoom.LOWEST, Zoom.HIGHEST, 10, Zoom::max, Zoom::setMax, times)
				.defaults(() -> Zoom.DEFAULT_MAX).keywords("zoom maximum max magnification");
		Setting<String> corner = Setting.choice("aetherium.zoom.corner", "Readout corner",
						"The screen corner the zoom's panels sit in.",
						ZoomHud.Corner::ids, () -> ZoomHud.corner().id,
						ZoomHud::setCorner, id -> ZoomHud.Corner.of(id).label)
				.defaults(() -> ZoomHud.Corner.TOP_RIGHT.id).keywords("zoom hud overlay position corner");
		Setting<Boolean> coordinates = Setting.toggle("aetherium.zoom.coordinates", "Coordinates panel",
						"While zoomed: the block coordinates of what the crosshair is on, how far away it is and the direction you face.",
						ZoomHud::showCoordinates, ZoomHud::setShowCoordinates)
				.defaults(() -> true).keywords("zoom hud xyz coordinates distance compass direction");
		Setting<Boolean> target = Setting.toggle("aetherium.zoom.target", "Target panel",
						"While zoomed: a 3D preview and the name of the block or mob the crosshair is on.",
						ZoomHud::showTarget, ZoomHud::setShowTarget)
				.defaults(() -> true).keywords("zoom hud block mob name preview");
		Setting<Boolean> crosshair = Setting.toggle("aetherium.crosshair.centred", "Centred crosshair",
						"Moves the crosshair onto the exact centre of the window, where the camera points. The game rounds its position down, which leaves it a pixel or more left of and above the centre at most window sizes.",
						Crosshair::centred, Crosshair::setCentred)
				.defaults(() -> true).keywords("crosshair centre center alignment offset pixel");
		return new Setting.Sections()
				.add("Zoom", List.of(key, nominal, min, max, sensitivity, cinematic))
				.add("Readout", List.of(corner, coordinates, target))
				.add("Crosshair", List.of(crosshair))
				.build();
	}

	/** The shadow settings as staged on this screen; saved (and the pack reloaded) on Apply. */
	private ShadowOverrides shadows = ShaderPackSettings.shadowOverrides();
	/** One pack reload per Apply, whichever shadow settings changed. */
	private final Runnable reloadForShadows = () -> {
		ShaderPackSettings.setShadowOverrides(shadows);
		if (ShaderPackSettings.activePack().isPresent()) {
			ShaderPackEngine.get().reload();
		}
	};

	private Setting<Integer> shadowDistance() {
		return Setting.intSlider("aetherium.shaders.shadow_distance", "Shadow distance",
						"How far the shadow map reaches, in chunks: rewrites the pack's own shadowDistance before its shaders are built, so its filtering stays matched. Shorter is sharper and cheaper. Pack: the pack's value. Reloads the pack.",
						0, ShadowOverrides.MAX_DISTANCE_CHUNKS, 1, () -> shadows.distanceChunks(),
						v -> shadows = new ShadowOverrides(v, shadows.mapScale()), v -> v == 0 ? "Pack" : v + " chunks")
				.defaults(() -> 0).afterApply(reloadForShadows).keywords("shadow distance shadowDistance");
	}

	private Setting<Integer> shadowMapScale() {
		return Setting.intSlider("aetherium.shaders.shadow_map_scale", "Shadow map scale",
						"The shadow map's size as a share of what the pack asks for: rewrites the pack's own shadowMapResolution before its shaders are built, so its filtering stays matched. Lower is faster with softer, coarser shadows. Reloads the pack.",
						ShadowOverrides.MIN_MAP_SCALE, ShadowOverrides.MAX_MAP_SCALE, 5, () -> shadows.mapScale(),
						v -> shadows = new ShadowOverrides(shadows.distanceChunks(), v), v -> v == 100 ? "Pack" : v + "%")
				.defaults(() -> 100).afterApply(reloadForShadows).tags(Tag.SPEEDS_UP_GPU).keywords("shadow resolution shadowMapResolution quality");
	}

	/** Applied from the next frame: a change of scale resizes the pack's targets, without a reload. */
	private Setting<Integer> renderScale() {
		return Setting.intSlider("aetherium.shaders.render_scale", "Render scale",
						"Draws the world, the pack's passes and the hand at this share of the window, then upscales the picture with FSR 1.0 before the interface (which stays sharp). Lower is faster in heavy packs; 100% is off. The shadow map has its own setting.",
						RenderScale.MIN_PERCENT, RenderScale.MAX_PERCENT, RenderScale.STEP_PERCENT, RenderScale::percent,
						v -> {
							ShaderPackSettings.setRenderScale(v);
							RenderScale.configure(v, RenderScale.sharpness());
						}, v -> v == RenderScale.MAX_PERCENT ? "Off" : v + "%")
				.defaults(() -> RenderScale.MAX_PERCENT).tags(Tag.SPEEDS_UP_GPU).keywords("render scale resolution upscale upscaling fsr");
	}

	private Setting<Integer> upscaleSharpness(Setting<Integer> renderScale) {
		return Setting.intSlider("aetherium.shaders.upscale_sharpness", "Upscale sharpness",
						"How strongly the upscaled picture is sharpened, on FSR's own scale: 0 is the strongest, each 1.00 halves it. The default is 0.20.",
						0, RenderScale.MAX_SHARPNESS, 5, RenderScale::sharpness,
						v -> {
							ShaderPackSettings.setUpscaleSharpness(v);
							RenderScale.configure(RenderScale.percent(), v);
						}, v -> String.format(Locale.ROOT, "%.2f", v / 100.0))
				.defaults(() -> RenderScale.DEFAULT_SHARPNESS)
				.unavailableWhen(() -> Integer.valueOf(RenderScale.MAX_PERCENT).equals(host.value(renderScale)) ? "Render scale is off" : null)
				.keywords("fsr rcas sharpen sharpening upscale");
	}

	private List<Setting.Section> advanced() {
		Setting<String> profile = Setting.choice("aetherium.profile", "Optimisation profile",
						"Recommended: every optimisation at its default. Baseline: everything off, the reference the benchmarks measure against. Switching resets the per-optimisation choices.",
						() -> List.of(AetheriumConfig.PROFILE_DEFAULT, AetheriumConfig.PROFILE_BASELINE), store::profile, store::setProfile,
						p -> AetheriumConfig.PROFILE_BASELINE.equals(p) ? "Baseline (all off)" : "Recommended")
				.defaults(() -> AetheriumConfig.PROFILE_DEFAULT).order(-5).tags(Tag.RESTART).restartPendingWhen(store::profileRestartPending);
		Setting<String> resetAll = Setting.action("aetherium.reset_all", "Reset Aetherium settings",
				"Puts every Aetherium setting on this screen back to its default (vanilla options are not touched). Apply to keep it.",
				"Reset", this::resetAetherium);
		Setting<String> config = Setting.action("aetherium.open_config", "Config folder", "Opens the folder that holds aetherium.json.", "Open",
				() -> Blaze3D.openPath(minecraft.gameDirectory.toPath().resolve("config")));
		Setting<String> logs = Setting.action("aetherium.open_logs", "Logs folder", "Opens the game's log folder (latest.log has Aetherium's start-up report).", "Open",
				() -> Blaze3D.openPath(minecraft.gameDirectory.toPath().resolve("logs")));
		Setting<String> clearShaderCache = Setting.action("aetherium.clear_shader_cache", "Clear shader cache",
				"Deletes the converted shader code kept on disk (aetherium/shader-cache). The next load of each pack converts its programs again.",
				"Clear", () -> {
					long freed = dev.spacebod.aetherium.shaders.pipeline.transform.TransformDiskCache.clear();
					host.toast("Shader cache cleared (" + (freed >> 20) + " MiB)", Theme.TEXT_DIM);
				});
		Setting<Boolean> gpuTimers = Setting.toggle("aetherium.bench.gpu_timers", "GPU timers",
						"Benchmark runs measure GPU time per frame with timestamp queries. Only used by the benchmark harness.", store::gpuTimers, store::setGpuTimers)
				.defaults(() -> false).tags(Tag.RESTART).restartPendingWhen(store::debugRestartPending);
		Setting<Boolean> passTimers = Setting.toggle("aetherium.bench.pass_timers", "Per-pass GPU timers",
						"Benchmark runs also time each render pass. Only used by the benchmark harness.", store::passTimers, store::setPassTimers)
				.defaults(() -> false).tags(Tag.RESTART).restartPendingWhen(store::debugRestartPending);
		return new Setting.Sections()
				.add("Profile", List.of(profile))
				.add("Tools", List.of(config, logs, clearShaderCache, resetAll))
				.add("Developer", dev.spacebod.aetherium.client.DevHooks.installed() ? List.of(gpuTimers, passTimers) : List.of())
				.add("System", system())
				.build();
	}

	private List<Setting<?>> system() {
		List<Setting<?>> rows = new ArrayList<>();
		var info = RenderSystem.getDevice().getDeviceInfo();
		rows.add(Setting.info("aetherium.sys.api", "Graphics API",
				"The renderer the game is running on. Aetherium's GPU work and shader packs need Vulkan.",
				() -> info.backendName() + (VulkanAccess.device() != null ? "" : " · set Graphics API to Vulkan in Video Settings")));
		rows.add(Setting.info("aetherium.sys.gpu", "GPU", "The graphics card in use.", info::name));
		rows.add(Setting.info("aetherium.sys.driver", "Driver", "The graphics driver version.", () -> info.driverInfo().lines().findFirst().orElse("")));
		int javaThreads = Runtime.getRuntime().availableProcessors();
		int osThreads = osLogicalProcessors();
		rows.add(Setting.info("aetherium.sys.cpu", "CPU threads",
				osThreads > javaThreads
						? "Java only sees " + javaThreads + " of this PC's " + osThreads + " threads (Windows processor groups), so chunk building uses half the CPU. Add -XX:+UseAllWindowsProcessorGroups to the game's JVM arguments in your launcher."
						: "Logical processors available to the game; chunk building and world generation use them.",
				() -> osThreads > javaThreads ? javaThreads + " of " + osThreads + " usable" : Integer.toString(javaThreads)).keywords("cores processor groups"));
		long maxHeap = Runtime.getRuntime().maxMemory();
		rows.add(Setting.info("aetherium.sys.memory", "Memory", "Memory the game may use (set with -Xmx in your launcher) and how much is in use now.",
				() -> {
					Runtime rt = Runtime.getRuntime();
					long used = rt.totalMemory() - rt.freeMemory();
					return String.format(Locale.ROOT, "%d / %d MB", used >> 20, maxHeap >> 20);
				}).keywords("ram heap xmx"));
		return rows;
	}

	// ================================================================ helpers

	private Setting<Boolean> optimisation(Optimisations.Optimisation o) {
		String id = o.id();
		Meta meta = META.getOrDefault(id, new Meta(EXPERIMENTAL, id, o.description(), List.of()));
		List<Tag> tags = new ArrayList<>(meta.tags());
		tags.add(0, AetheriumStore.live(id) ? Tag.INSTANT : Tag.RESTART);
		Setting<Boolean> s = Setting.toggle("aetherium." + id, meta.name(), meta.description(), () -> store.enabled(id), on -> store.setEnabled(id, on))
				.defaults(() -> Optimisations.profileDefault(AetheriumConfig.PROFILE_DEFAULT, id))
				.tags(tags)
				.restartPendingWhen(() -> store.restartPending(id))
				.keywords(id + " " + meta.group());
		return s;
	}

	private List<Setting<?>> group(String group) {
		List<Setting<?>> list = new ArrayList<>();
		META.forEach((id, meta) -> {
			Setting<Boolean> s = optimisations.get(id);
			if (meta.group().equals(group) && s != null) {
				list.add(s);
			}
		});
		// Registered optimisations without user-facing text land under Experimental.
		if (group.equals(EXPERIMENTAL)) {
			optimisations.forEach((id, s) -> {
				if (!META.containsKey(id)) {
					list.add(s);
				}
			});
		}
		return list;
	}

	private void preset(boolean recommended) {
		int changed = 0;
		for (Setting<Boolean> s : optimisations.values()) {
			String id = s.id.substring("aetherium.".length());
			boolean target = recommended && Optimisations.profileDefault(AetheriumConfig.PROFILE_DEFAULT, id);
			if (!Boolean.valueOf(target).equals(host.value(s))) {
				host.stage(s, target);
				changed++;
			}
		}
		host.toast(changed == 0 ? "Already set" : changed + " change" + (changed == 1 ? "" : "s") + " staged · Apply to keep", Theme.TEXT_DIM);
	}

	private void resetAetherium() {
		preset(true);
		Map<String, java.util.function.Supplier<List<Setting.Section>>> pages = Map.of("shaders", this::shaders, "hud", this::hud, "advanced", this::advanced);
		for (var page : pages.entrySet()) {
			for (Setting.Section section : built.computeIfAbsent(page.getKey(), k -> page.getValue().get())) {
				for (Setting<?> s : section.settings()) {
					Object def = s.defaultValue();
					if (s.id.startsWith("aetherium.") && def != null && !def.equals(host.value(s))) {
						host.stage(s, def);
					}
				}
			}
		}
	}

	/**
	 * Logical processors across every Windows processor group: without a JVM flag Java sees only its own group, while
	 * Windows reports the machine's total in this variable. Other systems report everything to Java already.
	 */
	private static int osLogicalProcessors() {
		if (!System.getProperty("os.name", "").startsWith("Windows")) {
			return Runtime.getRuntime().availableProcessors();
		}
		String env = System.getenv("NUMBER_OF_PROCESSORS");
		try {
			return env == null ? Runtime.getRuntime().availableProcessors() : Integer.parseInt(env.trim());
		} catch (NumberFormatException e) {
			return Runtime.getRuntime().availableProcessors();
		}
	}
}
