package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.spacebod.aetherium.shaders.engine.TerrainVertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Aetherium Shaders: the block snippet every block pipeline builds on (vanilla's {@code GENERIC_BLOCKS_SNIPPET}, the
 * first vertex binding in {@code RenderPipelines}) uses the extended block format, so the block geometry vanilla still
 * builds (moving pistons, falling blocks) carries what packs read whenever a pack is switched on. Vanilla's own shaders
 * read their elements from it at the usual offsets.
 */
@Mixin(RenderPipelines.class)
abstract class RenderPipelinesMixin {
	@ModifyArg(method = "<clinit>", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;withVertexBinding(ILcom/mojang/renderpearl/api/vertex/VertexFormat;)Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;",
			ordinal = 0), index = 1)
	private static VertexFormat aetherium$terrainFormat(VertexFormat vanilla) {
		return TerrainVertexFormat.EXTENDED;
	}
}
