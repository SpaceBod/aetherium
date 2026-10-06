package dev.spacebod.aetherium.client.mixin.chunks;

import com.llamalad7.mixinextras.sugar.Local;
import dev.spacebod.aetherium.shaders.chunks.ChunkMeshes;
import dev.spacebod.aetherium.shaders.chunks.ChunkVertexData;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code voxelizeLightBlocks=true}: the invisible light block has no model, so the pack's voxelising programs would
 * never see it. Each one is meshed into the cutout layer as a single degenerate quad (all four vertices a quarter block
 * into the block, no colour, no texture, the block's own light as its block and sky light) carrying the block's ID from
 * block.properties, its light emission, and a zero mid-block offset.
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
abstract class LightBlockMeshMixin {
	@Unique
	private static final ThreadLocal<ChunkVertexEncoder.Vertex[]> AETHERIUM$QUAD = ThreadLocal.withInitial(ChunkVertexEncoder.Vertex::uninitializedQuad);

	@Inject(method = "execute", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
	private void aetherium$voxelizeLightBlock(ChunkBuildContext context, CancellationToken token, CallbackInfoReturnable<ChunkBuildOutput> cir,
			@Local BlockState state, @Local(ordinal = 0) BlockPos.MutableBlockPos pos, @Local(ordinal = 1) BlockPos.MutableBlockPos offset,
			@Local ChunkBuildBuffers buffers) {
		if (!(state.getBlock() instanceof LightBlock) || !ChunkMeshes.extended() || !WorldRenderingSettings.INSTANCE.shouldVoxelizeLightBlocks()
				|| WorldRenderingSettings.INSTANCE.getBlockStateIds() == null) {
			return;
		}
		int emission = state.getLightEmission();
		ChunkVertexEncoder.Vertex[] quad = AETHERIUM$QUAD.get();
		for (ChunkVertexEncoder.Vertex vertex : quad) {
			vertex.x = offset.getX() + 0.25F;
			vertex.y = offset.getY() + 0.25F;
			vertex.z = offset.getZ() + 0.25F;
			vertex.color = 0;
			vertex.ao = 1.0F;
			vertex.u = 0.0F;
			vertex.v = 0.0F;
			vertex.light = emission << 4 | emission << 20;
		}
		ChunkVertexData.stamp(quad, ChunkVertexData.entity(state, false), ChunkVertexData.unmeasured(ChunkVertexData.block(pos, state)));
		buffers.get(DefaultMaterials.CUTOUT_MIPPED).getVertexBuffer(ModelQuadFacing.UNASSIGNED).push(quad, DefaultMaterials.CUTOUT_MIPPED);
	}
}
