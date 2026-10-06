package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.texture.InternalTextureFormat;
import dev.spacebod.aetherium.shaders.helpers.OptionalBoolean;
import dev.spacebod.aetherium.shaders.pbr.PbrTextures;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramArrayId;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramGroup;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramFallbackResolver;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackShadowDirectives;
import dev.spacebod.aetherium.shaders.shadows.ShadowCasterCulling;
import dev.spacebod.aetherium.shaders.shadows.ShadowView;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * Shadow pass: before the world, terrain around the player is drawn from the sun's (or moon's) point of view with
 * the pack's shadow programs into the shadow depth map and the {@code shadowcolor} targets, with the same matrices the
 * {@code shadowModelView} and {@code shadowProjection} uniforms carry. Shadow programs write OpenGL window depth
 * (1 = far), so the depth map is sampled as is: {@code shadowtex0} after translucents, {@code shadowtex1} a copy taken
 * after the opaque terrain.
 */
public final class ShadowPass implements AutoCloseable, TargetLayout {
	private final PackDirectives pack;
	private final PackShadowDirectives directives;
	private final int resolution;
	private final int[] attachments;
	private final int colorCount;
	private final GpuFormat[] formats;
	private final boolean renderTranslucent;
	/** The shadow program has a geometry stage: the pack is taken to voxelise and reads the map for more than shadows. */
	private final boolean voxelises;
	/** Some program reads the opaque-only shadow depth (shadowtex1). */
	private final boolean opaqueDepthSampled;
	/** Lean frames: the clears are folded into the first shadow render pass's load ops. */
	private boolean clearOnLoad;
	private @Nullable String lastCullingInfo;
	private @Nullable GpuTexture depth;
	private @Nullable GpuTextureView depthView;
	/** Per shadowcolor target, two sides when a shadowcomp pass writes it (ping-pong, as colortex), else one. */
	private final GpuTexture[][] colors;
	private final GpuTextureView[][] colorViews;
	/** Targets shadowcomp passes write: they get the second side. */
	private final boolean[] pingPong;
	/** Per target: the side holding its contents at frame start, and whether it was flipped (odd number of times) since. */
	private final int[] base;
	private final boolean[] flipped;
	/** Per target: the last flip this frame left its current side partly written (copied back at the end of the frame). */
	private final boolean[] partial;
	/** Depth after the opaque terrain ({@code shadowtex1}); only when translucents are drawn into the map. */
	private @Nullable GpuTexture opaqueDepth;
	private @Nullable GpuTextureView opaqueDepthView;
	private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("Aetherium Shaders shadow");
	/**
	 * Mip chains ({@code generateShadowMipmap}, {@code shadowtexNMipmap}, {@code shadowcolorNMipmap}, and the
	 * shadowcomp passes' {@code mipmappedBuffers}): per depth map (0 = shadowtex0, 1 = shadowtex1) and per shadowcolor
	 * target, whether the format asks for a chain; generated after the shadow map is drawn (and before a shadowcomp pass
	 * that lists the target), then read mipmapped for the rest of the frame.
	 */
	private final boolean[] depthMipmapped = new boolean[2];
	private final boolean[] colorMipmapped;
	/** Shadowcolor targets a shadowcomp pass reads mipmapped (they get a chain even without their own directive). */
	private final boolean[] compositeMipmapped;
	private int depthLevels = 1;
	private int opaqueLevels = 1;
	private int colorLevels = 1;
	private @Nullable GpuTextureView depthChainView;
	private @Nullable GpuTextureView opaqueChainView;
	private final GpuTextureView[][] colorChainViews;
	/** This frame: the depth texture's / the opaque depth's chain was generated. */
	private boolean depthChainOn;
	private boolean opaqueChainOn;
	/** This frame, per target and side: the chain was generated after the shadow map (directive) or by a shadowcomp pass. */
	private final boolean[][] colorChainOn;
	private final boolean[][] colorChainByComposite;

