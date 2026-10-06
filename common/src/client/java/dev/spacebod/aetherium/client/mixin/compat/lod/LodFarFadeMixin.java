package dev.spacebod.aetherium.client.mixin.compat.lod;

import dev.spacebod.aetherium.shaders.compat.lod.LodCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The LOD mod's far-clip fade while a shader pack draws the LODs: it only writes the mod's own colour texture, whose
 * copy into the frame is cancelled under a pack, so the full-screen pass (and its copy) would be thrown away.
 * Skipped when the mod is absent.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeDhFarFadeRenderer", remap = false)
public class LodFarFadeMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
	private void aetherium$skipUnderPack(CallbackInfo ci) {
		if (LodCompat.packDrawsLods()) {
			ci.cancel();
		}
	}
}
