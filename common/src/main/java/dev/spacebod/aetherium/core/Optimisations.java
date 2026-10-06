package dev.spacebod.aetherium.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The registry of every optimisation Aetherium ships. Each one is a kill switch: its mixins, if it has any, live in
 * {@code dev.spacebod.aetherium[.client].mixin.opt.<id>} (dots in the id are package separators), and
 * {@link AetheriumMixinPlugin} only applies that package when the optimisation is enabled at start-up. Some also have
 * a runtime switch and can be turned off and on again while the game runs.
 *
 * <p>Add an entry here in the same change that adds the mixin package. The {@code baseline} profile turns everything
 * off, which is what every optimisation is measured against.
 */
public final class Optimisations {
	/**
	 * @param id               dotted id, also the mixin sub-package under {@code mixin.opt}
	 * @param description      one line, shown in generated docs and the benchmark report
	 * @param enabledByDefault whether the {@code default} profile turns it on
	 */
	public record Optimisation(String id, String description, boolean enabledByDefault) {
	}

	/** Every optimisation, in the order they were added. */
	public static final List<Optimisation> ALL = List.of(
			new Optimisation("mem.state_cache_dedup",
					"Block-state caches share equal collision shapes, faceSturdy arrays and occlusion face arrays, interned by exact equality",
					true),
			new Optimisation("mem.component_patch",
					"Item component patches that become empty again share the empty map instead of keeping an emptied copy",
					true),
			new Optimisation("mem.palette_lock",
					"Paletted containers check for cross-thread access with one int instead of allocating a ThreadingDetector with a semaphore and a lock each",
					true),
			new Optimisation("shaders.lean_frame",
					"Shader packs: depth textures aliased instead of copied, conversions and shadowtex1 copies only when sampled, shadow and colour clears folded into load ops or skipped when fully overwritten",
					true),
			new Optimisation("shaders.reach_snapshots",
					"Shader packs: a world pass copies (snapshots) only the gbuffer attachments that the programs able to draw in that kind of pass (sky, opaque, translucent, hand, other) can read, per the samplers each compiled program reaches, instead of every attachment any gbuffers program declares",
					true),
			new Optimisation("shaders.settled_bindings",
					"Shader packs: a world program's samplers are resolved once per render pass and bound only when the pass holds something else under that name; per draw only the albedo's material maps are rebound. One identity lookup per pipeline bind; the uniform block slice is reused while unchanged",
					true),
			new Optimisation("shaders.frozen_fullscreen",
					"Shader packs: full-screen passes keep their sampler plan, label and render area from load, resolve their samplers without allocating, and upload the uniform blocks of a whole stage through one mapping",
					true),
			new Optimisation("shaders.cpu_matrices",
					"Shader packs: terrain programs read their normal matrix and inverse model-view as uniforms computed once per frame on the CPU instead of deriving them in every shader invocation. Applies when a pack loads",
					true),
			new Optimisation("shaders.shadow_cache",
					"Shader packs: the terrain part of the shadow map is kept between frames and redrawn only when the light moves 0.02 degrees, the shadow origin cell changes or a section is rebuilt; entities drawn on top every frame",
					true),
			new Optimisation("shaders.early_hand",
					"Shader packs: the solid first-person hand is drawn with the opaque world, before the deferred passes, so packs that light in deferred light it (off: it is drawn after them and stays unlit)",
					true),
			new Optimisation("shaders.nan_preserve",
					"Shader packs on Vulkan: shaders ask the driver for IEEE NaN/infinity handling (SignedZeroInfNanPreserve), so packs' own NaN checks work as on OpenGL instead of being compiled away (FastPBR went black without it). Applies when a pack loads",
					true),
			new Optimisation("shaders.inline_compute",
					"Shader packs on Vulkan: compute programs (and the pack storage's per-frame clears) are recorded into the frame's current command buffer instead of a command buffer of their own that ends vanilla's; one descriptor update per dispatch, compute-to-compute barriers between dispatches. Same order of work",
					true),
			new Optimisation("gpu.pipeline_cache",
					"Vulkan only: pipelines (vanilla's and shader packs') are compiled through one driver pipeline cache saved in aetherium/pipeline-cache.bin, so later launches and pack loads reuse compiled pipelines; data from another device or driver is ignored. Experimental, off by default: the load-time gain is not measured yet",
					false),
			new Optimisation("shaders.hw_shadow_compare",
					"Shader packs: sampler2DShadow lookups on the shadow depth maps run on a hardware depth-comparison sampler (one filtered tap, linear or nearest per shadowHardwareFiltering/shadowtexNNearest) instead of four fetches and a blend in the shader. Applies when a pack loads",
					true),
			new Optimisation("shaders.blit_mips",
					"Shader packs on Vulkan: colortex mip chains (colortexNMipmapEnabled) and shadow map mip chains are filled by image blits on the frame's command buffer, with transfer barriers between levels, instead of a render pass per level; a chain nothing wrote since it was filled is not filled again. Chains run down to 1x1 on non-square screens",
					true),
			new Optimisation("shaders.loadop_clears",
					"Shader packs: the per-frame colortex clears ride the load operation of the first render pass that attaches each target; the rest are recorded together as one empty pass per size, instead of a standalone clear (and full barrier) per target. Same picture",
					true),
			new Optimisation("shaders.narrow_barrier",
					"Shader packs on Vulkan: the engine's own render passes end on a barrier naming their attachment and storage writes and what may read them next, instead of vanilla's full memory barrier; vanilla's passes keep theirs. Same picture",
					true),
			new Optimisation("shaders.flip_only_doubles",
					"Shader packs: only colour targets a full-screen pass writes or flips get a second (ping-pong) texture; VRAM saving, logged per load. Applies when the targets are created",
					true),
			new Optimisation("shaders.shadow_cache_bind",
					"Shader packs (needs shaders.shadow_cache): on a frame that reuses the cached shadow terrain and draws nothing on top of it, the cached maps are sampled directly instead of being copied back into the live ones",
					true),
			new Optimisation("shaders.zero_locals",
					"Shader packs on Vulkan: variables a pack shader can read before writing them start at zero, as OpenGL drivers give them (only those; found by a dataflow pass over each compiled module). Without it they read leftover register values: black faces, flickering lines",
					true),
			new Optimisation("shaders.transform_cache",
					"Shader packs: each program's transformed GLSL and interface are kept on disk (aetherium/shader-cache/transform, at most 256 MiB, least recently used dropped first), keyed by its preprocessed sources, every transform setting and a hash of Aetherium's own code, so later loads of the same pack skip the transform. Same output",
					true));

