package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import dev.spacebod.aetherium.shaders.engine.TargetStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Allow shader-pack targets to expose compatible storage image formats. */
@Mixin(VulkanGpuTexture.class)
abstract class VulkanGpuTextureMixin {
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;flags(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"), index = 0)
	private int aetherium$mutablePackTarget(int flags) {
		return TargetStorage.imageFlags(flags);
	}
}
