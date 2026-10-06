package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code weather=false}: the pack draws its own rain and snow, vanilla's is not drawn while it runs. */
@Mixin(WeatherEffectRenderer.class)
abstract class WeatherEffectRendererMixin {
	private static boolean aetherium$hidden() {
		PackDirectives d = ShaderPackEngine.get().activeDirectives();
		return d != null && !d.shouldRenderWeather();
	}

	@Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
			at = @At("HEAD"), cancellable = true)
	private void aetherium$weather(CallbackInfo ci) {
		if (aetherium$hidden()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
	private void aetherium$weatherOit(CallbackInfo ci) {
		if (aetherium$hidden()) {
			ci.cancel();
		}
	}
}