	private ShadowPass(PackDirectives pack, int[] attachments, int colorCount, boolean[] pingPong, boolean voxelises, boolean opaqueDepthSampled,
			boolean[] compositeMipmapped) {
		this.pack = pack;
		this.voxelises = voxelises;
		this.opaqueDepthSampled = opaqueDepthSampled;
		this.directives = pack.getShadowDirectives();
		this.resolution = directives.getResolution();
		this.attachments = attachments;
		this.colorCount = colorCount;
		this.formats = new GpuFormat[colorCount];
		for (int i = 0; i < colorCount; i++) {
			PackShadowDirectives.SamplingSettings s = directives.getColorSamplingSettings().get(i);
			formats[i] = PackTargets.gpuFormat(s == null ? InternalTextureFormat.RGBA : s.getFormat());
		}
		this.colors = new GpuTexture[colorCount][2];
		this.colorViews = new GpuTextureView[colorCount][2];
		this.pingPong = pingPong;
		this.base = new int[colorCount];
		this.flipped = new boolean[colorCount];
		this.partial = new boolean[colorCount];
		this.renderTranslucent = directives.shouldRenderTranslucent();
		this.compositeMipmapped = compositeMipmapped;
		this.colorMipmapped = new boolean[colorCount];
		this.colorChainViews = new GpuTextureView[colorCount][2];
		this.colorChainOn = new boolean[colorCount][2];
		this.colorChainByComposite = new boolean[colorCount][2];
		for (int i = 0; i < 2 && i < directives.getDepthSamplingSettings().size(); i++) {
			depthMipmapped[i] = directives.getDepthSamplingSettings().get(i).getMipmap();
		}
		for (int i = 0; i < colorCount; i++) {
			PackShadowDirectives.SamplingSettings s = directives.getColorSamplingSettings().get(i);
			colorMipmapped[i] = s != null && s.getMipmap() || compositeMipmapped[i];
		}
	}

	/** The shadow pass for this pack, or null when the pack has no shadow program or turns shadows off. */
	public static @Nullable ShadowPass create(ProgramSet programs, PackDirectives pack, boolean opaqueDepthSampled) {
		if (pack.getShadowDirectives().isShadowEnabled() == OptionalBoolean.FALSE) {
			return null;
		}
		ProgramFallbackResolver resolver = new ProgramFallbackResolver(programs);
		TreeSet<Integer> buffers = new TreeSet<>();
		boolean any = false;
		for (ProgramId id : ProgramId.values()) {
			if (id.getGroup() != ProgramGroup.Shadow) {
				continue;
			}
			Optional<ProgramSource> source = resolver.resolve(id);
			if (source.isEmpty()) {
				continue;
			}
			any = true;
			for (int b : source.get().getDirectives().getDrawBuffers()) {
				if (b >= 0 && b < 8) {
					buffers.add(b);
				}
			}
		}
		if (!any) {
			return null;
		}
		// shadowcomp full-screen passes write shadowcolor targets too, ping-ponged.
		TreeSet<Integer> composited = new TreeSet<>();
		for (ProgramSource source : programs.getComposite(ProgramArrayId.ShadowComposite)) {
			if (source != null && source.isValid()) {
				for (int b : source.getDirectives().getDrawBuffers()) {
					if (b >= 0 && b < 8) {
						composited.add(b);
					}
				}
			}
		}
		int highest = Math.max(buffers.isEmpty() ? 1 : buffers.last(), composited.isEmpty() ? 1 : composited.last());
		int colorCount = Math.max(2, highest + 1);
		boolean[] pingPong = new boolean[colorCount];
		composited.forEach(b -> pingPong[b] = true);
		boolean[] compositeMipmapped = new boolean[colorCount];
		for (ProgramSource source : programs.getComposite(ProgramArrayId.ShadowComposite)) {
			if (source != null && source.isValid()) {
				for (int b : source.getDirectives().getMipmappedBuffers()) {
					if (b >= 0 && b < colorCount) {
						compositeMipmapped[b] = true;
					}
				}
			}
		}
		boolean voxelises = resolver.resolve(ProgramId.Shadow).map(p -> p.getGeometrySource().isPresent()).orElse(false);
		return new ShadowPass(pack, buffers.stream().mapToInt(Integer::intValue).toArray(), colorCount, pingPong, voxelises, opaqueDepthSampled,
				compositeMipmapped);
	}

	@Override
	public int[] attachments() {
		return attachments;
	}

