package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import dev.spacebod.aetherium.shaders.engine.TargetStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Aetherium Shaders: storage usage on pack targets bound as images ({@link TargetStorage}); vanilla's API has none. */
@Mixin(VulkanConst.class)
abstract class VulkanConstMixin {
	@Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true)
	private static void aetherium$targetStorage(int usage, GpuFormat format, CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(TargetStorage.adjust(cir.getReturnValue(), format));
	}
}
