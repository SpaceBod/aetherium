package dev.spacebod.aetherium.shaders.uniforms;

import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency;

import java.util.OptionalLong;
import java.util.function.IntSupplier;

/**
 * Uniforms driven by real (system) time rather than world time.
 */
public final class SystemTimeUniforms {
	public static final Timer TIMER = new Timer();
	public static final FrameCounter COUNTER = new FrameCounter();

	private SystemTimeUniforms() {
	}

	/**
	 * Starts {@code frameCounter} and {@code frameTime}/{@code frameTimeCounter} over: the next frame counts from zero and
	 * reports no elapsed time. Called when a pack goes active (including after a dimension change) and when a world is
	 * entered, so the time spent loading or in menus never shows up as one long frame.
	 */
	public static void reset() {
		COUNTER.reset();
		TIMER.reset();
	}

	public static void addSystemTimeUniforms(UniformHolder uniforms) {
		uniforms
			.uniform1i(UniformUpdateFrequency.PER_FRAME, "frameCounter", COUNTER)
			.uniform1f(UniformUpdateFrequency.PER_FRAME, "frameTime", TIMER::getLastFrameTime)
			.uniform1f(UniformUpdateFrequency.PER_FRAME, "frameTimeCounter", TIMER::getFrameTimeCounter);
	}

	/**
	 * Frame counter starting at zero, incremented once per frame and wrapping every 720720 frames. {@link #frameId()}
	 * also advances once per frame but is never reset or wrapped: use it to tell frames apart (per-frame caches).
	 */
	public static class FrameCounter implements IntSupplier {
		private int count;
		private long frameId;

		private FrameCounter() {
			this.count = 0;
		}

		@Override
		public int getAsInt() {
			return count;
		}

		/** A number unique to the current frame for the whole session (not reset with the counter). */
		public long frameId() {
			return frameId;
		}

		public void beginFrame() {
			count = (count + 1) % 720720;
			frameId++;
		}

		public void reset() {
			count = 0;
		}
	}

	/**
	 * Tracks the last frame's duration and the running frame-time counter, both in seconds at 1 ms resolution.
	 * Updated at the start of each frame; the counter wraps hourly.
	 */
	public static final class Timer {
		/**
		 * {@code AETHERIUM_SHADERS_FIXED_TIME=<seconds>}: pins frameTimeCounter, so screenshots taken in different runs
		 * show the same animation phase (the counter otherwise drifts with frame rate: each frame is truncated to 1 ms).
		 */
		private static final float FIXED_TIME = System.getenv("AETHERIUM_SHADERS_FIXED_TIME") == null ? -1.0F
				: Float.parseFloat(System.getenv("AETHERIUM_SHADERS_FIXED_TIME"));
		private float frameTimeCounter;
		private float lastFrameTime;

		// OptionalLong as a (valid, value) pair; empty before the first frame.
		@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
		private OptionalLong lastStartTime;

		public Timer() {
			reset();
		}

		public void beginFrame(long frameStartTime) {
			// Nanoseconds since the previous frame began; 0 on the first frame.
			long diffNs = frameStartTime - lastStartTime.orElse(frameStartTime);
			long diffMs = (diffNs / 1000) / 1000;

			// Seconds, truncated to 1 ms resolution.
			lastFrameTime = diffMs / 1000.0F;

			frameTimeCounter = FIXED_TIME >= 0 ? FIXED_TIME : frameTimeCounter + lastFrameTime;

			// Wrap hourly; large values cause issues with some packs.
			if (frameTimeCounter >= 3600.0F) {
				frameTimeCounter = 0.0F;
			}

			lastStartTime = OptionalLong.of(frameStartTime);
		}

		public float getFrameTimeCounter() {
			return frameTimeCounter;
		}

		public float getLastFrameTime() {
			return lastFrameTime;
		}

		public void reset() {
			frameTimeCounter = 0.0F;
			lastFrameTime = 0.0F;
			lastStartTime = OptionalLong.empty();
		}
	}
}
