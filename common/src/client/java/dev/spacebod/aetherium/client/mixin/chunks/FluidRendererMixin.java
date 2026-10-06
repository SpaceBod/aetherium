package dev.spacebod.aetherium.client.mixin.chunks;

import com.llamalad7.mixinextras.sugar.Local;
import dev.spacebod.aetherium.shaders.chunks.ChunkMeshes;
import dev.spacebod.aetherium.shaders.chunks.ChunkVertexData;
import dev.spacebod.aetherium.shaders.chunks.PackLayers;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fluids go to the terrain layer the pack gives their block. Their quads carry the fluid's own block (its legacy block
 * state, which is what packs' block IDs name) and that block's light emission, not the block sharing its position: a
 * waterlogged stair is meshed twice, and these quads are the water's.
 */
@Mixin(value = DefaultFluidRenderer.class, remap = false)
abstract class FluidRendererMixin {
	@Unique
	private int aetherium$entity;
	@Unique
	private int aetherium$block;

	@Inject(method = "render", at = @At("HEAD"))
	private void aetherium$fluid(LevelSlice level, BlockState blockState, FluidState fluidState, BlockPos blockPos, BlockPos offset,
			TranslucentGeometryCollector collector, ChunkModelBuilder meshBuilder, Material material, ColorProvider<FluidState> colorProvider,
			FluidModel sprites, CallbackInfo ci) {
		if (!ChunkMeshes.extended()) {
			return;
		}
		BlockState fluid = fluidState == null ? null : fluidState.createLegacyBlock();
		aetherium$entity = ChunkVertexData.entity(fluid, true);
		aetherium$block = ChunkVertexData.block(blockPos, fluid);
	}

	/** The terrain layer the pack's {@code layer.*} entries give the fluid's block, in place of the fluid's own. */
	@ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
	private Material aetherium$packLayer(Material material, @Local(argsOnly = true) FluidState fluidState) {
		ChunkSectionLayer layer = fluidState == null ? null : PackLayers.of(fluidState.createLegacyBlock().getBlock());
		return layer == null ? material : DefaultMaterials.forChunkLayer(layer);
	}

	/**
	 * A fluid's side faces get the game's fixed directional shade; while the pack lights faces itself
	 * ({@code oldLighting=false}) they are lit like the top, as block faces are.
	 */
	@ModifyArg(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/DefaultFluidRenderer;updateQuad(Lnet/caffeinemc/mods/sodium/client/model/quad/ModelQuadViewMutable;Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;Lnet/minecraft/core/BlockPos;Lnet/caffeinemc/mods/sodium/client/model/light/LightPipeline;Lnet/minecraft/core/Direction;Lnet/caffeinemc/mods/sodium/client/model/quad/properties/ModelQuadFacing;FLnet/caffeinemc/mods/sodium/client/model/color/ColorProvider;Lnet/minecraft/world/level/material/FluidState;)V",
			ordinal = 2), index = 6)
	private float aetherium$sideShading(float brightness) {
		return WorldRenderingSettings.INSTANCE.shouldDisableDirectionalShading() ? 1.0f : brightness;
	}

	@ModifyArg(method = "writeQuad", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;)V"),
			index = 0)
	private ChunkVertexEncoder.Vertex[] aetherium$stamp(ChunkVertexEncoder.Vertex[] vertices) {
		return ChunkMeshes.extended() ? ChunkVertexData.stamp(vertices, aetherium$entity, aetherium$block) : vertices;
	}

	@ModifyArg(method = "writeQuad", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;appendQuad([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;Lnet/caffeinemc/mods/sodium/client/model/quad/properties/ModelQuadFacing;I)Z"),
			index = 0)
	private ChunkVertexEncoder.Vertex[] aetherium$stampSorted(ChunkVertexEncoder.Vertex[] vertices) {
		return ChunkMeshes.extended() ? ChunkVertexData.stamp(vertices, aetherium$entity, aetherium$block) : vertices;
	}
}
