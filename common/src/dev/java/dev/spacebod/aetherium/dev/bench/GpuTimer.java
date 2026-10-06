package dev.spacebod.aetherium.dev.bench;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import dev.spacebod.aetherium.Aetherium;
import java.util.OptionalLong;

/**
 * GPU timestamps for whole frames and for every frame-graph pass, through vanilla's own
 * {@code GpuDevice.createTimestampQueryPool} / {@code CommandEncoder.writeTimestamp} (the API behind the F3
 * "GPU utilization" figure), so it works the same on the Vulkan and the OpenGL backend.
 *
 * <p>Results are read back without waiting: each frame owns one slot of a ring, and a slot is only
 * reused once its values have arrived. On Vulkan, {@code writeTimestamp} resets the query from the host
 * while recording, so reusing a slot whose query is still in flight would be undefined; a frame that finds
 * its slot still busy is not timed.
 */
public final class GpuTimer {
	/** Frames of latency the ring can absorb before frames go untimed. */
	private static final int SLOTS = 6;
	/** Slot layout: frame begin + end, then begin/end for up to this many passes. */
	private static final int MAX_PASSES = 64;
	private static final int PER_SLOT = 2 + 2 * MAX_PASSES;

	/** Receives resolved results, on the render thread, a few frames after the frame was recorded. */
	public interface Sink {
		void frame(long frameNo, long gpuNanos, String[] passNames, long[] passGpuNanos, int passCount);
	}

	private final GpuQueryPool pool;
	private final boolean passTimers;
	private final float period;
	private final long[] slotFrame = new long[SLOTS];
	private final boolean[] slotPending = new boolean[SLOTS];
	private final int[] slotPasses = new int[SLOTS];
	private final String[][] slotNames = new String[SLOTS][MAX_PASSES];
	private final long[] scratch = new long[MAX_PASSES];

	private int current = -1;
	private int passIndex;
	/** Open passes (their indices): passes may nest (an engine stage inside a frame-graph pass). */
	private final int[] open = new int[16];
	private int depth;
	private long untimedFrames;

	private GpuTimer(GpuQueryPool pool, boolean passTimers) {
		this.pool = pool;
		this.passTimers = passTimers;
		this.period = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
	}

	/** Null when the device cannot create timestamp queries; the benchmark then reports CPU times only. */
	public static GpuTimer create(boolean passTimers) {
		try {
			return new GpuTimer(RenderSystem.getDevice().createTimestampQueryPool(SLOTS * PER_SLOT), passTimers);
		} catch (RuntimeException e) {
			Aetherium.LOG.warn("GPU timestamp queries unavailable, benchmark will report CPU times only", e);
			return null;
		}
	}

	public void beginFrame(long frameNo) {
		int slot = (int) (frameNo % SLOTS);
		if (slotPending[slot]) {
			current = -1;
			untimedFrames++;
			return;
		}
		current = slot;
		passIndex = 0;
		depth = 0;
		slotFrame[slot] = frameNo;
		write(slot * PER_SLOT);
	}

	public void beforePass(String name) {
		if (current < 0 || !passTimers || passIndex >= MAX_PASSES) {
			return;
		}
		slotNames[current][passIndex] = name;
		write(current * PER_SLOT + 2 + passIndex * 2);
		if (depth < open.length) {
			open[depth++] = passIndex;
		}
		passIndex++;
	}

	public void afterPass() {
		if (current < 0 || !passTimers || depth == 0) {
			return;
		}
		write(current * PER_SLOT + 2 + open[--depth] * 2 + 1);
	}

	public void endFrame() {
		if (current < 0) {
			return;
		}
		write(current * PER_SLOT + 1);
		slotPasses[current] = passIndex;
		slotPending[current] = true;
		current = -1;
	}

	private void write(int index) {
		RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, index);
	}

	/** Collects every slot whose values have arrived. Never blocks. */
	public void poll(Sink sink) {
		for (int slot = 0; slot < SLOTS; slot++) {
			if (!slotPending[slot]) {
				continue;
			}
			int passes = slotPasses[slot];
			OptionalLong[] v = pool.getValues(slot * PER_SLOT, 2 + passes * 2);
			if (v[0].isEmpty() || v[1].isEmpty()) {
				continue;
			}
			boolean complete = true;
			for (int p = 0; p < passes; p++) {
				OptionalLong a = v[2 + p * 2];
				OptionalLong b = v[3 + p * 2];
				if (a.isEmpty() || b.isEmpty()) {
					complete = false;
					break;
				}
				scratch[p] = ticksToNanos(b.getAsLong() - a.getAsLong());
			}
			if (!complete) {
				continue;
			}
			slotPending[slot] = false;
			sink.frame(slotFrame[slot], ticksToNanos(v[1].getAsLong() - v[0].getAsLong()), slotNames[slot], scratch, passes);
		}
	}

	private long ticksToNanos(long ticks) {
		return (long) ((double) ticks * period);
	}

	public long untimedFrames() {
		return untimedFrames;
	}
}
