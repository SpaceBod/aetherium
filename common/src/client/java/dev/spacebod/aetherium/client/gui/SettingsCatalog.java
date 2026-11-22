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

	/** Where one optimisation is listed and its tags; its name and description are in the language files. */
	private record Meta(String group, List<Tag> tags) {
	}

	/** Group ids; each one's section title is {@code aetherium.settings.group.<id>} in the language files. */
	private static final String MEMORY = "memory";
	private static final String SHADER_ENGINE = "shader_engine";
	private static final String EXPERIMENTAL = "experimental";

	private static final Map<String, Meta> META = new LinkedHashMap<>();

	static {
		meta("mem.state_cache_dedup", MEMORY, Tag.LOSSLESS, Tag.SAVES_MEMORY);
		meta("mem.component_patch", MEMORY, Tag.LOSSLESS, Tag.SAVES_MEMORY);
		meta("mem.palette_lock", MEMORY, Tag.LOSSLESS, Tag.SAVES_MEMORY);
		meta("shaders.lean_frame", SHADER_ENGINE, Tag.LOSSLESS, Tag.SPEEDS_UP_GPU);
		meta("shaders.shadow_cache", SHADER_ENGINE, Tag.NEAR_LOSSLESS, Tag.SPEEDS_UP_GPU, Tag.SPEEDS_UP_CPU);
		meta("shaders.early_hand", SHADER_ENGINE);
		meta("shaders.nan_preserve", SHADER_ENGINE);
		meta("shaders.inline_compute", SHADER_ENGINE, Tag.LOSSLESS, Tag.SPEEDS_UP_CPU);
		meta("gpu.pipeline_cache", EXPERIMENTAL, Tag.LOSSLESS, Tag.EXPERIMENTAL);
		meta("shaders.reach_snapshots", SHADER_ENGINE, Tag.LOSSLESS, Tag.SPEEDS_UP_GPU);
		meta("shaders.settled_bindings", SHADER_ENGINE, Tag.LOSSLESS, Tag.SPEEDS_UP_CPU);
		meta("shaders.frozen_fullscreen", SHADER_ENGINE, Tag.LOSSLESS, Tag.SPEEDS_UP_CPU);
		meta("shaders.cpu_matrices", SHADER_ENGINE, Tag.LOSSLESS, Tag.SPEEDS_UP_GPU);
		meta("shaders.hw_shadow_compare", SHADER_ENGINE, Tag.LOSSLESS, Tag.SPEEDS_UP_GPU);
		meta("shaders.blit_mips", SHADER_ENGINE, Tag.VULKAN, Tag.SPEEDS_UP_GPU);
		meta("shaders.loadop_clears", SHADER_ENGINE, Tag.LOSSLESS, Tag.SPEEDS_UP_GPU);
		meta("shaders.narrow_barrier", SHADER_ENGINE, Tag.LOSSLESS, Tag.VULKAN, Tag.SPEEDS_UP_GPU);
		meta("shaders.flip_only_doubles", SHADER_ENGINE, Tag.LOSSLESS, Tag.SAVES_MEMORY);
		meta("shaders.shadow_cache_bind", SHADER_ENGINE, Tag.LOSSLESS, Tag.SPEEDS_UP_GPU);
		meta("shaders.zero_locals", SHADER_ENGINE);
		meta("shaders.transform_cache", SHADER_ENGINE, Tag.LOSSLESS, Tag.SPEEDS_UP_CPU);
	}

	private static void meta(String id, String group, Tag... tags) {
		META.put(id, new Meta(group, List.of(tags)));
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
		pages.add(page("performance", "⚡", this::performance));
		pages.add(page("shaders", "☀", this::shaders));
		pages.add(page("hud", "⌕", this::hud));
		pages.add(page("advanced", "⚙", this::advanced));
		return pages;
	}

	private final Map<String, List<Setting.Section>> built = new LinkedHashMap<>();

	private Setting.Page page(String id, String icon, java.util.function.Supplier<List<Setting.Section>> sections) {
		return new Setting.Page(id, t("page." + id + ".title"), t("page." + id + ".subtitle"), icon,
				() -> built.computeIfAbsent(id, k -> sections.get()));
	}

	Iterable<Setting<Boolean>> allOptimisations() {
		return optimisations.values();
	}

	// ================================================================ text

	/** {@code aetherium.settings.<key>} in the current language. */
	private static String t(String key, Object... args) {
		return Lang.t("aetherium.settings." + key, args);
	}

	/** A setting's name: {@code aetherium.settings.<id>.name}, {@code id} without its {@code aetherium.} prefix. */
	private static String name(String id) {
		return t(id + ".name");
	}

	private static String description(String id) {
		return t(id + ".description");
	}

	private static String section(String id) {
		return t("section." + id);
	}

	private static String groupTitle(String id) {
		return t("group." + id);
	}

	// ================================================================ pages

	private List<Setting.Section> performance() {
		Setting<String> recommended = Setting.action("aetherium.preset.recommended", name("preset.recommended"),
				description("preset.recommended"), t("button.use"), () -> preset(true));
		Setting<String> allOff = Setting.action("aetherium.preset.off", name("preset.off"),
				description("preset.off"), t("button.use"), () -> preset(false));
		Setting.Sections sections = new Setting.Sections()
				.add(section("presets"), List.of(recommended, allOff));
		for (String group : List.of(MEMORY, EXPERIMENTAL)) {
			sections.add(groupTitle(group), group(group));
		}
		return sections.build();
	}

	private List<Setting.Section> shaders() {
		ShaderPackEngine engine = ShaderPackEngine.get();
		Setting<String> packs = Setting.action("aetherium.shader_packs", name("shader_packs"), description("shader_packs"),
				t("button.open_screen"), host::openShaderPacks).keywords("shaderpack pack");
		Setting<String> active = Setting.info("aetherium.shader_active", name("shader_active"), description("shader_active"), () -> {
			String pack = ShaderPackSettings.activePack().orElse(null);
			if (pack == null) {
				return t("value.off");
			}
			String name = ShaderPackSettings.displayName(pack);
			if (engine.lastError() != null) {
				return t("value.pack_failed", name);
			}
			return t(engine.running() ? "value.pack_active" : minecraft.level == null ? "value.pack_in_world" : "value.pack_loading", name);
		});
		Setting<String> key = Setting.info("aetherium.shader_key", name("shader_key"), description("shader_key"),
				() -> t("value.shortcuts", ShaderKeys.OPEN_SETTINGS.getTranslatedKeyMessage().getString(),
						ShaderKeys.TOGGLE.getTranslatedKeyMessage().getString())
						+ (ShaderKeys.RELOAD.isUnbound() ? "" : t("value.shortcuts_reload", ShaderKeys.RELOAD.getTranslatedKeyMessage().getString())));
		Setting<Integer> renderScale = renderScale();
		List<Setting<?>> pack = new ArrayList<>(List.of(packs, active, key, shadowDistance(), shadowMapScale(), renderScale,
				upscaleSharpness(renderScale)));
		if (LodCompat.installed()) {
			pack.add(Setting.info("aetherium.lod.status", name("lod.status"), description("lod.status"),
					() -> t(!LodCompat.hasRenderingEnabled() ? "value.lod_off"
							: engine.running() ? (LodCompat.packDrawsLods() ? "value.lod_pack" : "value.lod_hidden")
							: "value.lod_mod")).keywords("lod distant terrain"));
		}
		return new Setting.Sections()
				.add(section("shader_pack"), pack)
				.add(groupTitle(SHADER_ENGINE), group(SHADER_ENGINE))
				.build();
	}

	private List<Setting.Section> hud() {
		java.util.function.Function<Integer, String> times = v -> String.format(Locale.ROOT, "%.1f×", v / 10.0);
		Setting<String> key = Setting.info("aetherium.zoom.key", name("zoom.key"), description("zoom.key"),
				() -> Zoom.KEY.getTranslatedKeyMessage().getString()).keywords("zoom spyglass key");
		Setting<Integer> sensitivity = Setting.intSlider("aetherium.zoom.sensitivity", name("zoom.sensitivity"), description("zoom.sensitivity"),
						Zoom.MIN_SENSITIVITY, Zoom.MAX_SENSITIVITY, 5, Zoom::sensitivity, Zoom::setSensitivity, v -> v + "%")
				.defaults(() -> Zoom.DEFAULT_SENSITIVITY).keywords("zoom mouse sensitivity speed aim");
		Setting<Boolean> cinematic = Setting.toggle("aetherium.zoom.cinematic", name("zoom.cinematic"), description("zoom.cinematic"),
						Zoom::cinematic, Zoom::setCinematic)
				.defaults(() -> true).keywords("zoom cinematic smooth camera");
		Setting<Integer> nominal = Setting.intSlider("aetherium.zoom.nominal", name("zoom.nominal"), description("zoom.nominal"),
						Zoom.LOWEST, Zoom.HIGHEST, 5, Zoom::nominal, Zoom::setNominal, times)
				.defaults(() -> Zoom.DEFAULT_NOMINAL).keywords("zoom nominal default start magnification");
		Setting<Integer> min = Setting.intSlider("aetherium.zoom.min", name("zoom.min"), description("zoom.min"),
						Zoom.LOWEST, Zoom.HIGHEST, 5, Zoom::min, Zoom::setMin, times)
				.defaults(() -> Zoom.DEFAULT_MIN).keywords("zoom minimum min magnification");
		Setting<Integer> max = Setting.intSlider("aetherium.zoom.max", name("zoom.max"), description("zoom.max"),
						Zoom.LOWEST, Zoom.HIGHEST, 10, Zoom::max, Zoom::setMax, times)
				.defaults(() -> Zoom.DEFAULT_MAX).keywords("zoom maximum max magnification");
		Setting<String> corner = Setting.choice("aetherium.zoom.corner", name("zoom.corner"), description("zoom.corner"),
						ZoomHud.Corner::ids, () -> ZoomHud.corner().id,
						ZoomHud::setCorner, id -> ZoomHud.Corner.of(id).label())
				.defaults(() -> ZoomHud.Corner.TOP_RIGHT.id).keywords("zoom hud overlay position corner");
		Setting<Boolean> coordinates = Setting.toggle("aetherium.zoom.coordinates", name("zoom.coordinates"), description("zoom.coordinates"),
						ZoomHud::showCoordinates, ZoomHud::setShowCoordinates)
				.defaults(() -> true).keywords("zoom hud xyz coordinates distance compass direction");
		Setting<Boolean> target = Setting.toggle("aetherium.zoom.target", name("zoom.target"), description("zoom.target"),
						ZoomHud::showTarget, ZoomHud::setShowTarget)
				.defaults(() -> true).keywords("zoom hud block mob name preview");
		Setting<Boolean> crosshair = Setting.toggle("aetherium.crosshair.centred", name("crosshair.centred"), description("crosshair.centred"),
						Crosshair::centred, Crosshair::setCentred)
				.defaults(() -> true).keywords("crosshair centre center alignment offset pixel");
		return new Setting.Sections()
				.add(section("zoom"), List.of(key, nominal, min, max, sensitivity, cinematic))
				.add(section("readout"), List.of(corner, coordinates, target))
				.add(section("crosshair"), List.of(crosshair))
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
		return Setting.intSlider("aetherium.shaders.shadow_distance", name("shaders.shadow_distance"), description("shaders.shadow_distance"),
						0, ShadowOverrides.MAX_DISTANCE_CHUNKS, 1, () -> shadows.distanceChunks(),
						v -> shadows = new ShadowOverrides(v, shadows.mapScale()), v -> v == 0 ? t("value.pack") : t("value.chunks", v))
				.defaults(() -> 0).afterApply(reloadForShadows).keywords("shadow distance shadowDistance");
	}

	private Setting<Integer> shadowMapScale() {
		return Setting.intSlider("aetherium.shaders.shadow_map_scale", name("shaders.shadow_map_scale"), description("shaders.shadow_map_scale"),
						ShadowOverrides.MIN_MAP_SCALE, ShadowOverrides.MAX_MAP_SCALE, 5, () -> shadows.mapScale(),
						v -> shadows = new ShadowOverrides(shadows.distanceChunks(), v), v -> v == 100 ? t("value.pack") : v + "%")
				.defaults(() -> 100).afterApply(reloadForShadows).tags(Tag.SPEEDS_UP_GPU).keywords("shadow resolution shadowMapResolution quality");
	}

	/** Applied from the next frame: a change of scale resizes the pack's targets, without a reload. */
	private Setting<Integer> renderScale() {
		return Setting.intSlider("aetherium.shaders.render_scale", name("shaders.render_scale"), description("shaders.render_scale"),
						RenderScale.MIN_PERCENT, RenderScale.MAX_PERCENT, RenderScale.STEP_PERCENT, RenderScale::percent,
						v -> {
							ShaderPackSettings.setRenderScale(v);
							RenderScale.configure(v, RenderScale.sharpness());
						}, v -> v == RenderScale.MAX_PERCENT ? t("value.off") : v + "%")
				.defaults(() -> RenderScale.MAX_PERCENT).tags(Tag.SPEEDS_UP_GPU).keywords("render scale resolution upscale upscaling fsr");
	}

	private Setting<Integer> upscaleSharpness(Setting<Integer> renderScale) {
		return Setting.intSlider("aetherium.shaders.upscale_sharpness", name("shaders.upscale_sharpness"), description("shaders.upscale_sharpness"),
						0, RenderScale.MAX_SHARPNESS, 5, RenderScale::sharpness,
						v -> {
							ShaderPackSettings.setUpscaleSharpness(v);
							RenderScale.configure(RenderScale.percent(), v);
						}, v -> String.format(Locale.ROOT, "%.2f", v / 100.0))
				.defaults(() -> RenderScale.DEFAULT_SHARPNESS)
				.unavailableWhen(() -> Integer.valueOf(RenderScale.MAX_PERCENT).equals(host.value(renderScale)) ? t("value.render_scale_off") : null)
				.keywords("fsr rcas sharpen sharpening upscale");
	}

	private List<Setting.Section> advanced() {
		Setting<String> profile = Setting.choice("aetherium.profile", name("profile"), description("profile"),
						() -> List.of(AetheriumConfig.PROFILE_DEFAULT, AetheriumConfig.PROFILE_BASELINE), store::profile, store::setProfile,
						p -> t(AetheriumConfig.PROFILE_BASELINE.equals(p) ? "value.profile_baseline" : "value.profile_recommended"))
				.defaults(() -> AetheriumConfig.PROFILE_DEFAULT).order(-5).tags(Tag.RESTART).restartPendingWhen(store::profileRestartPending);
		Setting<String> resetAll = Setting.action("aetherium.reset_all", name("reset_all"), description("reset_all"),
				t("button.reset"), this::resetAetherium);
		Setting<String> config = Setting.action("aetherium.open_config", name("open_config"), description("open_config"), t("button.open"),
				() -> Blaze3D.openPath(minecraft.gameDirectory.toPath().resolve("config")));
		Setting<String> logs = Setting.action("aetherium.open_logs", name("open_logs"), description("open_logs"), t("button.open"),
				() -> Blaze3D.openPath(minecraft.gameDirectory.toPath().resolve("logs")));
		Setting<String> clearShaderCache = Setting.action("aetherium.clear_shader_cache", name("clear_shader_cache"),
				description("clear_shader_cache"), t("button.clear"), () -> {
					long freed = dev.spacebod.aetherium.shaders.pipeline.transform.TransformDiskCache.clear();
					host.toast(t("toast.shader_cache_cleared", freed >> 20), Theme.TEXT_DIM);
				});
		Setting<Boolean> gpuTimers = Setting.toggle("aetherium.bench.gpu_timers", name("bench.gpu_timers"), description("bench.gpu_timers"),
						store::gpuTimers, store::setGpuTimers)
				.defaults(() -> false).tags(Tag.RESTART).restartPendingWhen(store::debugRestartPending);
		Setting<Boolean> passTimers = Setting.toggle("aetherium.bench.pass_timers", name("bench.pass_timers"), description("bench.pass_timers"),
						store::passTimers, store::setPassTimers)
				.defaults(() -> false).tags(Tag.RESTART).restartPendingWhen(store::debugRestartPending);
		return new Setting.Sections()
				.add(section("profile"), List.of(profile))
				.add(section("tools"), List.of(config, logs, clearShaderCache, resetAll))
				.add(section("developer"), dev.spacebod.aetherium.client.DevHooks.installed() ? List.of(gpuTimers, passTimers) : List.of())
				.add(section("system"), system())
				.build();
	}

	private List<Setting<?>> system() {
		List<Setting<?>> rows = new ArrayList<>();
		var info = RenderSystem.getDevice().getDeviceInfo();
		rows.add(Setting.info("aetherium.sys.api", name("sys.api"), description("sys.api"),
				() -> VulkanAccess.device() != null ? info.backendName() : t("value.api_needs_vulkan", info.backendName())));
		rows.add(Setting.info("aetherium.sys.gpu", name("sys.gpu"), description("sys.gpu"), info::name));
		rows.add(Setting.info("aetherium.sys.driver", name("sys.driver"), description("sys.driver"),
				() -> info.driverInfo().lines().findFirst().orElse("")));
		int javaThreads = Runtime.getRuntime().availableProcessors();
		int osThreads = osLogicalProcessors();
		rows.add(Setting.info("aetherium.sys.cpu", name("sys.cpu"),
				osThreads > javaThreads ? t("sys.cpu.description_split", javaThreads, osThreads) : description("sys.cpu"),
				() -> osThreads > javaThreads ? t("value.threads_usable", javaThreads, osThreads) : Integer.toString(javaThreads))
				.keywords("cores processor groups"));
		long maxHeap = Runtime.getRuntime().maxMemory();
		rows.add(Setting.info("aetherium.sys.memory", name("sys.memory"), description("sys.memory"),
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
		Meta meta = META.get(id);
		// Registered optimisations without user-facing text keep their id and the description they were registered with.
		String name = meta == null ? id : name("opt." + id);
		String description = meta == null ? o.description() : description("opt." + id);
		String group = meta == null ? EXPERIMENTAL : meta.group();
		List<Tag> tags = new ArrayList<>(meta == null ? List.of() : meta.tags());
		tags.add(0, AetheriumStore.live(id) ? Tag.INSTANT : Tag.RESTART);
		return Setting.toggle("aetherium." + id, name, description, () -> store.enabled(id), on -> store.setEnabled(id, on))
				.defaults(() -> Optimisations.profileDefault(AetheriumConfig.PROFILE_DEFAULT, id))
				.tags(tags)
				.restartPendingWhen(() -> store.restartPending(id))
				.keywords(id + " " + groupTitle(group));
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
		host.toast(changed == 0 ? t("toast.already_set") : Lang.plural("aetherium.settings.toast.staged", changed), Theme.TEXT_DIM);
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
