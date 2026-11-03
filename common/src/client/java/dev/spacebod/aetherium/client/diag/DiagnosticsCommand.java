package dev.spacebod.aetherium.client.diag;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.spacebod.aetherium.client.gui.AetheriumSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * {@code /aetherium diagnostics} (or {@code /aetherium}) opens the Diagnostics page, {@code /aetherium record} starts a
 * recording. Answered on the client before the command would go to the server, on any loader and any server.
 */
public final class DiagnosticsCommand {
	private static final String ROOT = "aetherium";

	private DiagnosticsCommand() {
	}

	/** Whether {@code command} (without the slash) was ours; it is not sent on then. */
	public static boolean handle(String command) {
		String c = command.trim();
		if (!c.equals(ROOT) && !c.startsWith(ROOT + " ")) {
			return false;
		}
		String argument = c.substring(ROOT.length()).trim();
		Minecraft mc = Minecraft.getInstance();
		switch (argument) {
			case "", "diagnostics", "diag" ->
				// After the chat screen has closed itself.
					mc.schedule(() -> mc.gui.setScreen(AetheriumSettingsScreen.onPage(null, "diagnostics")));
			case "record" -> {
				Diagnostics.requestRecording();
				reply("Aetherium diagnostics: recording starts in 3 s. Stand next to the problem, still at first, then walk a few blocks.");
			}
			default -> reply("Usage: /aetherium diagnostics | /aetherium record");
		}
		return true;
	}

	/** Adds the command to the client's tab completion (the server's command tree replaces the client's on join). */
	public static void register(CommandDispatcher<ClientSuggestionProvider> dispatcher) {
		dispatcher.register(LiteralArgumentBuilder.<ClientSuggestionProvider>literal(ROOT)
				.then(LiteralArgumentBuilder.literal("diagnostics"))
				.then(LiteralArgumentBuilder.literal("record")));
	}

	private static void reply(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal(text));
		}
	}
}
