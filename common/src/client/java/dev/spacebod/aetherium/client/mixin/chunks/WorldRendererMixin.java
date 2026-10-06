package dev.spacebod.aetherium.client.mixin.chunks;

import dev.spacebod.aetherium.shaders.chunks.ChunkMeshes;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.joml.FrustumIntersection;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The chunk renderer's world renderer:
 * <ul>
 *   <li>the chunk mesh format is settled whenever it builds its renderer ({@link ChunkMeshes#settle});</li>
 *   <li>{@code frustum.culling=false}: the terrain walk keeps every section, in view or not.</li>
 * </ul>
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
abstract class WorldRendererMixin {
	@Unique
	private static final Frustum AETHERIUM$EVERYTHING = new Frustum() {
		@Override
		public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			return true;
		}

		@Override
		public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			return FrustumIntersection.INSIDE;
		}

		@Override
		public boolean testSection(float x, float y, float z) {
			return true;
		}

		@Override
		public boolean testSectionExpanded(float x, float y, float z, float extend) {
			return true;
		}
	};

	@Inject(method = "initRenderer", at = @At("HEAD"))
	private void aetherium$settleFormat(CallbackInfo ci) {
		ChunkMeshes.settle();
	}

	@ModifyVariable(method = "setupTerrain", at = @At("HEAD"), argsOnly = true)
	private Viewport aetherium$packFrustumCulling(Viewport viewport) {
		if (ShaderPackEngine.get().cullsFrustum()) {
			return viewport;
		}
		CameraTransform at = viewport.getTransform();
		return new Viewport(AETHERIUM$EVERYTHING, new Vector3d(at.x, at.y, at.z));
	}
}
