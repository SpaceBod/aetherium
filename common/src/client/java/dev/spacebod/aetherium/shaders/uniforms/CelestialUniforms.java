package dev.spacebod.aetherium.shaders.uniforms;

import com.mojang.math.Axis;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;
import dev.spacebod.aetherium.shaders.shadows.ShadowMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EndFlashState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.PER_FRAME;

public final class CelestialUniforms {
	private static final Vector4f ZERO = new Vector4f();
	private final float sunPathRotation;

	public CelestialUniforms(float sunPathRotation) {
		this.sunPathRotation = sunPathRotation;
	}

	public static float getSunAngle(boolean sun) {
		float currentAngle = Minecraft.getInstance().gameRenderer.mainCamera().attributeProbe().getValue(sun ? EnvironmentAttributes.SUN_ANGLE : EnvironmentAttributes.MOON_ANGLE, CapturedRenderingState.INSTANCE.getTickDelta());

		float c = currentAngle + 90.0f;

		if (c < 0) {
			c += 360;
		} else if (c > 360) {
			c -= 360;
		}

		return c;
	}

	private static float getShadowAngle() {
		float shadowAngle = getSunAngle(isDay());

		return shadowAngle / 360.0f;
	}

	private static Vector4f getUpPosition() {
		Vector4f upVector = new Vector4f(0.0F, 100.0F, 0.0F, 0.0F);

		// The celestial model-view is built on the gbuffer model-view.
		Matrix4f preCelestial = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());

		// Vanilla sky rendering's fixed -90 degree Y rotation, without the sky-angle rotation.
		preCelestial.rotate(Axis.YP.rotationDegrees(-90.0F));

		upVector = preCelestial.transform(upVector);

		return upVector;
	}

	public static boolean isDay() {
		// Same source as the sunAngle uniform.
		float sunAngle = CelestialUniforms.getSunAngle(true);
		return sunAngle < 180;
	}

	public void addCelestialUniforms(UniformHolder uniforms) {
		uniforms
			.uniform1f(PER_FRAME, "sunAngle", () -> CelestialUniforms.getSunAngle(true) / 360.0f)
			.uniformTruncated3f(PER_FRAME, "sunPosition", this::getSunPosition)
			.uniformTruncated3f(PER_FRAME, "moonPosition", this::getMoonPosition)
			.uniform1f(PER_FRAME, "shadowAngle", CelestialUniforms::getShadowAngle)
			.uniformTruncated3f(PER_FRAME, "shadowLightPosition", this::getShadowLightPosition)
			.uniformTruncated3f(PER_FRAME, "endFlashPosition", () -> {
				if (Minecraft.getInstance().level.dimension() == Level.END) {
					return getEndFlashPosition();
				} else {
					return ZERO;
				}
			})
			.uniformTruncated3f(PER_FRAME, "upPosition", CelestialUniforms::getUpPosition);
	}

	private Vector4f getSunPosition() {
		return getCelestialPosition(true);
	}

	private Vector4f getMoonPosition() {
		return getCelestialPosition(false);
	}

	private Vector4f getEndFlashPosition() {
		EndFlashState state = Minecraft.getInstance().level.endFlashState();
		if (state == null) return ZERO;

		float h = state.getYAngle(); // yaw around Y
		float g = state.getXAngle();

		Vector4f pos = new Vector4f(0f, 100f, 0f, 0f);

		Matrix4f m = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
		m.rotate(Axis.YP.rotationDegrees(180.0F - h));
		m.rotate(Axis.XP.rotationDegrees(-90.0F - g));
		return m.transform(pos);
	}

	public Vector4f getShadowLightPosition() {
		if (Minecraft.getInstance().level.dimension() == Level.END && ShadowMatrices.endFlashSupported) {
			return getEndFlashPosition();
		}
		return isDay() ? getSunPosition() : getMoonPosition();
	}

	private Vector4f getCelestialPosition(boolean sun) {
		Vector4f position = new Vector4f(0.0F, 100.0f, 0.0F, 1.0F);

		Matrix4f celestial = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());

		// Vanilla sky rendering's transform, reproduced because the result is needed before vanilla applies it.
		celestial.rotate(Axis.YP.rotationDegrees(-90.0F));
		celestial.rotate(Axis.ZP.rotationDegrees(sunPathRotation));
		float currentAngle = Minecraft.getInstance().gameRenderer.mainCamera().attributeProbe().getValue(sun ? EnvironmentAttributes.SUN_ANGLE : EnvironmentAttributes.MOON_ANGLE, CapturedRenderingState.INSTANCE.getTickDelta());

		celestial.rotate(Axis.XP.rotationDegrees(currentAngle));
		position = celestial.transform(position);

		return position;
	}
}
