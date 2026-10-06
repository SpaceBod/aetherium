package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.spacebod.aetherium.client.gpu.DeviceTraits;
import dev.spacebod.aetherium.client.gpu.StorageFeatures;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Asks for the shader-pack device features (each its own optional set, see {@link StorageFeatures}), geometry shaders
 * and, on a portability-subset device, comparison samplers ({@link DeviceTraits#COMPARISON_SAMPLERS}) when vanilla
 * creates its Vulkan device.
 */
@Mixin(VulkanFeatureSets.class)
abstract class VulkanFeatureSetsMixin {
	@Inject(method = "optionalFeatureSets", at = @At("RETURN"))
	private static void aetherium$storageFeatures(CallbackInfoReturnable<Set<FeatureSet>> cir) {
		cir.getReturnValue().addAll(StorageFeatures.ALL);
		cir.getReturnValue().add(dev.spacebod.aetherium.client.gpu.GeometryStages.FEATURE);
		cir.getReturnValue().add(DeviceTraits.COMPARISON_SAMPLERS);
	}
}
