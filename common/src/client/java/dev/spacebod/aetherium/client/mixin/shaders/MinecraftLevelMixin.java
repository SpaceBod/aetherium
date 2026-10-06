package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Aetherium Shaders: entering a world or a dimension starts loading the pack while the terrain loads. */
@Mixin(Minecraft.class)
abstract class MinecraftLevelMixin {
	@Inject(method = "setLevel", at = @At("TAIL"))
	private void aetherium$levelSet(ClientLevel level, CallbackInfo ci) {
		ShaderPackEngine.get().onLevelSet();
	}
}
