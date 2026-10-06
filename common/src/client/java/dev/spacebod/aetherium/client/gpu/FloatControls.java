package dev.spacebod.aetherium.client.gpu;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Properties;

/**
 * IEEE handling of NaN, infinities and signed zero in pack shaders. Vulkan lets a driver assume floats are never NaN
 * unless a shader asks otherwise (SPIR-V {@code SignedZeroInfNanPreserve}); it then drops checks such as
 * {@code isnan(x)} or {@code x != x}, which OpenGL drivers keep and packs rely on to stop a NaN pixel spreading through
 * their temporal buffers. Pack programs that test for NaN ask for it when the device supports it for 32-bit floats;
 * the others do not, as it slows a program down (Complementary: about 45% more GPU time with it in every program).
 */
public final class FloatControls {
	/** {@code GL_EXT_spirv_intrinsics}: the execution mode (4461) with its capability (4466), for 32-bit floats. */
	public static final String PRESERVE_NAN = """
			#extension GL_EXT_spirv_intrinsics : require
			spirv_execution_mode(extensions = ["SPV_KHR_float_controls"], capabilities = [4466], 4461, 32);
			""";

	private static volatile int supported = -1;
	private static volatile boolean assumed;

	private FloatControls() {
	}

	/** Compile harness only: report support without a device. */
	public static void assumeForCompileCheck() {
		assumed = true;
	}

	/**
	 * Whether pack shaders get IEEE NaN/Inf behaviour: {@code shaders.nan_preserve} on, Vulkan, and the device preserves
	 * them for 32-bit floats.
	 */
	public static boolean preserveNaN() {
		if (assumed) {
			return true;
		}
		if (!dev.spacebod.aetherium.core.Optimisations.isEnabled("shaders.nan_preserve")) {
			return false;
		}
		int known = supported;
		if (known < 0) {
			VulkanDevice device = VulkanAccess.device();
			if (device == null) {
				return false;
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkPhysicalDeviceVulkan12Properties vk12 = VkPhysicalDeviceVulkan12Properties.calloc(stack).sType$Default();
				VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(vk12);
				VK12.vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(), properties);
				known = vk12.shaderSignedZeroInfNanPreserveFloat32() ? 1 : 0;
			}
			supported = known;
		}
		return known == 1;
	}

	/** {@code glsl} (a whole stage, starting with its {@code #version} line) asking for IEEE NaN/Inf behaviour. */
	public static String preservingNaN(String glsl) {
		int line = glsl.indexOf('\n');
		return line < 0 ? glsl : glsl.substring(0, line + 1) + PRESERVE_NAN + glsl.substring(line + 1);
	}
}
