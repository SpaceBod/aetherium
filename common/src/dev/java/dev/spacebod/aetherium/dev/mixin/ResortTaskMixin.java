package dev.spacebod.aetherium.dev.mixin;

import dev.spacebod.aetherium.dev.bench.BenchCounters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts translucent-geometry resorts and their worker time. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$ResortTransparencyTask")
abstract class ResortTaskMixin {
	@Inject(method = "doTask", at = @At("HEAD"))
	private void aetherium$start(CallbackInfoReturnable<?> cir) {
		BenchCounters.taskStart();
	}

	@Inject(method = "doTask", at = @At("RETURN"))
	private void aetherium$end(CallbackInfoReturnable<?> cir) {
		BenchCounters.RESORT_NANOS.add(BenchCounters.taskElapsed());
		BenchCounters.RESORTS.increment();
	}
}
