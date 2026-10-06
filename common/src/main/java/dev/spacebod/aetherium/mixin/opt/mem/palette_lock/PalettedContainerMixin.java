package dev.spacebod.aetherium.mixin.opt.mem.palette_lock;

import dev.spacebod.aetherium.memory.PaletteLock;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code mem.palette_lock}: every paletted container (two per chunk section, on both sides) allocates a
 * {@link ThreadingDetector} (the detector, a {@code Semaphore} and a {@code ReentrantLock} with their sync
 * objects, ~130 bytes) that exists only to crash when two threads touch one container at once. The
 * detector is not allocated (the field holds one shared, unused instance); the same check is one int in
 * the container, taken with a CAS.
 *
 * <p>Differences to vanilla: a second thread crashes at once with its own stack instead of first waiting
 * for the owner's, and a release without an acquire does not raise the permit count.
 */
@Mixin(PalettedContainer.class)
abstract class PalettedContainerMixin {
	@Unique
	private volatile int aetherium$lock;

	@Redirect(method = "<init>*", at = @At(value = "NEW", target = "(Ljava/lang/String;)Lnet/minecraft/util/ThreadingDetector;"))
	private static ThreadingDetector aetherium$noDetector(String name) {
		// Mixin rejects a null result from a constructor redirect; one shared instance is never used (see below).
		return PaletteLock.UNUSED_DETECTOR;
	}

	/**
	 * @author Aetherium
	 * @reason the detector is not allocated (mem.palette_lock)
	 */
	@Overwrite
	public void acquire() {
		PaletteLock.acquire(this);
	}

	/**
	 * @author Aetherium
	 * @reason the detector is not allocated (mem.palette_lock)
	 */
	@Overwrite
	public void release() {
		PaletteLock.release(this);
	}
}