	@Override
	public GpuFormat format(int target) {
		return formats[target];
	}

	/** The shadow model-view matrix for this frame (camera-relative, snapped to the pack's interval). */
	public Matrix4f modelView() {
		return new Matrix4f(ShadowView.modelView(pack));
	}

	/** The shadow origin's grid cell size in blocks (the origin moves only when the camera enters another cell). */
	public float intervalSize() {
		return directives.getIntervalSize();
	}

	/** The shadow projection (OpenGL convention; a perspective one for legacy packs that set shadowMapFov). */
	public Matrix4f projection() {
		return new Matrix4f(ShadowView.projection(pack));
	}

	static float renderDistanceBlocks() {
		return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
	}

	/** Blocks from the shadow origin to the map's corners (orthographic maps; the caster distance for perspective ones). */
	public double mapReach() {
		return directives.getFov() != null ? casterDistance() : directives.getDistance() * Math.sqrt(2.0);
	}

	/** How far from the camera shadow casters are drawn (half plane length x distance multiplier; the render distance when the multiplier is negative). */
	public double casterDistance() {
		float mul = directives.getDistanceRenderMul();
		return mul < 0 ? renderDistanceBlocks() : directives.getDistance() * mul;
	}

	/**
	 * This frame's caster culling, centred on the camera. {@code playerView} is the player's projection x view rotation
	 * (OpenGL clip convention); {@code shadowModelView} the matrix the map is drawn with.
	 */
	public ShadowCasterCulling casterCulling(Matrix4fc playerView, Matrix4fc shadowModelView, double cameraX, double cameraY, double cameraZ) {
		// Towards the light: the map's depth axis (+z of shadow view space) in world space. This is the direction the
		// map is actually drawn along, whatever sky transform the celestial uniforms reproduce.
		Vector3f light = new Matrix4f(shadowModelView).invert().transformDirection(new Vector3f(0.0F, 0.0F, 1.0F)).normalize();
		ShadowCasterCulling culling = ShadowCasterCulling.create(directives, voxelises, renderDistanceBlocks(), playerView, light);
		culling.prepare(cameraX, cameraY, cameraZ);
		String info = culling.getClass().getSimpleName();
		if (!info.equals(lastCullingInfo)) {
			lastCullingInfo = info;
			AetheriumShaders.logger.info("shadow culling {}", culling.describe());
		}
		return culling;
	}

	public boolean renderTranslucent() {
		return renderTranslucent;
	}

	/** The shadow maps' size in texels (square). */
	public int resolution() {
		return resolution;
	}

	public boolean renderEntities() {
		return directives.shouldRenderEntities();
	}

	/** {@code shadowLightBlockEntities}: light-emitting block entities go into the map even when other block entities do not. */
	public boolean renderLightBlockEntities() {
		return directives.shouldRenderLightBlockEntities();
	}

	/** {@code shadowPlayer}: the camera's own player goes into the shadow map even when other entities do not. */
	public boolean renderPlayer() {
		return directives.shouldRenderPlayer();
	}

	/** How far from the camera entities cast into the map: its reach times {@code entityShadowDistanceMul}. */
	public double entityCasterDistance() {
		return Math.min(mapReach(), casterDistance()) * directives.getEntityShadowDistanceMul();
	}

	/** {@code shadowTerrain}: section meshes go into the shadow map. */
	public boolean renderTerrain() {
		return directives.shouldRenderTerrain();
	}

	public boolean renderBlockEntities() {
		return directives.shouldRenderBlockEntities();
	}

