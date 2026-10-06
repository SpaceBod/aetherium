package dev.spacebod.aetherium.client.mixin.shaders;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import dev.spacebod.aetherium.shaders.engine.ShadowSampling;
import dev.spacebod.aetherium.shaders.pbr.PbrTextures;
import java.util.OptionalDouble;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Aetherium Shaders: sampler states vanilla's sampler API cannot describe, asked for through a {@code maxLod} value no
 * vanilla sampler uses:
 * <ul>
 *   <li>{@link PbrTextures#NEAREST_MIPS_LOD}: nearest mip selection with mip levels (material maps whose values are
 *       codes, never blended);</li>
 *   <li>{@link ShadowSampling#COMPARE_LOD}: a depth-comparison sampler, LEQUAL (1 where the reference is at or in front
 *       of the stored depth), base level only, for {@code sampler2DShadow} lookups on the shadow depth maps.</li>
 * </ul>
 */
@Mixin(VulkanGpuSampler.class)
abstract class VulkanGpuSamplerMixin {
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;mipmapMode(I)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"))
	private int aetherium$nearestMips(int mode, @Local(argsOnly = true) OptionalDouble maxLod) {
		return maxLod.isPresent() && (maxLod.getAsDouble() == PbrTextures.NEAREST_MIPS_LOD || maxLod.getAsDouble() == ShadowSampling.COMPARE_LOD) ? 0 : mode;
	}

	@WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;maxLod(F)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"))
	private VkSamplerCreateInfo aetherium$compare(VkSamplerCreateInfo info, float lod, Operation<VkSamplerCreateInfo> original,
			@Local(argsOnly = true) OptionalDouble maxLod) {
		if (maxLod.isPresent() && maxLod.getAsDouble() == ShadowSampling.COMPARE_LOD) {
			return original.call(info, 0.0F).compareEnable(true).compareOp(VK10.VK_COMPARE_OP_LESS_OR_EQUAL);
		}
		return original.call(info, lod);
	}
}
