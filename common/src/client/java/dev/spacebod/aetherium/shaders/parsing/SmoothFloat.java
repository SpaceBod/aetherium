package dev.spacebod.aetherium.shaders.parsing;

import dev.spacebod.aetherium.shaders.uniforms.SystemTimeUniforms;

/**
 * Basic exponential smoothing of a per-frame value sequence, with separate half-lives for rising and falling input.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Exponential_smoothing#Basic_(simple)_exponential_smoothing_(Holt_linear)">
 * Wikipedia: Basic (simple) exponential smoothing (Holt linear)</a>
 */
public class SmoothFloat {
	private static final double LN_OF_2 = Math.log(2.0);

	/** Bumped by {@link #resetAll()}; an instance that sees a new value starts over from its next sample. */
	private static volatile int generation;

	private int seenGeneration = generation;

	/** Forgets every smoothed value (world change), so the next sample seeds each accumulator again. */
	public static void resetAll() {
		generation++;
	}

	/** Current smoothed value. */
	private float accumulator;

	/** False until the first sample has seeded {@link #accumulator}. */
	private boolean hasInitialValue;
	private float cachedHalfLifeUp;
	private float cachedDecayUp;
	private float cachedHalfLifeDown;
	private float cachedDecayDown;

	/**
	 * {@code e^(-kt)}.
	 *
	 * @param k the decay constant, derived from the half-life
	 * @param t elapsed time
	 */
	private static float exponentialDecayFactor(float k, float t) {
		return (float) Math.exp(-k * t);
	}

	private static float lerp(float v0, float v1, float t) {
		return (1 - t) * v0 + t * v1;
	}

	/**
	 * Smooths one raw sample into the accumulator and returns the result. A half-life of 0 disables smoothing in that
	 * direction.
	 */
	public float updateAndGet(float value, float halfLifeUp, float halfLifeDown) {
		if (halfLifeUp != cachedHalfLifeUp) {
			cachedHalfLifeUp = halfLifeUp;
			if (halfLifeUp == 0.0f) {
				cachedDecayUp = 0;
			} else {
				cachedDecayUp = computeDecay(halfLifeUp * 0.1F);
			}
		}

		if (halfLifeDown != cachedHalfLifeDown) {
			cachedHalfLifeDown = halfLifeDown;
			if (halfLifeDown == 0.0f) {
				cachedDecayDown = 0;
			} else {
				cachedDecayDown = computeDecay(halfLifeDown * 0.1F);
			}
		}

		if (seenGeneration != generation) {
			seenGeneration = generation;
			hasInitialValue = false;
		}

		if (!hasInitialValue) {
			// The first sample seeds the accumulator unsmoothed; crude, but adequate here.
			// https://en.wikipedia.org/wiki/Exponential_smoothing#Choosing_the_initial_smoothed_value
			accumulator = value;
			hasInitialValue = true;

			return accumulator;
		}

		// 𝚫t
		float lastFrameTime = SystemTimeUniforms.TIMER.getLastFrameTime();

		float decay = value > this.accumulator ? cachedDecayUp : cachedDecayDown;

		if (decay == 0.0f) {
			accumulator = value;
			return accumulator;
		}

		// α = 1 - e^(-𝚫t/τ) = 1 - e^(-k𝚫t)
		float smoothingFactor = 1.0f - exponentialDecayFactor(decay, lastFrameTime);

		// sₜ = αxₜ + (1 - α)sₜ₋₁
		accumulator = lerp(accumulator, value, smoothingFactor);

		return accumulator;
	}

	private float computeDecay(float halfLife) {
		// k = 1 / τ, where τ = halfLife / ln(2)
		return (float) (1.0f / (halfLife / LN_OF_2));
	}
}
