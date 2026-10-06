package dev.spacebod.aetherium.dev.bench;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;

/**
 * Wraps the {@link FrameGraphBuilder.Inspector} that {@code LevelRenderer.render} hands to
 * {@code FrameGraphBuilder.execute} (vanilla's only uses it for profiler sections), adding CPU and GPU
 * timing around every pass. Vanilla's calls still go through first/last, so the profiler is unchanged.
 */
final class BenchInspector implements FrameGraphBuilder.Inspector {
	private final FrameGraphBuilder.Inspector delegate;
	private final BenchDriver driver;
	private long passStart;

	BenchInspector(FrameGraphBuilder.Inspector delegate, BenchDriver driver) {
		this.delegate = delegate;
		this.driver = driver;
	}

	@Override
	public void acquireResource(String name) {
		delegate.acquireResource(name);
	}

	@Override
	public void releaseResource(String name) {
		delegate.releaseResource(name);
	}

	@Override
	public void beforeExecutePass(String name) {
		delegate.beforeExecutePass(name);
		driver.beforePass(name);
		passStart = System.nanoTime();
	}

	@Override
	public void afterExecutePass(String name) {
		long cpu = System.nanoTime() - passStart;
		driver.afterPass(name, cpu);
		delegate.afterExecutePass(name);
	}
}
