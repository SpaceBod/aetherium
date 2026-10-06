package dev.spacebod.aetherium.client.mixin.shaders.culling;

import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Aetherium Shaders, {@code occlusion.culling=false}: vanilla's section occlusion culling (smart cull) is off. */
@Mixin(Camera.class)
abstract class CameraOcclusionMixin {
	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void aetherium$packOcclusionCulling(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!ShaderPackEngine.get().cullsOcclusion()) {
			cameraState.smartCull = false;
		}
	}
}
