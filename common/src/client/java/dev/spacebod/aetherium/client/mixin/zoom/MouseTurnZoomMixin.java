package dev.spacebod.aetherium.client.mixin.zoom;

import dev.spacebod.aetherium.client.zoom.Zoom;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While zoomed, the mouse movement gathered since the last frame turns the camera less ({@link Zoom#turnFactor}). */
@Mixin(MouseHandler.class)
abstract class MouseTurnZoomMixin {
	@Shadow
	private double accumulatedDX;
	@Shadow
	private double accumulatedDY;

	@Inject(method = "turnPlayer", at = @At("HEAD"))
	private void aetherium$zoomSensitivity(CallbackInfo ci) {
		double factor = Zoom.turnFactor();
		if (factor != 1.0) {
			accumulatedDX *= factor;
			accumulatedDY *= factor;
		}
	}
}
