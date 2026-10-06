package dev.spacebod.aetherium.client.mixin.zoom;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.spacebod.aetherium.client.zoom.Zoom;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The zoom narrows the field of view ({@link Zoom}). */
@Mixin(Camera.class)
abstract class CameraZoomMixin {
	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
	private float aetherium$zoom(float fov) {
		return fov * Zoom.fovMultiplier();
	}
}
