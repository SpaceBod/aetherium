package dev.spacebod.aetherium.client.mixin.shaders.census;

import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import dev.spacebod.aetherium.client.gpu.FrameCensus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The F3 census ({@link FrameCensus}): one queue submit per closed submission. */
@Mixin(VulkanQueue.Submission.class)
abstract class VulkanQueueSubmissionCensusMixin {
	@Inject(method = "close", at = @At("HEAD"))
	private void aetherium$countSubmit(CallbackInfo ci) {
		FrameCensus.submits++;
	}
}
