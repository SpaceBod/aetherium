package dev.spacebod.aetherium.client.mixin.chunks;

import dev.spacebod.aetherium.shaders.chunks.ChunkVertexData;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The block a mesh vertex belongs to ({@link ChunkVertexData}), copied along with the vertex's own fields. */
@Mixin(value = ChunkVertexEncoder.Vertex.class, remap = false)
abstract class ChunkVertexMixin implements ChunkVertexData {
	@Unique
	private int aetherium$entity = 0xFFFF;
	@Unique
	private int aetherium$block;

	@Override
	public int aetherium$entity() {
		return aetherium$entity;
	}

	@Override
	public int aetherium$block() {
		return aetherium$block;
	}

	@Override
	public void aetherium$set(int entity, int block) {
		aetherium$entity = entity;
		aetherium$block = block;
	}

	@Inject(method = "copyVertexTo", at = @At("TAIL"))
	private static void aetherium$copy(ChunkVertexEncoder.Vertex from, ChunkVertexEncoder.Vertex to, CallbackInfo ci) {
		ChunkVertexData source = (ChunkVertexData) from;
		((ChunkVertexData) to).aetherium$set(source.aetherium$entity(), source.aetherium$block());
	}
}