	/**
	 * (Re)creates the maps and clears them for a new frame: depth to far (1, OpenGL convention), colours to their clear
	 * colour. Lean frames clear nothing here: the first shadow render pass clears its attachments as it loads them, and
	 * colour targets no shadow program writes keep the clear colour they got when created.
	 */
	public void beginFrame(GpuBuffer quad, boolean lean) {
		boolean fresh = depth == null;
		cacheBound = false;
		depthChainOn = false;
		opaqueChainOn = false;
		for (int i = 0; i < colorCount; i++) {
			colorChainOn[i][0] = colorChainOn[i][1] = false;
			colorChainByComposite[i][0] = colorChainByComposite[i][1] = false;
		}
		if (depth == null) {
			var device = RenderSystem.getDevice();
			// Depth chains stop at 2x2 (as many levels as the format gives them); colour chains run to 1x1.
			int depthChain = Math.max(1, 31 - Integer.numberOfLeadingZeros(resolution));
			depthLevels = depthMipmapped[0] || depthMipmapped[1] && !renderTranslucent ? depthChain : 1;
			opaqueLevels = depthMipmapped[1] && renderTranslucent ? depthChain : 1;
			colorLevels = MipChains.fullLevels(resolution, resolution);
			depth = device.createTexture(() -> "Aetherium Shaders shadowtex0",
					GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
					GpuFormat.D32_FLOAT, resolution, resolution, 1, depthLevels);
			// Level 0 alone: what render passes attach; the chain view is what samplers read once the chain was generated.
			depthView = device.createTextureView(depth, 0, 1);
			depthChainView = depthLevels > 1 ? device.createTextureView(depth) : null;
			if (renderTranslucent) {
				opaqueDepth = device.createTexture(() -> "Aetherium Shaders shadowtex1",
						GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_RENDER_ATTACHMENT,
						GpuFormat.D32_FLOAT, resolution, resolution, 1, opaqueLevels);
				opaqueDepthView = device.createTextureView(opaqueDepth, 0, 1);
				opaqueChainView = opaqueLevels > 1 ? device.createTextureView(opaqueDepth) : null;
			}
			for (int i = 0; i < colorCount; i++) {
				int levels = colorMipmapped[i] ? colorLevels : 1;
				for (int side = 0; side < (pingPong[i] ? 2 : 1); side++) {
					int index = i;
					String suffix = side == 0 ? "" : " alt";
					colors[i][side] = TargetStorage.create(() -> device.createTexture(() -> "Aetherium Shaders shadowcolor" + index + suffix,
							GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
							formats[index], resolution, resolution, 1, levels));
					colorViews[i][side] = device.createTextureView(colors[i][side], 0, 1);
					colorChainViews[i][side] = levels > 1 ? device.createTextureView(colors[i][side]) : null;
				}
				base[i] = 0;
				flipped[i] = false;
			}
		}
		if (lean && !fresh) {
			clearOnLoad = true;
			return;
		}
		clearOnLoad = false;
		clearNow(fresh);
	}

	private void clearNow(boolean all) {
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.clearDepthTexture(depth, 1.0);
		for (int i = 0; i < colorCount; i++) {
			// A target no shadow program writes keeps the clear colour it got when created (as on lean frames).
			if (!all && !isAttachment(i)) {
				continue;
			}
			PackShadowDirectives.SamplingSettings s = directives.getColorSamplingSettings().get(i);
			if (all || s == null || s.getClear()) {
				for (int side = 0; side < 2; side++) {
					if (colors[i][side] != null && (all || side == base[i])) {
						encoder.clearColorTexture(colors[i][side], clearColor(s));
					}
				}
			}
		}
	}

	private boolean isAttachment(int target) {
		for (int a : attachments) {
			if (a == target) {
				return true;
			}
		}
		return false;
	}

	private static Vector4f clearColor(PackShadowDirectives.@Nullable SamplingSettings s) {
		return s == null ? new Vector4f(1.0f) : new Vector4f(s.getClearColor());
	}

	// ------------------------------------------------------------------ terrain cache

	private @Nullable GpuTexture cacheDepth;
	private @Nullable GpuTexture cacheOpaque;
	private final @Nullable GpuTexture[] cacheColors = new GpuTexture[8];
	private @Nullable GpuTextureView cacheDepthView;
	private @Nullable GpuTextureView cacheOpaqueView;
	private final @Nullable GpuTextureView[] cacheColorViews = new GpuTextureView[8];
	/** This frame samples the cache textures instead of the live maps ({@link #bindTerrain}). */
	private boolean cacheBound;

