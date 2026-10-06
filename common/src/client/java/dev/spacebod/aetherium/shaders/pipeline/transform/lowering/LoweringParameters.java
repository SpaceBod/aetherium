package dev.spacebod.aetherium.shaders.pipeline.transform.lowering;

import com.mojang.renderpearl.api.GpuFormat;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the Vulkan lowering of one program needs to know beyond its source. Part of the transform cache key, so every
 * component is an immutable value with {@code equals}.
 *
 * @param kind               which kind of program (decides the passes)
 * @param attributes         world and LOD programs: vertex attributes the pipeline's vertex format provides, by shader
 *                           name (the transformer's {@code aeth_*} names), with their formats; empty otherwise
 * @param glDepth            world programs: write OpenGL window depth (the shadow pass) instead of vanilla's reversed depth
 * @param alphaReference     world programs: the constant {@code aeth_currentAlphaTest} becomes; null = left a uniform
 * @param samplerRenames     sampler renames applied where the program declares the old name as a sampler, in order
 * @param outputAttachments  world programs: fragment output location -> render pass attachment index (-1 = no
 *                           attachment: the output becomes a plain global); null = locations kept
 * @param lodVariant         LOD programs: the LOD mod's own interface to adopt
 * @param glint              world programs drawn by a single-pass glint pipeline: the per-draw texture matrix is the
 *                           glint's (so the pack's reads of it become the identity), and the attachment whose output
 *                           gets vanilla's glint term added (-1 = none)
 * @param grayscaleAlbedo    world programs for an intensity-texture pipeline ({@code IS_GRAYSCALE}: unifont and TTF
 *                           glyphs): reads of the albedo ({@code Sampler0}) become {@code .rrrr}, as vanilla's text shader does
 * @param storage            the active pack's custom images and storage buffers (set 1)
 * @param shadow             how the active pack samples its shadow depth maps
 * @param platform           what the device runs the program on
 */
