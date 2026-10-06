package dev.spacebod.aetherium.shaders.uniforms;

import dev.spacebod.aetherium.shaders.engine.VanillaWorldState;
import dev.spacebod.aetherium.shaders.gl.state.FogMode;
import dev.spacebod.aetherium.shaders.gl.state.StateUpdateNotifiers;
import dev.spacebod.aetherium.shaders.gl.uniform.DynamicUniformHolder;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.PER_FRAME;

public class FogUniforms {
	private FogUniforms() {
	}

	public static void addFogUniforms(DynamicUniformHolder uniforms, FogMode fogMode) {
		if (fogMode == FogMode.OFF) {
			uniforms.uniform1i(UniformUpdateFrequency.ONCE, "fogMode", () -> 0);
			uniforms.uniform1i(UniformUpdateFrequency.ONCE, "fogShape", () -> -1);
		} else if (fogMode == FogMode.PER_VERTEX || fogMode == FogMode.PER_FRAGMENT) {
			uniforms.uniform1i("fogMode", () -> {
				float fogDensity = CapturedRenderingState.INSTANCE.getFogDensity();

				if (fogDensity < 0.0F) {
					return GL11.GL_LINEAR;
				} else {
					return GL11.GL_EXP2;
				}
			}, listener -> {
			});

			// Stable encoding independent of vanilla's enum order: 0 = spherical, 1 = cylindrical.
			uniforms.uniform1i(PER_FRAME, "fogShape", () -> 1);
		}

		uniforms.uniform1f("fogDensity", () -> Math.max(0.0F, CapturedRenderingState.INSTANCE.getFogDensity()), notifier -> {
		});

		uniforms.uniform1f("fogStart", () -> VanillaWorldState.fog().environmentalStart, listener -> StateUpdateNotifiers.fogStartNotifier.setListener(listener));

		uniforms.uniform1f("fogEnd", () -> VanillaWorldState.fog().environmentalEnd, listener -> StateUpdateNotifiers.fogEndNotifier.setListener(listener));

		// The fog colour is captured once at the frame start.
		Vector3f fogColor = new Vector3f();
		uniforms.uniform3f(PER_FRAME, "fogColor", () -> {
			Vector3d colour = CapturedRenderingState.INSTANCE.getFogColor();
			return fogColor.set((float) colour.x, (float) colour.y, (float) colour.z);
		});
	}
}
