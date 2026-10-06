package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import dev.spacebod.aetherium.client.gpu.DeviceTraits;
import dev.spacebod.aetherium.shaders.pbr.PbrKind;
import dev.spacebod.aetherium.shaders.pbr.PbrTextures;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import java.util.OptionalDouble;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The one place a pack sampler name becomes a texture view and a sampler state, for every kind of program: gbuffers and
 * shadow programs (inside a world or shadow render pass), full-screen passes and compute programs. What a pack can
 * observe follows the pack format:
 * <ul>
 *   <li>custom textures of the program's stage first ({@code texture.<stage>.<name>}, {@code customTexture.}), with their
 *       own filtering;</li>
 *   <li>{@code colortexN} and the legacy names: the side the program reads (inside a world pass, the snapshot taken when
 *       it opened); linear, nearest for integer formats, mipmapped once the target's chain was generated this frame;</li>
 *   <li>{@code depthtex0..2}, {@code gdepthtex} and the LOD depth textures: nearest;</li>
 *   <li>shadow depth maps nearest or linear per {@code shadowtexNNearest}; {@code shadowcolorN} per
 *       {@code shadowcolorNNearest} (integer formats always nearest);</li>
 *   <li>comparison samplers on the shadow depth maps ({@link ShadowSampling#COMPARE_PREFIX} names): the map with a
 *       depth-comparison sampler, linear where the map's comparisons are filtered, else nearest;</li>
 *   <li>{@code noisetex}: linear with repeat;</li>
 *   <li>any other name: in full-screen and compute programs {@code colortex0} (the default texture unit there), in world
 *       programs opaque white.</li>
 * </ul>
 */
final class SamplerTable {
	/** Where the program runs; decides snapshots and the fallback for unknown names. */
	enum Scope {
		/** A gbuffers program inside a world render pass (colour targets it also renders into are read from snapshots). */
		GBUFFER,
		/** A shadow program inside the shadow render pass. */
		SHADOW,
		/** A full-screen pass (begin, prepare, deferred, composite, final, shadowcomp). */
		FULLSCREEN,
		/** A compute program. */
		COMPUTE
	}

	/** A resolved sampler: the view to bind and how it is sampled. */
	record Bound(GpuTextureView view, GpuSampler sampler) {
	}

	private final PackTargets targets;
	private final DepthTargets depth;
	private final @Nullable ShadowPass shadow;
	private final @Nullable CustomTextures custom;
	private final @Nullable CenterDepth centerDepth;
	private final int noiseSize;

	SamplerTable(PackTargets targets, DepthTargets depth, @Nullable ShadowPass shadow, @Nullable CustomTextures custom,
			@Nullable CenterDepth centerDepth, int noiseSize) {
		this.targets = targets;
		this.depth = depth;
		this.shadow = shadow;
		this.custom = custom;
		this.centerDepth = centerDepth;
		this.noiseSize = noiseSize;
	}

	/** Whether the pack binds a custom texture under {@code name} for {@code stage}. */
	boolean customised(String name, TextureStage stage) {
		return custom != null && custom.get(stage, name) != null;
	}

	/**
	 * Resolves {@code name} for a program of {@code stage} running in {@code scope}. {@code watershadow}: the program
	 * declares {@code watershadow} (then {@code shadow} reads the opaque-only map). {@code snapshots}: inside a world
	 * pass, the copy of each colour target taken when the pass opened, by colortex index (null entries = read the target
	 * directly). {@code written}: colour targets earlier passes of the stage wrote; a custom texture bound under such a
	 * target's name no longer replaces it (null = none written).
	 */
	Bound resolve(String name, TextureStage stage, Scope scope, boolean watershadow, GpuTextureView @Nullable [] snapshots,
			@Nullable Set<Integer> written) {
		Slot slot = new Slot();
		plan(name, stage, scope, watershadow, written).resolve(snapshots, slot);
		return new Bound(slot.view, slot.sampler);
	}

	/** A resolved view and sampler, written in place. */
	static final class Slot {
		GpuTextureView view;
		GpuSampler sampler;

		void set(GpuTextureView view, GpuSampler sampler) {
			this.view = view;
			this.sampler = sampler;
		}
	}

	private enum Kind { COMPARE, CUSTOM, SHADOW, TARGET, DEPTH, NOISE, NORMALS, SPECULAR, OVERLAY, CENTER, WHITE }

	/**
	 * How {@code name} resolves for a program of {@code stage} running in {@code scope} (as {@link #resolve}): the name's
	 * rules are applied once here, so {@link Plan#resolve} only reads the current views. What a plan decides holds for
	 * the pack's life ({@code written} is fixed per full-screen pass); the views it reads may change between passes.
	 */
	Plan plan(String name, TextureStage stage, Scope scope, boolean watershadow, @Nullable Set<Integer> written) {
		if (name.startsWith(ShadowSampling.COMPARE_PREFIX)) {
			return new Plan(Kind.COMPARE, ShadowSampling.samplerName(name), 0, watershadow, scope, null, null);
		}
		if (custom != null) {
			CustomTextures.Binding binding = custom.get(stage, name);
			int overridden = GbufferLayout.targetIndex(name);
			if (binding != null && (written == null || overridden < 0 || name.equals("texture") || !written.contains(overridden))) {
				return new Plan(Kind.CUSTOM, name, 0, watershadow, scope, binding, null);
			}
		}
		Plan rest = rest(name, scope, watershadow);
		if (name.startsWith("shadow") || name.equals("watershadow")) {
			return new Plan(Kind.SHADOW, name, 0, watershadow, scope, null, rest);
		}
		return rest;
	}

	/** {@link #plan} past the comparison, custom texture and shadow map rules. */
	private Plan rest(String name, Scope scope, boolean watershadow) {
		int target = GbufferLayout.targetIndex(name);
		if (target >= 0 && !(name.equals("texture") && (scope == Scope.GBUFFER || scope == Scope.SHADOW))) {
			return new Plan(Kind.TARGET, name, target, watershadow, scope, null, null);
		}
		Kind kind;
		int index = 0;
		switch (name) {
			case "depthtex0", "gdepthtex" -> {
				kind = Kind.DEPTH;
				index = DepthTargets.ALL;
			}
			case "depthtex1" -> {
				kind = Kind.DEPTH;
				index = DepthTargets.NO_TRANSLUCENTS;
			}
			case "depthtex2" -> {
				kind = Kind.DEPTH;
				index = DepthTargets.NO_HAND;
			}
			case "dhDepthTex", "dhDepthTex0" -> {
				kind = Kind.DEPTH;
				index = DepthTargets.LOD_ALL;
			}
			case "dhDepthTex1" -> {
				kind = Kind.DEPTH;
				index = DepthTargets.LOD_NO_TRANSLUCENTS;
			}
			case "noisetex" -> kind = Kind.NOISE;
			// Gbuffer and shadow draws read the maps of the texture they draw with (ShaderPackEngine); elsewhere the default.
			case "normals" -> kind = Kind.NORMALS;
			case "specular" -> kind = Kind.SPECULAR;
			// The overlay, where vanilla's draw binds none: big enough for any overlay coordinate.
			case "Sampler1" -> kind = Kind.OVERLAY;
			case CenterDepth.SAMPLER -> kind = Kind.CENTER;
			// Any other name: colortex0 in full-screen and compute programs (the default texture unit), white in world programs.
			default -> kind = scope == Scope.FULLSCREEN || scope == Scope.COMPUTE ? Kind.TARGET : Kind.WHITE;
		}
		return new Plan(kind, name, index, watershadow, scope, null, null);
	}

	/** One sampler name's resolution, decided by {@link #plan}. */
	final class Plan {
		private final Kind kind;
		/** The name the rule reads (for a comparison sampler, its map's name). */
		private final String name;
		/** colortex index ({@code TARGET}) or depth texture ({@code DEPTH}). */
		private final int index;
		private final boolean watershadow;
		private final Scope scope;
		private final CustomTextures.@Nullable Binding binding;
		/** {@code SHADOW}: what applies when the shadow map has no texture under the name. */
		private final @Nullable Plan fallback;

		private Plan(Kind kind, String name, int index, boolean watershadow, Scope scope, CustomTextures.@Nullable Binding binding,
				@Nullable Plan fallback) {
			this.kind = kind;
			this.name = name;
			this.index = index;
			this.watershadow = watershadow;
			this.scope = scope;
			this.binding = binding;
			this.fallback = fallback;
		}

		/** Writes the view and sampler the name reads now into {@code out}; {@code snapshots} as for {@link #resolve}. */
		void resolve(GpuTextureView @Nullable [] snapshots, Slot out) {
			switch (kind) {
				case COMPARE -> comparison(name, watershadow, out);
				case CUSTOM -> out.set(binding.view().get(), binding.sampler());
				case SHADOW -> {
					if (!shadowSampler(name, watershadow, out)) {
						fallback.resolve(snapshots, out);
					}
				}
				case TARGET -> colourTarget(index, scope, snapshots, out);
				case DEPTH -> nearest(depth.view(index), out);
				case NOISE -> out.set(targets.noise(noiseSize), RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
				case NORMALS -> nearest(PbrTextures.defaultView(PbrKind.NORMAL), out);
				case SPECULAR -> nearest(PbrTextures.defaultView(PbrKind.SPECULAR), out);
				case OVERLAY -> nearest(targets.overlayWhite(), out);
				case CENTER -> nearest(centerDepth == null ? targets.white() : centerDepth.view(targets), out);
				case WHITE -> nearest(targets.white(), out);
			}
		}
	}

	private void colourTarget(int target, Scope scope, GpuTextureView @Nullable [] snapshots, Slot out) {
		if (target >= targets.count() || !targets.used(target)) {
			nearest(targets.white(), out);
			return;
		}
		GpuTextureView view = null;
		if (scope == Scope.GBUFFER && snapshots != null && target < snapshots.length) {
			view = snapshots[target];
		}
		FilterMode filter = linearFilter(targets.format(target)) ? FilterMode.LINEAR : FilterMode.NEAREST;
		if (view == null) {
			view = targets.readView(target);
			if (targets.readsMipmapped(target)) {
				out.set(view, RenderSystem.getSamplerCache().getClampToEdge(filter, true));
				return;
			}
		}
		out.set(view, RenderSystem.getSamplerCache().getClampToEdge(filter));
	}

	/**
	 * Whether a target of {@code format} is read with linear filtering: not integer formats, and not formats the device
	 * cannot filter (Metal filters no 32-bit float format), which are read nearest instead.
	 */
	static boolean linearFilter(GpuFormat format) {
		return !WorldProgram.isIntegerFormat(format) && DeviceTraits.filtersLinearly(VulkanConst.toVk(format));
	}

	/** The shadow map texture under {@code name} into {@code out}; false (nothing written) when there is none. */
	private boolean shadowSampler(String name, boolean watershadow, Slot out) {
		if (shadow == null) {
			return false;
		}
		GpuTextureView view = shadow.samplerView(name, watershadow);
		if (view == null) {
			return false;
		}
		// Through the map's mip chain when the pack asked for one and it was generated this frame.
		GpuSampler mips = shadow.mipSampler(name, watershadow);
		if (mips != null) {
			out.set(view, mips);
			return true;
		}
		int map = ShadowSampling.depthIndex(name, watershadow);
		boolean nearest = map >= 0 ? ShadowSampling.nearest(map) : shadow.colorNearest(name) || !linearFilter(view.texture().getFormat());
		out.set(view, RenderSystem.getSamplerCache().getClampToEdge(nearest ? FilterMode.NEAREST : FilterMode.LINEAR));
		return true;
	}

	/**
	 * A comparison sampler on a shadow depth map. The lowering only keeps comparisons on the depth map names, and only
	 * for packs with a shadow pass, so the map exists; the depth fallback (cleared to the far plane: every comparison
	 * passes) only covers a pack whose shadow pass could not be created.
	 */
	private void comparison(String name, boolean watershadow, Slot out) {
		int map = ShadowSampling.depthIndex(name, watershadow);
		GpuTextureView view = shadow == null ? null : shadow.samplerView(name, watershadow);
		out.set(view != null ? view : farDepth(), compareSampler(map >= 0 && ShadowSampling.filteredCompare(map)));
	}

	private static @Nullable GpuSampler compareLinear;
	private static @Nullable GpuSampler compareNearest;
	private static @Nullable GpuTextureView farDepth;

	/** The depth-comparison sampler (clamped to the edge, base level), made once for the device's life. */
	private static GpuSampler compareSampler(boolean linear) {
		GpuSampler sampler = linear ? compareLinear : compareNearest;
		if (sampler == null) {
			FilterMode filter = linear ? FilterMode.LINEAR : FilterMode.NEAREST;
			sampler = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, filter, filter, 1,
					OptionalDouble.of(ShadowSampling.COMPARE_LOD));
			if (linear) {
				compareLinear = sampler;
			} else {
				compareNearest = sampler;
			}
		}
		return sampler;
	}

	private static GpuTextureView farDepth() {
		if (farDepth == null) {
			GpuTexture texture = RenderSystem.getDevice().createTexture(() -> "Aetherium Shaders far depth",
					GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, 1, 1, 1, 1);
			RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(texture, 1.0);
			farDepth = RenderSystem.getDevice().createTextureView(texture);
		}
		return farDepth;
	}

	private static void nearest(GpuTextureView view, Slot out) {
		out.set(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
	}
}
