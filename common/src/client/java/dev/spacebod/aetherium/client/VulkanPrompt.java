package dev.spacebod.aetherium.client;

import dev.spacebod.aetherium.client.gpu.VulkanAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Once per launch, when the title screen first shows on a game not running on Vulkan (which Aetherium's shader engine and
 * GPU work need): if the Graphics API setting is not Vulkan, an offer to switch it and quit so the next start uses it;
 * if it already is (Vulkan could not start on this PC and the game fell back to OpenGL), a note saying so.
 */
public final class VulkanPrompt {
	private static boolean done;

	private VulkanPrompt() {
	}

	/** Every client tick. */
	public static void tick(Minecraft mc) {
		if (done || mc.gui.overlay() != null || !(mc.gui.screen() instanceof TitleScreen title)) {
			return;
		}
		done = true;
		if (VulkanAccess.device() != null) {
			return;
		}
		var api = mc.options.preferredGraphicsBackend();
		Screen prompt;
		if (api.get() == PreferredGraphicsApi.VULKAN) {
			prompt = new AlertScreen(() -> mc.gui.setScreen(title), Component.translatable("aetherium.vulkan.failed.title"),
					Component.translatable("aetherium.vulkan.failed.message"));
		} else {
			prompt = new ConfirmScreen(switchNow -> {
				if (switchNow) {
					api.set(PreferredGraphicsApi.VULKAN);
					mc.options.save();
					mc.stop();
				} else {
					mc.gui.setScreen(title);
				}
			}, Component.translatable("aetherium.vulkan.switch.title"), Component.translatable("aetherium.vulkan.switch.message"),
					Component.translatable("aetherium.vulkan.switch.yes"), Component.translatable("aetherium.vulkan.switch.no"));
		}
		mc.gui.setScreen(prompt);
	}
}
