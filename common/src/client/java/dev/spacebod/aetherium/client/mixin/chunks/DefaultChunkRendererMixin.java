package dev.spacebod.aetherium.client.mixin.chunks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.spacebod.aetherium.shaders.chunks.ChunkShadows;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The shadow map's draw lists ({@link ChunkShadows}) keep every face: block face culling keeps the faces turned towards
 * the camera, and the light sees others.
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
abstract class DefaultChunkRendererMixin {
	@WrapOperation(method = "prepare", at = @At(value = "FIELD",
			target = "Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;useBlockFaceCulling:Z"))
	private boolean aetherium$allFacesForShadows(SodiumOptions.PerformanceSettings settings, Operation<Boolean> original) {
		return !ChunkShadows.drawing() && original.call(settings);
	}
}
