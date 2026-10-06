package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla's underwater overlay is drawn under a pack only when it sets {@code underwaterOverlay=true}. */
@Mixin(ScreenEffectRenderer.class)
abstract class ScreenEffectRendererMixin {
	@Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
	private static void aetherium$packUnderwaterOverlay(CallbackInfo ci) {
		PackDirectives d = ShaderPackEngine.get().activeDirectives();
		if (d != null && !d.underwaterOverlay()) {
			ci.cancel();
		}
	}
}
