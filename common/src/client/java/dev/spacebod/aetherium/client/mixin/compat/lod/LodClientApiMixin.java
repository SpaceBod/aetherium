package dev.spacebod.aetherium.client.mixin.compat.lod;

import dev.spacebod.aetherium.shaders.compat.lod.LodCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The LOD mod's render entry points under a shader pack: its vanilla-fade passes (they blend its own image into
 * vanilla's main colour, which the pack's world passes do not use) stand down whenever a pack draws the world, and its
 * own deferred transparent call (the engine makes that call itself, after the pack's deferred passes) while the pack
 * draws the LODs. Skipped when the mod is absent.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public class LodClientApiMixin {
	@Inject(method = "renderFadeOpaque", at = @At("HEAD"), cancellable = true, require = 0)
	private void aetherium$fadeOpaque(CallbackInfo ci) {
		if (packActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderFadeTransparent", at = @At("HEAD"), cancellable = true, require = 0)
	private void aetherium$fadeTransparent(CallbackInfo ci) {
		if (packActive()) {
			ci.cancel();
		}
	}

	/**
	 * Any active pack, whether it draws the LODs or hides them: the fades blend the mod's own image into vanilla's main
	 * colour, which a pack's passes replace.
	 */
	private static boolean packActive() {
		return LodCompat.packDrawsLods() || dev.spacebod.aetherium.shaders.engine.ShaderPackEngine.get().worldActive();
	}

	@Inject(method = "renderDeferredLodsForShaders", at = @At("HEAD"), cancellable = true, require = 0)
	private void aetherium$deferred(CallbackInfo ci) {
		if (LodCompat.packDrawsLods() && !LodCompat.inEngineDeferredCall()) {
			ci.cancel();
		}
	}
}
