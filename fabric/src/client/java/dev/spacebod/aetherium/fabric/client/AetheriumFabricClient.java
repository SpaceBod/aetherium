package dev.spacebod.aetherium.fabric.client;

import dev.spacebod.aetherium.client.AetheriumClient;
import net.fabricmc.api.ClientModInitializer;

public final class AetheriumFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		AetheriumClient.init();
	}
}
