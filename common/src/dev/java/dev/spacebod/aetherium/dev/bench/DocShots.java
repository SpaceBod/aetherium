package dev.spacebod.aetherium.dev.bench;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spacebod.aetherium.Aetherium;
import dev.spacebod.aetherium.client.gui.AetheriumSettingsScreen;
import dev.spacebod.aetherium.client.zoom.Zoom;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.gui.ShaderPackScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.phys.AABB;

/**
 * Play mode with {@code AETHERIUM_DOC_SHOTS=1}: a scripted tour that takes the documentation screenshots
 * ({@code screenshots/doc-*.png}): the Options screen, the Shaders settings page, the shader pack screen with a
 * changed option, the Vulkan prompt, the zoom readout on a mob, and the loading indicator. Then the game quits.
 * Starts once the pack is active; each step waits {@link #STEP_SECONDS} for the screen to settle before its shot.
 */
final class DocShots {
	private static final double STEP_SECONDS = 2.5;

	/** One step: what to do when it starts, and the screenshot name taken at its end (null: no shot). */
	private record Step(Consumer<Minecraft> action, String shot) {
	}

	private final List<Step> steps = new ArrayList<>();
	private int index = -1;
	private double stepStart;
	private boolean done;

	DocShots() {
		steps.add(new Step(mc -> mc.gui.setScreen(new OptionsScreen(null, mc.options)), "options"));
		// The Shaders page (the second) of the settings screen.
		steps.add(new Step(mc -> {
			AetheriumSettingsScreen settings = new AetheriumSettingsScreen(null);
			mc.gui.setScreen(settings);
			settings.showPage(1);
		}, "settings"));
		// The pack screen with the keyboard on an option (its details show) that has been stepped once, so it shows as
		// changed with its Reset button and the status line counts it (pending only: closing never applies it).
		steps.add(new Step(mc -> mc.gui.setScreen(new ShaderPackScreen(null)), null));
		steps.add(new Step(mc -> {
			press(mc, InputConstants.KEY_DOWN, 3);
			press(mc, InputConstants.KEY_RIGHT, 1);
		}, "pack-screen"));
		steps.add(new Step(mc -> mc.gui.setScreen(new ConfirmScreen(yes -> mc.gui.setScreen(null),
				Component.translatable("aetherium.vulkan.switch.title"), Component.translatable("aetherium.vulkan.switch.message"),
				Component.translatable("aetherium.vulkan.switch.yes"), Component.translatable("aetherium.vulkan.switch.no"))), "vulkan-prompt"));
		// A cow ahead, held still; then the camera is turned onto it (the summon uses the server's view of the player's
		// rotation, so aiming at where it actually landed is what puts it under the crosshair) and zoomed.
		steps.add(new Step(mc -> {
			mc.gui.setScreen(null);
			command(mc, "execute as @p at @p anchored eyes run summon minecraft:cow ^ ^-1 ^12 {NoAI:1b,NoGravity:1b,Rotation:[200f,0f]}");
		}, null));
		steps.add(new Step(mc -> {
			aimAtCow(mc);
			Zoom.KEY.setDown(true);
		}, "zoom-mob"));
		steps.add(new Step(mc -> {
			Zoom.KEY.setDown(false);
			ShaderPackEngine.get().reload();
		}, null));
		steps.add(new Step(mc -> {
		}, "loading"));
	}

	/** Every client tick in play mode, with the seconds since play started; returns true when the tour is over. */
	boolean tick(Minecraft mc, double seconds) {
		if (done) {
			return true;
		}
		ShaderPackEngine engine = ShaderPackEngine.get();
		if (index < 0) {
			// Start once the world and the pack are ready.
			if (seconds < 10 || !engine.running() || engine.loading()) {
				return false;
			}
			next(mc, seconds);
			return false;
		}
		// The loading shot is taken early, while the pack is still loading.
		double wait = "loading".equals(steps.get(index).shot()) ? 0.3 : STEP_SECONDS;
		if (seconds - stepStart < wait) {
			return false;
		}
		String shot = steps.get(index).shot();
		if (shot != null) {
			Screenshot.grab(mc.gameDirectory, "doc-" + shot + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> {
			});
			Aetherium.LOG.info("Doc shots: {}", shot);
		}
		if (index + 1 >= steps.size()) {
			done = true;
			Aetherium.LOG.info("Doc shots: done");
			mc.stop();
			return true;
		}
		next(mc, seconds);
		return false;
	}

	private void next(Minecraft mc, double seconds) {
		index++;
		stepStart = seconds;
		steps.get(index).action().accept(mc);
	}

	private static void press(Minecraft mc, int key, int times) {
		Screen screen = mc.gui.screen();
		if (screen == null) {
			return;
		}
		for (int i = 0; i < times; i++) {
			screen.keyPressed(new KeyEvent(key, 0, 0));
		}
	}

	/** Turns the camera so the crosshair sits on the middle of the nearest cow. */
	private static void aimAtCow(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		AABB around = mc.player.getBoundingBox().inflate(32.0);
		Cow nearest = null;
		for (Cow cow : mc.level.getEntitiesOfClass(Cow.class, around)) {
			if (nearest == null || cow.distanceToSqr(mc.player) < nearest.distanceToSqr(mc.player)) {
				nearest = cow;
			}
		}
		if (nearest != null) {
			mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, nearest.getBoundingBox().getCenter());
		}
	}

	private static void command(Minecraft mc, String command) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
		}
	}
}