	/** The map drawn this frame (terrain only) becomes the cache: depth, opaque depth and the written shadowcolors. */
	public void storeTerrain() {
		var device = RenderSystem.getDevice();
		if (cacheDepth == null) {
			// Sampleable too: a frame that draws nothing on top of the cached terrain reads it in place (bindTerrain).
			int usage = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
			cacheDepth = device.createTexture(() -> "Aetherium Shaders shadow cache depth", usage,
					GpuFormat.D32_FLOAT, resolution, resolution, 1, 1);
			cacheDepthView = device.createTextureView(cacheDepth);
			if (opaqueDepth != null) {
				cacheOpaque = device.createTexture(() -> "Aetherium Shaders shadow cache opaque depth", usage,
						GpuFormat.D32_FLOAT, resolution, resolution, 1, 1);
				cacheOpaqueView = device.createTextureView(cacheOpaque);
			}
			for (int target : attachments) {
				cacheColors[target] = device.createTexture(() -> "Aetherium Shaders shadow cache colour" + target,
						usage, formats[target], resolution, resolution, 1, 1);
				cacheColorViews[target] = device.createTextureView(cacheColors[target]);
			}
		}
		var encoder = device.createCommandEncoder();
		encoder.copyTextureToTexture(depth, cacheDepth, 0, 0, 0, 0, 0, resolution, resolution);
		if (cacheOpaque != null) {
			encoder.copyTextureToTexture(opaqueDepth, cacheOpaque, 0, 0, 0, 0, 0, resolution, resolution);
		}
		for (int target : attachments) {
			encoder.copyTextureToTexture(colorTexture(target), cacheColors[target], 0, 0, 0, 0, 0, resolution, resolution);
		}
	}

	/** The cached terrain back into the live maps (instead of clearing and drawing them). */
	public void restoreTerrain() {
		clearOnLoad = false;
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.copyTextureToTexture(cacheDepth, depth, 0, 0, 0, 0, 0, resolution, resolution);
		if (cacheOpaque != null) {
			encoder.copyTextureToTexture(cacheOpaque, opaqueDepth, 0, 0, 0, 0, 0, resolution, resolution);
		}
		for (int target : attachments) {
			encoder.copyTextureToTexture(cacheColors[target], colorTexture(target), 0, 0, 0, 0, 0, resolution, resolution);
		}
	}

	public boolean hasTerrainCache() {
		return cacheDepth != null;
	}

