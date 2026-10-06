package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.device.GpuDeviceLossException;

/**
 * A lost GPU device is not a pack failure: the game must see it. Every broad catch in the engine passes what it caught
 * through {@link #rethrow} first, and teardown stops waiting on the device once it is lost.
 */
final class DeviceLoss {
	private static volatile boolean lost;

	private DeviceLoss() {
	}

	/**
	 * Rethrows the device loss {@code t} reports, itself or as the cause of a wrapper (a failed background task),
	 * remembering it; returns when there is none.
	 */
	static void rethrow(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
			if (c instanceof GpuDeviceLossException loss) {
				lost = true;
				throw loss;
			}
		}
	}

	/** Whether a device loss went through {@link #rethrow}. */
	static boolean happened() {
		return lost;
	}
}
