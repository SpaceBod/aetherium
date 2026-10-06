package dev.spacebod.aetherium.client.gpu;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * How much GPU work the frames hand to the Vulkan backend: render passes, standalone clears, texture copies and queue
 * submits, averaged per frame over about a second, for the F3 screen. The counters are plain fields bumped by mixins
 * on the render thread (one increment per command, nothing else); the frame submit closes the window once a second.
 */
public final class FrameCensus {
	/** This window's totals (render thread). */
	public static int passes;
	public static int clears;
	public static int copies;
	public static int submits;
	private static int frames;
	private static long windowStart;
	/** The last closed window, per frame; null until one closed. */
	private static volatile @Nullable String line;

	private FrameCensus() {
	}

	/** The end of a frame (its submit): counts it, and closes the window after a second. */
	public static void endFrame() {
		frames++;
		long now = System.nanoTime();
		if (now - windowStart < 1_000_000_000L) {
			return;
		}
		if (windowStart != 0 && frames > 0) {
			float f = frames;
			line = String.format(Locale.ROOT, "GPU work per frame: %.1f render passes, %.1f clears, %.1f texture copies, %.1f submits",
					passes / f, clears / f, copies / f, submits / f);
		}
		windowStart = now;
		frames = 0;
		passes = 0;
		clears = 0;
		copies = 0;
		submits = 0;
	}

	/** The F3 line for the last second, or null before the first second passed (or on a backend that is not Vulkan). */
	public static @Nullable String line() {
		return line;
	}
}
