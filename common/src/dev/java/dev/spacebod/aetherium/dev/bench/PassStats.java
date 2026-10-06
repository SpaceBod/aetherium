package dev.spacebod.aetherium.dev.bench;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per frame-graph pass timings over the measured window: CPU time spent recording the pass on the render
 * thread, and GPU time between the timestamps around it. Pass names are vanilla's (for example
 * {@code main}, {@code sky}, {@code clouds}, {@code weather}), kept in first-seen order.
 */
public final class PassStats {
	public static final class Series {
		private long[] values = new long[1024];
		private int size;

		void add(long v) {
			if (size == values.length) {
				values = Arrays.copyOf(values, size * 2);
			}
			values[size++] = v;
		}

		public long[] toArray() {
			return Arrays.copyOf(values, size);
		}
	}

	public static final class Pass {
		public final Series cpu = new Series();
		public final Series gpu = new Series();
	}

	private final Map<String, Pass> passes = new LinkedHashMap<>();

	public void reset() {
		passes.clear();
	}

	public void cpu(String name, long nanos) {
		passes.computeIfAbsent(name, n -> new Pass()).cpu.add(nanos);
	}

	public void gpu(String name, long nanos) {
		passes.computeIfAbsent(name, n -> new Pass()).gpu.add(nanos);
	}

	public Map<String, Pass> passes() {
		return passes;
	}
}
