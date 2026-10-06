package dev.spacebod.aetherium.shaders.engine;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * Where a pack load's time goes, summed over every thread that worked on it: the GLSL transform (in-memory cache misses
 * only; programs read from the disk cache count with the time the read took, and are also counted as cached), GLSL to
 * SPIR-V compilation, and pipeline creation (graphics and compute). Fed from the compile paths on any thread;
 * the engine takes a {@link #snapshot} when a load starts and logs the difference when the pack goes active.
 */
public final class LoadTimings {
	public enum Kind {
		TRANSFORM, SHADERC, GRAPHICS_PIPELINE, COMPUTE_PIPELINE
	}

	private static final Kind[] KINDS = Kind.values();
	private static final LongAdder[] NANOS = new LongAdder[KINDS.length];
	private static final LongAdder[] COUNTS = new LongAdder[KINDS.length];
	private static final LongAdder[] CACHED = new LongAdder[KINDS.length];

	static {
		for (int i = 0; i < KINDS.length; i++) {
			NANOS[i] = new LongAdder();
			COUNTS[i] = new LongAdder();
			CACHED[i] = new LongAdder();
		}
	}

	private LoadTimings() {
	}

	/** One piece of work of {@code kind} that took {@code nanos}. */
	public static void add(Kind kind, long nanos) {
		NANOS[kind.ordinal()].add(nanos);
		COUNTS[kind.ordinal()].increment();
	}

	/** One piece of work of {@code kind} served from a cache, which took {@code nanos} to read. */
	public static void addCached(Kind kind, long nanos) {
		add(kind, nanos);
		CACHED[kind.ordinal()].increment();
	}

	/** The totals so far. */
	public record Snapshot(long[] nanos, long[] counts, long[] cached) {
		public long nanos(Kind kind) {
			return nanos[kind.ordinal()];
		}

		public long count(Kind kind) {
			return counts[kind.ordinal()];
		}

		public long cached(Kind kind) {
			return cached[kind.ordinal()];
		}
	}

	public static Snapshot snapshot() {
		long[] nanos = new long[KINDS.length];
		long[] counts = new long[KINDS.length];
		long[] cached = new long[KINDS.length];
		for (int i = 0; i < KINDS.length; i++) {
			nanos[i] = NANOS[i].sum();
			counts[i] = COUNTS[i].sum();
			cached[i] = CACHED[i].sum();
		}
		return new Snapshot(nanos, counts, cached);
	}

	/**
	 * What was spent since {@code start}: "transform 3.1 s (120, 0 cached), shaderc 4.2 s (240), vkCreateGraphicsPipelines
	 * 1.9 s (260), compute pipelines 0.1 s (4)". Times are summed over threads (they overlap in wall time); the cached count
	 * is shown for the transform, and for other kinds once any was served from a cache.
	 */
	public static String since(Snapshot start) {
		Snapshot now = snapshot();
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < KINDS.length; i++) {
			if (b.length() > 0) {
				b.append(", ");
			}
			b.append(switch (KINDS[i]) {
				case TRANSFORM -> "transform";
				case SHADERC -> "shaderc";
				case GRAPHICS_PIPELINE -> "vkCreateGraphicsPipelines";
				case COMPUTE_PIPELINE -> "compute pipelines";
			});
			long cached = now.cached[i] - start.cached[i];
			b.append(String.format(Locale.ROOT, " %.2f s (%d", (now.nanos[i] - start.nanos[i]) / 1e9, now.counts[i] - start.counts[i]));
			if (cached > 0 || KINDS[i] == Kind.TRANSFORM) {
				b.append(", ").append(cached).append(" cached");
			}
			b.append(')');
		}
		return b.toString();
	}
}