	/**
	 * Whether the cached terrain can stand in for the live maps when nothing is drawn on top of it this frame: none of
	 * the maps has a mip chain, no shadowcolor the shadow programs write is kept across frames or written by a
	 * shadowcomp pass (those read or write the live textures' history), and no program binds targets as images.
	 */
	public boolean canBindCache() {
		if (cacheDepth == null || depthLevels > 1 || opaqueLevels > 1 || TargetStorage.active()) {
			return false;
		}
		for (int target : attachments) {
			PackShadowDirectives.SamplingSettings s = directives.getColorSamplingSettings().get(target);
			if (pingPong[target] || colorMipmapped[target] || s != null && !s.getClear()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * This frame reads the cached terrain in place of the live maps (it draws nothing on top of it): samplers get the
	 * cache textures, nothing is copied. The live maps are cleared and drawn, or restored, again before they are used.
	 */
	public void bindTerrain() {
		clearOnLoad = false;
		cacheBound = true;
	}

	/** Whether the opaque-only depth (shadowtex1) exists and something reads it. */
	public boolean opaqueDepthLive() {
		return opaqueDepth != null && opaqueDepthSampled;
	}

	private final @Nullable GpuTexture[] scratchColors = new GpuTexture[8];
	private final @Nullable GpuTextureView[] scratchViews = new GpuTextureView[8];

	/**
	 * A render pass that draws on top of the maps (entities after the terrain), everything loaded. Into
	 * {@code shadowtex0} with the shadowcolor targets; into {@code shadowtex1} with throwaway colour targets, so the
	 * colours are written once (the pipelines need colour attachments of these formats either way).
	 */
	public RenderPassDescriptor overlayDescriptor(String label, boolean intoOpaqueDepth) {
		RenderPassDescriptor.Builder builder = RenderPassDescriptor.builder(() -> "Aetherium Shaders shadow (" + label + ")");
		for (int target : attachments) {
			builder.withColorAttachment(intoOpaqueDepth ? scratchView(target) : colorView(target), Optional.<Vector4fc>empty());
		}
		builder.withDepthAttachment(intoOpaqueDepth ? opaqueDepthView : depthView, OptionalDouble.empty());
		return builder.build();
	}

	private GpuTextureView scratchView(int target) {
		if (scratchViews[target] == null) {
			scratchColors[target] = RenderSystem.getDevice().createTexture(() -> "Aetherium Shaders shadow scratch colour" + target,
					GpuTexture.USAGE_RENDER_ATTACHMENT, formats[target], resolution, resolution, 1, 1);
			scratchViews[target] = RenderSystem.getDevice().createTextureView(scratchColors[target]);
		}
		return scratchViews[target];
	}

	private void closeCache() {
		for (int i = 0; i < scratchColors.length; i++) {
			if (scratchColors[i] != null) {
				scratchViews[i].close();
				scratchColors[i].close();
				scratchColors[i] = null;
				scratchViews[i] = null;
			}
		}
		cacheBound = false;
		if (cacheDepth != null) {
			cacheDepthView.close();
			cacheDepth.close();
			cacheDepth = null;
			cacheDepthView = null;
		}
		if (cacheOpaque != null) {
			cacheOpaqueView.close();
			cacheOpaque.close();
			cacheOpaque = null;
			cacheOpaqueView = null;
		}
		for (int i = 0; i < cacheColors.length; i++) {
			if (cacheColors[i] != null) {
				cacheColorViews[i].close();
				cacheColors[i].close();
				cacheColors[i] = null;
				cacheColorViews[i] = null;
			}
		}
	}

	/** A lean frame drew no shadow render pass (nothing to load-clear into): clears now. */
	public void flushClear() {
		if (clearOnLoad) {
			clearOnLoad = false;
			clearNow(false);
		}
	}

	/** The render pass the shadow terrain draws in: shadowcolor attachments and the shadow depth map. */
	public RenderPassDescriptor descriptor(String label) {
		RenderPassDescriptor.Builder builder = RenderPassDescriptor.builder(() -> "Aetherium Shaders shadow (" + label + ")");
		boolean clear = clearOnLoad;
		clearOnLoad = false;
		for (int target : attachments) {
			PackShadowDirectives.SamplingSettings s = directives.getColorSamplingSettings().get(target);
			boolean clears = clear && (s == null || s.getClear());
			builder.withColorAttachment(colorView(target), clears ? Optional.<Vector4fc>of(clearColor(s)) : Optional.<Vector4fc>empty());
		}
		builder.withDepthAttachment(depthView, clear ? OptionalDouble.of(1.0) : OptionalDouble.empty());
		return builder.build();
	}

	/** Makes {@code projection} (OpenGL convention) the bound projection, in vanilla's reversed zero-to-one form. */
	public void bindProjection(Matrix4f projection) {
		// z_r = (w - z_gl) / 2: row 2 := (row 3 - row 2) / 2. The pack program recovers the OpenGL matrix exactly.
		Matrix4f m = new Matrix4f(projection);
		m.m02((projection.m03() - projection.m02()) * 0.5f);
		m.m12((projection.m13() - projection.m12()) * 0.5f);
		m.m22((projection.m23() - projection.m22()) * 0.5f);
		m.m32((projection.m33() - projection.m32()) * 0.5f);
		RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(m), ProjectionType.ORTHOGRAPHIC);
	}

	/** After the opaque terrain: keeps its depth as {@code shadowtex1} when translucents follow. Not inside a render pass. */
	public void afterOpaque(boolean lean) {
		if (opaqueDepth != null && (!lean || opaqueDepthSampled)) {
			RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(depth, opaqueDepth, 0, 0, 0, 0, 0, resolution, resolution);
		}
	}

	/** {@code shadowcolorN} index of a sampler name ({@code shadowcolor} = 0), or -1. */
	static int colorIndex(String name) {
		if (name.equals("shadowcolor")) {
			return 0;
		}
		if (name.startsWith("shadowcolor")) {
			try {
				return Integer.parseInt(name.substring(11));
			} catch (NumberFormatException e) {
				return -1;
			}
		}
		return -1;
	}

	/** Whether a shadowcolor sampler reads nearest ({@code shadowcolorNNearest}, or an integer format). */
	public boolean colorNearest(String name) {
		int i = colorIndex(name);
		if (i < 0) {
			return false;
		}
		PackShadowDirectives.SamplingSettings s = directives.getColorSamplingSettings().get(i);
		return s != null && s.getNearest() || i < colorCount && WorldProgram.isIntegerFormat(formats[i]);
	}

	/**
	 * The view behind a shadow sampler name, or null when the name is not a shadow sampler: the mip chain once it was
	 * generated this frame ({@link #mipSampler} then gives the sampler), the cache textures on a frame that reads the
	 * cached terrain in place.
	 */
	public @Nullable GpuTextureView samplerView(String name, boolean packUsesWatershadow) {
		return switch (name) {
			case "shadowtex0", "watershadow", "shadowtex0HW" -> depthSampleView(false);
			case "shadowtex1", "shadowtex1HW" -> depthSampleView(true);
			// "shadow" is shadowtex1 when the pack also declares watershadow, else shadowtex0.
			case "shadow" -> depthSampleView(packUsesWatershadow);
			default -> {
				int i = colorIndex(name);
				yield i >= 0 && i < colorCount ? colorSampleView(i) : null;
			}
		};
	}

	/** The depth map {@code shadowtex0} or (when {@code opaque} and it exists) {@code shadowtex1} as samplers read it now. */
	private GpuTextureView depthSampleView(boolean opaque) {
		if (opaque && opaqueDepthView != null) {
			return cacheBound && cacheOpaqueView != null ? cacheOpaqueView : opaqueChainOn ? opaqueChainView : opaqueDepthView;
		}
		return cacheBound && cacheDepthView != null ? cacheDepthView : depthChainOn ? depthChainView : depthView;
	}

	private GpuTextureView colorSampleView(int target) {
		if (cacheBound && target < cacheColorViews.length && cacheColorViews[target] != null) {
			return cacheColorViews[target];
		}
		int side = side(target);
		return colorChainOn[target][side] ? colorChainViews[target][side] : colorViews[target][side];
	}

	private static @Nullable GpuSampler nearestMips;

	/**
	 * The sampler of a shadow sampler name whose texture is read through its mip chain now, or null (read level 0 as
	 * before). As the format sets them: nearest-filtered maps sample the nearest level nearest, the others blend two
	 * levels linearly; a shadowcomp pass's chain makes the minification of colour targets linear between levels
	 * (nearest for integer formats), the magnification staying as the target's directive says.
	 */
	public @Nullable GpuSampler mipSampler(String name, boolean packUsesWatershadow) {
		int map = ShadowSampling.depthIndex(name, packUsesWatershadow);
		if (map >= 0) {
			boolean opaque = map == 1 && opaqueDepthView != null;
			if (!(opaque ? opaqueChainOn : depthChainOn) || !depthMipmapped[map] || cacheBound) {
				return null;
			}
			return mipSampler(ShadowSampling.nearest(map), ShadowSampling.nearest(map));
		}
		int i = colorIndex(name);
		if (i < 0 || i >= colorCount || cacheBound) {
			return null;
		}
		int side = side(i);
		if (!colorChainOn[i][side]) {
			return null;
		}
		boolean magNearest = colorNearest(name);
		boolean minNearest = colorChainByComposite[i][side] ? WorldProgram.isIntegerFormat(formats[i]) : magNearest;
		return mipSampler(minNearest, magNearest);
	}

	private static GpuSampler mipSampler(boolean minNearest, boolean magNearest) {
		if (minNearest && magNearest) {
			if (nearestMips == null) {
				nearestMips = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST,
						FilterMode.NEAREST, 1, OptionalDouble.of(PbrTextures.NEAREST_MIPS_LOD));
			}
			return nearestMips;
		}
		return RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
				minNearest ? FilterMode.NEAREST : FilterMode.LINEAR, magNearest ? FilterMode.NEAREST : FilterMode.LINEAR, true);
	}

