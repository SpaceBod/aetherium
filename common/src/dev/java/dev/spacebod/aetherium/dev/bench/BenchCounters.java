package dev.spacebod.aetherium.dev.bench;

import java.util.concurrent.atomic.LongAdder;

/**
 * Work counters bumped from the section-compile worker threads. Always on (a LongAdder add is a few ns),
 * so the same numbers exist whether or not a benchmark is running and the overlay can show them later.
 */
public final class BenchCounters {
	/** Finished section builds, counted by the release mod itself (the shadow cache reads it). */
	public static final LongAdder SECTIONS_COMPILED = dev.spacebod.aetherium.client.render.SectionBuilds.COMPLETED;
	public static final LongAdder SECTIONS_CANCELLED = new LongAdder();
	public static final LongAdder COMPILE_NANOS = new LongAdder();
	public static final LongAdder RESORTS = new LongAdder();
	public static final LongAdder RESORT_NANOS = new LongAdder();
	/** Compiles of a section that was last compiled less than a second earlier (redundant-rebuild indicator). */
	public static final LongAdder REBUILT_WITHIN_1S = new LongAdder();
	private static final java.util.concurrent.ConcurrentHashMap<Long, Long> LAST_COMPILE = new java.util.concurrent.ConcurrentHashMap<>();

	/** A section build finished; {@code sectionNode} is its {@code SectionPos} key. */
	public static void compiled(long sectionNode) {
		long now = System.nanoTime();
		Long previous = LAST_COMPILE.put(sectionNode, now);
		if (previous != null && now - previous < 1_000_000_000L) {
			REBUILT_WITHIN_1S.increment();
			DirtySources.rebuiltQuickly(sectionNode);
		} else {
			DirtySources.forget(sectionNode);
		}
		if (LAST_COMPILE.size() > PRUNE_AT) {
			// Only the last second matters: drop older builds so the map stays bounded over a long session.
			LAST_COMPILE.values().removeIf(t -> now - t >= 1_000_000_000L);
		}
	}

	/** Size past which {@link #compiled} drops builds older than a second (an RD32 load can mesh ~70k sections). */
	private static final int PRUNE_AT = 1 << 17;

	private static final ThreadLocal<long[]> START = ThreadLocal.withInitial(() -> new long[1]);

	private BenchCounters() {
	}

	public static void taskStart() {
		START.get()[0] = System.nanoTime();
	}

	/** Elapsed time since this thread's last {@link #taskStart()}. */
	public static long taskElapsed() {
		return System.nanoTime() - START.get()[0];
	}

	/** A point-in-time copy, so a benchmark can report deltas over its own window. */
	public record Snapshot(long compiled, long cancelled, long compileNanos, long resorts, long resortNanos,
						   long rebuiltWithin1s, long[] dirtiedBy, long[] rebuiltBy) {
		public static Snapshot take() {
			return new Snapshot(SECTIONS_COMPILED.sum(), SECTIONS_CANCELLED.sum(), COMPILE_NANOS.sum(), RESORTS.sum(), RESORT_NANOS.sum(),
					REBUILT_WITHIN_1S.sum(), DirtySources.snapshot(DirtySources.DIRTIED), DirtySources.snapshot(DirtySources.REBUILT_WITHIN_1S));
		}

		public Snapshot minus(Snapshot o) {
			return new Snapshot(compiled - o.compiled, cancelled - o.cancelled, compileNanos - o.compileNanos,
					resorts - o.resorts, resortNanos - o.resortNanos, rebuiltWithin1s - o.rebuiltWithin1s,
					minus(dirtiedBy, o.dirtiedBy), minus(rebuiltBy, o.rebuiltBy));
		}

		private static long[] minus(long[] a, long[] b) {
			long[] r = new long[a.length];
			for (int i = 0; i < a.length; i++) {
				r[i] = a[i] - b[i];
			}
			return r;
		}
	}
}
