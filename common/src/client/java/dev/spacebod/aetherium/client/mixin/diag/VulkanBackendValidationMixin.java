package dev.spacebod.aetherium.client.mixin.diag;

import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import dev.spacebod.aetherium.client.diag.Diagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * {@link Diagnostics#validationAtLaunch()}: the device is created with the Khronos validation layer (when installed) and
 * its messages logged, as vanilla's {@code --vulkanValidation} argument does.
 */
@Mixin(VulkanBackend.class)
abstract class VulkanBackendValidationMixin {
	@ModifyVariable(method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
			at = @At("HEAD"), argsOnly = true)
	private GpuDebugOptions aetherium$validation(GpuDebugOptions options) {
		if (!Diagnostics.validationAtLaunch() || options.useValidationLayers()) {
			return options;
		}
		return new GpuDebugOptions(Math.max(options.logLevel(), 2), options.synchronousLogs(), options.useLabels(), true);
	}
}
