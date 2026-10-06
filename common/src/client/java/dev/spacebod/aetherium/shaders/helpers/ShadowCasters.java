package dev.spacebod.aetherium.shaders.helpers;

import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Entities only the shadow map draws: the shadow pass replays the camera's entity draws, so entities the camera does
 * not see (outside its view, hidden behind terrain, the player itself in first person) are extracted as well, within
 * the pack's entity shadow distance. Their draws are tagged {@link DrawIds#SHADOW_ONLY}: the gbuffer passes skip them.
 * Render thread only.
 */
public final class ShadowCasters {
	private static final Set<EntityRenderState> SHADOW_ONLY = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Entities vanilla extracted for the camera this frame. */
	private static final IntOpenHashSet EXTRACTED = new IntOpenHashSet();

	private ShadowCasters() {
	}

	/** Entity extraction starts. */
	public static void beginFrame() {
		SHADOW_ONLY.clear();
		EXTRACTED.clear();
	}

	/** Vanilla extracted {@code entity} for the camera. */
	public static void extracted(Entity entity) {
		EXTRACTED.add(entity.getId());
	}

	/** Shadow-only casters this frame. */
	public static int count() {
		return SHADOW_ONLY.size();
	}

	public static boolean isShadowOnly(EntityRenderState state) {
		return !SHADOW_ONLY.isEmpty() && SHADOW_ONLY.contains(state);
	}

	/** After vanilla's extraction: the shadow-only casters, appended to the frame's entity states. */
	public static void extractCasters(ClientLevel level, Camera camera, DeltaTracker deltaTracker, EntityRenderDispatcher dispatcher,
			LevelRenderState output) {
		ShaderPackEngine engine = ShaderPackEngine.get();
		double distance = engine.shadowCasterEntityDistance();
		if (distance <= 0.0) {
			return;
		}
		boolean playerOnly = !engine.shadowsDrawEntities();
		Vec3 cameraPos = camera.position();
		Frustum everything = new Frustum(camera.getCullFrustum());
		((AcceptAllFrustum) everything).aetherium$acceptAll();
		double maxSq = distance * distance;
		for (Entity entity : level.entitiesForRendering()) {
			boolean cameraEntity = entity == camera.entity();
			if (EXTRACTED.contains(entity.getId()) || playerOnly && !cameraEntity || entity instanceof LocalPlayer && !cameraEntity
					|| entity.distanceToSqr(cameraPos) > maxSq) {
				continue;
			}
			float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
			if (!cameraEntity && !dispatcher.shouldRender(entity, everything, cameraPos.x, cameraPos.y, cameraPos.z, partialTicks)) {
				continue;
			}
			EntityRenderState state = dispatcher.extractEntity(entity, partialTicks);
			// Nothing of it reaches the screen: no outline, no name tag.
			state.outlineColor = 0;
			state.nameTag = null;
			state.scoreText = null;
			SHADOW_ONLY.add(state);
			output.entityRenderStates.add(state);
		}
	}
}