public record LoweringParameters(Kind kind, Map<String, GpuFormat> attributes, boolean glDepth, @Nullable Float alphaReference,
		List<Rename> samplerRenames, @Nullable List<Integer> outputAttachments, LodVariant lodVariant, Glint glint, boolean grayscaleAlbedo, Storage storage, Shadow shadow,
		Platform platform) {

	public LoweringParameters {
		attributes = Map.copyOf(attributes);
		samplerRenames = List.copyOf(samplerRenames);
		outputAttachments = outputAttachments == null ? null : List.copyOf(outputAttachments);
	}

	public enum Kind {
		/** gbuffers / shadow programs standing in for a vanilla pipeline (and LOD programs for the LOD mod's). */
		WORLD,
		/** Full-screen passes: begin, prepare, deferred, composite, final, shadowcomp. */
		FULLSCREEN,
		/** Compute programs: set 0 is the uniform block (binding 0) and the sampled textures (1..n). */
		COMPUTE
	}

	public enum LodVariant {
		NONE,
		/** {@code dh_terrain}, {@code dh_water}, {@code dh_shadow}: the mod's per-buffer origin block. */
		TERRAIN,
		/** {@code dh_generic}: the mod's per-group block, boxes pre-expanded into vertices. */
		GENERIC
	}

	/**
	 * Single-pass enchantment glint (26.3 draws it in the item's own draw): {@code identityTextureMatrix} makes the
	 * pack's texture matrix the identity (the per-draw one scrolls the glint, not the item); {@code attachment} is the
	 * render pass attachment whose output gets {@code glint.rgb * glint.rgb} added, as vanilla's item shader does (-1 = none).
	 */
	public record Glint(boolean identityTextureMatrix, int attachment) {
		public static final Glint NONE = new Glint(false, -1);
	}

	/** A sampler rename ({@code gtexture} -> {@code Sampler0}). */
	public record Rename(String from, String to) {
	}

	/**
	 * The pack's custom images (by image name: set-1 storage binding and GLSL format; by sampler name: set-1 sampled
	 * binding) and storage buffers ({@code binding = N} -> set 1, binding {@code bufferBase + N}).
	 *
	 * @param targetImages   graphics programs bind {@code colorimgN} / {@code shadowcolorimgN} in set 1 at
	 *                       {@code targetImageBase + N} / {@code shadowTargetImageBase + N} (compute programs in their set 0)
	 * @param samplerViews   by image sampler name: the binding of the image's view read through an integer sampler of
	 *                       another kind than the image, by kind key ({@code kind:uint}, {@code kind:int})
	 * @param vertexWrites   vertex, geometry and tessellation stages may write storage images and buffers (else they are
	 *                       declared read-only there, and a program writing one does not build)
	 * @param fragmentWrites likewise for fragment stages
	 */
	public record Storage(Map<String, Image> images, Map<String, Integer> samplerBindings, boolean buffers, int bufferBase, boolean targetImages,
			int targetImageBase, int shadowTargetImageBase, Map<String, Map<String, Integer>> samplerViews, boolean vertexWrites, boolean fragmentWrites) {
		public static final Storage NONE = new Storage(Map.of(), Map.of(), false, 64, false, 256, 288, Map.of(), true, true);

		public Storage {
			images = Map.copyOf(images);
			samplerBindings = Map.copyOf(samplerBindings);
			Map<String, Map<String, Integer>> views = new java.util.HashMap<>();
			samplerViews.forEach((name, byKind) -> views.put(name, Map.copyOf(byKind)));
			samplerViews = Map.copyOf(views);
		}

		public boolean active() {
			return !images.isEmpty() || buffers || !samplerBindings.isEmpty() || targetImages;
		}
	}

	/**
	 * A custom image's storage binding, the format qualifier used when the GLSL declares none, and its views in other
	 * formats: by the declaration's format qualifier, or for a declaration without one whose type reads another kind
	 * than the image, by kind key ({@code kind:uint}, {@code kind:int}, {@code kind:float}).
	 */
	public record Image(int binding, String glslFormat, Map<String, View> views) {
		public Image {
			views = Map.copyOf(views);
		}

		public Image(int binding, String glslFormat) {
			this(binding, glslFormat, Map.of());
		}
	}

	/** A view of a custom image in another format: its set-1 binding and format qualifier. */
	public record View(int binding, String glslFormat) {
	}

	/**
	 * Per shadow depth map (0, 1): sampled nearest; depth comparisons filtered (OpenGL's hardware PCF). {@code hardware}:
	 * comparisons on the depth maps keep their type and run on a comparison sampler (graphics programs); otherwise they
	 * are made in the shader.
	 */
	public record Shadow(boolean nearest0, boolean nearest1, boolean filtered0, boolean filtered1, boolean hardware) {
		public static final Shadow DEFAULT = new Shadow(false, false, false, false, false);

		public boolean filtered(int map) {
			return map == 0 ? filtered0 : filtered1;
		}
	}

	/**
	 * The device a program is lowered for.
	 *
	 * @param metal          the driver translates SPIR-V to Metal (MoltenVK): every vertex stage's position is
	 *                       {@code invariant} (Metal's fast math would otherwise place the same vertex differently in two
	 *                       programs), and the float packing built-ins become integer arithmetic
	 * @param geometryStages the device runs geometry stages; without, a geometry stage that only hands each triangle
	 *                       corner on is folded away (the fragment stage reads the vertex stage's outputs)
	 */
	public record Platform(boolean metal, boolean geometryStages) {
	}

	/** A full-screen pass. */
	public static LoweringParameters fullscreen(Storage storage, Shadow shadow, Platform platform) {
		return new LoweringParameters(Kind.FULLSCREEN, Map.of(), false, null, List.of(), null, LodVariant.NONE, Glint.NONE, false, storage, shadow, platform);
	}

	/** A compute program. */
	public static LoweringParameters compute(Storage storage, Shadow shadow, Platform platform) {
		return new LoweringParameters(Kind.COMPUTE, Map.of(), false, null, List.of(), null, LodVariant.NONE, Glint.NONE, false, storage, shadow, platform);
	}
}
