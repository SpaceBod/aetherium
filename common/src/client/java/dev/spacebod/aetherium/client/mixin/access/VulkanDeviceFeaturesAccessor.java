package dev.spacebod.aetherium.client.mixin.access;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The device features vanilla enabled when it created its Vulkan device. */
@Mixin(VulkanDevice.class)
public interface VulkanDeviceFeaturesAccessor {
	@Accessor("enabledFeatures")
	FeatureSet aetherium$enabledFeatures();
}
