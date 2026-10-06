package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.spacebod.aetherium.client.render.SectionBuilds;
import dev.spacebod.aetherium.shaders.chunks.ChunkShadows;
import dev.spacebod.aetherium.shaders.compat.lod.LodCompat;
import dev.spacebod.aetherium.shaders.pipeline.WorldRenderingPhase;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramGroup;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import dev.spacebod.aetherium.shaders.shadows.ShadowRenderer;
import dev.spacebod.aetherium.shaders.uniforms.CapturedRenderingState;
import dev.spacebod.aetherium.shaders.uniforms.CelestialUniforms;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * One frame of the shadow map: which terrain sections cast, the terrain and entity render passes, and the terrain
 * cache ({@link EngineSwitches#SHADOW_CACHE}): the terrain drawn earlier is reused while the light, the shadow origin
 * cell, the render distance and the terrain stay the same, with the shadow matrices pinned to the angle it was drawn
 * with, and entities drawn on top every frame.
 */
final class ShadowFrame {
	/** Opens and closes the engine's shadow render passes (pipelines bound inside get the shadow programs). */
	interface Passes {
		void openShadowPass();

		void closeShadowPass();

		/** LODs go into the shadow map this frame ({@code dh_shadow}): casters are culled against their reach. */
		boolean lodShadows();
	}

	/** Light movement (in turns, 0.02 degrees) below which the cached map is kept; the matrices stay pinned meanwhile. */
	private static final float ANGLE_EPSILON = 0.02f / 360.0f;
	/** Frames with no section rebuilt before the terrain is cached again (a compiled mesh is uploaded a little later). */
	private static final int STABLE_FRAMES = 4;
	/**
	 * Inputs that change a shadow program's output while the terrain, the light and the shadow origin stay put: time and
	 * weather, and where the player is or looks (the cache keeps the map through camera moves inside a cell and turns).
	 */
	private static final Pattern ANIMATED = Pattern.compile("\\b(frameTimeCounter|frameCounter|frameTime|worldTime|worldDay|rainStrength"
			+ "|wetness|thunderStrength|cameraPosition\\w*|previousCameraPosition\\w*|eyePosition|playerLookVector|relativeEyePosition"
			+ "|gbufferModelView\\w*|gbufferProjection\\w*|gbufferPrevious\\w*)\\b");

	private final ShadowPass shadow;
	/** The pack's shadow programs read something the cache cannot see change: never cached. */
	private final boolean animated;
	private final Matrix4f shadowFromView = new Matrix4f();
	private final Matrix4f playerView = new Matrix4f();
	private final ChunkShadows terrain = new ChunkShadows();
	private int casterSections;

	// Terrain cache
	private boolean cacheOn;
	/** The terrain is steady enough to store (no section rebuilt for STABLE_FRAMES frames). */
	private boolean cacheValid;
	/** The terrain for the current key was actually stored (a miss frame can end before drawing). */
	private boolean stored;
	/** This frame reuses the stored terrain instead of drawing it. */
	private boolean reuse;
	private float cachedAngle;
	private boolean cachedDay;
	private long cachedCellX;
	private long cachedCellY;
	private long cachedCellZ;
	private int cachedRenderDistance;
	private long lastSectionsCompiled = -1;
	private int stableFrames;
	private int hits;
	private int misses;

	ShadowFrame(ShadowPass shadow, boolean animated) {
		this.shadow = shadow;
		this.animated = animated;
	}

	/**
	 * Whether any shadow program reads an input the cache cannot see change (see {@link #ANIMATED}), or any custom
	 * uniform or variable of the pack (their expressions may read any of those).
	 */
	static boolean animated(ProgramSet programs, Set<String> customUniforms) {
		Pattern custom = customUniforms.isEmpty() ? null : Pattern.compile("\\b(" + customUniforms.stream()
				.map(Pattern::quote).collect(Collectors.joining("|")) + ")\\b");
		for (ProgramId id : ProgramId.values()) {
			if (id.getGroup() != ProgramGroup.Shadow) {
				continue;
			}
			Optional<ProgramSource> source = programs.get(id);
			if (source.isEmpty()) {
				continue;
			}
			String text = source.get().getVertexSource().orElse("") + "\n" + source.get().getFragmentSource().orElse("");
			if (ANIMATED.matcher(text).find() || custom != null && custom.matcher(text).find()) {
				return true;
			}
		}
		return false;
	}

	boolean cacheAvailable() {
		return !animated;
	}

	int casterSections() {
		return casterSections;
	}

	int cacheHits() {
		return hits;
	}

	int cacheMisses() {
		return misses;
	}

	/** Shadow model-view x inverse camera view: rebases entity draws prepared for the player's camera onto the shadow map. */
	Matrix4fc shadowFromView() {
		return shadowFromView;
	}

	/**
	 * Start of a frame, before the uniforms are computed: whether the stored terrain is still exact (same light angle
	 * within {@link #ANGLE_EPSILON}, same shadow origin cell and render distance, no section rebuilt), and pins the
	 * shadow matrices to the angle the map was, or is about to be, drawn with.
	 */
	void beginFrame(CameraRenderState camera, boolean cacheEnabled) {
		reuse = false;
		cacheOn = cacheEnabled && !animated;
		if (!cacheOn) {
			cacheValid = false;
			ShadowRenderer.unpinShadowAngle();
			return;
		}
		long compiled = SectionBuilds.COMPLETED.sum();
		if (compiled != lastSectionsCompiled) {
			lastSectionsCompiled = compiled;
			stableFrames = 0;
		} else if (stableFrames < Integer.MAX_VALUE) {
			stableFrames++;
		}
		float live = ShadowRenderer.liveShadowAngle();
		boolean day = CelestialUniforms.isDay();
		float interval = shadow.intervalSize();
		long cx = cell(camera.pos.x, interval), cy = cell(camera.pos.y, interval), cz = cell(camera.pos.z, interval);
		// The shadow planes and caster distance follow the render distance when the pack leaves them unset.
		int renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance();
		reuse = cacheValid && stored && shadow.hasTerrainCache() && day == cachedDay && Math.abs(live - cachedAngle) < ANGLE_EPSILON
				&& cx == cachedCellX && cy == cachedCellY && cz == cachedCellZ && renderDistance == cachedRenderDistance
				&& stableFrames >= STABLE_FRAMES;
		if (!reuse) {
			// Drawn fresh this frame: stored only once the terrain has stopped changing.
			cacheValid = stableFrames >= STABLE_FRAMES;
			stored = false;
			cachedAngle = live;
			cachedDay = day;
			cachedCellX = cx;
			cachedCellY = cy;
			cachedCellZ = cz;
			cachedRenderDistance = renderDistance;
		}
		ShadowRenderer.pinShadowAngle(cachedAngle);
	}

	/** The shadow origin's grid cell along one axis, as {@code ShadowMatrices.snapModelViewToGrid} places it. */
	private static long cell(double camera, float interval) {
		if (interval == 0.0f) {
			return Double.doubleToLongBits(camera);
		}
		double origin = camera - ((float) camera % interval);
		return Math.round(origin / interval);
	}

	/** Before vanilla's world passes: the shadow map (terrain, then entities). */
	void draw(FeatureRenderDispatcher.@Nullable PreparedFrame features, boolean lean, GpuBuffer quad, Passes passes) {
		ShadowPass s = shadow;
		s.beginFrame(quad, lean);
		Matrix4f modelView = s.modelView();
		Matrix4f projection = s.projection();
		CameraRenderState cameraState = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		shadowFromView.set(modelView).mul(new Matrix4f(cameraState.viewRotationMatrix).invert());
		// Entities, block entities and items: this frame's prepared draws (the camera's, plus the casters it does not see,
		// ShadowCasters), rebased.
		var entities = features != null && (s.renderEntities() || s.renderBlockEntities() || s.renderPlayer() || s.renderLightBlockEntities()) ? features : null;
		if (cacheOn && reuse) {
			hits++;
			casterSections = 0;
			if (entities == null && !passes.lodShadows() && EngineSwitches.enabled(EngineSwitches.SHADOW_CACHE_BIND) && s.canBindCache()) {
				// Nothing goes on top of the stored terrain this frame: it is read where it is (shaders.shadow_cache_bind).
				s.bindTerrain();
				return;
			}
			// The terrain is unchanged since it was stored: copy it back, skip the caster walk and the draw list.
			s.restoreTerrain();
			drawEntities(entities, projection, passes);
			return;
		}
		// Casters: sections that can shadow what the player sees (ShadowCasterCulling: never tested against the shadow
		// projection, which packs distort), within the culling radius. A map that will be stored must not depend on where
		// the player looks: then every section within the shadow distance casts; a miss frame that stores nothing (terrain
		// still changing) culls.
		boolean store = cacheOn && cacheValid;
		var camera = cameraState.pos;
		// With LOD shadows, what the player sees reaches the LOD distance: the LOD projection bounds the casters.
		playerView.set(passes.lodShadows() ? LodCompat.getProjection()
				: CapturedRenderingState.INSTANCE.getGbufferProjection()).mul(CapturedRenderingState.INSTANCE.getGbufferModelView());
		var culling = !store ? s.casterCulling(playerView, modelView, camera.x, camera.y, camera.z) : null;
		double cullRadius = culling == null ? -1 : culling.radius();
		double walk = culling == null ? s.casterDistance() : cullRadius < 0 ? ShadowPass.renderDistanceBlocks() : cullRadius;
		int casting = terrain.collect(camera.x, camera.y, camera.z, culling, walk + 16.0);
		if (casting < 0) {
			s.flushClear();
			return;
		}
		casterSections = casting;
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		RenderSystem.backupProjectionMatrix();
		try {
			terrain.prepare();
			s.bindProjection(projection);
			// With the cache the entities go on top afterwards, so the stored map holds terrain only.
			drawTerrain(ChunkSectionLayerGroup.OPAQUE, nearest, "opaque", cacheOn ? null : entities, passes);
			if (s.renderTranslucent()) {
				s.afterOpaque(lean && !cacheOn);
				drawTerrain(ChunkSectionLayerGroup.TRANSLUCENT, nearest, "translucent", null, passes);
			}
		} finally {
			terrain.finish();
			RenderSystem.restoreProjectionMatrix();
		}
		if (cacheOn) {
			if (store) {
				s.storeTerrain();
				stored = true;
			}
			misses++;
			drawEntities(entities, projection, passes);
		}
	}

	private void drawTerrain(ChunkSectionLayerGroup group, GpuSampler sampler, String label, FeatureRenderDispatcher.@Nullable PreparedFrame features,
			Passes passes) {
		passes.openShadowPass();
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(shadow.descriptor(label))) {
			RenderSystem.bindDefaultUniforms(pass);
			// renderStage as the pack format defines it for the shadow pass: opaque terrain is TERRAIN_SOLID (cutout
			// included), translucent terrain TERRAIN_TRANSLUCENT, the entities drawn with them ENTITIES.
			RenderPhase.override(group == ChunkSectionLayerGroup.TRANSLUCENT ? WorldRenderingPhase.TERRAIN_TRANSLUCENT : WorldRenderingPhase.TERRAIN_SOLID);
			if (shadow.renderTerrain()) {
				terrain.draw(group, pass, sampler);
			}
			if (features != null) {
				RenderPhase.override(WorldRenderingPhase.ENTITIES);
				features.executeSolid(pass);
				EntityPhases.drawTranslucentEntities(features, pass);
			}
		} finally {
			RenderPhase.override(null);
			passes.closeShadowPass();
		}
	}

	/**
	 * Entities drawn over terrain already in the maps (the cached path): into shadowtex0 with the shadowcolor targets,
	 * then, when something reads it, depth-only into the opaque depth (shadowtex1). Depth-tested writes give the same
	 * depth and colours as drawing entities before the translucent terrain, except for packs that blend shadow colours.
	 */
	private void drawEntities(FeatureRenderDispatcher.@Nullable PreparedFrame entities, Matrix4f projection, Passes passes) {
		if (entities == null) {
			return;
		}
		RenderSystem.backupProjectionMatrix();
		try {
			shadow.bindProjection(projection);
			for (boolean opaqueDepth : shadow.opaqueDepthLive() ? new boolean[]{false, true} : new boolean[]{false}) {
				passes.openShadowPass();
				try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(shadow.overlayDescriptor("entities", opaqueDepth))) {
					RenderSystem.bindDefaultUniforms(pass);
					RenderPhase.override(WorldRenderingPhase.ENTITIES);
					entities.executeSolid(pass);
					EntityPhases.drawTranslucentEntities(entities, pass);
				} finally {
					RenderPhase.override(null);
					passes.closeShadowPass();
				}
			}
		} finally {
			RenderSystem.restoreProjectionMatrix();
		}
	}
}
