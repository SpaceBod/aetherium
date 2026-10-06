package dev.spacebod.aetherium.client.gpu;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.spacebod.aetherium.client.mixin.access.FrontendRenderPassAccessor;

/**
 * A viewport smaller than the render pass's targets (vanilla always sets the full size): implemented on vanilla's
 * Vulkan render pass by a mixin. The shader engine uses it for {@code scale.<pass>}.
 */
public interface PassViewport {
	void aetherium$setViewport(float x, float y, float width, float height);

	/** Sets the viewport of {@code pass} when its backend supports it; false otherwise (the full size stays). */
	static boolean set(RenderPass pass, float x, float y, float width, float height) {
		if (pass instanceof FrontendRenderPassAccessor frontend && frontend.aetherium$backend() instanceof PassViewport viewport) {
			viewport.aetherium$setViewport(x, y, width, height);
			return true;
		}
		return false;
	}
}
