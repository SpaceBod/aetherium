package dev.spacebod.aetherium.client.mixin.chunks;

import dev.spacebod.aetherium.shaders.chunks.ChunkMeshes;
import dev.spacebod.aetherium.shaders.chunks.ChunkVertexData;
import dev.spacebod.aetherium.shaders.chunks.PackLayers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Every block-model quad goes to the terrain layer the pack gives its block, and carries its block
 * ({@link ChunkVertexData}) on both of its ways out: straight into the mesh, or into the translucency sorter. Worked out once per block, not per quad (the context reuses one position object,
 * so it is compared by value).
 */
@Mixin(value = BlockRenderer.class, remap = false)
abstract class BlockRendererMixin extends AbstractBlockRenderContext {
	@Unique
	private BlockState aetherium$state;
	@Unique
	private long aetherium$at = Long.MIN_VALUE;
	@Unique
	private int aetherium$entity;
	@Unique
	private int aetherium$block;

	@Unique
	private BlockState aetherium$layerState;
	@Unique
	private @Nullable ChunkSectionLayer aetherium$layer;

	/** The terrain layer the pack's {@code layer.*} entries give the block, in place of the one its model asks for. */
	@ModifyArg(method = "processQuad", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/DefaultMaterials;forChunkLayer(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;"))
	private ChunkSectionLayer aetherium$packLayer(ChunkSectionLayer layer) {
		if (state != aetherium$layerState) {
			aetherium$layerState = state;
			aetherium$layer = state == null ? null : PackLayers.of(state.getBlock());
		}
		return aetherium$layer != null ? aetherium$layer : layer;
	}

	@ModifyArg(method = "bufferQuad", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V"),
			index = 0)
	private ChunkVertexEncoder.Vertex[] aetherium$stamp(ChunkVertexEncoder.Vertex[] vertices) {
		return aetherium$stampBlock(vertices);
	}

	@ModifyArg(method = "bufferQuad", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;appendQuad([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;Lnet/caffeinemc/mods/sodium/client/model/quad/properties/ModelQuadFacing;I)Z"),
			index = 0)
	private ChunkVertexEncoder.Vertex[] aetherium$stampSorted(ChunkVertexEncoder.Vertex[] vertices) {
		return aetherium$stampBlock(vertices);
	}

	@Unique
	private ChunkVertexEncoder.Vertex[] aetherium$stampBlock(ChunkVertexEncoder.Vertex[] vertices) {
		if (!ChunkMeshes.extended()) {
			return vertices;
		}
		long at = pos == null ? Long.MIN_VALUE : pos.asLong();
		if (state != aetherium$state || at != aetherium$at) {
			aetherium$state = state;
			aetherium$at = at;
			aetherium$entity = ChunkVertexData.entity(state, false);
			aetherium$block = ChunkVertexData.block(pos, state);
		}
		return ChunkVertexData.stamp(vertices, aetherium$entity, aetherium$block);
	}
}
