package dev.spacebod.aetherium.dev.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import dev.spacebod.aetherium.dev.bench.BenchDriver;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Times every frame-graph pass by wrapping the inspector vanilla passes to {@code FrameGraphBuilder.execute}. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@ModifyArg(method = "render", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"),
			index = 1)
	private FrameGraphBuilder.Inspector aetherium$timePasses(FrameGraphBuilder.Inspector inspector) {
		BenchDriver driver = BenchDriver.active();
		return driver == null ? inspector : driver.wrap(inspector);
	}
}
