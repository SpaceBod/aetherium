package dev.spacebod.aetherium.dev.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;

/**
 * JVM and OS numbers once a second on its own daemon thread. OS CPU-load reads take ~170 ms on Windows
 * and summing per-thread allocation touches every thread, so none of this may run on
 * the render thread.
 */
final class SystemSampler implements Runnable {
	private final long originNanos;
	private final JsonArray samples = new JsonArray();
	private volatile boolean running = true;
	private final Thread thread;

	SystemSampler(long originNanos) {
		this.originNanos = originNanos;
		this.thread = new Thread(this, "Aetherium bench sampler");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
	}

	void start() {
		thread.start();
	}

	/** Stops sampling and returns everything collected (safe to call once). */
	JsonArray stop() {
		running = false;
		thread.interrupt();
		try {
			thread.join(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		synchronized (samples) {
			return samples.deepCopy();
		}
	}

	@Override
	public void run() {
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		com.sun.management.ThreadMXBean sunThreads = threads instanceof com.sun.management.ThreadMXBean t ? t : null;
		java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
		com.sun.management.OperatingSystemMXBean sunOs = os instanceof com.sun.management.OperatingSystemMXBean o ? o : null;
		long lastAlloc = totalAllocated(threads, sunThreads);
		long[] lastGc = gc();
		while (running) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				break;
			}
			MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
			long alloc = totalAllocated(threads, sunThreads);
			long[] gcNow = gc();
			JsonObject s = new JsonObject();
			s.addProperty("t", (System.nanoTime() - originNanos) / 1e9);
			s.addProperty("heapUsedMB", heap.getUsed() / 1048576.0);
			s.addProperty("heapCommittedMB", heap.getCommitted() / 1048576.0);
			if (alloc >= 0 && lastAlloc >= 0) {
				s.addProperty("allocMB", Math.max(0, alloc - lastAlloc) / 1048576.0);
			}
			s.addProperty("gcCount", gcNow[0] - lastGc[0]);
			s.addProperty("gcMs", gcNow[1] - lastGc[1]);
			if (sunOs != null) {
				s.addProperty("processCpu", sunOs.getProcessCpuLoad());
				s.addProperty("systemCpu", sunOs.getCpuLoad());
			}
			s.addProperty("threads", threads.getThreadCount());
			lastAlloc = alloc;
			lastGc = gcNow;
			synchronized (samples) {
				samples.add(s);
			}
		}
	}

	private static long totalAllocated(ThreadMXBean threads, com.sun.management.ThreadMXBean sunThreads) {
		if (sunThreads == null || !sunThreads.isThreadAllocatedMemorySupported()) {
			return -1;
		}
		try {
			return sunThreads.getTotalThreadAllocatedBytes();
		} catch (UnsupportedOperationException e) {
			long sum = 0;
			for (long bytes : sunThreads.getThreadAllocatedBytes(threads.getAllThreadIds())) {
				if (bytes > 0) {
					sum += bytes;
				}
			}
			return sum;
		}
	}

	private static long[] gc() {
		long count = 0;
		long ms = 0;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			count += Math.max(0, gc.getCollectionCount());
			ms += Math.max(0, gc.getCollectionTime());
		}
		return new long[]{count, ms};
	}
}
