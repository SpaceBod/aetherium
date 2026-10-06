package dev.spacebod.aetherium.client.mixin.hud;

import dev.spacebod.aetherium.client.Crosshair;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The crosshair, with the attack indicator under it, moved onto the window's centre ({@link Crosshair}). */
@Mixin(Hud.class)
abstract class CrosshairMixin {
	@Inject(method = "extractCrosshair", at = @At("HEAD"))
	private void aetherium$centre(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(Crosshair.shiftX(), Crosshair.shiftY());
	}

	@Inject(method = "extractCrosshair", at = @At("RETURN"))
	private void aetherium$centreEnd(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		graphics.pose().popMatrix();
	}
}
