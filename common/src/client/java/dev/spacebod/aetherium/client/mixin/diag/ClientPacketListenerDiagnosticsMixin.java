package dev.spacebod.aetherium.client.mixin.diag;

import com.mojang.brigadier.CommandDispatcher;
import dev.spacebod.aetherium.client.diag.DiagnosticsCommand;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code /aetherium ...} ({@link DiagnosticsCommand}): handled here instead of being sent, and offered in tab completion. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerDiagnosticsMixin {
	@Shadow
	private CommandDispatcher<ClientSuggestionProvider> commands;

	@Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
	private void aetherium$diagnosticsCommand(String command, CallbackInfo ci) {
		if (DiagnosticsCommand.handle(command)) {
			ci.cancel();
		}
	}

	@Inject(method = "handleCommands", at = @At("TAIL"))
	private void aetherium$suggestDiagnostics(ClientboundCommandsPacket packet, CallbackInfo ci) {
		DiagnosticsCommand.register(commands);
	}
}
