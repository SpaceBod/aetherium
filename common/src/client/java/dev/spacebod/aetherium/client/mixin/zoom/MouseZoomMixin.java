package dev.spacebod.aetherium.client.mixin.zoom;

import dev.spacebod.aetherium.client.zoom.Zoom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While zoomed in game, the scroll wheel sets the magnification instead of moving through the hotbar. */
@Mixin(MouseHandler.class)
abstract class MouseZoomMixin {
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void aetherium$scrollZoom(long window, double xOffset, double yOffset, CallbackInfo ci) {
		if (Minecraft.getInstance().gui.screen() == null && Zoom.scroll(yOffset)) {
			ci.cancel();
		}
	}
}
