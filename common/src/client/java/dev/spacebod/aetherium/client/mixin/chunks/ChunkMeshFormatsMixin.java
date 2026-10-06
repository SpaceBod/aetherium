package dev.spacebod.aetherium.client.mixin.chunks;

import dev.spacebod.aetherium.shaders.chunks.ChunkMeshes;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The chunk mesh format every reader asks for (builder, renderer, region buffers): ours while a pack is configured. */
@Mixin(value = ChunkMeshFormats.class, remap = false)
abstract class ChunkMeshFormatsMixin {
	@Inject(method = "getCurrent", at = @At("HEAD"), cancellable = true)
	private static void aetherium$format(CallbackInfoReturnable<ChunkVertexType> cir) {
		ChunkVertexType ours = ChunkMeshes.current();
		if (ours != null) {
			cir.setReturnValue(ours);
		}
	}
}
