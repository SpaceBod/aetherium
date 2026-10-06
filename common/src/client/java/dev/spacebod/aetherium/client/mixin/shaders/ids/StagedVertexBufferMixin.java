package dev.spacebod.aetherium.client.mixin.shaders.ids;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.spacebod.aetherium.shaders.engine.EntityVertexFormats;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Aetherium Shaders: while a pack draws the world, entity and world-text draws are built in the extended formats
 * ({@link EntityVertexFormats}): ids, mid-texture coordinate, tangent (and text normals) per vertex.
 */
@Mixin(StagedVertexBuffer.class)
abstract class StagedVertexBufferMixin {
	@ModifyVariable(method = "appendDraw(Lcom/mojang/renderpearl/api/vertex/VertexFormat;Lcom/mojang/renderpearl/api/pipeline/PrimitiveTopology;Lcom/mojang/blaze3d/vertex/VertexSorting;)Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;",
			at = @At("HEAD"), argsOnly = true)
	private VertexFormat aetherium$extendedFormat(VertexFormat format) {
		return EntityVertexFormats.extend(format);
	}
}
