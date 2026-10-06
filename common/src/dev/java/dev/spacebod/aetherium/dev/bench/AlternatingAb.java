package dev.spacebod.aetherium.dev.bench;

import com.google.gson.JsonObject;
import dev.spacebod.aetherium.shaders.engine.EngineSwitches;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Arrays;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * In-run A/B: switches one optimisation on and off every second of the measured window and tags every frame
 * with the state it rendered under, so both variants see the same scene, machine state and JVM. Frames right
 * after a switch are dropped (the optimisation's own warm-up, e.g. gpu.cull needs one frame to build its
 * depth pyramid). Enabled with {@code AETHERIUM_BENCH_ALTERNATE=<optimisation id>}; only optimisations with a
 * runtime switch can alternate ({@code gpu.cull} and the runtime {@code shaders.*} ones).
 */
final class AlternatingAb {
	private static final int MASK = 16383;
	private static final int SETTLE_FRAMES = 3;
	private final String id;
	private final byte[] state = new byte[MASK + 1]; // 0 unknown/dropped, 1 off, 2 on
	private final LongArrayList[] cpu = {new LongArrayList(), new LongArrayList()};
	private final LongArrayList[] gpu = {new LongArrayList(), new LongArrayList()};
	private final LongArrayList[] mainPass = {new LongArrayList(), new LongArrayList()};
	private final LongArrayList[] interval = {new LongArrayList(), new LongArrayList()};
	private boolean current = true;
	private int sinceSwitch;

	private AlternatingAb(String id) {
		this.id = id;
	}

	static @Nullable AlternatingAb fromEnvironment() {
		String id = System.getenv("AETHERIUM_BENCH_ALTERNATE");
		if (id == null || id.isBlank()) {
			return null;
		}
		if (!EngineSwitches.isRuntime(id)) {
			throw new IllegalArgumentException("AETHERIUM_BENCH_ALTERNATE: no runtime switch for " + id);
		}
		return new AlternatingAb(id);
	}

	/** End of measured frame {@code frame}, {@code elapsedNanos} into the window: records it and sets the state for the next frame. */
	void frameEnd(long frame, long elapsedNanos, long intervalNanos, long cpuNanos) {
		int s = state[(int) (frame & MASK)];
		if (s != 0) {
			cpu[s - 1].add(cpuNanos);
			interval[s - 1].add(intervalNanos);
		}
		boolean next = ((elapsedNanos / 1_000_000_000L) & 1) == 0;
		if (next != current) {
			current = next;
			sinceSwitch = 0;
			apply(next);
		}
		sinceSwitch++;
		state[(int) ((frame + 1) & MASK)] = (byte) (sinceSwitch <= SETTLE_FRAMES ? 0 : next ? 2 : 1);
	}

	void gpuFrame(long frame, long gpuNanos, String[] names, long[] passNanos, int passCount) {
		int s = state[(int) (frame & MASK)];
		if (s == 0) {
			return;
		}
		gpu[s - 1].add(gpuNanos);
		for (int i = 0; i < passCount; i++) {
			if ("main".equals(names[i])) {
				mainPass[s - 1].add(passNanos[i]);
			}
		}
	}

	/** End of the window: the switch goes back to what the profile says. */
	void finish() {
		EngineSwitches.reset(id);
	}

	private void apply(boolean on) {
		EngineSwitches.set(id, on);
	}

	JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("optimisation", id);
		root.add("off", side(0));
		root.add("on", side(1));
		return root;
	}

	String summary() {
		return String.format(Locale.ROOT, "%s off/on: frame p50 %.3f/%.3f ms, CPU p50 %.3f/%.3f, GPU p50 %.3f/%.3f, main pass GPU p50 %.3f/%.3f p95 %.3f/%.3f",
				id, q(interval[0], .5), q(interval[1], .5), q(cpu[0], .5), q(cpu[1], .5), q(gpu[0], .5), q(gpu[1], .5),
				q(mainPass[0], .5), q(mainPass[1], .5), q(mainPass[0], .95), q(mainPass[1], .95));
	}

	private JsonObject side(int i) {
		JsonObject o = new JsonObject();
		o.addProperty("frames", interval[i].size());
		o.addProperty("frameP50Ms", q(interval[i], .5));
		o.addProperty("frameP99Ms", q(interval[i], .99));
		o.addProperty("cpuP50Ms", q(cpu[i], .5));
		o.addProperty("gpuP50Ms", q(gpu[i], .5));
		o.addProperty("gpuP95Ms", q(gpu[i], .95));
		o.addProperty("mainPassGpuP50Ms", q(mainPass[i], .5));
		o.addProperty("mainPassGpuP95Ms", q(mainPass[i], .95));
		return o;
	}

	private static double q(LongArrayList values, double p) {
		if (values.isEmpty()) {
			return -1;
		}
		long[] a = values.toLongArray();
		Arrays.sort(a);
		return a[Math.min(a.length - 1, (int) (a.length * p))] / 1e6;
	}
}
