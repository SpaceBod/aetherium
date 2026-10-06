package dev.spacebod.aetherium.shaders.uniforms;

import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;
import dev.spacebod.aetherium.shaders.helpers.JomlConversions;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.ONCE;
import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.PER_FRAME;

public class CameraUniforms {
	private static final Minecraft client = Minecraft.getInstance();

	private CameraUniforms() {
	}

	public static void addCameraUniforms(UniformHolder uniforms, FrameUpdateNotifier notifier) {
		CameraPositionTracker tracker = new CameraPositionTracker(notifier);

		uniforms
			.uniform1f(ONCE, "near", () -> 0.05)
			.uniform1f(PER_FRAME, "far", CameraUniforms::getRenderDistanceInBlocks)
			.uniform3d(PER_FRAME, "cameraPosition", tracker::getCurrentCameraPosition)
			.uniform1f(PER_FRAME, "eyeAltitude", tracker::getCurrentCameraPositionY)
			.uniform3d(PER_FRAME, "previousCameraPosition", tracker::getPreviousCameraPosition)
			.uniform3i(PER_FRAME, "cameraPositionInt", () -> getCameraPositionInt(getUnshiftedCameraPosition()))
			.uniform3f(PER_FRAME, "cameraPositionFract", () -> getCameraPositionFract(getUnshiftedCameraPosition()))
			.uniform3i(PER_FRAME, "previousCameraPositionInt", () -> getCameraPositionInt(tracker.getPreviousCameraPositionUnshifted()))
			.uniform3f(PER_FRAME, "previousCameraPositionFract", () -> getCameraPositionFract(tracker.getPreviousCameraPositionUnshifted()));
	}

	/** {@code far}: the render distance in blocks (not the projection's far plane). */
	private static int getRenderDistanceInBlocks() {
		return client.options.getEffectiveRenderDistance() * 16;
	}

	public static Vector3d getUnshiftedCameraPosition() {
		return JomlConversions.fromVec3(client.gameRenderer.mainCamera().position());
	}

	public static Vector3f getCameraPositionFract(Vector3d originalPos) {
		return new Vector3f(
			(float) (originalPos.x - Math.floor(originalPos.x)),
			(float) (originalPos.y - Math.floor(originalPos.y)),
			(float) (originalPos.z - Math.floor(originalPos.z))
		);
	}

	public static Vector3i getCameraPositionInt(Vector3d originalPos) {
		return new Vector3i(
			(int) Math.floor(originalPos.x),
			(int) Math.floor(originalPos.y),
			(int) Math.floor(originalPos.z)
		);
	}

	static class CameraPositionTracker {
		/**
		 * Value range of cameraPosition: small enough to keep precision when converted to float, large enough that
		 * shifts (each a visible jump in shader animations) are rare. {@link #TP_RANGE} also forces a shift when the
		 * camera moves further than that in one frame (teleport).
		 */
		private static final double WALK_RANGE = 30000;
		private static final double TP_RANGE = 1000;
		private final Vector3d shift = new Vector3d();
		private Vector3d previousCameraPosition = new Vector3d();
		private Vector3d currentCameraPosition = new Vector3d();
		private Vector3d previousCameraPositionUnshifted = new Vector3d();
		private Vector3d currentCameraPositionUnshifted = new Vector3d();

		CameraPositionTracker(FrameUpdateNotifier notifier) {
			notifier.addListener(this::update);
			notifier.addResetListener(this::reset);
		}

		/**
		 * Back to the state of a new tracker: no shift, and previous positions at the origin until the next frame, as on
		 * a pack's first frame.
		 */
		private void reset() {
			shift.zero();
			previousCameraPosition = new Vector3d();
			currentCameraPosition = new Vector3d();
			previousCameraPositionUnshifted = new Vector3d();
			currentCameraPositionUnshifted = new Vector3d();
		}

		private static double getShift(double value, double prevValue) {
			if (Math.abs(value) > WALK_RANGE || Math.abs(value - prevValue) > TP_RANGE) {
				// Shift only in whole multiples of WALK_RANGE; some packs depend on it.
				return -(value - (value % WALK_RANGE));
			} else {
				return 0.0;
			}
		}

		private void update() {
			previousCameraPosition = currentCameraPosition;
			previousCameraPositionUnshifted = currentCameraPositionUnshifted;
			currentCameraPosition = getUnshiftedCameraPosition().add(shift);
			currentCameraPositionUnshifted = getUnshiftedCameraPosition();

			updateShift();
		}

		/**
		 * Shifts x/z to keep them within +-WALK_RANGE. The 2*WALK_RANGE window means moving back and forth across a
		 * boundary doesn't repeatedly re-shift.
		 */
		private void updateShift() {
			double dX = getShift(currentCameraPosition.x, previousCameraPosition.x);
			double dZ = getShift(currentCameraPosition.z, previousCameraPosition.z);

			if (dX != 0.0 || dZ != 0.0) {
				applyShift(dX, dZ);
			}
		}

		/**
		 * Shifts current, previous and future positions together, preserving cameraPosition - previousCameraPosition.
		 */
		private void applyShift(double dX, double dZ) {
			shift.x += dX;
			currentCameraPosition.x += dX;
			previousCameraPosition.x += dX;

			shift.z += dZ;
			currentCameraPosition.z += dZ;
			previousCameraPosition.z += dZ;
		}

		public Vector3d getCurrentCameraPosition() {
			return currentCameraPosition;
		}

		public Vector3d getPreviousCameraPosition() {
			return previousCameraPosition;
		}

		public Vector3d getPreviousCameraPositionUnshifted() {
			return previousCameraPositionUnshifted;
		}

		public double getCurrentCameraPositionY() {
			return currentCameraPosition.y;
		}
	}
}
