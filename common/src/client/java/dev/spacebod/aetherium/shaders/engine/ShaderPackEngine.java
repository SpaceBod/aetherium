package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.spacebod.aetherium.client.DevHooks;
import dev.spacebod.aetherium.client.gpu.FrameCensus;
import dev.spacebod.aetherium.client.gpu.PassViewport;
import dev.spacebod.aetherium.client.gpu.PipelineCacheStore;
import dev.spacebod.aetherium.client.gpu.StorageFeatures;
import dev.spacebod.aetherium.client.gpu.VulkanAccess;
import dev.spacebod.aetherium.client.gpu.VulkanCompute;
import dev.spacebod.aetherium.client.mixin.access.GameRendererHandAccessor;
import dev.spacebod.aetherium.client.mixin.access.VulkanCommandEncoderAccessor;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.chunks.ChunkMeshes;
import dev.spacebod.aetherium.shaders.compat.lod.LodCompat;
import dev.spacebod.aetherium.shaders.config.ShaderPackSettings;
import dev.spacebod.aetherium.shaders.gl.ShaderLimits;
import dev.spacebod.aetherium.shaders.gl.framebuffer.ViewportData;
import dev.spacebod.aetherium.shaders.helpers.DrawIds;
import dev.spacebod.aetherium.shaders.helpers.StringPair;
import dev.spacebod.aetherium.shaders.gl.shader.StandardMacros;
import dev.spacebod.aetherium.shaders.helpers.ShadowCasters;
import dev.spacebod.aetherium.shaders.pbr.PbrKind;
import dev.spacebod.aetherium.shaders.pbr.PbrTextures;
import dev.spacebod.aetherium.shaders.pipeline.PipelinePrograms;
import dev.spacebod.aetherium.shaders.pipeline.WorldRenderingPhase;
import dev.spacebod.aetherium.shaders.pipeline.transform.TransformPatcher;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.TargetImages;
import dev.spacebod.aetherium.shaders.shaderpack.ShaderPack;
import dev.spacebod.aetherium.shaders.shaderpack.ShadowOverrides;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramArrayId;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.BlockMaterialMapping;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.NamespacedId;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ComputeSource;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import dev.spacebod.aetherium.shaders.shaderpack.properties.CloudSetting;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import dev.spacebod.aetherium.shaders.shadows.ShadowMatrices;
import dev.spacebod.aetherium.shaders.shadows.ShadowRenderer;
import dev.spacebod.aetherium.shaders.uniforms.CapturedRenderingState;
import dev.spacebod.aetherium.shaders.uniforms.CommonUniforms;
import dev.spacebod.aetherium.shaders.uniforms.DateTimeUniforms;
import dev.spacebod.aetherium.shaders.uniforms.DrawState;
import dev.spacebod.aetherium.shaders.uniforms.FrameUpdateNotifier;
import dev.spacebod.aetherium.shaders.parsing.SmoothFloat;
import dev.spacebod.aetherium.shaders.uniforms.SystemTimeUniforms;
import dev.spacebod.aetherium.shaders.uniforms.custom.CustomUniforms;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Aetherium Shaders: the active shader pack on vanilla 26.3's renderer (Vulkan or OpenGL). Each frame:
 * <ol>
 *   <li>level start: colortex clears, custom uniforms, {@code begin} and {@code prepare} passes;</li>
 *   <li>before the world: the shadow map ({@link ShadowFrame});</li>
 *   <li>world: every vanilla render pass that targets the main colour target is re-targeted to the pack's gbuffer
 *       attachments ({@link GbufferLayout}), and every pipeline bound in it is replaced by the pack program the program
 *       table assigns, or the vanilla pipeline widened to those attachments ({@link WorldPrograms});</li>
 *   <li>after the opaque world: {@code depthtex2}, the solid first-person hand (with {@code gbuffers_hand} at
 *       {@link #HAND_DEPTH}; packs light it in deferred), {@code depthtex1}, the {@code deferred} passes, then
 *       translucents;</li>
 *   <li>vanilla's hand pass: the rest of the hand (translucent held items);</li>
 *   <li>then {@code depthtex0}, the {@code composite} passes and {@code final} into vanilla's main target, before
 *       vanilla's screen effects and GUI.</li>
 * </ol>
 * Selected in {@code config/aetherium.json}: {@code "shaders": { "enabled": true, "pack": "<zip or folder>" }} (packs in
 * {@code <game dir>/shaderpacks}). Anything that fails is logged under "Aetherium Shaders" and the game keeps vanilla
 * visuals.
 */
public final class ShaderPackEngine implements ShadowFrame.Passes {
	private static final ShaderPackEngine INSTANCE = new ShaderPackEngine();
	/** Diagnostic views ({@link DevFlags#DEBUG_VIEW}); never called when they are off. */
	private final DebugView debug = new DebugView();
	/** Two triangles over [0,1]^2: Position (xyz) + UV0 (uv), the vertex layout full-screen programs read. */
	private static final float[] QUAD = {0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 1, 0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 0, 1, 0, 0, 1};
	/** Hand depth ({@code MC_HAND_DEPTH}): the hand's OpenGL clip z is scaled by this, so it stays in front of the world. */
	private static final float HAND_DEPTH = 0.125f;

	/**
	 * UNLOADED: a load starts at the next frame. LOADING: read and converted on a worker ({@link PackLoad}). WARMING: set
	 * up, its world programs building; both draw vanilla visuals. ACTIVE: the pack draws. DISABLED: off or failed.
	 */
	private enum State { UNLOADED, LOADING, WARMING, ACTIVE, DISABLED }

	private State state = State.UNLOADED;
	/** The pack in use: its open files (a zip stays mounted while it runs) and what it was read with. */
	private @Nullable LoadedPack current;
	/** A pack kept from the last load for the next one (after a dimension change): not read from disk again. */
	private @Nullable LoadedPack carried;

	/** A read pack and what its sources depend on; the next load reuses it only when all of that is unchanged. */
	private record LoadedPack(String name, ShaderPackSettings.OpenPack opened, ShaderPack pack, Map<String, String> options,
			List<StringPair> defines, ShadowOverrides shadows) {
		boolean readWith(String name, Map<String, String> options, List<StringPair> defines, ShadowOverrides shadows) {
			return this.name.equals(name) && this.options.equals(options) && this.defines.equals(defines) && this.shadows.equals(shadows);
		}
	}
	/** The loaded pack's profile line ("Profile: ... (+N options changed by user)"), for F3. */
	private @Nullable String profileInfo;
	private @Nullable ProgramSet programs;
	private @Nullable PackTargets targets;
	private final DepthTargets depth = new DepthTargets();
	private @Nullable GbufferLayout layout;
	private @Nullable WorldPrograms worldPrograms;
	private @Nullable ShadowPass shadow;
	private @Nullable WorldPrograms shadowPrograms;
	/** The shadow pass's render pass is open (its pipelines come from the shadow program table). */
	private boolean shadowPassOpen;
	private @Nullable ShadowFrame shadowFrame;
	private boolean preparedThisFrame;

	/** The full-screen side of the frame (stages, computes, flips), resolved at load. */
	private @Nullable FramePlan plan;
	/** Custom images and storage buffers of the pack (null without, or when the device lacks storage features). */
	private @Nullable PackStorage storage;
	private boolean setupRan;
	private @Nullable CustomUniforms customUniforms;
	private @Nullable FrameUpdateNotifier updateNotifier;
	private @Nullable GpuBuffer quad;
	private final BlitPass blit = new BlitPass();
	private final UniformArena arena = new UniformArena();
	private final Map<CompiledRenderPipeline, RenderPipeline> compiledToVanilla = new IdentityHashMap<>();
	private int noiseSize = 256;
	private @Nullable CenterDepth centerDepth;
	private @Nullable CustomTextures customTextures;
	/** Sampler name -> view and filtering, for every program kind (built at load). */
	private @Nullable SamplerTable samplerTable;

	/** Between level start and the end of the hand: vanilla passes on the main target become gbuffer passes. */
	private boolean worldActive;
	/** A re-targeted gbuffer render pass is open (FrontendCommandEncoder allows one render pass at a time). */
	private boolean gbufferPassOpen;
	/** The open gbuffer pass is the LOD mod's terrain pass (it keeps the mod's own depth, not vanilla's). */
	private boolean lodPassOpen;
	/** The pack has LOD programs and the LOD mod renders: the pack draws the LODs this frame. */
	private boolean lodsThisFrame;
	/** The pack has LOD programs ({@code dh_terrain}, or {@code dh_water} falling back to it). */
	private boolean packHasLodPrograms;
	/** The pack draws LODs into its shadow map too ({@code dh_shadow}, {@code dhShadow.enabled}). */
	private boolean packHasLodShadow;
	/** The LOD shadow call currently draws into the opaque-only depth ({@code shadowtex1}) rather than {@code shadowtex0}. */
	private boolean lodShadowIntoOpaque;
	/** Whether LOD rendering was on when the pack loaded (its DISTANT_HORIZONS macros): a change reloads the pack. */
	private boolean lodRenderingAtLoad;
	private PipelinePrograms.Context context = PipelinePrograms.Context.WORLD;
	/**
	 * Per colortex: the snapshot programs of the open world pass read it from (taken when that pass or an earlier one
	 * opened, and not written since), null before the first.
	 */
	private final GpuTextureView[] snapshots = new GpuTextureView[ShaderLimits.MAX_COLOR_BUFFERS];
	/** Per colortex: written since its snapshot was taken. */
	private final boolean[] snapshotStale = new boolean[ShaderLimits.MAX_COLOR_BUFFERS];
	/** Main depth written since depthtex0 was last captured. */
	private boolean depthStale = true;
	/** Main depth still holds vanilla's frame clear (nothing that writes depth has been bound yet this frame). */
	private boolean depthCleared = true;
	/** {@link EngineSwitches#LEAN_FRAME} for this frame. */
	private boolean lean;
	private @Nullable SamplerUsage usage;
	/** Per colortex: its frame-start clear is overwritten before anything reads it (lean frames skip it). */
	private boolean @Nullable [] clearOverwritten;
	private @Nullable RenderTarget main;

	private ShaderPackEngine() {
	}

	public static ShaderPackEngine get() {
		return INSTANCE;
	}

	private @Nullable String loadedPack;
	private @Nullable NamespacedId loadedDimension;
	private @Nullable String lastError;

	/**
	 * Throws away the running pack (or the load in progress) and loads the configured one (with its saved options) from
	 * the next frame, in the background: vanilla visuals until it is ready ({@link #loadProgress}). Called by the
	 * settings screen between frames, never inside a render pass.
	 */
	public void reload() {
		if (gbufferPassOpen || shadowPassOpen) {
			throw new IllegalStateException("reload inside a render pass");
		}
		disable();
		state = State.UNLOADED;
		lastError = null;
	}

	/** The pack the engine last tried to load (null = shaders off). */
	public @Nullable String loadedPack() {
		return loadedPack;
	}

	/** Why the last load or frame failed (null = it did not), for the settings screen. */
	public @Nullable String lastError() {
		return lastError;
	}

	/** Lines for the F3 screen: the pack, its programs and its shadow map. */
	public List<String> debugLines() {
		List<String> lines = new ArrayList<>();
		if (loadedPack == null) {
			lines.add("Aetherium Shaders: off");
			return lines;
		}
		String name = ShaderPackSettings.displayName(loadedPack);
		if (state != State.ACTIVE) {
			lines.add("Aetherium Shaders: " + name + (lastError != null ? " (failed, vanilla visuals)" : " (loading)"));
			return lines;
		}
		lines.add("Aetherium Shaders: " + name);
		if (profileInfo != null) {
			lines.add(profileInfo);
		}
		WorldPrograms world = worldPrograms;
		WorldPrograms shadows = shadowPrograms;
		int pending = (world == null ? 0 : world.pending()) + (shadows == null ? 0 : shadows.pending());
		GbufferLayout l = layout;
		lines.add("Programs: gbuffer attachments colortex" + (l == null ? "[]" : Arrays.toString(l.attachments()))
				+ (pending > 0 ? ", " + pending + " building" : ""));
		ShadowPass s = shadow;
		ShadowFrame frame = shadowFrame;
		if (s == null || frame == null) {
			lines.add("Shadow map: none");
		} else {
			int hits = frame.cacheHits(), misses = frame.cacheMisses();
			lines.add(String.format(Locale.ROOT, "Shadow map: %d px, %.0f blocks, %d caster sections%s", s.resolution(), s.mapReach(),
					frame.casterSections(), hits + misses == 0 ? "" : String.format(Locale.ROOT, ", cache %d%%", 100 * hits / (hits + misses))));
			lines.add("Shadow entities: " + (s.renderEntities() ? "on" : s.renderPlayer() ? "player only" : "off") + ", "
					+ ShadowCasters.count() + " outside the view");
		}
		String scale = RenderScale.debugLine();
		if (scale != null) {
			lines.add(scale);
		}
		String census = FrameCensus.line();
		if (census != null) {
			lines.add(census);
		}
		return lines;
	}

	/** Whether a pack is loaded and rendering (false while it loads, when off, or after a failure). */
	public boolean running() {
		return state == State.ACTIVE;
	}

	/** True when the active pack draws a shadow map (vanilla's blob shadows are dropped then). */
	public boolean hasShadowMap() {
		return state == State.ACTIVE && shadow != null;
	}

	/**
	 * True when the active pack's shadow map draws entities. They are replayed from the frame's prepared draws: the
	 * camera's, plus the casters it does not see ({@link ShadowCasters}).
	 */
	public boolean shadowsDrawEntities() {
		ShadowPass s = shadow;
		return state == State.ACTIVE && s != null && s.renderEntities();
	}

	/**
	 * How far from the camera entities the camera does not see are still extracted for the shadow map (0 = none: no
	 * map, or it draws no entities and not the player).
	 */
	public double shadowCasterEntityDistance() {
		ShadowPass s = shadow;
		return state == State.ACTIVE && s != null && (s.renderEntities() || s.renderPlayer()) ? s.entityCasterDistance() : 0.0;
	}

	/** The active pack's {@code frustum.culling}, {@code occlusion.culling} and {@code skipAllRendering}. */
	private boolean frustumCulling = true;
	private boolean occlusionCulling = true;
	private boolean skipAllRendering;

	/** False when the active pack turns frustum culling of terrain sections off ({@code frustum.culling=false}). */
	public boolean cullsFrustum() {
		return state != State.ACTIVE || frustumCulling;
	}

	/** False when the active pack turns vanilla's occlusion culling off ({@code occlusion.culling=false}). */
	public boolean cullsOcclusion() {
		return state != State.ACTIVE || occlusionCulling;
	}

	/** True while a pack is drawing the world (mixins use it to change vanilla's behaviour only then). */
	public boolean worldActive() {
		return state == State.ACTIVE && worldActive;
	}

	/** True when a pack is loaded and active (vanilla's improved transparency is off then: packs handle translucency). */
	public boolean packActive() {
		if (state == State.UNLOADED) {
			load();
		}
		return state == State.ACTIVE;
	}

	// ------------------------------------------------------------------ frame

	/** {@code GameRenderer.renderLevel} HEAD. */
	public void beginLevel(CameraRenderState camera, float partialTick, RenderTarget mainTarget) {
		if (teardownPending) {
			// A pack that failed during the last frame: its frame has been submitted, its objects can go now.
			teardownPending = false;
			teardown();
		}
		// Packs ship per-dimension program sets (world-1/, world1/, dimension.properties).
		if (state == State.ACTIVE && !AetheriumShaders.getCurrentDimension().equals(loadedDimension)) {
			enterDimension(AetheriumShaders.getCurrentDimension());
		}
		if (state == State.ACTIVE && LodCompat.hasRenderingEnabled() != lodRenderingAtLoad) {
			AetheriumShaders.logger.info("level-of-detail rendering switched {}, reloading the pack", lodRenderingAtLoad ? "off" : "on");
			reload();
		}
		if (PbrTextures.consumeFormatChange() && state != State.UNLOADED && loadedPack != null) {
			AetheriumShaders.logger.info("the resource packs' material format changed, reloading the pack");
			reload();
		}
		// Entering a world or a dimension: the pack is on from the first frame (it finishes behind the loading screen).
		// Switching packs inside a world loads in the background instead.
		var level = Minecraft.getInstance().level;
		boolean entering = level != renderedLevel;
		renderedLevel = level;
		PackLoad started = pendingLoad;
		if (entering && started != null && !started.dimension.equals(AetheriumShaders.getCurrentDimension())) {
			// Started for another dimension (at the title screen, or before a portal): start again for this one.
			reload();
		}
		if (state == State.UNLOADED) {
			load();
		}
		if (entering && loading()) {
			finishLoadNow();
		}
		if (state == State.ACTIVE) {
			// Material maps the last frame's draws asked for (outside any render pass: loading draws into the new maps).
			PbrTextures.loadPending();
		}
		if (loading()) {
			// The longest frame while the pack loads (logged when it goes active): loading must not stall the game.
			long now = System.nanoTime();
			if (lastLevelStart != 0) {
				longestLoadingFrame = Math.max(longestLoadingFrame, now - lastLevelStart);
			}
			lastLevelStart = now;
		}
		if (state == State.LOADING) {
			pollLoad();
		}
		if (state == State.WARMING) {
			warm();
		}
		boolean lodRendering = state == State.ACTIVE && LodCompat.hasRenderingEnabled();
		lodsThisFrame = lodRendering && packHasLodPrograms;
		LodCompat.beginFrame(lodsThisFrame, lodRendering && !packHasLodPrograms, lodsThisFrame && hideLodClouds());
		if (state != State.ACTIVE) {
			// After a failed load or a mid-frame failure: meshes built for the pack go back to vanilla's.
			rebuildMeshesIfNeeded(ChunkMeshes.stale());
			return;
		}
		try {
			main = mainTarget;
			// Advanced once per frame; frameTime, frameCounter and every per-frame uniform depend on them.
			SystemTimeUniforms.COUNTER.beginFrame();
			SystemTimeUniforms.TIMER.beginFrame(Util.getNanos());
			DateTimeUniforms.updateTime();
			CapturedRenderingState.INSTANCE.setGbufferModelView(new Matrix4f(camera.viewRotationMatrix));
			CapturedRenderingState.INSTANCE.setGbufferProjection(toGLProjection(new Matrix4f(camera.projectionMatrix)));
			CapturedRenderingState.INSTANCE.setTickDelta(partialTick);
			var fog = camera.fogData.color;
			CapturedRenderingState.INSTANCE.setFogColor(fog.x(), fog.y(), fog.z());
			captureFogAndDarkness();

			boolean building = worldPrograms.pending() > 0 || shadowPrograms != null && shadowPrograms.pending() > 0;
			worldPrograms.drainFinished();
			if (shadowPrograms != null) {
				shadowPrograms.drainFinished();
			}
			if (building && worldPrograms.pending() == 0 && (shadowPrograms == null || shadowPrograms.pending() == 0)) {
				// The pack's programs are built: keep their pipelines for the next launch (gpu.pipeline_cache).
				PipelineCacheStore.save();
			}
			if (customTextures != null) {
				// Before any pass or dispatch samples them: a resource-pack texture's first lookup loads it.
				customTextures.beginFrame();
			}
			PackTargets t = targets;
			if (t.resize(mainTarget.width, mainTarget.height)) {
				// setup.csh and setup_a..z run again after a resize (packs size their images from the screen).
				setupRan = false;
			}
			if (storage != null) {
				storage.resize(mainTarget.width, mainTarget.height);
				PackStorage s = storage;
				recordAndExecute(s::beginFrame);
				if (!setupRan) {
					setupRan = true;
					runComputes(plan.setup, null);
				}
				// shadow.csh, shadow_a.csh ...: at the start of the shadow pass, sized to the shadow map.
				int shadowSize = shadow != null ? shadow.resolution() : 0;
				runComputes(plan.shadow, shadowSize, shadowSize, null);
			}
			depth.resize(mainTarget.width, mainTarget.height);
			arena.beginFrame();
			if (shadowFrame != null) {
				shadowFrame.beginFrame(camera, EngineSwitches.enabled(EngineSwitches.SHADOW_CACHE));
			}
			updateNotifier.onNewFrame();
			customUniforms.update();
			lean = EngineSwitches.enabled(EngineSwitches.LEAN_FRAME);
			settledBindings = EngineSwitches.enabled(EngineSwitches.SETTLED_BINDINGS);
			reachSnapshots = EngineSwitches.enabled(EngineSwitches.REACH_SNAPSHOTS);
			frozenFullscreen = EngineSwitches.enabled(EngineSwitches.FROZEN_FULLSCREEN);
			depth.beginFrame();
			depthCleared = true;
			solidHandDrawn = false;
			if (handBuffers != null) {
				handBuffers.endFrame();
			}
			loadOps = EngineSwitches.enabled(EngineSwitches.LOADOP_CLEARS);
			blitMips = EngineSwitches.enabled(EngineSwitches.BLIT_MIPS);
			PassBarriers.setNarrow(EngineSwitches.enabled(EngineSwitches.NARROW_BARRIER));
			t.beginFrame(new Vector4f(fog.x(), fog.y(), fog.z(), 1.0f), lean ? clearOverwritten : null, loadOps);
			// Created outside any render pass (samplers are resolved inside world passes too).
			t.noise(noiseSize);
			for (PbrKind material : PbrKind.values()) {
				PbrTextures.defaultView(material);
			}
			t.white();
			t.overlayWhite();
			t.flatNormal();
			t.transparentBlack();
			Arrays.fill(snapshotStale, true);
			depthStale = true;
			RenderPhase.set(WorldRenderingPhase.NONE);
			runStage(ProgramArrayId.Begin);
			worldActive = true;
			EntityVertexFormats.setActive(true);
			preparedThisFrame = false;
			context = PipelinePrograms.Context.WORLD;
		} catch (RuntimeException e) {
			fail("starting the frame", e);
		}
	}

	/**
	 * State vanilla keeps outside the render state packs read: {@code fogDensity} (exponential underwater fog, -1 = linear
	 * fog, which selects {@code fogMode}) and {@code darknessLightFactor} (the Darkness effect's lightmap pulse).
	 */
	private static void captureFogAndDarkness() {
		Minecraft mc = Minecraft.getInstance();
		var camera = mc.gameRenderer.mainCamera();
		float density = -1.0f;
		if (camera.getFluidInCamera() == FogType.WATER) {
			density = 0.05f;
			if (camera.entity() instanceof LocalPlayer player) {
				density -= player.getWaterVision() * player.getWaterVision() * 0.03f;
			}
		}
		CapturedRenderingState.INSTANCE.setFogDensity(density);
		CapturedRenderingState.INSTANCE.setDarknessLightFactor(mc.gameRenderer.gameRenderState().lightmapRenderState.darknessEffectScale);
	}

	/**
	 * Before vanilla's world passes ({@code LevelRenderer.render}, once this frame's sections are known): the shadow map,
	 * then the prepare passes.
	 */
	public void renderShadows(LevelRenderer levelRenderer,
			FeatureRenderDispatcher.@Nullable PreparedFrame features) {
		if (!worldActive() || preparedThisFrame) {
			return;
		}
		preparedThisFrame = true;
		try {
			if (shadowFrame != null) {
				DevHooks.stageBegin("shaders:shadow");
				try {
					shadowFrame.draw(features, lean, quad, this);
					if (lodsThisFrame && packHasLodShadow) {
						drawLodShadows();
					}
					// The shadow maps' mip chains the pack asks for, once everything is drawn into them.
					shadow.generateMipmaps(blitMips);
				} finally {
					DevHooks.stageEnd();
				}
			}
			RenderPhase.set(WorldRenderingPhase.NONE);
			// shadowcomp stage: its computes and full-screen passes after the shadow map, on the shadowcolor targets.
			if (shadow != null) {
				runStage(ProgramArrayId.ShadowComposite);
			}
			runStage(ProgramArrayId.Prepare);
		} catch (RuntimeException e) {
			fail("rendering the shadow map", e);
		}
	}

	// ------------------------------------------------------------------ shadow map

	/** The running pack's shaders.properties directives, or null when no pack is active (mixins use it). */
	public @Nullable PackDirectives activeDirectives() {
		ProgramSet p = programs;
		return state == State.ACTIVE && p != null ? p.getPackDirectives() : null;
	}

	/** The cloud mode the running pack forces ({@code clouds=off/fast/fancy}), or null to keep the player's. */
	public @Nullable CloudStatus packCloudStatus() {
		ProgramSet p = programs;
		if (state != State.ACTIVE || p == null) {
			return null;
		}
		return switch (p.getPackDirectives().getCloudSetting()) {
			case OFF -> CloudStatus.OFF;
			case FAST -> CloudStatus.FAST;
			case FANCY -> CloudStatus.FANCY;
			case DEFAULT -> null;
		};
	}

	/** {@code dhClouds=off}, or {@code dhClouds} unset and {@code clouds=off}: no LOD clouds. */
	private boolean hideLodClouds() {
		var d = programs.getPackDirectives();
		var lod = d.getDHCloudSetting();
		return lod == CloudSetting.OFF
				|| lod == CloudSetting.DEFAULT
				&& d.getCloudSetting() == CloudSetting.OFF;
	}

	/** The LOD mod's opaque LODs into shadowtex0, and into shadowtex1 when a pass reads it (one call per map). */
	private void drawLodShadows() {
		var camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		RenderSystem.backupProjectionMatrix();
		try {
			for (boolean opaque : shadow.opaqueDepthLive() ? new boolean[]{false, true} : new boolean[]{false}) {
				lodShadowIntoOpaque = opaque;
				// Transparent LODs (water) into shadowtex0 only, when the pack's shadow map takes translucents at all.
				LodCompat.renderShadow(camera.x, camera.z, shadow.mapReach(),
						!opaque && shadow.renderTranslucent());
			}
		} finally {
			lodShadowIntoOpaque = false;
			RenderSystem.restoreProjectionMatrix();
		}
	}

	/** The shadow map's model-view (camera-relative positions in), for LOD shadow programs. */
	public Matrix4f shadowModelView() {
		return shadow == null ? new Matrix4f() : shadow.modelView();
	}

	/** The shadow map's projection (OpenGL convention), for LOD shadow programs. */
	public Matrix4f shadowProjection() {
		return shadow == null ? new Matrix4f() : shadow.projection();
	}

	/** Sections drawn into the shadow map this frame (benchmark timeline). */
	public int shadowCasterSections() {
		return shadowFrame == null ? 0 : shadowFrame.casterSections();
	}

	/** Frames that reused / redrew the cached shadow terrain since start (benchmark timeline). */
	public int shadowCacheHits() {
		return shadowFrame == null ? 0 : shadowFrame.cacheHits();
	}

	public int shadowCacheMisses() {
		return shadowFrame == null ? 0 : shadowFrame.cacheMisses();
	}

	private static final Matrix4fc IDENTITY = new Matrix4f();

	/** Shadow model-view x inverse camera view: rebases entity draws prepared for the player's camera onto the shadow map. */
	public Matrix4fc shadowFromView() {
		return shadowFrame == null ? IDENTITY : shadowFrame.shadowFromView();
	}

	@Override
	public void openShadowPass() {
		beforeShadowPass();
		shadowPassOpen = true;
		forgetPassBindings();
	}

	/**
	 * Before a shadow render pass opens: shadow programs that read colour targets read them inside the pass, so their
	 * pending clears are recorded first; programs that store into targets as images leave every mip chain stale.
	 */
	private void beforeShadowPass() {
		PackTargets t = targets;
		if (t == null) {
			return;
		}
		if (shadowReadsTargets) {
			t.flushClears();
		}
		if (TargetStorage.active()) {
			t.markAllWritten();
		}
	}

	/** Some shadow program declares a colour target sampler, or the pack binds targets as images (set at load). */
	private boolean shadowReadsTargets;
	/** Per frame: {@link EngineSwitches#LOADOP_CLEARS} and {@link EngineSwitches#BLIT_MIPS}. */
	private boolean loadOps;
	private boolean blitMips;
	/** Scratch for the clears a world pass's attachments took, per colortex. */
	private Vector4fc[] takenClears = new Vector4fc[0];

	@Override
	public void closeShadowPass() {
		shadowPassOpen = false;
	}

	@Override
	public boolean lodShadows() {
		return lodsThisFrame && packHasLodShadow;
	}

	/** The world projection actually bound this frame (vanilla multiplies view bobbing into it), read by packs as gbufferProjection. */
	public void onWorldProjection(Matrix4fc projection) {
		if (state == State.ACTIVE) {
			CapturedRenderingState.INSTANCE.setGbufferProjection(toGLProjection(new Matrix4f(projection)));
		}
	}

	/**
	 * {@code FrontendCommandEncoder.createRenderPass}: a vanilla world pass on the main colour target is re-targeted to the
	 * pack's gbuffer attachments. Returns the descriptor to use.
	 */
	public RenderPassDescriptor redirect(RenderPassDescriptor descriptor) {
		if (state == State.ACTIVE && shadow != null && !gbufferPassOpen && !shadowPassOpen && LodCompat.inShadowCall()
				&& LodCompat.isTerrainPass(descriptor)) {
			// LODs drawn on top of the shadow map, like entities: with the shadow programs (dh_shadow).
			beforeShadowPass();
			shadowPassOpen = true;
			forgetPassBindings();
			return shadow.overlayDescriptor("lod", lodShadowIntoOpaque);
		}
		if (!worldActive() || gbufferPassOpen || main == null || descriptor.colorAttachments().size() != 1) {
			return descriptor;
		}
		RenderPassDescriptor.Attachment<?> color = descriptor.colorAttachments().getFirst();
		// The LOD mod's terrain pass draws into the gbuffers too, against its own depth.
		boolean lod = lodsThisFrame && LodCompat.isLodPass(descriptor);
		if (color == null || !lod && color.textureView().texture() != main.getColorTexture()) {
			return descriptor;
		}
		try {
			// Snapshots are refreshed only when something wrote the target (or depth) since they were taken, and (reach
			// snapshots) only for what the programs that can draw in this kind of pass read.
			int kind = passKind(descriptor, lod);
			long wanted = reachSnapshots ? passReach(kind) : -1L;
			// Load-op clears: the attachments carry their pending frame-start clears; every other pending clear is
			// recorded now, before anything reads it, together with depthtex0's far clear and the snapshots of cleared
			// attachments (the clear colour: what the pass starts from).
			Vector4fc[] taken = loadOps ? takenClears : null;
			if (taken != null) {
				Arrays.fill(taken, null);
			}
			RenderPassDescriptor redirected = layout.redirect(descriptor, taken);
			if (taken != null) {
				List<LoadOpClears.Pending> clears = new ArrayList<>();
				targets.drainClears(clears);
				for (int target : layout.sampledAttachments()) {
					if ((wanted & 1L << target) != 0 && snapshotStale[target] && taken[target] != null) {
						snapshots[target] = targets.snapshotView(target);
						clears.add(new LoadOpClears.Pending(snapshots[target], taken[target]));
						snapshotStale[target] = false;
					}
				}
				if (!lod && layout.samplesDepth() && (wanted & WorldProgram.REACH_DEPTH) != 0 && descriptor.depthAttachment() != null && depthStale
						&& lean && depthCleared) {
					clears.add(depth.farClear(DepthTargets.ALL));
					depthStale = false;
				}
				LoadOpClears.flush(clears);
			}
			DevHooks.stageBegin("shaders:snapshots");
			try {
				for (int target : layout.sampledAttachments()) {
					if ((wanted & 1L << target) != 0 && snapshotStale[target]) {
						snapshots[target] = targets.snapshot(target);
						snapshotStale[target] = false;
					}
				}
				if (!lod && layout.samplesDepth() && (wanted & WorldProgram.REACH_DEPTH) != 0 && descriptor.depthAttachment() != null && depthStale) {
					if (lean && depthCleared) {
						// Nothing has drawn depth yet (the sky pass): depthtex0 is the far plane, no conversion needed.
						depth.clearToFar(DepthTargets.ALL);
					} else {
						depth.capture(DepthTargets.ALL, descriptor.depthAttachment().textureView(), quad);
					}
					depthStale = false;
				}
			} finally {
				DevHooks.stageEnd();
			}
			gbufferPassOpen = true;
			lodPassOpen = lod;
			openPassKind = kind;
			forgetPassBindings();
			// The pass writes its attachments (and, through images, possibly any target): their mip chains go stale.
			for (int target : layout.attachments()) {
				targets.markWritten(target);
			}
			if (TargetStorage.active()) {
				targets.markAllWritten();
			}
			return redirected;
		} catch (RuntimeException e) {
			fail("opening a world pass", e);
			return descriptor;
		}
	}

	/** {@code FrontendCommandEncoder.submitRenderPass}: whichever pass was open has closed. */
	public void onRenderPassClosed() {
		gbufferPassOpen = false;
		lodPassOpen = false;
		shadowPassOpen = false;
		forgetPassBindings();
		if (disableAfterPass) {
			disableAfterPass = false;
			stopAndTearDownLater();
		}
	}

	/** Records which vanilla pipeline a compiled pipeline belongs to ({@code RenderSystem.getCompiledPipelineNullable}). */
	public void onCompiledPipeline(RenderPipeline pipeline, @Nullable CompiledRenderPipeline compiled) {
		if (compiled != null && state != State.DISABLED) {
			compiledToVanilla.put(compiled, pipeline);
		}
		if (compiled != null && LodCompat.key(pipeline, false) != null && lodPipelines.add(pipeline)
				&& (state == State.ACTIVE || state == State.WARMING) && packHasLodPrograms && lodRenderingAtLoad) {
			// First sight of a LOD pipeline (the mod creates them on its first frame): build the pack's programs for it now.
			worldPrograms.prebuild(pipeline, PipelinePrograms.Context.WORLD);
			if (shadowPrograms != null) {
				shadowPrograms.prebuild(pipeline, PipelinePrograms.Context.WORLD);
			}
		}
	}

	/**
	 * A terrain pipeline created after start-up ({@link PipelinePrograms#addTerrain}): it draws only in the opaque or
	 * only in the translucent level pass, and the loaded pack's programs for it are built now.
	 */
	public void onTerrainPipeline(RenderPipeline pipeline, boolean translucent) {
		(translucent ? TRANSLUCENT_ONLY : OPAQUE_ONLY).add(pipeline);
		passReachGeneration = -1;
		if (state == State.ACTIVE || state == State.WARMING) {
			worldPrograms.prebuild(pipeline, PipelinePrograms.Context.WORLD);
			if (shadowPrograms != null) {
				shadowPrograms.prebuild(pipeline, PipelinePrograms.Context.WORLD);
			}
		}
	}

	/** The LOD mod's terrain pipelines seen so far (kept across pack loads, so later loads prebuild their programs). */
	private final Set<RenderPipeline> lodPipelines = Collections.newSetFromMap(new IdentityHashMap<>());

	/** {@link DevFlags#TRACE}: the cases logged so far. */
	private final Set<String> traced = new HashSet<>();

	/** {@code FrontendRenderPass.setPipeline} HEAD: the pipeline to bind instead, inside a gbuffer pass. */
	public CompiledRenderPipeline substitute(CompiledRenderPipeline compiled) {
		if (state == State.WARMING) {
			RenderPipeline vanilla = compiledToVanilla.get(compiled);
			if (vanilla != null) {
				seenWhileWarming.add(vanilla);
			}
		}
		CompiledRenderPipeline result = substituteUntraced(compiled);
		if (DevFlags.TRACE != null) {
			trace(compiled, result);
		}
		return result;
	}

	/** {@link DevFlags#TRACE}: logs this bind once when the vanilla pipeline's location matches. */
	private void trace(CompiledRenderPipeline compiled, CompiledRenderPipeline result) {
		RenderPipeline vanilla = compiledToVanilla.get(compiled);
		List<String> texts = DevFlags.TRACE;
		if (vanilla == null || texts == null || texts.stream().noneMatch(vanilla.getLocation().toString()::contains)) {
			return;
		}
		String pass = gbufferPassOpen ? (lodPassOpen ? "lod" : "gbuffer") : shadowPassOpen ? "shadow" : "none (vanilla)";
		WorldPrograms programs = shadowPassOpen ? shadowPrograms : worldPrograms;
		WorldProgram program = result == compiled || programs == null ? null : programs.program(result);
		String line = vanilla.getLocation() + " pass=" + pass + " feature=" + featureDraw + " flags=" + featureFlags + " phase=" + RenderPhase.current()
				+ " -> " + (result == compiled ? "unchanged" : program != null ? program.name() : "widened vanilla / skip");
		if (traced.add(line)) {
			AetheriumShaders.logger.info("Aetherium Shaders trace: {}", line);
		}
	}

	private CompiledRenderPipeline substituteUntraced(CompiledRenderPipeline compiled) {
		substituted = null;
		if (!gbufferPassOpen && !shadowPassOpen || state != State.ACTIVE) {
			return compiled;
		}
		if (settledBindings) {
			return substituteRouted(compiled);
		}
		RenderPipeline vanilla = compiledToVanilla.get(compiled);
		if (vanilla == null) {
			return compiled; // already one of ours, or unknown (fails vanilla's attachment check, loudly)
		}
		try {
			if (skipAllRendering && gbufferPassOpen) {
				// skipAllRendering: the pack draws the frame from its full-screen passes alone.
				return worldPrograms.skip(vanilla);
			}
			PipelinePrograms.Context c = blockEntityDraw && !context.hand() ? PipelinePrograms.Context.BLOCK_ENTITY : context;
			if (shadowPassOpen && featureDraw && shadowSkips()) {
				// shadowEntities / shadowBlockEntities = false: the draw goes through a pipeline that draws nothing.
				return shadowPrograms.skip(vanilla);
			}
			if (gbufferPassOpen && (featureFlags & DrawIds.SHADOW_ONLY) != 0) {
				// An entity extracted for the shadow map alone (ShadowCasters).
				return worldPrograms.skip(vanilla);
			}
			return (shadowPassOpen ? shadowPrograms : worldPrograms).resolve(vanilla, c);
		} catch (RuntimeException e) {
			fail("building the program for " + vanilla.getLocation(), e);
			return compiled;
		}
	}

	/**
	 * {@link #substituteUntraced} through the program table's route for {@code compiled}: one identity lookup, and the
	 * program behind the result kept for the bind that follows.
	 */
	private CompiledRenderPipeline substituteRouted(CompiledRenderPipeline compiled) {
		WorldPrograms programs = shadowPassOpen ? shadowPrograms : worldPrograms;
		WorldPrograms.Route route = programs.route(compiled, compiledToVanilla);
		if (route == null) {
			return compiled; // already one of ours, or unknown (fails vanilla's attachment check, loudly)
		}
		RenderPipeline vanilla = route.vanilla();
		try {
			if (skipAllRendering && gbufferPassOpen) {
				return worldPrograms.skip(vanilla);
			}
			PipelinePrograms.Context c = blockEntityDraw && !context.hand() ? PipelinePrograms.Context.BLOCK_ENTITY : context;
			if (shadowPassOpen && featureDraw && shadowSkips()) {
				return shadowPrograms.skip(vanilla);
			}
			if (gbufferPassOpen && (featureFlags & DrawIds.SHADOW_ONLY) != 0) {
				return worldPrograms.skip(vanilla);
			}
			CompiledRenderPipeline result = programs.resolve(route, c);
			substituted = result;
			substitutedProgram = WorldPrograms.program(route, c);
			return result;
		} catch (RuntimeException e) {
			fail("building the program for " + vanilla.getLocation(), e);
			return compiled;
		}
	}

	/** What {@link #substituteRouted} last returned (null after anything else), and the pack program behind it. */
	private @Nullable CompiledRenderPipeline substituted;
	private @Nullable WorldProgram substitutedProgram;

	/** Instance field: INSTANCE is built before later static initialisers run. */
	private final int addressModes = AddressMode.values().length;
	/** Nearest-filtered albedo samplers, indexed by (address mode U, address mode V). */
	private final @Nullable GpuSampler[] albedoSamplers = new GpuSampler[addressModes * addressModes];
	/** Albedo samplers replaced after an anisotropy change; still possibly in flight, closed at teardown. */
	private final List<GpuSampler> retiredSamplers = new ArrayList<>();

	/**
	 * The sampler for the albedo ({@code Sampler0}) inside a gbuffer pass. Vanilla 26.3 binds the block atlas with a
	 * linear sampler and filters in its own shader; pack programs sample it with plain {@code texture()} and expect
	 * nearest magnification with linearly blended mip levels. The address mode stays vanilla's: scrolling textures
	 * (glint, beacon beam, energy swirl, leash) need REPEAT. Outside gbuffer passes vanilla's sampler is kept.
	 */
	public GpuSampler albedoSampler(GpuSampler vanilla) {
		if (!gbufferPassOpen && !shadowPassOpen || state != State.ACTIVE) {
			return vanilla;
		}
		var u = vanilla.getAddressModeU();
		var v = vanilla.getAddressModeV();
		// Vanilla's anisotropic filtering setting stays on unless the pack declares it breaks (breaksAnisotropy).
		int anisotropy = WorldRenderingSettings.INSTANCE.breaksAnisotropy() ? 1
				: Math.max(1, vanilla.getMaxAnisotropy());
		int slot = u.ordinal() * addressModes + v.ordinal();
		GpuSampler sampler = albedoSamplers[slot];
		if (sampler == null || sampler.getMaxAnisotropy() != anisotropy) {
			if (sampler != null) {
				retiredSamplers.add(sampler);
			}
			sampler = RenderSystem.getDevice().createSampler(u, v, FilterMode.NEAREST, FilterMode.NEAREST, anisotropy, OptionalDouble.empty());
			albedoSamplers[slot] = sampler;
		}
		return sampler;
	}

	/** {@code FrontendRenderPass.setPipeline} TAIL: a pack program was bound; give it its uniforms and samplers. */
	public void afterSetPipeline(RenderPass pass, CompiledRenderPipeline bound) {
		if (!gbufferPassOpen && !shadowPassOpen || state != State.ACTIVE) {
			return;
		}
		try {
			bindProgramInputs(pass, bound);
		} catch (RuntimeException e) {
			fail("binding a program's uniforms and samplers", e);
		}
	}

	private void bindProgramInputs(RenderPass pass, CompiledRenderPipeline bound) {
		WorldProgram program = settledBindings && bound == substituted ? substitutedProgram
				: (shadowPassOpen ? shadowPrograms : worldPrograms).program(bound);
		if (gbufferPassOpen) {
			// What this pass may write, for the snapshot bookkeeping (a widened vanilla pipeline writes the first attachment).
			if (program == null) {
				snapshotStale[layout.attachments()[0]] = true;
				depthStale = true;
				depthCleared = false;
			} else {
				for (int target : program.drawBuffers()) {
					if (target >= 0 && target < snapshotStale.length) {
						snapshotStale[target] = true;
					}
				}
				// LOD programs write the LOD mod's depth, not vanilla's.
				depthStale |= program.writesDepth() && !lodPassOpen;
				depthCleared &= !program.writesDepth() || lodPassOpen;
				if (reachSnapshots) {
					checkPassReach(program);
				}
			}
		}
		passProgram = program;
		if (program == null) {
			return;
		}
		RenderPhase.bind(program.phase());
		DrawState.setBlend(program.blend());
		if (settledBindings) {
			bindSettled(pass, program);
			return;
		}
		if (program.uniforms().hasBlock()) {
			program.updateUniforms(customUniforms, false);
			pass.setUniform(UniformBlock.BLOCK_NAME, program.upload(arena, false, true));
		}
		boolean water = program.usesWatershadow();
		SamplerTable.Scope scope = shadowPassOpen ? SamplerTable.Scope.SHADOW : SamplerTable.Scope.GBUFFER;
		for (String sampler : program.samplers()) {
			PbrKind material = PbrKind.ofSampler(sampler);
			if (material != null && !samplerTable.customised(sampler, TextureStage.GBUFFERS_AND_SHADOW)) {
				bindMaterial(pass, material);
				continue;
			}
			SamplerTable.Bound resolved = samplerTable.resolve(sampler, TextureStage.GBUFFERS_AND_SHADOW, scope, water, shadowPassOpen ? null : snapshots,
					null);
			pass.setUniform(sampler, resolved.view(), resolved.sampler());
		}
	}

	// ------------------------------------------------------------------ settled bindings (shaders.settled_bindings)

	/** {@link EngineSwitches#SETTLED_BINDINGS} for this frame. */
	private boolean settledBindings;
	/** Counts render passes opened and closed: a program's settled samplers are resolved again in each new pass. */
	private int passSerial;
	/** The open pass's uniform block binding, for skipping an unchanged one. */
	private @Nullable GpuBufferSlice passBlock;
	/** Binding-table slot per sampler name settled programs bind (names only the engine sets). */
	private final Map<String, Integer> slotOf = new HashMap<>();
	/** Per slot: the view and sampler the open render pass holds under that name (null: not set in this pass). */
	private GpuTextureView[] passViews = new GpuTextureView[16];
	private GpuSampler[] passFilters = new GpuSampler[16];
	private final SamplerTable.Slot resolvedSlot = new SamplerTable.Slot();

	/**
	 * A program's block and samplers with settled state: the block slice is reused while its bytes are unchanged; its
	 * samplers are resolved on its first bind in the render pass, and each is set only when the pass holds something
	 * else under that name (a render pass keeps every binding across pipeline changes). Names vanilla may bind too
	 * ({@code Sampler<n>}) are set on every bind; the material maps follow the draw's albedo ({@link #onAlbedo}).
	 */
	private void bindSettled(RenderPass pass, WorldProgram program) {
		if (program.uniforms().hasBlock()) {
			boolean wrote = program.updateUniforms(customUniforms, true);
			GpuBufferSlice block = program.upload(arena, true, wrote);
			if (passBlock != block) {
				pass.setUniform(UniformBlock.BLOCK_NAME, block);
				passBlock = block;
			}
		}
		WorldProgram.Settled settled = settled(program);
		if (settled.pass != passSerial) {
			GpuTextureView[] read = shadowPassOpen ? null : snapshots;
			SamplerTable.Slot slot = resolvedSlot;
			for (int i = 0; i < settled.plans.length; i++) {
				settled.plans[i].resolve(read, slot);
				settled.views[i] = slot.view;
				settled.samplers[i] = slot.sampler;
			}
			settled.pass = passSerial;
		}
		for (int i = 0; i < settled.plans.length; i++) {
			int k = settled.slots[i];
			GpuTextureView view = settled.views[i];
			GpuSampler filter = settled.samplers[i];
			if (k < 0) {
				pass.setUniform(settled.names[i], view, filter);
			} else if (passViews[k] != view || passFilters[k] != filter) {
				pass.setUniform(settled.names[i], view, filter);
				passViews[k] = view;
				passFilters[k] = filter;
			}
		}
		for (int i = 0; i < settled.materials.length; i++) {
			bindMaterialSettled(pass, settled.materials[i], settled.materialSlots[i]);
		}
	}

	/** The program's settled sampler state, made on its first bind (per pack: the sampler table does not change). */
	private WorldProgram.Settled settled(WorldProgram program) {
		WorldProgram.Settled settled = program.settled;
		if (settled != null && settled.table == samplerTable) {
			return settled;
		}
		boolean water = program.usesWatershadow();
		SamplerTable.Scope scope = program.isShadow() ? SamplerTable.Scope.SHADOW : SamplerTable.Scope.GBUFFER;
		List<SamplerTable.Plan> plans = new ArrayList<>();
		List<String> names = new ArrayList<>();
		List<Integer> slots = new ArrayList<>();
		List<PbrKind> materials = new ArrayList<>();
		for (String sampler : program.samplers()) {
			PbrKind material = PbrKind.ofSampler(sampler);
			if (material != null && !samplerTable.customised(sampler, TextureStage.GBUFFERS_AND_SHADOW)) {
				materials.add(material);
				continue;
			}
			plans.add(samplerTable.plan(sampler, TextureStage.GBUFFERS_AND_SHADOW, scope, water, null));
			names.add(sampler);
			// Names like vanilla's own (Sampler0, GlintSampler ...) may be set by vanilla draws in the same pass: never assumed
			// to hold ours.
			slots.add(sampler.startsWith("Sampler") || sampler.endsWith("Sampler") ? -1 : slot(sampler));
		}
		int[] materialSlots = new int[materials.size()];
		for (int i = 0; i < materialSlots.length; i++) {
			materialSlots[i] = slot(materials.get(i).sampler());
		}
		settled = new WorldProgram.Settled(samplerTable, plans.toArray(SamplerTable.Plan[]::new), names.toArray(String[]::new),
				slots.stream().mapToInt(Integer::intValue).toArray(), materials.toArray(PbrKind[]::new), materialSlots);
		program.settled = settled;
		return settled;
	}

	/** The binding-table slot of {@code name}, made on first use. */
	private int slot(String name) {
		Integer slot = slotOf.get(name);
		if (slot == null) {
			slot = slotOf.size();
			slotOf.put(name, slot);
			if (slot >= passViews.length) {
				passViews = Arrays.copyOf(passViews, passViews.length * 2);
				passFilters = Arrays.copyOf(passFilters, passFilters.length * 2);
			}
		}
		return slot;
	}

	/** {@link #bindMaterial} through the binding table: set only when the pass holds another map under the name. */
	private void bindMaterialSettled(RenderPass pass, PbrKind material, int slot) {
		GpuTextureView view = PbrTextures.view(material, passAlbedo);
		GpuSampler filter = PbrTextures.sampler(material, passAlbedoSampler);
		if (passViews[slot] != view || passFilters[slot] != filter) {
			pass.setUniform(material.sampler(), view, filter);
			passViews[slot] = view;
			passFilters[slot] = filter;
		}
	}

	private void forgetPassBindings() {
		passSerial++;
		passBlock = null;
		Arrays.fill(passViews, null);
		Arrays.fill(passFilters, null);
		substituted = null;
		substitutedProgram = null;
		passProgram = null;
		passAlbedo = null;
		passAlbedoSampler = null;
	}

	/** The pack program bound in the open pass (null: a widened vanilla pipeline, or none yet). */
	private @Nullable WorldProgram passProgram;
	/** The colour texture the open pass's draws bind as their albedo, and its sampler (for their material maps). */
	private @Nullable GpuTexture passAlbedo;
	private @Nullable GpuSampler passAlbedoSampler;

	/**
	 * {@code FrontendRenderPass.setUniform("Sampler0")}: a draw binds its albedo. A pack program reading {@code normals}
	 * or {@code specular} gets that texture's material maps.
	 */
	public void onAlbedo(RenderPass pass, GpuTextureView albedo, GpuSampler sampler) {
		if (!gbufferPassOpen && !shadowPassOpen || state != State.ACTIVE) {
			return;
		}
		passAlbedo = albedo.texture();
		passAlbedoSampler = sampler;
		WorldProgram program = passProgram;
		if (program == null) {
			return;
		}
		WorldProgram.Settled settled = program.settled;
		if (settledBindings && settled != null && settled.table == samplerTable) {
			for (int i = 0; i < settled.materials.length; i++) {
				bindMaterialSettled(pass, settled.materials[i], settled.materialSlots[i]);
			}
			return;
		}
		for (PbrKind material : PbrKind.values()) {
			if (program.samplers().contains(material.sampler()) && !samplerTable.customised(material.sampler(), TextureStage.GBUFFERS_AND_SHADOW)) {
				bindMaterial(pass, material);
			}
		}
	}

	/** Binds the {@code material} map of the pass's current albedo (the default until it is loaded). */
	private void bindMaterial(RenderPass pass, PbrKind material) {
		pass.setUniform(material.sampler(), PbrTextures.view(material, passAlbedo), PbrTextures.sampler(material, passAlbedoSampler));
	}

	// ------------------------------------------------------------------ snapshot sets (shaders.reach_snapshots)

	/** Kinds of world pass, by what can draw in them: each has its own snapshot set. */
	private static final int PASS_SKY = 0, PASS_OPAQUE = 1, PASS_TRANSLUCENT = 2, PASS_HAND = 3, PASS_OTHER = 4, PASS_KINDS = 5;
	/** {@link EngineSwitches#REACH_SNAPSHOTS} for this frame. */
	private boolean reachSnapshots;
	/** Per pass kind: the colour targets (bit N) and depthtex0 ({@link WorldProgram#REACH_DEPTH}) its programs can read. */
	private final long[] passReach = new long[PASS_KINDS];
	/** Per pass kind: reads a bind found outside the kind's set (added to it from then on, and logged once). */
	private final long[] passEscapes = new long[PASS_KINDS];
	/** {@link WorldPrograms#generation} {@link #passReach} was computed for (-1: not yet). */
	private int passReachGeneration = -1;
	/** The kind of the open world pass. */
	private int openPassKind = PASS_OTHER;
	/** The label of the translucent half of the level ({@code LevelRendererMixin}). */
	private static final String TRANSLUCENT_PASS = "Main (translucent, shaders)";

	/** The kind of a world pass opening: by the program context (hand) or vanilla's pass label; anything else is OTHER. */
	private int passKind(RenderPassDescriptor descriptor, boolean lod) {
		if (lod) {
			return PASS_OTHER;
		}
		if (context.hand()) {
			return PASS_HAND;
		}
		return switch (descriptor.label().get()) {
			case "Sky", "Horizon" -> PASS_SKY;
			// Vanilla's level pass, which draws only the opaque world while a pack is active (the rest is deferred).
			case "Main" -> PASS_OPAQUE;
			case TRANSLUCENT_PASS -> PASS_TRANSLUCENT;
			default -> PASS_OTHER;
		};
	}

	/** Pipelines only vanilla's sky draws with (the sky pass and the horizon). */
	private static final Set<RenderPipeline> SKY_PIPELINES = pipelines(RenderPipelines.SKY, RenderPipelines.STARS, RenderPipelines.SUNRISE_SUNSET,
			RenderPipelines.CELESTIAL, RenderPipelines.END_SKY);
	/** Pipelines only the translucent half of the level draws with: translucent terrain, clouds, weather, the world border. */
	private static final Set<RenderPipeline> TRANSLUCENT_ONLY = pipelines(RenderPipelines.TRANSLUCENT_TERRAIN,
			RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW, RenderPipelines.CLOUDS, RenderPipelines.FLAT_CLOUDS, RenderPipelines.WEATHER,
			RenderPipelines.WORLD_BORDER);
	/** Pipelines only the opaque level pass draws with: the solid and cutout terrain layers. */
	private static final Set<RenderPipeline> OPAQUE_ONLY = pipelines(RenderPipelines.SOLID_TERRAIN, RenderPipelines.SOLID_TERRAIN_MULTIDRAW,
			RenderPipelines.CUTOUT_TERRAIN, RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW);

	private static Set<RenderPipeline> pipelines(RenderPipeline... pipelines) {
		Set<RenderPipeline> set = Collections.newSetFromMap(new IdentityHashMap<>());
		for (RenderPipeline pipeline : pipelines) {
			if (pipeline != null) {
				set.add(pipeline);
			}
		}
		return set;
	}

	/**
	 * What the programs that can draw in a {@code kind} pass read among the gbuffer attachments: per vanilla pipeline
	 * and context that kind of pass can bind, the reach of its built program (or its declared samplers while it builds),
	 * plus whatever a bind has found outside the set. Recomputed when a program build finishes.
	 */
	private long passReach(int kind) {
		int generation = worldPrograms.generation();
		if (generation != passReachGeneration) {
			passReachGeneration = generation;
			Arrays.fill(passReach, 0);
			for (RenderPipeline pipeline : PipelinePrograms.mainPipelines()) {
				long world = worldPrograms.reach(pipeline, PipelinePrograms.Context.WORLD)
						| worldPrograms.reach(pipeline, PipelinePrograms.Context.BLOCK_ENTITY);
				if (SKY_PIPELINES.contains(pipeline)) {
					passReach[PASS_SKY] |= world;
				} else {
					if (!TRANSLUCENT_ONLY.contains(pipeline)) {
						passReach[PASS_OPAQUE] |= world;
					}
					if (!OPAQUE_ONLY.contains(pipeline)) {
						passReach[PASS_TRANSLUCENT] |= world;
					}
				}
				passReach[PASS_OTHER] |= world;
				passReach[PASS_HAND] |= worldPrograms.reach(pipeline, PipelinePrograms.Context.HAND)
						| worldPrograms.reach(pipeline, PipelinePrograms.Context.HAND_TRANSLUCENT);
			}
			for (RenderPipeline pipeline : lodPipelines) {
				passReach[PASS_OTHER] |= worldPrograms.reach(pipeline, PipelinePrograms.Context.WORLD);
			}
			for (int k = 0; k < PASS_KINDS; k++) {
				passReach[k] |= passEscapes[k];
			}
		}
		return passReach[kind];
	}

	/**
	 * A program bound in a world pass reads a gbuffer attachment (or depthtex0) the pass's kind did not snapshot: it
	 * reads the last snapshot taken of it. The kind takes it from the next pass on, and the case is logged once.
	 */
	private void checkPassReach(WorldProgram program) {
		long missing = program.reach() & ~passReach(openPassKind);
		if (missing == 0) {
			return;
		}
		// Only reads a world pass serves from a copy: its sampled attachments, and depthtex0 outside LOD passes.
		long served = 0;
		for (int target : layout.sampledAttachments()) {
			served |= 1L << target;
		}
		if (layout.samplesDepth() && !lodPassOpen) {
			served |= WorldProgram.REACH_DEPTH;
		}
		missing &= served;
		if (missing != 0 && (passEscapes[openPassKind] & missing) != missing) {
			passEscapes[openPassKind] |= missing;
			passReach[openPassKind] |= missing;
			AetheriumShaders.logger.info("[{}] reads gbuffer targets {} in a world pass of kind {} that did not snapshot them; taken from the next such pass on",
					program.name(), Long.toBinaryString(missing), openPassKind);
		}
	}

	/** After vanilla's opaque world pass closed, before translucents: depthtex1 and the deferred passes. */
	public void afterOpaque(RenderTarget mainTarget) {
		if (!worldActive()) {
			return;
		}
		try {
			// The solid hand belongs to the opaque world: deferred-lit packs light it in their deferred passes and find it
			// by comparing depthtex2 (no hand) with depthtex0. depthtex2 is taken just before it.
			boolean hand = EngineSwitches.enabled(EngineSwitches.EARLY_HAND) && drawSolidHand(mainTarget);
			depth.capture(DepthTargets.NO_TRANSLUCENTS, mainTarget.getDepthTextureView(), quad);
			// depthtex0 is the live depth: the deferred passes must see this frame's opaque depth, not last frame's
			// (packs compare depthtex0 with depthtex1/2, e.g. Sildur's hand-shadow test). Lean frames alias.
			if (lean) {
				if (!hand) {
					depth.alias(DepthTargets.NO_HAND, DepthTargets.NO_TRANSLUCENTS);
				}
				depth.alias(DepthTargets.ALL, DepthTargets.NO_TRANSLUCENTS);
			} else {
				if (!hand) {
					depth.copy(DepthTargets.NO_TRANSLUCENTS, DepthTargets.NO_HAND);
				}
				depth.copy(DepthTargets.NO_TRANSLUCENTS, DepthTargets.ALL);
			}
			depthStale = false;
			GpuTextureView lodDepth = lodsThisFrame ? LodCompat.depthView() : null;
			if (lodDepth != null) {
				// The opaque LODs were drawn before vanilla's terrain; transparent ones are deferred until after this.
				depth.capture(DepthTargets.LOD_NO_TRANSLUCENTS, lodDepth, quad);
				depth.alias(DepthTargets.LOD_ALL, DepthTargets.LOD_NO_TRANSLUCENTS);
			}
			RenderPhase.set(WorldRenderingPhase.NONE);
			DevHooks.stageBegin("shaders:deferred");
			try {
				runStage(ProgramArrayId.Deferred);
			} finally {
				DevHooks.stageEnd();
			}
			if (lodDepth != null) {
				// Transparent LODs (dh_water) after the deferred passes, before vanilla's translucents; then dhDepthTex0.
				LodCompat.renderDeferredTransparent();
				depth.capture(DepthTargets.LOD_ALL, lodDepth, quad);
			}
		} catch (RuntimeException e) {
			fail("running the deferred passes", e);
		}
	}

	/** Inside {@link #drawSolidHand}: vanilla's hand pass draws only its solid features. */
	private boolean drawingSolidHand;
	/** A block entity's render type is drawing: its pipeline gets the pack's block program. */
	private boolean blockEntityDraw;

	/** A prepared feature draw (entity, block entity, item ...) is binding its pipeline. */
	private boolean featureDraw;
	/** Its {@link DrawIds} flags. */
	private int featureFlags;

	/** {@code PreparedRenderType.draw}: a feature draw starts (with its draw flags) or ends. */
	public void setFeatureDraw(boolean drawing, int flags) {
		featureDraw = drawing;
		featureFlags = drawing ? flags : 0;
		blockEntityDraw = drawing && (flags & DrawIds.BLOCK_ENTITY) != 0;
	}

	/** Whether the shadow pass leaves this feature draw out ({@code shadowEntities}, {@code shadowBlockEntities}, {@code shadowPlayer}). */
	private boolean shadowSkips() {
		ShadowPass s = shadow;
		if (s == null) {
			return true;
		}
		if (blockEntityDraw) {
			return !s.renderBlockEntities()
					&& !(s.renderLightBlockEntities() && (featureFlags & DrawIds.LIGHT) != 0);
		}
		return !s.renderEntities() && !(s.renderPlayer() && (featureFlags & DrawIds.PLAYER) != 0);
	}
	/** The solid hand was drawn before the deferred passes this frame: vanilla's hand pass draws the rest. */
	private boolean solidHandDrawn;
	private @Nullable RenderBuffers handBuffers;
	private @Nullable FeatureRenderDispatcher handDispatcher;

	public boolean drawingSolidHand() {
		return drawingSolidHand;
	}

	public boolean solidHandDrawn() {
		return solidHandDrawn && worldActive();
	}

	/** A feature dispatcher with its own vertex staging for the early solid hand (vanilla's is busy with the level's frame). */
	public FeatureRenderDispatcher handDispatcher() {
		if (handDispatcher == null) {
			Minecraft mc = Minecraft.getInstance();
			handBuffers = new RenderBuffers(1);
			handDispatcher = new FeatureRenderDispatcher(handBuffers, mc.getModelManager(), mc.getAtlasManager(),
					mc.font, mc.gameRenderer.gameRenderState());
		}
		return handDispatcher;
	}

	/**
	 * After the opaque world, before the deferred passes: depthtex2 (the world without the hand), then vanilla's own
	 * first-person hand pass with only its solid features, under the hand projection and hand programs. Vanilla draws
	 * the hand outside the level, where the model-view stack is the identity; inside the level it holds the camera's
	 * view rotation (LevelRenderer.render), so it is reset for the call, else the hand is rotated by the view twice.
	 */
	private boolean drawSolidHand(RenderTarget mainTarget) {
		Minecraft mc = Minecraft.getInstance();
		var access = (GameRendererHandAccessor) mc.gameRenderer;
		var state = mc.gameRenderer.gameRenderState();
		CameraRenderState camera = state.levelRenderState.cameraRenderState;
		if (!lean || usage == null || usage.samplesDepth(DepthTargets.NO_HAND)) {
			depth.capture(DepthTargets.NO_HAND, mainTarget.getDepthTextureView(), quad);
		}
		var projection = access.aetherium$hudProjection();
		projection.setupPerspective(0.05F, camera.depthFar, camera.hudFov, state.windowRenderState.width, state.windowRenderState.height);
		PipelinePrograms.Context previous = context;
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix().identity();
		RenderSystem.backupProjectionMatrix();
		try {
			RenderSystem.setProjectionMatrix(access.aetherium$hud3dProjectionMatrixBuffer().getBuffer(handProjection(projection.getMatrix(new Matrix4f()))),
					ProjectionType.PERSPECTIVE);
			context = PipelinePrograms.Context.HAND;
			drawingSolidHand = true;
			access.aetherium$renderItemInHand(camera, state.levelRenderState.playerRenderState, mainTarget.getDepthTextureView());
			solidHandDrawn = true;
		} finally {
			drawingSolidHand = false;
			context = previous;
			RenderSystem.restoreProjectionMatrix();
			modelView.popMatrix();
		}
		return true;
	}

	/** Before vanilla draws the hand: depthtex2 = everything but the hand; hand programs (translucent ones after an early solid hand) from here. */
	public void beforeHand(GpuTextureView depthView) {
		if (!worldActive()) {
			return;
		}
		try {
			// When the solid hand was drawn early, depthtex2 was taken before it and stays.
			if (!solidHandDrawn && (!lean || usage == null || usage.samplesDepth(DepthTargets.NO_HAND))) {
				depth.capture(DepthTargets.NO_HAND, depthView, quad);
			}
			// centerDepthSmooth: the world (translucents included) at the screen centre, read as the hand starts.
			if (centerDepth != null) {
				centerDepth.update(depthView, true, arena, quad);
			}
		} catch (RuntimeException e) {
			fail("capturing depthtex2", e);
		}
		// After the early solid hand, vanilla's pass draws only the translucent rest: the hand_water programs.
		context = solidHandDrawn ? PipelinePrograms.Context.HAND_TRANSLUCENT : PipelinePrograms.Context.HAND;
	}

	/** Vanilla's hand projection with {@link #HAND_DEPTH} applied. */
	public Matrix4f handProjection(Matrix4f vanilla) {
		// In OpenGL clip space z' = 0.125 z; vanilla's reversed zero-to-one z_r = (w - z_gl) / 2 turns that into
		// z_r' = 0.4375 w + 0.125 z_r: row 2 := 0.4375 row 3 + 0.125 row 2.
		Matrix4f m = new Matrix4f(vanilla);
		float a = (1.0f - HAND_DEPTH) * 0.5f;
		m.m02(a * vanilla.m03() + HAND_DEPTH * vanilla.m02());
		m.m12(a * vanilla.m13() + HAND_DEPTH * vanilla.m12());
		m.m22(a * vanilla.m23() + HAND_DEPTH * vanilla.m22());
		m.m32(a * vanilla.m33() + HAND_DEPTH * vanilla.m32());
		return m;
	}

	/** After the hand: depthtex0, the composite passes and final into vanilla's main target. */
	public void afterHand(RenderTarget mainTarget) {
		if (!worldActive()) {
			return;
		}
		worldActive = false;
		EntityVertexFormats.setActive(false);
		context = PipelinePrograms.Context.WORLD;
		DevHooks.stageBegin("shaders:composite");
		try {
			depth.capture(DepthTargets.ALL, mainTarget.getDepthTextureView(), quad);
			RenderPhase.set(WorldRenderingPhase.NONE);
			if (DevFlags.DEBUG_VIEW && debug.replaceComposite(mainTarget, targets, depth, shadow, blit, quad)) {
				return;
			}
			if (!DevFlags.DEBUG_VIEW || !debug.finalOnly()) {
				runStage(ProgramArrayId.Composite);
			}
			if (DevFlags.DEBUG_VIEW && debug.endComposite()) {
				return;
			}
			runComputes(plan.finalComputes, plan.writtenBeforeFinal);
			CompositePass finalPass = plan.finalPass;
			if (finalPass != null) {
				for (int target : finalPass.mipmappedBuffers()) {
					targets.generateMipmaps(target, blit, quad, blitMips);
				}
				draw(finalPass, mainTarget, plan.writtenBeforeFinal, false);
			} else {
				blit.blit(targets.readView(0), mainTarget.getColorTextureView(), mainTarget.getColorTexture().getFormat(), quad);
			}
		} catch (RuntimeException e) {
			fail("running the composite passes", e);
		} finally {
			// The side each target was left on becomes its base for the next frame (debug views included).
			if (targets != null) {
				targets.endFrame();
			}
			if (shadow != null) {
				shadow.endFrame();
			}
			DevHooks.stageEnd();
		}
	}

	/** Compute programs from {@code sources} (null entries skipped); empty when the device has no storage features. */
	private ComputePass[] buildComputes(ComputeSource @Nullable [] sources, TextureStage stage, PackLoad load) {
		if (sources == null || sources.length == 0 || !StorageFeatures.compute()) {
			return new ComputePass[0];
		}
		var device = Objects.requireNonNull(VulkanAccess.device());
		List<ComputePass> out = new ArrayList<>();
		for (ComputeSource source : sources) {
			if (source != null && source.isValid()) {
				long storageLayout = storage == null ? 0 : storage.setLayout();
				ComputePass pass;
				if (load.hasCompute(source)) {
					ComputePass.Prepared prepared = load.takeCompute(source);
					pass = prepared == null ? null : ComputePass.create(source, stage, prepared, customUniforms, device);
				} else {
					pass = ComputePass.create(source, stage, customUniforms, device, storageLayout);
				}
				if (pass != null) {
					out.add(pass);
				}
			}
		}
		return out.toArray(ComputePass[]::new);
	}

	/**
	 * Dispatches {@code list} into vanilla's frame between render passes ({@link #recordAndExecute}): everything earlier
	 * in the frame is ordered before it, each dispatch before the next, and all of it before what follows.
	 */
	private void runComputes(ComputePass @Nullable [] list, @Nullable Set<Integer> written) {
		RenderTarget screen = main != null ? main : Minecraft.getInstance().gameRenderer.mainRenderTarget();
		runComputes(list, screen.width, screen.height, written);
	}

	/**
	 * {@code width} x {@code height}: what {@code workGroupsRender} scales (the screen, or the shadow map). {@code written}:
	 * targets earlier steps of the stage wrote (they switch off custom textures bound under their names).
	 */
	private void runComputes(ComputePass @Nullable [] list, int width, int height, @Nullable Set<Integer> written) {
		if (list == null || list.length == 0) {
			return;
		}
		PackTargets t = targets;
		if (t != null) {
			// Compute programs read and store into targets through images: their pending clears go first, and every mip
			// chain may be stale afterwards.
			t.flushClears();
			t.markAllWritten();
		}
		long set = storage == null ? 0 : storage.descriptorSet();
		// What a dispatch may read after earlier work: shader reads and writes, and an indirect dispatch's arguments.
		int computeStages = VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
		int computeAccess = VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
		recordAndExecute(cb -> {
			// Everything earlier in the frame before the group; each dispatch before the next; the group before what follows.
			VulkanCompute.barrier(cb, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_ACCESS_MEMORY_WRITE_BIT, computeStages, computeAccess);
			for (int i = 0; i < list.length; i++) {
				ComputePass pass = list[i];
				if (i > 0) {
					VulkanCompute.barrier(cb, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT, computeStages, computeAccess);
				}
				pass.dispatch(cb, customUniforms, name -> computeTexture(name, pass.stage(), written), set, storage, width, height);
			}
			VulkanCompute.barrier(cb, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
					VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT);
		});
	}

	/** What a compute program's sampler reads: the same textures full-screen passes get, custom textures included. */
	private SamplerTable.Bound computeTexture(String name, TextureStage stage, @Nullable Set<Integer> written) {
		return samplerTable.resolve(name, stage, SamplerTable.Scope.COMPUTE, false, null, written);
	}

	/** {@code shaders.inline_compute}: Aetherium commands go into the frame's current command buffer (off: a buffer of their own). */
	private static final String INLINE_COMPUTE = "shaders.inline_compute";

	/**
	 * Records Aetherium Vulkan commands into vanilla's frame, ordered between the commands before and after. Not inside a
	 * render pass. Inline: into the frame's current command buffer. Otherwise into a command buffer of their own,
	 * spliced into the frame (which ends vanilla's current one).
	 */
	private static void recordAndExecute(Consumer<VkCommandBuffer> work) {
		var encoder = VulkanAccess.encoder();
		if (encoder == null) {
			return;
		}
		if (EngineSwitches.enabled(INLINE_COMPUTE)) {
			work.accept(((VulkanCommandEncoderAccessor) encoder).aetherium$commandBuffer());
			return;
		}
		VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
		boolean recorded = false;
		try {
			work.accept(cb);
			recorded = true;
		} finally {
			// Always closed; executed only when complete (a half-recorded buffer is dropped with its transient pool).
			VulkanCompute.check(VK10.vkEndCommandBuffer(cb), "end Aetherium command buffer");
			if (recorded) {
				encoder.execute(cb);
			}
		}
	}

	/** Vanilla's reversed zero-to-one projection -> its OpenGL twin (row 2 := row 3 - 2 row 2), what packs expect. */
	static Matrix4f toGLProjection(Matrix4f m) {
		Matrix4f r = new Matrix4f(m);
		r.m02(m.m03() - 2.0f * m.m02());
		r.m12(m.m13() - 2.0f * m.m12());
		r.m22(m.m23() - 2.0f * m.m22());
		r.m32(m.m33() - 2.0f * m.m32());
		return r;
	}

	/**
	 * One stage of the frame plan: its pre-flips, then each step (the computes of an index, its full-screen pass, the
	 * targets it flips). The shadowcomp stage works on the shadowcolor targets at the shadow map's size.
	 */
	private void runStage(ProgramArrayId id) {
		FramePlan.Stage stage = plan.stage(id);
		boolean shadowTargets = id == ProgramArrayId.ShadowComposite;
		if (frozenFullscreen) {
			// The final pass reads the same state as the composite passes: its block goes up with theirs.
			uploadStageBlocks(stage, id == ProgramArrayId.Composite ? plan.finalPass : null);
		}
		for (int target : stage.preFlips()) {
			flip(target, shadowTargets, false);
		}
		for (FramePlan.Step step : stage.steps()) {
			if (DevFlags.DEBUG_VIEW && debug.stopped()) {
				return;
			}
			if (shadowTargets) {
				runComputes(step.computes(), shadow.resolution(), shadow.resolution(), step.written());
			} else {
				runComputes(step.computes(), step.written());
			}
			CompositePass pass = step.pass();
			if (pass != null) {
				if (!shadowTargets) {
					for (int target : pass.mipmappedBuffers()) {
						targets.generateMipmaps(target, blit, quad, blitMips);
					}
				} else {
					for (int target : pass.mipmappedBuffers()) {
						shadow.generateColorMipmaps(target, blitMips);
					}
				}
				draw(pass, main, step.written(), shadowTargets);
				if (!shadowTargets) {
					// What the pass wrote: those targets' mip chains go stale.
					if (!pass.writesMain()) {
						for (int target : pass.drawBuffers()) {
							targets.markWritten(target);
						}
					}
					for (int target : pass.colourImages()) {
						targets.markWritten(target);
					}
				}
			}
			for (int target : step.flips()) {
				flip(target, shadowTargets, pass != null && pass.writesWhole(target));
			}
			if (DevFlags.DEBUG_VIEW && pass != null && !shadowTargets) {
				debug.afterPass(id, pass, targets, blit, quad, main);
			}
		}
	}

	/** Flips a target after a pass; {@code whole}: the pass wrote every texel of it (see {@link PackTargets#flip}). */
	private void flip(int target, boolean shadowTarget, boolean whole) {
		if (shadowTarget) {
			if (target < shadow.colorCount()) {
				shadow.flip(target, whole);
			}
		} else if (target >= 0 && target < targets.count()) {
			targets.flip(target, whole);
			snapshotStale[target] = true;
		}
	}

	/**
	 * The storage set of a full-screen pass that binds targets as images ({@code colorimgN}, {@code shadowcolorimgN}):
	 * each target's view as the pass's samplers read it this pass; 0 for other passes (the shared set is bound).
	 */
	private long targetImageSet(CompositePass pass, boolean water, Set<Integer> written) {
		PackStorage s = storage;
		if (s == null || pass.colourImages().isEmpty() && pass.shadowColourImages().isEmpty()) {
			return 0;
		}
		Map<Integer, Long> colour = new HashMap<>();
		Map<Integer, Long> shadowColour = new HashMap<>();
		for (int target : pass.colourImages()) {
			colour.put(target, imageView(samplerTable.resolve("colortex" + target, pass.stage(), SamplerTable.Scope.FULLSCREEN, water, null, written)));
		}
		for (int target : pass.shadowColourImages()) {
			shadowColour.put(target, imageView(samplerTable.resolve("shadowcolor" + target, pass.stage(), SamplerTable.Scope.FULLSCREEN, water, null, written)));
		}
		return s.targetImageSet(pass, colour, shadowColour);
	}

	private static long imageView(SamplerTable.@Nullable Bound bound) {
		return bound != null && bound.view() instanceof VulkanGpuTextureView view ? view.vkImageView() : 0;
	}

	/** The colour (index &lt; 32) and shadow colour (32 + index) targets the loaded programs bind as images; per program set. */
	private @Nullable ProgramSet imageTargetsOf;
	private int[] imageTargets = new int[0];

	/**
	 * The storage set of the world or shadow render pass being drawn when the pack's programs bind targets as images
	 * ({@code colorimgN}, {@code shadowcolorimgN}): each target's view as gbuffer and shadow programs read it now (image
	 * bindings follow buffer flips); 0 outside those passes or without such programs (the shared set is bound then).
	 * Asked once per render pass ({@link StorageSet#renderPassSet}).
	 */
	long worldTargetImageSet() {
		PackStorage s = storage;
		ProgramSet set = programs;
		if (s == null || set == null || samplerTable == null || !TargetStorage.active() || state != State.ACTIVE || !gbufferPassOpen && !shadowPassOpen) {
			return 0;
		}
		if (imageTargetsOf != set) {
			java.util.BitSet found = new java.util.BitSet();
			for (String text : TargetStorage.sourceTexts(set)) {
				Matcher m = TargetImages.matcher(text);
				while (m.find()) {
					found.set((TargetImages.shadow(m) ? 32 : 0) + TargetImages.index(m));
				}
			}
			imageTargets = found.stream().toArray();
			imageTargetsOf = set;
		}
		if (imageTargets.length == 0) {
			return 0;
		}
		SamplerTable.Scope scope = shadowPassOpen ? SamplerTable.Scope.SHADOW : SamplerTable.Scope.GBUFFER;
		Map<Integer, Long> colour = new HashMap<>();
		Map<Integer, Long> shadowColour = new HashMap<>();
		for (int target : imageTargets) {
			if (target < 32) {
				// The target itself, never a custom texture bound under its name (written: no override).
				colour.put(target, imageView(samplerTable.resolve("colortex" + target, TextureStage.GBUFFERS_AND_SHADOW, scope, false, null, Set.of(target))));
			} else if (shadow != null) {
				shadowColour.put(target - 32, imageView(samplerTable.resolve("shadowcolor" + (target - 32), TextureStage.GBUFFERS_AND_SHADOW, scope, false, null, null)));
			}
		}
		return s.worldImageSet(colour, shadowColour);
	}

	/**
	 * One full-screen pass into its draw buffers (the side they are written to), vanilla's main target for the final
	 * pass, or the shadowcolor targets for shadowcomp passes. {@code scale.<pass>} draws into a viewport that part of
	 * the targets (the rest keeps its contents).
	 */
	private void draw(CompositePass pass, RenderTarget mainTarget, Set<Integer> written, boolean shadowTargets) {
		CompositePass.Frozen frozen = frozenFullscreen ? frozen(pass, written) : null;
		GpuBufferSlice block = null;
		if (frozen != null && frozen.blockStage == stageUploads) {
			block = frozen.block;
		} else {
			pass.updateUniforms(customUniforms);
		}
		ByteBuffer bytes = pass.uniforms().buffer();
		RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(frozen != null ? frozen.label : () -> "Aetherium Shaders " + pass.name());
		int width;
		int height;
		if (pass.writesMain()) {
			descriptor.withColorAttachment(mainTarget.getColorTextureView());
			width = mainTarget.width;
			height = mainTarget.height;
		} else if (shadowTargets) {
			for (int target : pass.drawBuffers()) {
				descriptor.withColorAttachment(shadow.colorWriteView(target));
			}
			width = height = shadow.resolution();
		} else {
			for (int target : pass.drawBuffers()) {
				// A side still owed its frame-start clear takes it as its load operation (whole-target passes only).
				GpuTextureView view = targets.writeView(target);
				descriptor.withColorAttachment(view, targets.loadClear(target, view, !pass.scaled()));
			}
			width = targets.width(pass.drawBuffers()[0]);
			height = targets.height(pass.drawBuffers()[0]);
		}
		var viewport = pass.viewport();
		boolean scaled = pass.scaled();
		if (scaled) {
			descriptor.withRenderArea(frozen != null ? renderArea(frozen, viewport, width, height) : renderArea(viewport, width, height));
		}
		if (frozen != null) {
			drawFrozen(pass, frozen, descriptor, block, bytes, scaled, viewport, width, height);
			return;
		}
		Map<String, SamplerTable.Bound> bound = new LinkedHashMap<>();
		boolean water = ShadowSampling.declaresWatershadow(pass.samplers());
		for (String sampler : pass.samplers()) {
			bound.put(sampler, samplerTable.resolve(sampler, pass.stage(), SamplerTable.Scope.FULLSCREEN, water, null, written));
		}
		long targetImages = targetImageSet(pass, water, written);
		try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor.build())) {
			if (scaled) {
				PassViewport.set(renderPass, width * viewport.viewportX(), height * viewport.viewportY(),
						width * viewport.scale(), height * viewport.scale());
			}
			StorageSet.passSet(targetImages);
			try {
				renderPass.setPipeline(pass.compiled());
			} finally {
				StorageSet.passSet(0);
			}
			if (pass.uniforms().hasBlock()) {
				renderPass.setUniform(UniformBlock.BLOCK_NAME, arena.upload(bytes.duplicate().clear()));
			}
			bound.forEach((sampler, b) -> renderPass.setUniform(sampler, b.view(), b.sampler()));
			renderPass.setVertexBuffer(0, quad.slice());
			renderPass.draw(6, 1, 0, 0);
		}
	}

	/** The part of a {@code width} x {@code height} target a {@code scale.<pass>} viewport covers. */
	private static RenderPass.RenderArea renderArea(ViewportData viewport, int width, int height) {
		int x = Math.round(width * viewport.viewportX());
		int y = Math.round(height * viewport.viewportY());
		int w = Math.max(1, Math.min(width - x, Math.round(width * viewport.scale())));
		int h = Math.max(1, Math.min(height - y, Math.round(height * viewport.scale())));
		return new RenderPass.RenderArea(x, y, w, h);
	}

	// ------------------------------------------------------------------ frozen full-screen passes (shaders.frozen_fullscreen)

	/** {@link EngineSwitches#FROZEN_FULLSCREEN} for this frame. */
	private boolean frozenFullscreen;
	/** Counts stage block uploads: a pass's {@link CompositePass.Frozen#block} is current when it carries the last one. */
	private int stageUploads;
	private ByteBuffer[] stageBytes = new ByteBuffer[16];
	private GpuBufferSlice[] stageSlices = new GpuBufferSlice[16];
	private CompositePass[] stagePasses = new CompositePass[16];
	private final SamplerTable.Slot fullscreenSlot = new SamplerTable.Slot();

	/**
	 * Updates the uniforms of every pass of {@code stage} (and {@code extra}) and uploads their blocks through one
	 * mapping. Nothing a stage does between its passes changes a uniform value, so this equals updating each block
	 * just before its pass.
	 */
	private void uploadStageBlocks(FramePlan.Stage stage, @Nullable CompositePass extra) {
		stageUploads++;
		int count = 0;
		int steps = stage.steps().size();
		for (int i = 0; i <= steps; i++) {
			CompositePass pass = i < steps ? stage.steps().get(i).pass() : extra;
			if (pass == null) {
				continue;
			}
			CompositePass.Frozen frozen = frozen(pass, i < steps ? stage.steps().get(i).written() : plan.writtenBeforeFinal);
			pass.updateUniforms(customUniforms);
			frozen.block = null;
			frozen.blockStage = stageUploads;
			if (!pass.uniforms().hasBlock()) {
				continue;
			}
			if (count == stagePasses.length) {
				stagePasses = Arrays.copyOf(stagePasses, count * 2);
				stageBytes = Arrays.copyOf(stageBytes, count * 2);
				stageSlices = Arrays.copyOf(stageSlices, count * 2);
			}
			stagePasses[count] = pass;
			stageBytes[count] = pass.uniforms().buffer().duplicate().clear();
			count++;
		}
		arena.upload(stageBytes, count, stageSlices);
		for (int i = 0; i < count; i++) {
			stagePasses[i].frozen.block = stageSlices[i];
			stagePasses[i] = null;
			stageBytes[i] = null;
		}
	}

	/** The pass's frozen state, made on first use ({@code written} is fixed per pass). */
	private CompositePass.Frozen frozen(CompositePass pass, Set<Integer> written) {
		CompositePass.Frozen frozen = pass.frozen;
		if (frozen == null || frozen.table != samplerTable) {
			boolean water = ShadowSampling.declaresWatershadow(pass.samplers());
			String[] names = pass.samplers().toArray(String[]::new);
			SamplerTable.Plan[] plans = new SamplerTable.Plan[names.length];
			for (int i = 0; i < names.length; i++) {
				plans[i] = samplerTable.plan(names[i], pass.stage(), SamplerTable.Scope.FULLSCREEN, water, written);
			}
			int[] colour = pass.colourImages().stream().mapToInt(Integer::intValue).toArray();
			SamplerTable.Plan[] colourPlans = new SamplerTable.Plan[colour.length];
			for (int i = 0; i < colour.length; i++) {
				colourPlans[i] = samplerTable.plan("colortex" + colour[i], pass.stage(), SamplerTable.Scope.FULLSCREEN, water, written);
			}
			int[] shadowColour = pass.shadowColourImages().stream().mapToInt(Integer::intValue).toArray();
			SamplerTable.Plan[] shadowPlans = new SamplerTable.Plan[shadowColour.length];
			for (int i = 0; i < shadowColour.length; i++) {
				shadowPlans[i] = samplerTable.plan("shadowcolor" + shadowColour[i], pass.stage(), SamplerTable.Scope.FULLSCREEN, water, written);
			}
			frozen = new CompositePass.Frozen(samplerTable, names, plans, colour, colourPlans, shadowColour, shadowPlans,
					"Aetherium Shaders " + pass.name());
			pass.frozen = frozen;
		}
		return frozen;
	}

	/** {@link #renderArea(ViewportData, int, int)}, kept while the size stays. */
	private static RenderPass.RenderArea renderArea(CompositePass.Frozen frozen, ViewportData viewport, int width, int height) {
		if (frozen.area == null || frozen.areaWidth != width || frozen.areaHeight != height) {
			frozen.area = renderArea(viewport, width, height);
			frozen.areaWidth = width;
			frozen.areaHeight = height;
		}
		return frozen.area;
	}

	/**
	 * The rest of {@link #draw} for a frozen pass: samplers resolved through the pass's plans without allocating, image
	 * targets into the pass's kept maps, the stage's block slice (uploaded on its own when the stage did not).
	 */
	private void drawFrozen(CompositePass pass, CompositePass.Frozen frozen, RenderPassDescriptor.Builder descriptor, @Nullable GpuBufferSlice block,
			ByteBuffer bytes, boolean scaled, ViewportData viewport, int width, int height) {
		SamplerTable.Slot slot = fullscreenSlot;
		for (int i = 0; i < frozen.plans.length; i++) {
			frozen.plans[i].resolve(null, slot);
			frozen.views[i] = slot.view;
			frozen.samplers[i] = slot.sampler;
		}
		long targetImages = 0;
		PackStorage s = storage;
		if (s != null && (frozen.colourImageTargets.length > 0 || frozen.shadowImageTargets.length > 0)) {
			for (int i = 0; i < frozen.colourImageTargets.length; i++) {
				frozen.colourImagePlans[i].resolve(null, slot);
				frozen.colourImageViews.put(frozen.colourImageTargets[i], imageView(slot.view));
			}
			for (int i = 0; i < frozen.shadowImageTargets.length; i++) {
				frozen.shadowImagePlans[i].resolve(null, slot);
				frozen.shadowImageViews.put(frozen.shadowImageTargets[i], imageView(slot.view));
			}
			targetImages = s.targetImageSet(pass, frozen.colourImageViews, frozen.shadowImageViews);
		}
		if (block == null && pass.uniforms().hasBlock()) {
			block = arena.upload(bytes.duplicate().clear());
		}
		try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor.build())) {
			if (scaled) {
				PassViewport.set(renderPass, width * viewport.viewportX(), height * viewport.viewportY(),
						width * viewport.scale(), height * viewport.scale());
			}
			StorageSet.passSet(targetImages);
			try {
				renderPass.setPipeline(pass.compiled());
			} finally {
				StorageSet.passSet(0);
			}
			if (block != null) {
				renderPass.setUniform(UniformBlock.BLOCK_NAME, block);
			}
			for (int i = 0; i < frozen.names.length; i++) {
				renderPass.setUniform(frozen.names[i], frozen.views[i], frozen.samplers[i]);
			}
			renderPass.setVertexBuffer(0, quad.slice());
			renderPass.draw(6, 1, 0, 0);
		}
	}

	private static long imageView(@Nullable GpuTextureView view) {
		return view instanceof VulkanGpuTextureView vulkan ? vulkan.vkImageView() : 0;
	}

	// ------------------------------------------------------------------ loading

	/**
	 * Starts loading the configured pack (see {@link PackLoad}): the pack is read and its full-screen programs converted
	 * on a worker while frames keep coming with vanilla visuals; {@link #finishLoad} picks the result up at a frame start.
	 */
	private void load() {
		Optional<String> packName = ShaderPackSettings.activePack();
		loadedPack = packName.orElse(null);
		lastError = null;
		if (packName.isEmpty()) {
			state = State.DISABLED;
			rebuildMeshesIfNeeded(ChunkMeshes.stale());
			return;
		}
		loadStarted = System.nanoTime();
		loadTimings = LoadTimings.snapshot();
		lastLevelStart = 0;
		longestLoadingFrame = 0;
		LoadedPack kept = carried;
		carried = null;
		try {
			RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			var format = mainTarget.getColorTexture().getFormat();
			NamespacedId dimension = AetheriumShaders.getCurrentDimension();
			PackLoad started = null;
			if (kept != null) {
				Map<String, String> options = ShaderPackSettings.readOptions(packName.get());
				var defines = StandardMacros.createStandardEnvironmentDefines();
				ShadowOverrides shadows = ShaderPackSettings.shadowOverrides();
				if (kept.readWith(packName.get(), options, defines, shadows)) {
					// Another dimension of the pack in use: only its programs are built.
					started = PackLoad.start(kept.opened(), kept.pack(), packName.get(), options, defines, shadows, dimension, format);
					kept = null;
				}
			}
			pendingLoad = started != null ? started : PackLoad.start(packName.get(), dimension, format);
			state = State.LOADING;
		} catch (RuntimeException e) {
			loadFailed(packName.get(), e);
		} finally {
			if (kept != null) {
				kept.opened().close();
			}
		}
	}

	/** Load timing totals when the current load started ({@link LoadTimings}). */
	private LoadTimings.Snapshot loadTimings = LoadTimings.snapshot();

	/**
	 * The player is in another dimension than the pack was set up for. When both dimensions take their programs from
	 * the same folder of the pack, everything stays (programs, targets, history); otherwise the pack is set up again
	 * for the new dimension from the pack already read (no disk read), building that dimension's programs.
	 */
	private void enterDimension(NamespacedId dimension) {
		LoadedPack loaded = current;
		NamespacedId from = loadedDimension;
		if (loaded != null && from != null && loaded.pack().programFolder(dimension).equals(loaded.pack().programFolder(from))) {
			AetheriumShaders.logger.info("dimension changed to {}: same programs, the pack stays", dimension);
			loadedDimension = dimension;
			return;
		}
		AetheriumShaders.logger.info("dimension changed to {}, setting the pack up for it", dimension);
		current = null;
		reload();
		carried = loaded;
	}

	/** The load running on the worker (state LOADING), else null. */
	private @Nullable PackLoad pendingLoad;
	/** When the current load started ({@link System#nanoTime}). */
	private long loadStarted;
	/** Frame starts while loading, for the longest frame logged when the pack goes active. */
	private long lastLevelStart;
	private long longestLoadingFrame;
	/** Vanilla pipelines the (vanilla) frames bound while warming: the pack goes active once their programs are built. */
	private final Set<RenderPipeline> seenWhileWarming = Collections.newSetFromMap(new IdentityHashMap<>());
	private int warmFrames;
	/** World programs queued when warming started (state WARMING), for the progress shown. */
	private int warmTotal;
	/** Mesh settings of the loaded pack, applied when it becomes active (meshes built meanwhile stay vanilla's). */
	private @Nullable Runnable onActivate;
	/** The longest the world programs may build before the pack goes active anyway (the rest then build on demand). */
	private static final long WARM_LIMIT_NANOS = 20_000_000_000L;

	/** Stops a load still running on the worker (and frees what it prepared). */
	private void cancelLoad() {
		PackLoad load = pendingLoad;
		pendingLoad = null;
		if (load != null) {
			load.cancel();
		}
	}

	private void loadFailed(String packName, Throwable e) {
		DeviceLoss.rethrow(e);
		AetheriumShaders.logger.error("could not load pack '{}', keeping vanilla visuals", packName, e);
		lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		disable();
		notifyFailure("message.aetherium.shaders_load_failed", ShaderPackSettings.displayName(packName));
	}

	/**
	 * Progress of a pack load, for the settings screen and the HUD: the step (0 reading the pack, 1 converting its
	 * full-screen programs, 2 starting it, 3 building its world programs), its name, and how far it is ({@code total} 0 =
	 * no count).
	 */
	public record LoadProgress(String pack, int step, String phase, int done, int total, double seconds) {
		private static final float[] START = {0f, 0.04f, 0.5f, 0.54f};
		private static final float[] SPAN = {0.04f, 0.46f, 0.04f, 0.46f};

		/** This step's progress, 0..1, or -1 when it has no count. */
		public float fraction() {
			return total <= 0 ? -1 : Math.min(1f, (float) done / total);
		}

		/** The whole load's progress, 0..1 (the two counted steps weigh about the same). */
		public float overall() {
			return START[step] + SPAN[step] * Math.max(0, fraction());
		}
	}

	/** The running load's progress, or null when no load is running. */
	public @Nullable LoadProgress loadProgress() {
		double seconds = (System.nanoTime() - loadStarted) / 1e9;
		PackLoad load = pendingLoad;
		if (state == State.LOADING && load != null) {
			return new LoadProgress(load.packName, load.step, load.phase, load.done.get(), load.total, seconds);
		}
		if (state == State.WARMING && loadedPack != null) {
			return new LoadProgress(loadedPack, 3, "Building world shaders", Math.max(0, warmTotal - pendingPrograms()), warmTotal, seconds);
		}
		return null;
	}

	/** True while a pack loads (read and converted on a worker, then its world programs built); vanilla visuals meanwhile. */
	public boolean loading() {
		return state == State.LOADING || state == State.WARMING;
	}

	/** After a few warming frames: every pipeline they bound has its pack programs (world, hand, shadow) built. */
	private boolean viewReady() {
		if (warmFrames < 3 || seenWhileWarming.isEmpty()) {
			return false;
		}
		for (RenderPipeline vanilla : seenWhileWarming) {
			if (!worldPrograms.ready(vanilla, PipelinePrograms.Context.WORLD) || !worldPrograms.ready(vanilla, PipelinePrograms.Context.HAND)
					|| !worldPrograms.ready(vanilla, PipelinePrograms.Context.HAND_TRANSLUCENT)
					|| shadowPrograms != null && !shadowPrograms.ready(vanilla, PipelinePrograms.Context.WORLD)) {
				return false;
			}
		}
		return true;
	}

	private int pendingPrograms() {
		return (worldPrograms == null ? 0 : worldPrograms.pending()) + (shadowPrograms == null ? 0 : shadowPrograms.pending());
	}

	/**
	 * {@code Minecraft.setLevel} (between frames): a world or a dimension is being entered. The pack (for that dimension)
	 * starts loading in the background while its terrain loads; the first frame of the level finishes it.
	 */
	public void onLevelSet() {
		PackLoad started = pendingLoad;
		NamespacedId dimension = AetheriumShaders.getCurrentDimension();
		if (state == State.LOADING && started != null && !started.dimension.equals(dimension)) {
			reload();
		} else if ((state == State.ACTIVE || state == State.WARMING) && !dimension.equals(loadedDimension)) {
			enterDimension(dimension);
		}
		if (state == State.ACTIVE) {
			// The pack stays (same dimension): its time and history start over as for a fresh load.
			SystemTimeUniforms.reset();
			if (updateNotifier != null) {
				updateNotifier.reset();
			}
			SmoothFloat.resetAll();
		}
		if (state == State.UNLOADED) {
			load();
		}
	}

	/** The level the last frame drew (a different one: the player entered a world or a dimension). */
	private @Nullable ClientLevel renderedLevel;

	/** Waits for the load running in the background and every world program, then goes active (entering a world). */
	private void finishLoadNow() {
		PackLoad load = pendingLoad;
		if (state == State.LOADING && load != null) {
			pendingLoad = null;
			try {
				finishLoad(load.result());
			} catch (IOException | RuntimeException e) {
				load.releaseUnused();
				loadFailed(load.packName, e);
				return;
			}
		}
		if (state == State.WARMING) {
			worldPrograms.finishAll();
			if (shadowPrograms != null) {
				shadowPrograms.finishAll();
			}
			warm();
		}
	}

	/** Frame start, state LOADING: when the worker is done, builds what needs the render thread and starts warming. */
	private void pollLoad() {
		PackLoad load = pendingLoad;
		if (load == null || !load.isDone()) {
			return;
		}
		pendingLoad = null;
		try {
			finishLoad(load.result());
		} catch (IOException | RuntimeException e) {
			load.releaseUnused();
			loadFailed(load.packName, e);
		}
	}

	/**
	 * The render-thread half of a load: GPU objects (storage, textures, buffers, the prepared passes' pipelines) and the
	 * frame plan. The pack then warms up: its world programs build in the background and it goes active when they are
	 * done ({@link #warm}), so its first frames do not wait for them.
	 */
	private void finishLoad(PackLoad load) throws IOException {
		var opened = Objects.requireNonNull(load.opened);
		load.opened = null;
		ShaderPack pack = Objects.requireNonNull(load.pack);
		current = new LoadedPack(load.packName, opened, pack, load.options, load.defines, load.shadows);
		profileInfo = pack.getProfileInfo();
		String packName = load.packName;
		loadedDimension = load.dimension;
		programs = Objects.requireNonNull(load.programs);
		PackDirectives directives = programs.getPackDirectives();
		// endFlashShadows: in the End the shadow map and sun uniforms follow the End flash instead of the sky angle.
		ShadowMatrices.endFlashSupported = directives.supportsEndFlash();
		if (load.storageNeeded) {
			RenderTarget screen = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			storage = new PackStorage(Objects.requireNonNull(VulkanAccess.device()),
					pack.getCustomImages(), pack.getBufferObjects(), load.raws, TargetStorage.active(), screen.width, screen.height, load.storageSetLayout);
			load.storageSetLayout = 0;
			StorageSet.activate(storage);
		}
		updateNotifier = new FrameUpdateNotifier();
		FrameUpdateNotifier notifier = updateNotifier;
		customUniforms = pack.customUniforms.build(holder -> CommonUniforms.addNonDynamicUniforms(holder, pack.getIdMap(), directives, notifier));
		noiseSize = directives.getNoiseTextureResolution();
		centerDepth = new CenterDepth(directives.getCenterDepthHalfLife());
		customTextures = new CustomTextures(pack);
		// World settings: ID maps the uniforms (currentSelectedBlockId, entityId, heldItemId ...) and, later, the mesher read.
		var settings = WorldRenderingSettings.INSTANCE;
		settings.setEntityIds(pack.getIdMap().getEntityIdMap());
		settings.setItemIds(pack.getIdMap().getItemIdMap());
		frustumCulling = directives.shouldUseFrustumCulling();
		occlusionCulling = directives.shouldUseOcclusionCulling();
		skipAllRendering = directives.skipAllRendering();
		var blockStateIds = BlockMaterialMapping.createBlockStateIdMap(
				pack.getIdMap().getBlockProperties(), pack.getIdMap().getTagEntries());
		var blockTypeIds = BlockMaterialMapping.createBlockTypeMap(
				pack.getIdMap().getBlockRenderTypeMap());
		// What chunk meshes bake in: applied when the pack goes active, so meshes built while it warms up stay vanilla's.
		onActivate = () -> {
			settings.setDisableDirectionalShading(!directives.isOldLighting());
			settings.setAmbientOcclusionLevel(directives.getAmbientOcclusionLevel());
			settings.setUseSeparateAo(directives.shouldUseSeparateAo());
			settings.setBreaksAnisotropy(directives.breaksAnisotropy());
			settings.setVoxelizeLightBlocks(directives.shouldVoxelizeLightBlocks());
			settings.setSeparateEntityDraws(directives.shouldUseSeparateEntityDraws());
			settings.setBlockStateIds(blockStateIds);
			settings.setBlockTypeIds(blockTypeIds);
		};
		// colortex0..31, from the worker (the textures are created on first use).
		targets = Objects.requireNonNull(load.targets);
		// Targets some full-screen pass reads mipmapped get a mip chain (generated before that pass, as on OpenGL).
		for (ProgramArrayId stage : ProgramArrayId.values()) {
			for (ProgramSource source : programs.getComposite(stage)) {
				if (source != null) {
					source.getDirectives().getMipmappedBuffers().forEach(targets::markMipmapped);
				}
			}
		}
		programs.get(ProgramId.Final).ifPresent(source -> source.getDirectives().getMipmappedBuffers().forEach(targets::markMipmapped));
		layout = GbufferLayout.of(programs, targets);
		// size.buffer.colortexN, except for targets world programs draw into (one size per render pass).
		targets.setSizes(directives, layout.attachments());
		StringBuilder kept = new StringBuilder();
		directives.getRenderTargetDirectives().getRenderTargetSettings().forEach((index, target) -> {
			if (!target.shouldClear()) {
				kept.append(" colortex").append(index);
			}
		});
		AetheriumShaders.logger.info("buffers kept across frames (clear = false):{}", kept.length() == 0 ? " none" : kept);
		worldPrograms = new WorldPrograms(programs, layout, customUniforms, false);
		packHasLodPrograms = programs.get(ProgramId.DhTerrain).isPresent() || programs.get(ProgramId.DhWater).isPresent();
		packHasLodShadow = packHasLodPrograms && programs.get(ProgramId.DhShadow).isPresent()
				&& directives.getShadowDirectives().isDhShadowEnabled().orElse(true);
		lodRenderingAtLoad = LodCompat.hasRenderingEnabled();
		if (LodCompat.installed()) {
			AetheriumShaders.logger.info("level-of-detail terrain {}", !lodRenderingAtLoad ? "off"
					: packHasLodPrograms ? "drawn with the pack's dh_ programs" : "hidden (the pack has no dh_terrain/dh_water)");
		}
		usage = SamplerUsage.of(programs);

		shadow = ShadowPass.create(programs, directives, usage.samplesOpaqueShadowDepth());
		samplerTable = new SamplerTable(targets, depth, shadow, customTextures, centerDepth, noiseSize);
		if (shadow != null) {
			shadowPrograms = new WorldPrograms(programs, shadow, customUniforms, true);
			// Shadow programs that write the pack's images (voxelisation) must run every frame: no cached shadow terrain.
			shadowFrame = new ShadowFrame(shadow, ShadowFrame.animated(programs, pack.customUniforms.names()) || storage != null);
			AetheriumShaders.logger.info("shadow terrain cache {}", shadowFrame.cacheAvailable() ? "available"
					: "not available (the shadow programs read time, weather, the camera or custom uniforms)");
		}
		RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		var mainFormat = mainTarget.getColorTexture().getFormat();
		ShadowPass shadowPass = shadow;
		try {
			plan = FramePlan.build(programs, directives, new FramePlan.Builders() {
				@Override
				public @Nullable CompositePass pass(ProgramSource source, ProgramArrayId stage) {
					if (stage == ProgramArrayId.ShadowComposite) {
						if (shadowPass == null) {
							AetheriumShaders.logger.warn("[{}] shadowcomp pass without a shadow map; skipped", source.getName());
							return null;
						}
						return CompositePass.create(source, TextureStage.SHADOWCOMP, source.getDirectives().getDrawBuffers(), false, shadowPass::format,
								customUniforms);
					}
					if (load.hasPass(source)) {
						CompositePass.Prepared prepared = load.takePass(source);
						return prepared == null ? null : CompositePass.finish(prepared, customUniforms);
					}
					return CompositePass.create(source, FramePlan.textureStage(stage), source.getDirectives().getDrawBuffers(), false, targets::format,
							customUniforms);
				}

				@Override
				public @Nullable CompositePass finalPass(ProgramSource source) {
					if (load.hasPass(source)) {
						CompositePass.Prepared prepared = load.takePass(source);
						return prepared == null ? null : CompositePass.finish(prepared, customUniforms);
					}
					return CompositePass.create(source, TextureStage.COMPOSITE_AND_FINAL, new int[]{0}, true, target -> mainFormat, customUniforms);
				}

				@Override
				public ComputePass[] computes(ComputeSource @Nullable [] sources, TextureStage textures) {
					return buildComputes(sources, textures, load);
				}
			});
		} finally {
			load.releaseUnused();
		}
		plan.log();
		int built = plan.built, skipped = plan.skipped;
		setupRan = false;
		clearOverwritten = clearOverwritten(targets.count());
		// Second (ping-pong) textures only for the targets full-screen passes write or flip; clears still pending when a
		// pass is open are reported, never recorded inside it.
		targets.setNeedsSecond(plan.writtenOrFlipped(targets.count()));
		targets.setPassOpen(() -> gbufferPassOpen || shadowPassOpen);
		takenClears = new Vector4fc[targets.count()];
		shadowReadsTargets = shadow != null && (TargetStorage.active() || shadowProgramsSampleTargets(programs));
		ByteBuffer quadBytes = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder());
		for (float f : QUAD) {
			quadBytes.putFloat(f);
		}
		quadBytes.flip();
		quad = RenderSystem.getDevice().createBuffer(() -> "Aetherium Shaders full-screen quad", GpuBuffer.USAGE_VERTEX, quadBytes);
		state = State.WARMING;
		seenWhileWarming.clear();
		warmFrames = 0;
		prebuildWorldPrograms();
		warmTotal = pendingPrograms();
		AetheriumShaders.logger.info("pack '{}' read and converted in {} s on {}: {} full-screen passes built, {} skipped; gbuffer attachments colortex{}, shadow map {}",
				packName, String.format(Locale.ROOT, "%.1f", load.seconds()), RenderSystem.getDevice().getDeviceInfo().backendName(), built, skipped,
				Arrays.toString(layout.attachments()), shadow == null ? "none" : "shadowcolor" + Arrays.toString(shadow.attachments()));
	}

	/**
	 * Frame start, state WARMING: takes the world programs built so far. Active once every program the frames so far
	 * needed is built (the rest keep building in the background, needed first by rarer draws), or after
	 * {@link #WARM_LIMIT_NANOS}.
	 */
	private void warm() {
		worldPrograms.drainFinished();
		if (shadowPrograms != null) {
			shadowPrograms.drainFinished();
		}
		warmFrames++;
		long warming = System.nanoTime() - loadStarted;
		if (pendingPrograms() > 0 && !viewReady() && warming < WARM_LIMIT_NANOS) {
			return;
		}
		seenWhileWarming.clear();
		if (pendingPrograms() == 0) {
			// The pack's programs are built: keep their pipelines for the next launch (gpu.pipeline_cache).
			PipelineCacheStore.save();
		}
		state = State.ACTIVE;
		// The pack's first frame: frameCounter from zero, no frameTime for the load.
		SystemTimeUniforms.reset();
		Runnable activate = onActivate;
		onActivate = null;
		if (activate != null) {
			activate.run();
		}
		// Chunk meshes built before the block IDs existed carry mc_Entity = -1; face shading or their format may have changed.
		rebuildMeshesIfNeeded(ChunkMeshes.stale());
		AetheriumShaders.logger.info("pack '{}' active after {} s (longest frame meanwhile {} ms){}; summed over threads: {}", loadedPack,
				String.format(Locale.ROOT, "%.1f", warming / 1e9), longestLoadingFrame / 1_000_000,
				pendingPrograms() > 0 ? ", " + pendingPrograms() + " world programs still building" : "", LoadTimings.since(loadTimings));
	}

	/**
	 * Per colortex: whether its frame-start clear is dead. True when no gbuffers program writes or samples it and the
	 * first full-screen pass that touches it writes it without sampling it (a full-screen pass writes every texel of
	 * the side it renders into, and that pass reads the other side only if it samples the target).
	 */
	private boolean[] clearOverwritten(int count) {
		boolean[] dead = new boolean[count];
		List<CompositePass> order = plan.colourPasses();
		for (int t = 0; t < count; t++) {
			int target = t;
			if (Arrays.stream(layout.attachments()).anyMatch(a -> a == target) || layout.samples(target)) {
				continue;
			}
			for (CompositePass pass : order) {
				boolean reads = pass.samplers().stream().anyMatch(name -> GbufferLayout.targetIndex(name) == target);
				boolean writes = !pass.writesMain() && Arrays.stream(pass.drawBuffers()).anyMatch(b -> b == target);
				if (reads) {
					break;
				}
				if (writes) {
					dead[target] = true;
					break;
				}
			}
		}
		return dead;
	}

	private static final Pattern TARGET_NAME = Pattern.compile("\\b(colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])\\b");

	/** Whether a shadow program (dh_shadow included) declares a sampler on a colour target ({@code texture} is its albedo). */
	private static boolean shadowProgramsSampleTargets(ProgramSet programs) {
		for (ProgramId id : ProgramId.values()) {
			if (id.getGroup() != dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramGroup.Shadow && id != ProgramId.DhShadow) {
				continue;
			}
			Optional<ProgramSource> source = programs.get(id);
			if (source.isEmpty()) {
				continue;
			}
			for (Optional<String> text : List.of(source.get().getVertexSource(), source.get().getGeometrySource(), source.get().getFragmentSource())) {
				for (String sampler : text.map(SamplerUsage::declaredSamplers).orElse(Set.of())) {
					if (!sampler.equals("texture") && GbufferLayout.targetIndex(sampler) >= 0) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Colour targets some program writes (DRAWBUFFERS) or names in its source, plus colortex0 (the final pass's input).
	 * Over-inclusive by design: a false match costs one texture pair, a miss would sample a missing target.
	 */
	static boolean[] referencedTargets(ProgramSet programs, int count) {
		boolean[] used = new boolean[count];
		used[0] = true;
		List<ProgramSource> sources = new ArrayList<>();
		for (ProgramId id : ProgramId.values()) {
			programs.get(id).ifPresent(sources::add);
		}
		for (ProgramArrayId stage : ProgramArrayId.values()) {
			for (ProgramSource source : programs.getComposite(stage)) {
				if (source != null) {
					sources.add(source);
				}
			}
		}
		for (ProgramSource source : sources) {
			for (int buffer : source.getDirectives().getDrawBuffers()) {
				if (buffer >= 0 && buffer < count) {
					used[buffer] = true;
				}
			}
		}
		for (String text : TargetStorage.sourceTexts(programs)) {
			Matcher m = TARGET_NAME.matcher(text);
			while (m.find()) {
				int target = GbufferLayout.targetIndex(m.group(1));
				if (target >= 0 && target < count) {
					used[target] = true;
				}
			}
			// A target written as an image (colorimgN) exists even when no pass names colortexN.
			Matcher image = TargetImages.matcher(text);
			while (image.find()) {
				if (!TargetImages.shadow(image)) {
					int target = TargetImages.index(image);
					if (target < count) {
						used[target] = true;
					}
				}
			}
		}
		return used;
	}

	/** Queues background builds of every program the pack can use (terrain first), so few are built mid-frame. */
	private void prebuildWorldPrograms() {
		for (RenderPipeline pipeline : PipelinePrograms.mainPipelines()) {
			worldPrograms.prebuild(pipeline, PipelinePrograms.Context.WORLD);
		}
		for (PipelinePrograms.Context hand : new PipelinePrograms.Context[]{PipelinePrograms.Context.HAND, PipelinePrograms.Context.HAND_TRANSLUCENT}) {
			for (RenderPipeline pipeline : PipelinePrograms.mainPipelines()) {
				worldPrograms.prebuild(pipeline, hand);
			}
		}
		if (shadowPrograms != null) {
			for (RenderPipeline pipeline : PipelinePrograms.shadowPipelines()) {
				shadowPrograms.prebuild(pipeline, PipelinePrograms.Context.WORLD);
			}
		}
		// LOD programs only while LODs render (packs gate them on DISTANT_HORIZONS, defined only then).
		for (RenderPipeline pipeline : packHasLodPrograms && lodRenderingAtLoad ? lodPipelines : Set.<RenderPipeline>of()) {
			worldPrograms.prebuild(pipeline, PipelinePrograms.Context.WORLD);
			if (shadowPrograms != null) {
				shadowPrograms.prebuild(pipeline, PipelinePrograms.Context.WORLD);
			}
		}
		AetheriumShaders.logger.info("{} world programs building in the background", worldPrograms.pending()
				+ (shadowPrograms == null ? 0 : shadowPrograms.pending()));
	}

	private void fail(String what, RuntimeException e) {
		DeviceLoss.rethrow(e);
		AetheriumShaders.logger.error("failed {}, back to vanilla visuals", what, e);
		lastError = "Failed " + what + (e.getMessage() == null ? "" : ": " + e.getMessage());
		notifyFailure("message.aetherium.shaders_stopped", what);
		if (gbufferPassOpen || shadowPassOpen) {
			// The open render pass has the pack's attachments (gbuffers or shadow map); vanilla's own pipelines cannot bind
			// to it, and its textures must outlive it. Finish it with pack/widened pipelines and switch off when it closes.
			disableAfterPass = true;
			return;
		}
		stopAndTearDownLater();
	}

	private boolean disableAfterPass;

	/** A line above the hotbar: the pack failed (the shader settings screen shows why). */
	private static void notifyFailure(String key, String argument) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui != null && mc.level != null) {
			mc.gui.hud.setOverlayMessage(Component.translatable(key, argument), false);
		}
	}

	/**
	 * Rebuilds every chunk mesh when a setting baked into them changed since they were built (face shading, AO level,
	 * block ids), or when {@code force}d. Mesh settings return to vanilla's when a pack is unloaded.
	 */
	private static void rebuildMeshesIfNeeded(boolean force) {
		var settings = WorldRenderingSettings.INSTANCE;
		if (force || settings.isReloadRequired()) {
			settings.clearReloadRequired();
			Minecraft mc = Minecraft.getInstance();
			if (mc.level != null) {
				mc.levelExtractor.allChanged();
			}
		}
	}

	/**
	 * Switches the pack off now and frees everything it owns. Only between frames (or before anything of this frame used
	 * the pack): command buffers already submitted may still reference its objects, so the device is idled first.
	 */
	private void disable() {
		cancelLoad();
		stop();
		teardownPending = false;
		teardown();
		if (carried != null) {
			carried.opened().close();
			carried = null;
		}
	}

	/**
	 * Mid-frame failure: the pack stops being used from here on (vanilla visuals for the rest of the frame), but its
	 * objects stay alive until the next frame starts. This frame's command buffers, recorded but not yet submitted,
	 * still reference its pipelines, images and descriptor sets; destroying them now loses the device.
	 */
	private void stopAndTearDownLater() {
		stop();
		teardownPending = true;
	}

	/** Set by {@link #stopAndTearDownLater}: free the stopped pack's objects at the next frame start. */
	private boolean teardownPending;

	/** No pack in use from here on: flags and settings back to vanilla's (no GPU object is freed). */
	private void stop() {
		state = State.DISABLED;
		onActivate = null;
		// Meshes go back to vanilla's shading; rebuilt when the next load (or "no pack") settles.
		var meshSettings = WorldRenderingSettings.INSTANCE;
		meshSettings.setDisableDirectionalShading(false);
		meshSettings.setAmbientOcclusionLevel(1.0f);
		meshSettings.setUseSeparateAo(false);
		meshSettings.setBlockTypeIds(null);
		frustumCulling = true;
		occlusionCulling = true;
		skipAllRendering = false;
		ShadowMatrices.endFlashSupported = false;
		worldActive = false;
		EntityVertexFormats.setActive(false);
		blockEntityDraw = false;
		featureDraw = false;
		featureFlags = 0;
		gbufferPassOpen = false;
		shadowPassOpen = false;
		drawingSolidHand = false;
		lodShadowIntoOpaque = false;
		context = PipelinePrograms.Context.WORLD;
		debug.reset();
		lodPassOpen = false;
		lodsThisFrame = false;
		packHasLodPrograms = false;
		packHasLodShadow = false;
	}

	/** Frees the stopped pack's objects, after the GPU finished every frame that used them (a lost device finishes none). */
	private void teardown() {
		var vulkan = VulkanAccess.device();
		if (vulkan != null && !DeviceLoss.happened()) {
			VK10.vkDeviceWaitIdle(vulkan.vkDevice());
		}
		// Everything below refers to this pack's textures and programs.
		Arrays.fill(snapshots, null);
		forgetPassBindings();
		slotOf.clear();
		passViews = new GpuTextureView[16];
		passFilters = new GpuSampler[16];
		passReachGeneration = -1;
		Arrays.fill(passEscapes, 0);
		compiledToVanilla.clear();
		shadowFrame = null;
		ShadowRenderer.unpinShadowAngle();
		for (int i = 0; i < albedoSamplers.length; i++) {
			if (albedoSamplers[i] != null) {
				albedoSamplers[i].close();
				albedoSamplers[i] = null;
			}
		}
		retiredSamplers.forEach(GpuSampler::close);
		retiredSamplers.clear();
		if (plan != null) {
			plan.close();
			plan = null;
		}
		// Program builds still running read the storage bindings and shadow sampling: they finish (and are dropped)
		// before either is reset.
		if (worldPrograms != null) {
			worldPrograms.close();
			worldPrograms = null;
		}
		if (shadowPrograms != null) {
			shadowPrograms.close();
			shadowPrograms = null;
		}
		ShadowSampling.reset();
		// Material maps are only read by pack programs: they go with the pack (loaded again when one draws).
		PbrTextures.clear();
		if (storage != null) {
			StorageSet.deactivate();
			storage.close();
			storage = null;
		}
		StorageBindings.reset();
		TexturePatching.reset();
		TargetStorage.configure(false);
		if (shadow != null) {
			shadow.close();
			shadow = null;
		}
		if (targets != null) {
			targets.close();
			targets = null;
		}
		depth.close();
		if (centerDepth != null) {
			centerDepth.close();
			centerDepth = null;
		}
		if (customTextures != null) {
			customTextures.close();
			customTextures = null;
		}
		arena.close();
		if (handDispatcher != null) {
			handDispatcher.close();
			handDispatcher = null;
		}
		if (handBuffers != null) {
			handBuffers.close();
			handBuffers = null;
		}
		if (quad != null) {
			quad.close();
			quad = null;
		}
		blit.close();
		debug.close();
		// The pack's sources, uniform tables (which still reference the closed programs) and layouts, freed while off.
		TransformPatcher.clearCache();
		programs = null;
		profileInfo = null;
		samplerTable = null;
		layout = null;
		customUniforms = null;
		updateNotifier = null;
		usage = null;
		clearOverwritten = null;
		main = null;
		if (current != null) {
			current.opened().close();
			current = null;
		}
	}
}
