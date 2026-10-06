package dev.spacebod.aetherium.dev.mixin;

import dev.spacebod.aetherium.dev.bench.DirtySources;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the tagged source of every clean -> dirty transition. */
@Mixin(SectionUpdateTracker.class)
abstract class SectionUpdateTrackerMixin {
	@Shadow
	public abstract SectionUpdateTracker.SectionDirtyState getDirtyState(long sectionNode);

	@Inject(method = "setDirty", at = @At("HEAD"))
	private void aetherium$record(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
		long node = SectionPos.asLong(sectionX, sectionY, sectionZ);
		SectionUpdateTracker.SectionDirtyState state = getDirtyState(node);
		if (state != null && !state.isDirty()) {
			DirtySources.dirtied(node);
		}
	}
}
