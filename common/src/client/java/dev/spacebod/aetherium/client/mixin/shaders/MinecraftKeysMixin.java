package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.client.zoom.Zoom;
import dev.spacebod.aetherium.shaders.gui.ShaderKeys;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Aetherium's key bindings (shaders, zoom), handled with vanilla's own key bindings every tick. */
@Mixin(Minecraft.class)
abstract class MinecraftKeysMixin {
	@Inject(method = "handleKeybinds", at = @At("TAIL"))
	private void aetherium$shaderKeys(CallbackInfo ci) {
		ShaderKeys.handle((Minecraft) (Object) this);
		Zoom.tick((Minecraft) (Object) this);
	}
}
