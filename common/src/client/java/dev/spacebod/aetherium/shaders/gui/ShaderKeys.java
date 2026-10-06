package dev.spacebod.aetherium.shaders.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spacebod.aetherium.shaders.config.ShaderPackSettings;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import java.util.Optional;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Aetherium Shaders key bindings (rebindable in Controls): O opens the shader pack settings, K turns shaders on and off
 * (the configured pack stays selected; turning it back on reads its files again), and an unbound key reloads the active
 * pack from disk.
 */
public final class ShaderKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("aetherium", "shaders"));
	public static final KeyMapping OPEN_SETTINGS = new KeyMapping("key.aetherium.shader_settings", InputConstants.KEY_O, CATEGORY);
	public static final KeyMapping TOGGLE = new KeyMapping("key.aetherium.shader_toggle", InputConstants.KEY_K, CATEGORY);
	public static final KeyMapping RELOAD = new KeyMapping("key.aetherium.shader_reload", InputConstants.UNKNOWN.getValue(), CATEGORY);
	/** Every key above, for vanilla's key list. */
	public static final KeyMapping[] ALL = {OPEN_SETTINGS, TOGGLE, RELOAD};

	private ShaderKeys() {
	}

	/** Once per client tick, after vanilla's own key handling (between frames: no render pass is open). */
	public static void handle(Minecraft minecraft) {
		while (OPEN_SETTINGS.consumeClick()) {
			if (minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new ShaderPackScreen(null));
			}
		}
		while (TOGGLE.consumeClick()) {
			if (minecraft.gui.screen() == null) {
				boolean on = ShaderPackSettings.activePack().isPresent();
				Optional<String> configured = ShaderPackSettings.configuredPack();
				if (!on && configured.isEmpty()) {
					message(minecraft, Component.translatable("message.aetherium.shaders_none"));
					continue;
				}
				ShaderPackSettings.setActivePack(on ? null : configured.get());
				// The pack's files may have changed while it was off: the settings screen reads them again too.
				PackOptions.forgetAll();
				ShaderPackEngine.get().reload();
				message(minecraft, on ? Component.translatable("message.aetherium.shaders_off")
						: Component.translatable("message.aetherium.shaders_on", ShaderPackSettings.displayName(configured.get())));
			}
		}
		while (RELOAD.consumeClick()) {
			if (minecraft.gui.screen() == null) {
				Optional<String> active = ShaderPackSettings.activePack();
				if (active.isEmpty()) {
					message(minecraft, Component.translatable("message.aetherium.shaders_none"));
					continue;
				}
				// Read again from disk: the settings screen's copies too.
				PackOptions.forgetAll();
				ShaderPackEngine.get().reload();
				message(minecraft, Component.translatable("message.aetherium.shaders_reloading", ShaderPackSettings.displayName(active.get())));
			}
		}
	}

	private static void message(Minecraft minecraft, Component text) {
		minecraft.gui.hud.setOverlayMessage(text, false);
	}
}
