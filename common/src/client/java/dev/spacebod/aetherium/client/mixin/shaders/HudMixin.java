package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.client.HudCorner;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code vignette=false} in a pack's shaders.properties hides vanilla's screen vignette while the pack runs; Aetherium's
 * corner panels ({@link HudCorner}) go over the HUD.
 */
@Mixin(Hud.class)
abstract class HudMixin {
	@Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
	private void aetherium$packVignette(CallbackInfo ci) {
		PackDirectives d = ShaderPackEngine.get().activeDirectives();
		if (d != null && !d.vignette()) {
			ci.cancel();
		}
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void aetherium$loadIndicator(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		HudCorner.draw(graphics);
	}
}
