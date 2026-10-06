package dev.spacebod.aetherium.dev.mixin;

import dev.spacebod.aetherium.dev.bench.BenchCounters;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts section compiles and the worker time they take (runs on the section-compile worker threads). */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
abstract class CompileTaskMixin {
	@Inject(method = "doTask", at = @At("HEAD"))
	private void aetherium$start(CallbackInfoReturnable<?> cir) {
		BenchCounters.taskStart();
	}

	@Inject(method = "doTask", at = @At("RETURN"))
	private void aetherium$end(CallbackInfoReturnable<?> cir) {
		BenchCounters.COMPILE_NANOS.add(BenchCounters.taskElapsed());
		if (cir.getReturnValue() instanceof Enum<?> result && "CANCELLED".equals(result.name())) {
			BenchCounters.SECTIONS_CANCELLED.increment();
		} else {
			net.minecraft.core.BlockPos origin = ((SectionRenderDispatcher.RenderSection.SectionTask) (Object) this).getRenderOrigin();
			BenchCounters.compiled(net.minecraft.core.SectionPos.asLong(origin.getX() >> 4, origin.getY() >> 4, origin.getZ() >> 4));
		}
	}
}
