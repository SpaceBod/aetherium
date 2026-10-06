package dev.spacebod.aetherium.client.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.spacebod.aetherium.client.mixin.access.FrontendGpuDeviceAccessor;
import org.jspecify.annotations.Nullable;

/** The Vulkan backend objects behind vanilla's renderer API, or null on OpenGL. */
public final class VulkanAccess {
	private VulkanAccess() {
	}

	public static @Nullable VulkanDevice device() {
		GpuDevice device = RenderSystem.getDevice();
		if (device instanceof FrontendGpuDeviceAccessor frontend) {
			GpuDeviceBackend backend = frontend.aetherium$backend();
			if (backend instanceof VulkanDevice vulkan) {
				return vulkan;
			}
		}
		return null;
	}

	/** The frame's command encoder; vanilla's device creates one encoder and hands out frontends over it. */
	public static @Nullable VulkanCommandEncoder encoder() {
		if (RenderSystem.getDevice().createCommandEncoder() instanceof FrontendCommandEncoder frontend
				&& frontend.backend() instanceof VulkanCommandEncoder vulkan) {
			return vulkan;
		}
		return null;
	}
}