	/**
	 * After the shadow map was drawn (terrain, entities, LODs): the mip chains the format asks for, by {@link MipChains}.
	 * Nothing when {@code blitted} is off (the maps are then read at level 0, as without chains). Not inside a render pass.
	 */
	public void generateMipmaps(boolean blitted) {
		if (!blitted || cacheBound) {
			return;
		}
		if (depthLevels > 1) {
			depthChainOn = MipChains.generate(depth, depthLevels);
		}
		if (opaqueLevels > 1 && opaqueDepthLive()) {
			opaqueChainOn = MipChains.generate(opaqueDepth, opaqueLevels);
		}
		for (int i = 0; i < colorCount; i++) {
			PackShadowDirectives.SamplingSettings s = directives.getColorSamplingSettings().get(i);
			if (s != null && s.getMipmap() && colorMipmapped[i]) {
				int side = side(i);
				colorChainOn[i][side] = MipChains.generate(colors[i][side], colorLevels);
			}
		}
	}

	/** Before a shadowcomp pass that reads {@code target} mipmapped: the chain of the side it reads. */
	public void generateColorMipmaps(int target, boolean blitted) {
		if (!blitted || target < 0 || target >= colorCount || !colorMipmapped[target]) {
			return;
		}
		int side = side(target);
		if (MipChains.generate(colors[target][side], colorLevels)) {
			colorChainOn[target][side] = true;
			colorChainByComposite[target][side] = true;
		}
	}

