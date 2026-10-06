package dev.spacebod.aetherium.client.mixin.chunks;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.spacebod.aetherium.shaders.chunks.ChunkPipelines;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The chunk renderer's terrain pipelines join the ones pack programs replace ({@link ChunkPipelines}). Its pipeline
 * cache outlives a renderer and is keyed by pass alone, so a pipeline cached for the other chunk mesh format (a pack
 * switched on or off since) is built again.
 */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
abstract class ShaderChunkRendererMixin {
	@Shadow
	@Final
	protected VertexFormat vertexFormat;

	@Redirect(method = "compileProgram", at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 0))
	private Object aetherium$ofThisFormat(Map<?, ?> programs, Object pass) {
		Object cached = programs.get(pass);
		return cached instanceof RenderPipeline pipeline && !vertexFormat.equals(pipeline.getVertexFormatBindings().getFirst()) ? null : cached;
	}

	@Inject(method = "compileProgram", at = @At("RETURN"))
	private void aetherium$created(TerrainRenderPass pass, @Nullable OitStage stage, CallbackInfoReturnable<RenderPipeline> cir) {
		if (stage == null && cir.getReturnValue() != null) {
			ChunkPipelines.created(pass, cir.getReturnValue());
		}
	}
}
