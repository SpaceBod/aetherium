package dev.spacebod.aetherium.shaders.uniforms;

import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;
import net.minecraft.client.Minecraft;

import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.PER_FRAME;

/**
 * Uniforms describing the current viewport.
 */
public final class ViewportUniforms {
	private ViewportUniforms() {
	}

	public static void addViewportUniforms(UniformHolder uniforms) {
		// The size the world renders at: the window's, or its share under the render scale (the main target carries
		// the scaled size for the world phase). A pack's scale.* directives do not change these. The render target is
		// looked up every time (it can be replaced at runtime).
		uniforms
			.uniform1f(PER_FRAME, "viewHeight", () -> Minecraft.getInstance().gameRenderer.mainRenderTarget().height)
			.uniform1f(PER_FRAME, "viewWidth", () -> Minecraft.getInstance().gameRenderer.mainRenderTarget().width)
			.uniform1f(PER_FRAME, "aspectRatio", ViewportUniforms::getAspectRatio);
	}

	/**
	 * @return width / height of the main render target
	 */
	private static float getAspectRatio() {
		return ((float) Minecraft.getInstance().gameRenderer.mainRenderTarget().width) / ((float) Minecraft.getInstance().gameRenderer.mainRenderTarget().height);
	}
}
