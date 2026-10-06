package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.client.zoom.Zoom;
import dev.spacebod.aetherium.shaders.gui.ShaderKeys;
import java.util.Arrays;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the Aetherium Shaders key bindings to vanilla's list before options.txt is read, so they are saved and
 * rebindable; and lets the active pack's {@code clouds} directive override the cloud mode.
 */
@Mixin(Options.class)
abstract class OptionsMixin {
	@Shadow
	@Final
	@Mutable
	public KeyMapping[] keyMappings;

	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;load()V"))
	private void aetherium$addKeys(CallbackInfo ci) {
		KeyMapping[] keys = Arrays.copyOf(keyMappings, keyMappings.length + ShaderKeys.ALL.length + 1);
		System.arraycopy(ShaderKeys.ALL, 0, keys, keyMappings.length, ShaderKeys.ALL.length);
		keys[keys.length - 1] = Zoom.KEY;
		keyMappings = keys;
	}

	@Shadow
	@Final
	private OptionInstance<Integer> renderDistance;

	/** {@code clouds=off/fast/fancy} in shaders.properties wins over the player's setting while the pack runs. */
	@Inject(method = "getCloudStatus", at = @At("HEAD"), cancellable = true)
	private void aetherium$packClouds(CallbackInfoReturnable<CloudStatus> cir) {
		// Vanilla draws no clouds below 4 chunks; injecting at the head must keep that.
		if (renderDistance.get() < 4) {
			return;
		}
		CloudStatus pack = ShaderPackEngine.get().packCloudStatus();
		if (pack != null) {
			cir.setReturnValue(pack);
		}
	}
}
