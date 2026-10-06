package dev.spacebod.aetherium.memory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.world.level.chunk.PalettedContainer;

/** The single-int threading check of {@code mem.palette_lock}; the field is added by its mixin. */
public final class PaletteLock {
	/** What every container's {@code threadingDetector} field points to instead of its own detector; never locked. */
	public static final ThreadingDetector UNUSED_DETECTOR = new ThreadingDetector("PalettedContainer (unused, see mem.palette_lock)");
	private static final VarHandle LOCK;

	static {
		try {
			LOCK = MethodHandles.privateLookupIn(PalettedContainer.class, MethodHandles.lookup())
					.findVarHandle(PalettedContainer.class, "aetherium$lock", int.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private PaletteLock() {
	}

	public static void acquire(Object container) {
		if (!LOCK.compareAndSet(container, 0, 1)) {
			throw ThreadingDetector.makeThreadingException("PalettedContainer", null);
		}
	}

	public static void release(Object container) {
		LOCK.setRelease(container, 0);
	}
}
