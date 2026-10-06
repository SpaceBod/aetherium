package dev.spacebod.aetherium.dev.mixin;

import dev.spacebod.aetherium.dev.bench.DirtySources;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tags dirty marks from block changes. */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorDirtyMixin {
	@Inject(method = {"blockChanged", "setBlocksDirty"}, at = @At("HEAD"))
	private void aetherium$enter(CallbackInfo ci) {
		DirtySources.enter(DirtySources.BLOCK);
	}

	@Inject(method = {"blockChanged", "setBlocksDirty"}, at = @At("RETURN"))
	private void aetherium$exit(CallbackInfo ci) {
		DirtySources.exit(DirtySources.BLOCK);
	}
}