	/** The shadowcolor count (targets 0..count-1 exist). */
	public int colorCount() {
		return colorCount;
	}

	private int side(int target) {
		return flipped[target] ? 1 - base[target] : base[target];
	}

	/** The side of {@code target} that holds its current contents (what passes read and the shadow pass draws into). */
	public GpuTextureView colorView(int target) {
		return colorViews[target][side(target)];
	}

	private GpuTexture colorTexture(int target) {
		return colors[target][side(target)];
	}

	/** The side a shadowcomp pass writing {@code target} renders into (its target must be ping-ponged). */
	public GpuTextureView colorWriteView(int target) {
		if (!pingPong[target]) {
			throw new IllegalStateException("shadowcolor" + target + " is not written by a shadowcomp pass");
		}
		return colorViews[target][1 - side(target)];
	}

	/**
	 * After a shadowcomp pass wrote {@code target}: its new contents are the other side. {@code whole}: the pass covered
	 * every texel of that side (false for a flip with no write).
	 */
	public void flip(int target, boolean whole) {
		if (pingPong[target]) {
			flipped[target] = !flipped[target];
			partial[target] = !whole;
		}
	}

	/**
	 * End of the frame: the side each target was left on becomes its base (targets kept across frames read it next). A
	 * kept target whose last flip left that side partly written is copied back to its base side instead, so its texels
	 * outside the written area do not alternate between two images.
	 */
	public void endFrame() {
		for (int i = 0; i < colorCount; i++) {
			int side = side(i);
			PackShadowDirectives.SamplingSettings s = directives.getColorSamplingSettings().get(i);
			boolean kept = s != null && !s.getClear();
			if (side != base[i] && partial[i] && kept) {
				RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(colors[i][side], colors[i][base[i]], 0, 0, 0, 0, 0, resolution, resolution);
			} else {
				base[i] = side;
			}
			flipped[i] = false;
			partial[i] = false;
		}
	}

	@Override
	public void close() {
		closeCache();
		if (depth != null) {
			if (depthChainView != null) {
				depthChainView.close();
				depthChainView = null;
			}
			depthView.close();
			depth.close();
			depth = null;
		}
		for (int i = 0; i < colorCount; i++) {
			for (int side = 0; side < 2; side++) {
				if (colors[i][side] != null) {
					if (colorChainViews[i][side] != null) {
						colorChainViews[i][side].close();
						colorChainViews[i][side] = null;
					}
					colorViews[i][side].close();
					colors[i][side].close();
					colors[i][side] = null;
					colorViews[i][side] = null;
				}
			}
		}
		if (opaqueDepth != null) {
			if (opaqueChainView != null) {
				opaqueChainView.close();
				opaqueChainView = null;
			}
			opaqueDepthView.close();
			opaqueDepth.close();
			opaqueDepth = null;
			opaqueDepthView = null;
		}
		projectionBuffer.close();
		AetheriumShaders.logger.debug("shadow pass closed");
	}
}
