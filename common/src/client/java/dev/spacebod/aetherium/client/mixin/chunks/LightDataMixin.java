package dev.spacebod.aetherium.client.mixin.chunks;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import net.caffeinemc.mods.sodium.client.model.light.data.LightDataAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code const float ambientOcclusionLevel}: how much of the game's ambient occlusion the pack keeps (0 = none, 1 = all).
 * Applied to each block's shade brightness as the chunk renderer's light cache reads it.
 */
@Mixin(value = LightDataAccess.class, remap = false)
abstract class LightDataMixin {
	@ModifyExpressionValue(method = "compute", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;getShadeBrightness(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"))
	private float aetherium$ambientOcclusionLevel(float brightness) {
		float level = WorldRenderingSettings.INSTANCE.getAmbientOcclusionLevel();
		return level == 1.0f ? brightness : 1.0f - level * (1.0f - brightness);
	}
}
