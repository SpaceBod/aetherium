package dev.spacebod.aetherium.shaders.compat.lod;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogDrawMode;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeApplyShaderRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeGenericObjectRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeTextureClearEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IOverrideInjector;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import org.jspecify.annotations.Nullable;

/**
 * The only class that uses the LOD mod's API (loaded by {@link LodCompat} when the mod is installed). Event policy while a
 * pack draws the LODs, as packs written for LODs expect: transparent LODs deferred (the engine draws them after its
 * deferred passes), the mod's fog and ambient occlusion off, its final colour copy cancelled, LOD clouds only when the
 * pack draws clouds. A pack without LOD programs gets no LODs at all (the mod's unshaded image would land in the
 * pack's albedo).
 * <p>
 * Three calls have no public API equivalent and go to the mod's internals: {@link #internalRenderLods},
 * {@link #internalRenderDeferredLods} and {@link #internalSetAntiAliasing}. Nothing else here does.
 */
final class LodBridge {
	private static volatile boolean ready;
	private static volatile boolean packDrawsLods;
	private static volatile boolean hideLods;
	private static volatile boolean hideClouds;
	private static boolean deferredCall;
	/** Inside {@link #renderShadow}: the mod draws into the shadow map, culled to the shadow distance. */
	private static boolean shadowCall;
	private static double shadowX;
	private static double shadowZ;
	private static double shadowDistance;
	/** What {@link #applySettings} last set: null = the user's own settings. */
	private static @Nullable Boolean applied;
	private static float near = 0.1F;
	private static float far = 1024.0F;

	private LodBridge() {
	}

	static void register() {
		DhApi.events.bind(DhApiAfterDhInitEvent.class, new DhApiAfterDhInitEvent() {
			@Override
			public void afterDistantHorizonsInit(DhApiEventParam<Void> input) {
				bindRenderEvents();
				ready = true;
				AetheriumShaders.logger.info("level-of-detail terrain available (LOD mod {})", DhApi.getModVersion());
			}
		});
	}

