package dev.spacebod.aetherium.client.mixin.shaders;

import com.llamalad7.mixinextras.sugar.Local;
import dev.spacebod.aetherium.client.gui.AetheriumSettingsScreen;
import dev.spacebod.aetherium.shaders.gui.ShaderPackScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Aetherium…" and "Shader Packs…" as the last row of the Options grid, so both are reachable whichever screen video
 * settings open.
 */
@Mixin(OptionsScreen.class)
abstract class OptionsScreenMixin {
	@Inject(method = "init", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToContents(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;"))
	private void aetherium$entries(CallbackInfo ci, @Local GridLayout.RowHelper rows) {
		Screen self = (Screen) (Object) this;
		rows.addChild(Button.builder(Component.translatable("options.aetherium.menu"),
				button -> Minecraft.getInstance().gui.setScreen(new AetheriumSettingsScreen(self))).build());
		rows.addChild(Button.builder(Component.translatable("options.aetherium.shader_packs"),
				button -> Minecraft.getInstance().gui.setScreen(new ShaderPackScreen(self))).build());
	}
}
