package dev.spacebod.aetherium.client.mixin.shaders;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Aetherium Shaders, {@code const float ambientOcclusionLevel}: how much of vanilla's ambient occlusion the pack keeps
 * (0 = none, 1 = vanilla's). Applied to each neighbour's shade brightness as the block lighter reads it; its cache
 * keeps vanilla's values.
 */
@Mixin(targets = "net.minecraft.client.renderer.block.BlockModelLighter$Cache")
abstract class BlockShadeBrightnessMixin {
	@ModifyReturnValue(method = "getShadeBrightness", at = @At("RETURN"))
	private float aetherium$ambientOcclusionLevel(float brightness) {
		float level = WorldRenderingSettings.INSTANCE.getAmbientOcclusionLevel();
		return level == 1.0f ? brightness : 1.0f - level * (1.0f - brightness);
	}
}