	private static void bindRenderEvents() {
		DhApi.overrides.bind(IDhApiCullingFrustum.class, new ShadowAwareFrustum());
		DhApi.events.bind(DhApiBeforeTextureClearEvent.class, new DhApiBeforeTextureClearEvent() {
			@Override
			public void beforeClear(DhApiCancelableEventParam<DhApiRenderParam> event) {
				// The shadow call draws into the shadow map; the mod's own targets are cleared by its main call.
				if (shadowCall) {
					event.cancelEvent();
				}
			}
		});
		DhApi.events.bind(DhApiBeforeRenderEvent.class, new DhApiBeforeRenderEvent() {
			@Override
			public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
				near = event.value.nearClipPlane;
				far = event.value.farClipPlane;
				if (hideLods) {
					event.cancelEvent();
				}
			}
		});
		DhApi.events.bind(DhApiBeforeApplyShaderRenderEvent.class, new DhApiBeforeApplyShaderRenderEvent() {
			@Override
			public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
				// The LODs are already in the pack's gbuffers; the copy would paint the mod's own image over the scene.
				if (packDrawsLods) {
					event.cancelEvent();
				}
			}
		});
		DhApi.events.bind(DhApiBeforeGenericObjectRenderEvent.class, new DhApiBeforeGenericObjectRenderEvent() {
			@Override
			public void beforeRender(DhApiCancelableEventParam<EventParam> event) {
				// The shadow map has no generic objects; LOD clouds follow the pack's dhClouds/clouds settings.
				if (packDrawsLods && (shadowCall || hideClouds && "Clouds".equalsIgnoreCase(event.value.resourceLocationPath))) {
					event.cancelEvent();
				}
			}
		});
	}

	static boolean renderingEnabled() {
		return ready && DhApi.Delayed.configs != null && Boolean.TRUE.equals(DhApi.Delayed.configs.graphics().renderingEnabled().getValue());
	}

	static void beginFrame(boolean drawsLods, boolean withoutLodPrograms, boolean noClouds) {
		if (!ready) {
			return;
		}
		packDrawsLods = drawsLods;
		hideLods = withoutLodPrograms;
		hideClouds = noClouds;
		applySettings(drawsLods);
	}

	/** The mod's own settings the pack replaces; set when a pack starts drawing LODs, cleared when it stops. */
	private static void applySettings(boolean pack) {
		if (applied != null && applied == pack || applied == null && !pack) {
			return;
		}
		applied = pack;
		var graphics = DhApi.Delayed.configs.graphics();
		DhApi.Delayed.renderProxy.setDeferTransparentRendering(pack);
		// Its anti-aliasing (and sharpen) only writes the mod's own colour texture, which is not shown under a pack.
		internalSetAntiAliasing(pack ? Boolean.FALSE : null);
		if (pack) {
			graphics.fog().drawMode().setValue(EDhApiFogDrawMode.FOG_DISABLED, "Aetherium Shaders");
			graphics.ambientOcclusion().enabled().setValue(false, "Aetherium Shaders");
			// The mod's automatic overdraw (its near plane, up to 0.9 of the vanilla render distance) becomes 0.2 while a
			// shader pack draws LODs, the value the mod itself uses under shader packs: packs fade LODs in over a band
			// inside the vanilla distance, which a far near plane would clip. A value the user set is left alone.
			Float overdraw = graphics.overdrawPreventionRadius().getTrueValue();
			if (overdraw != null && overdraw < 0.0F) {
				graphics.overdrawPreventionRadius().setValue(0.2F, "Aetherium Shaders");
			}
		} else {
			graphics.fog().drawMode().clearValue();
			graphics.ambientOcclusion().enabled().clearValue();
			graphics.overdrawPreventionRadius().clearValue();
		}
		// Cached LOD buffers were built for the other mode (material ids, normals are kept either way, but the mod
		// rebuilds on a shader toggle too).
		DhApi.Delayed.renderProxy.clearRenderDataCache();
		AetheriumShaders.logger.info("level-of-detail terrain {}", pack ? "drawn by the pack" : "drawn by its own renderer");
	}

	static boolean packDrawsLods() {
		return packDrawsLods;
	}

	static boolean inEngineDeferredCall() {
		return deferredCall;
	}

	static void renderDeferredTransparent() {
		if (!ready || !packDrawsLods) {
			return;
		}
		deferredCall = true;
		try {
			internalRenderDeferredLods();
		} finally {
			deferredCall = false;
		}
	}

	static boolean inShadowCall() {
		return shadowCall;
	}

	/**
	 * The mod's LODs once more, for the shadow map: every LOD within {@code distance} blocks of (x, z). Opaque ones, then
	 * with {@code transparent} the transparent ones (deferred while a pack draws LODs, so drawn by a second call here).
	 */
	static void renderShadow(double x, double z, double distance, boolean transparent) {
		if (!ready || !packDrawsLods) {
			return;
		}
		shadowX = x;
		shadowZ = z;
		shadowDistance = distance;
		shadowCall = true;
		try {
			internalRenderLods();
			if (transparent) {
				deferredCall = true;
				try {
					internalRenderDeferredLods();
				} finally {
					deferredCall = false;
				}
			}
		} finally {
			shadowCall = false;
		}
	}

	/**
	 * The mod's camera frustum, except during the shadow call: the mod culls its render list with this override (its
	 * own shadow-frustum setting does not reach this shadow pass), and the shadow map needs the LODs around the player,
	 * not those in view.
	 */
	private static final class ShadowAwareFrustum implements IDhApiCullingFrustum {
		private static @Nullable IDhApiCullingFrustum camera() {
			return DhApi.overrides.get(IDhApiCullingFrustum.class, IOverrideInjector.CORE_PRIORITY);
		}

		/** The mod's own frustum, looked up once per update (intersects runs per LOD tile). */
		private @Nullable IDhApiCullingFrustum cameraFrustum;

		@Override
		public void update(int worldMinBlockY, int worldMaxBlockY, DhApiMat4f worldViewProjection) {
			cameraFrustum = camera();
			if (!shadowCall && cameraFrustum != null) {
				cameraFrustum.update(worldMinBlockY, worldMaxBlockY, worldViewProjection);
			}
		}

		@Override
		public boolean intersects(int lodBlockPosMinX, int lodBlockPosMinZ, int lodBlockWidth, int lodDetailLevel) {
			if (shadowCall) {
				double dx = Math.max(0.0, Math.max(lodBlockPosMinX - shadowX, shadowX - (lodBlockPosMinX + lodBlockWidth)));
				double dz = Math.max(0.0, Math.max(lodBlockPosMinZ - shadowZ, shadowZ - (lodBlockPosMinZ + lodBlockWidth)));
				return dx * dx + dz * dz <= shadowDistance * shadowDistance;
			}
			IDhApiCullingFrustum camera = cameraFrustum != null ? cameraFrustum : camera();
			return camera == null || camera.intersects(lodBlockPosMinX, lodBlockPosMinZ, lodBlockWidth, lodDetailLevel);
		}
	}

	static @Nullable GpuTextureView depthView() {
		if (!ready) {
			return null;
		}
		DhApiResult<IDhApiBlazeTextureWrapper> result = DhApi.Delayed.renderProxy.getDhDepthTextureBlazeWrapper();
		if (!result.success || result.payload == null || !(result.payload.getWrappedMcObject() instanceof Object[] objects) || objects.length < 2) {
			return null;
		}
		return objects[1] instanceof GpuTextureView view ? view : null;
	}

	static float nearPlane() {
		return near;
	}

	static float farPlane() {
		return far;
	}

	static int chunkRenderDistance() {
		Integer value = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
		return value == null ? 0 : value;
	}

	// The mod's internals, for what its public API does not offer.

	/** Draws the LODs now (the opaque pass, plus the transparent one unless it is deferred). */
	private static void internalRenderLods() {
		ClientApi.INSTANCE.renderLods();
	}

	/** Draws the deferred transparent LODs now. */
	private static void internalRenderDeferredLods() {
		ClientApi.INSTANCE.renderDeferredLodsForShaders();
	}

	/** Overrides the mod's anti-aliasing setting ({@code null}: the user's own value again). */
	private static void internalSetAntiAliasing(@Nullable Boolean enabled) {
		Config.Client.Advanced.Graphics.enableAntiAliasing.setApiValue(enabled, "Aetherium Shaders");
	}
}