	private Optimisations() {
	}

	/** Whether {@code id} is enabled in this session (the config read at start-up, before mixins were applied). */
	public static boolean isEnabled(String id) {
		return isEnabled(AetheriumConfig.get(), id);
	}

	/** Whether {@code id} would be enabled under {@code config} (e.g. the settings saved for the next start). */
	public static boolean isEnabled(AetheriumConfig config, String id) {
		Boolean override = config.overrides.get(id);
		return override != null ? override : profileDefault(config.profile, id);
	}

	/** The value {@code id} takes under {@code profile} when the config has no override for it. */
	public static boolean profileDefault(String profile, String id) {
		if (AetheriumConfig.PROFILE_BASELINE.equals(profile)) {
			return false;
		}
		for (Optimisation o : ALL) {
			if (o.id().equals(id)) {
				return o.enabledByDefault();
			}
		}
		return false;
	}

	/**
	 * Ids that own mixins under {@code mixin.opt.<id>}: those are applied or skipped at start-up, so turning them on
	 * (or off, unless the code also has a runtime switch) takes a restart. Read from the mixin configs.
	 */
	public static Set<String> mixinBacked() {
		Set<String> ids = mixinBacked;
		if (ids == null) {
			ids = new HashSet<>();
			for (String resource : List.of("aetherium.mixins.json", "aetherium.client.mixins.json")) {
				try (InputStream in = Optimisations.class.getClassLoader().getResourceAsStream(resource)) {
					if (in == null) {
						continue;
					}
					JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
					for (String side : List.of("mixins", "client", "server")) {
						if (!root.has(side)) {
							continue;
						}
						for (JsonElement e : root.getAsJsonArray(side)) {
							String name = e.getAsString();
							if (name.startsWith("opt.") && name.lastIndexOf('.') > 4) {
								ids.add(name.substring(4, name.lastIndexOf('.')));
							}
						}
					}
				} catch (IOException | RuntimeException e) {
					// Unknown: callers treat the id as needing a restart, the safe answer.
				}
			}
			mixinBacked = ids = Set.copyOf(ids);
		}
		return ids;
	}

	private static volatile @Nullable Set<String> mixinBacked;

	/** Resolved state of every optimisation, recorded in each benchmark result. */
	public static Map<String, Boolean> snapshot() {
		Map<String, Boolean> map = new TreeMap<>();
		for (Optimisation o : ALL) {
			map.put(o.id(), isEnabled(o.id()));
		}
		return map;
	}
}
