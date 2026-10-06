package dev.spacebod.aetherium.shaders.uniforms.transforms;

import dev.spacebod.aetherium.shaders.gl.uniform.FloatSupplier;
import dev.spacebod.aetherium.shaders.uniforms.FrameUpdateNotifier;
import dev.spacebod.aetherium.shaders.uniforms.SystemTimeUniforms;

/**
 * Basic exponential smoothing of a per-frame sampled value, with separate half lives for rising and falling input.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Exponential_smoothing#Basic_(simple)_exponential_smoothing_(Holt_linear)">
 * Wikipedia: Basic (simple) exponential smoothing (Holt linear)</a>
 */
public class SmoothedFloat implements FloatSupplier {
	private static final double LN_OF_2 = Math.log(2.0);

	private final FloatSupplier unsmoothed;
	/**
	 * Decay constant k (in e^(-kt), per second) applied while the input is above the smoothed value; infinite for a
	 * half life of zero (no smoothing).
	 */
	private final float decayConstantUp;
	/**
	 * Decay constant k (in e^(-kt), per second) applied while the input is at or below the smoothed value.
	 */
	private final float decayConstantDown;
	/**
	 * The current smoothed value.
	 */
	private float accumulator;
	/**
	 * Whether the accumulator has been seeded; the first sample is taken unsmoothed.
	 */
	private boolean hasInitialValue;

	/**
	 * @param halfLifeUp   half life for rising input, in tenths of a second (2 ticks); 2.0 is 0.2 s
	 * @param halfLifeDown half life for falling input, same units
	 * @param unsmoothed   the raw input, sampled once per frame via {@code updateNotifier}
	 */
	public SmoothedFloat(float halfLifeUp, float halfLifeDown, FloatSupplier unsmoothed, FrameUpdateNotifier updateNotifier) {
		this.decayConstantUp = computeDecay(halfLifeUp * 0.1F);
		this.decayConstantDown = computeDecay(halfLifeDown * 0.1F);

		this.unsmoothed = unsmoothed;

		updateNotifier.addListener(this::update);
		updateNotifier.addResetListener(this::reset);
	}

	/**
	 * @param k the decay constant, derived from the half life
	 * @param t elapsed time in seconds
	 * @return e^(-kt)
	 */
	private static float exponentialDecayFactor(float k, float t) {
		return (float) Math.exp(-k * t);
	}

	private static float lerp(float v0, float v1, float t) {
		return (1 - t) * v0 + t * v1;
	}

	/**
	 * Samples the input once and folds it into the accumulator. Runs once per frame.
	 */
	private void update() {
		if (!hasInitialValue) {
			// Seed with the first sample unsmoothed; crude but adequate.
			// https://en.wikipedia.org/wiki/Exponential_smoothing#Choosing_the_initial_smoothed_value
			accumulator = unsmoothed.getAsFloat();
			hasInitialValue = true;

			return;
		}

		// xₜ
		float newValue = unsmoothed.getAsFloat();
		float decayConstant = newValue > this.accumulator ? this.decayConstantUp : decayConstantDown;

		if (Float.isInfinite(decayConstant)) {
			// A half life of zero: no smoothing at all (also keeps 0 * infinity out of the exponent).
			accumulator = newValue;
			return;
		}

		// 𝚫t
		float lastFrameTime = SystemTimeUniforms.TIMER.getLastFrameTime();

		if (lastFrameTime <= 0.0F) {
			// No time has passed (a frame shorter than the timer's 1 ms resolution, or the first frame after a reset).
			return;
		}

		// α = 1 - e^(-𝚫t/τ) = 1 - e^(-k𝚫t)
		float smoothingFactor = 1.0f - exponentialDecayFactor(decayConstant, lastFrameTime);

		// sₜ = αxₜ + (1 - α)sₜ₋₁
		accumulator = lerp(accumulator, newValue, smoothingFactor);
	}

	/** Forgets the smoothed value: the next frame's sample is taken unsmoothed. */
	public void reset() {
		hasInitialValue = false;
	}

	private float computeDecay(float halfLife) {
		// k = 1 / τ, with τ = halfLife / ln 2
		return (float) (1.0f / (halfLife / LN_OF_2));
	}

	/**
	 * @return the current smoothed value, or the raw input before the first update
	 */
	@Override
	public float getAsFloat() {
		if (!hasInitialValue) {
			return unsmoothed.getAsFloat();
		}

		return accumulator;
	}
}
