package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders, {@code separateAo}: the pack wants a block quad's brightness (ambient occlusion times face shading)
 * in the vertex alpha and an unshaded colour, so it can treat occlusion itself. Vanilla has just written the brightness
 * as a grey colour; the tint is multiplied in afterwards and works on the white RGB as before.
 */
@Mixin(BlockModelLighter.class)
abstract class BlockModelLighterMixin {
	@Inject(method = "prepareQuadAmbientOcclusion", at = @At("RETURN"))
	private void aetherium$separateAo(BlockAndTintGetter level, BlockState state, BlockPos centerPosition, BakedQuad quad, QuadInstance outputInstance,
			CallbackInfo ci) {
		aetherium$brightnessToAlpha(outputInstance);
	}

	@Inject(method = "prepareQuadFlat", at = @At("RETURN"))
	private void aetherium$separateAoFlat(BlockAndTintGetter level, BlockState state, BlockPos pos, int lightCoords, BakedQuad quad, QuadInstance outputInstance,
			CallbackInfo ci) {
		aetherium$brightnessToAlpha(outputInstance);
	}

	@Unique
	private static void aetherium$brightnessToAlpha(QuadInstance instance) {
		if (!WorldRenderingSettings.INSTANCE.shouldUseSeparateAo()) {
			return;
		}
		for (int v = 0; v < 4; v++) {
			int brightness = instance.getColor(v) >>> 16 & 0xFF;
			instance.setColor(v, brightness << 24 | 0xFFFFFF);
		}
	}
}
