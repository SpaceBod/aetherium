package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.shaders.uniforms.CapturedRenderingState;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders: {@code cloudTime}, how far vanilla's clouds have drifted (in blocks), computed as the cloud renderer
 * does for its default 256-cell texture (the offset wraps once per texture width).
 */
@Mixin(CloudRenderer.class)
abstract class CloudTimeMixin {
	@Unique
	private static final long WRAP = 256L * 400L;

	@Inject(method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V", at = @At("HEAD"))
	private void aetherium$cloudTime(int color, CloudStatus cloudStatus, float bottomY, int range, Vec3 cameraPosition, long gameTime, float partialTicks,
			CallbackInfo ci) {
		CapturedRenderingState.INSTANCE.setCloudTime(((float) (gameTime % WRAP) + partialTicks) * 0.030000001F);
	}
}
