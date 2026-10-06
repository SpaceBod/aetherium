package dev.spacebod.aetherium.shaders.shadows;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EndFlashState;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

/** The shadow map's projection and model-view matrices, as the pack format defines them. */
public class ShadowMatrices {
	/** Whether the active pack supports the End flash; set by the engine. */
	public static volatile boolean endFlashSupported;

	public static final float NEAR = -100.05f;
	public static final float FAR = 156.0f;

	/**
	 * The orthographic shadow projection in OpenGL's convention (z from -1 to 1), whatever the device's: packs read it as
	 * {@code shadowProjection} and do OpenGL depth maths with it, and the shadow pass converts it to the device's form
	 * when it binds it.
	 */
	public static Matrix4f createOrthoMatrix(float halfPlaneLength, float nearPlane, float farPlane) {
		return new Matrix4f().setOrthoSymmetric(halfPlaneLength * 2, halfPlaneLength * 2, nearPlane, farPlane, false);
	}

	/** The perspective shadow projection of packs that set {@code shadowMapFov}, in OpenGL's convention like the orthographic one. */
	public static Matrix4f createPerspectiveMatrix(float fov) {
		float yScale = (float) (1.0f / Math.tan(Math.toRadians(fov) * 0.5f));
		return new Matrix4f(
			// column 1
			yScale, 0f, 0f, 0f,
			// column 2
			0f, yScale, 0f, 0f,
			// column 3
			0f, 0f, (FAR + NEAR) / (NEAR - FAR), -1.0F,
			// column 4
			0f, 0f, 2.0F * FAR * NEAR / (NEAR - FAR), 1f
		);
	}

	public static void createBaselineModelViewMatrix(PoseStack target, float shadowAngle, float sunPathRotation) {
		target.last().normal().identity();
		target.last().pose().identity();

		if (Minecraft.getInstance().level.dimension() == Level.END && ShadowMatrices.endFlashSupported) {

			EndFlashState state = Minecraft.getInstance().level.endFlashState();
			float skyAngle = state.getXAngle();

			float h = state.getYAngle();

			target.last().pose().rotate(Axis.XP.rotationDegrees(0.0F - skyAngle));

			target.last().pose().rotate(Axis.YP.rotationDegrees(h));
		} else {
			float skyAngle;

			if (shadowAngle < 0.25f) {
				skyAngle = shadowAngle + 0.75f;
			} else {
				skyAngle = shadowAngle - 0.25f;
			}

			target.last().pose().rotate(Axis.XP.rotationDegrees(90.0F));
			target.last().pose().rotate(Axis.ZP.rotationDegrees(skyAngle * -360.0f));
			target.last().pose().rotate(Axis.XP.rotationDegrees(sunPathRotation));
		}
	}

	public static void snapModelViewToGrid(PoseStack target, float shadowIntervalSize, double cameraX, double cameraY, double cameraZ) {
		if (Math.abs(shadowIntervalSize) == 0.0F) {
			// A zero interval (grid cell size) means no snapping; also avoids a division by zero.
			return;
		}

		// Offset within the current grid cell, in (-shadowIntervalSize, shadowIntervalSize): Java's % keeps the
		// dividend's sign (-2.0f % 32.0f == -2.0f), so negative coordinates give negative offsets. Packs are written
		// against exactly this snapping, so it is kept as it is.
		float offsetX = (float) cameraX % shadowIntervalSize;
		float offsetY = (float) cameraY % shadowIntervalSize;
		float offsetZ = (float) cameraZ % shadowIntervalSize;

		float halfIntervalSize = shadowIntervalSize / 2.0f;

		// Shift by half a cell to snap to the cell centre. With the negative case above the result lies in
		// (-3*shadowIntervalSize/2, shadowIntervalSize/2).
		offsetX -= halfIntervalSize;
		offsetY -= halfIntervalSize;
		offsetZ -= halfIntervalSize;

		target.last().pose().translate(offsetX, offsetY, offsetZ);
	}

	public static void createModelViewMatrix(PoseStack target, float shadowAngle, float shadowIntervalSize,
											 float sunPathRotation, double cameraX, double cameraY, double cameraZ) {
		createBaselineModelViewMatrix(target, shadowAngle, sunPathRotation);
		snapModelViewToGrid(target, shadowIntervalSize, cameraX, cameraY, cameraZ);
	}
}
