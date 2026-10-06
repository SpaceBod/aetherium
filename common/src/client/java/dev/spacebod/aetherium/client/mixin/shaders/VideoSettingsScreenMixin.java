package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.client.gui.AetheriumSettingsScreen;
import dev.spacebod.aetherium.shaders.gui.ShaderPackScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** "Shader Packs…" and "Aetherium Settings…" side by side at the top of Video Settings, where players look for them. */
@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
	private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At("HEAD"))
	private void aetherium$entries(CallbackInfo ci) {
		if (list != null) {
			list.addSmall(
					Button.builder(Component.translatable("options.aetherium.shader_packs"),
							button -> minecraft.gui.setScreen(new ShaderPackScreen(this))).build(),
					Button.builder(Component.translatable("options.aetherium.settings"),
							button -> minecraft.gui.setScreen(new AetheriumSettingsScreen(this))).build());
		}
	}
}
