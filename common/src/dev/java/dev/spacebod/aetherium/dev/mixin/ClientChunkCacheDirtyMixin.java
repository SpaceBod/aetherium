package dev.spacebod.aetherium.dev.mixin;

import dev.spacebod.aetherium.dev.bench.DirtySources;
import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tags dirty marks from the client light engine's publish notifications. */
@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheDirtyMixin {
	@Inject(method = "onLightUpdate", at = @At("HEAD"))
	private void aetherium$enter(CallbackInfo ci) {
		DirtySources.enter(DirtySources.LIGHT_ENGINE);
	}

	@Inject(method = "onLightUpdate", at = @At("RETURN"))
	private void aetherium$exit(CallbackInfo ci) {
		DirtySources.exit(DirtySources.LIGHT_ENGINE);
	}
}
