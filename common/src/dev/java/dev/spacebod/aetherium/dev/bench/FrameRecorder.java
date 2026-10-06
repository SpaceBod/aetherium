package dev.spacebod.aetherium.dev.bench;

import java.util.Arrays;

/**
 * Per-frame samples for the measured window, in preallocated primitive arrays so recording allocates
 * nothing on the render thread. GPU times arrive a few frames late (see {@link GpuTimer}) and are filled in
 * by frame number.
 */
public final class FrameRecorder {
	private long firstFrame = -1;
	private int size;
	/** Nanoseconds since the start of the measured window, at the end of the frame. */
	private long[] at = new long[1 << 16];
	/** Wall time from the end of the previous frame to the end of this one (what FPS counters invert). */
	private long[] interval = new long[1 << 16];
	/** Vanilla's frame-time figure: CPU time from frame start to the swapchain blit, excluding the limiter. */
	private long[] cpu = new long[1 << 16];
	/** GPU time between the frame's first and last timestamp, or -1 when the query never resolved. */
	private long[] gpu = new long[1 << 16];

	public void reset() {
		firstFrame = -1;
		size = 0;
	}

	public void record(long frameNo, long atNanos, long intervalNanos, long cpuNanos) {
		if (firstFrame < 0) {
			firstFrame = frameNo;
		}
		int index = (int) (frameNo - firstFrame);
		if (index != size) {
			// A frame was skipped (surface not acquired); keep indices contiguous by frame number.
			while (size < index) {
				append(atNanos, 0, 0);
			}
		}
		append(atNanos, intervalNanos, cpuNanos);
	}

	private void append(long atNanos, long intervalNanos, long cpuNanos) {
		if (size == at.length) {
			int n = size * 2;
			at = Arrays.copyOf(at, n);
			interval = Arrays.copyOf(interval, n);
			cpu = Arrays.copyOf(cpu, n);
			gpu = Arrays.copyOf(gpu, n);
		}
		at[size] = atNanos;
		interval[size] = intervalNanos;
		cpu[size] = cpuNanos;
		gpu[size] = -1;
		size++;
	}

	public void setGpu(long frameNo, long gpuNanos) {
		if (firstFrame < 0) {
			return;
		}
		long index = frameNo - firstFrame;
		if (index >= 0 && index < size) {
			gpu[(int) index] = gpuNanos;
		}
	}

	public int size() {
		return size;
	}

	public long at(int i) {
		return at[i];
	}

	public long interval(int i) {
		return interval[i];
	}

	public long cpu(int i) {
		return cpu[i];
	}

	public long gpu(int i) {
		return gpu[i];
	}
}
