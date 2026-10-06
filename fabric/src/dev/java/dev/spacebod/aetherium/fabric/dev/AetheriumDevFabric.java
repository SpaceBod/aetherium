package dev.spacebod.aetherium.fabric.dev;

import dev.spacebod.aetherium.dev.DevClient;
import net.fabricmc.api.ClientModInitializer;

public final class AetheriumDevFabric implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		DevClient.init();
	}
}
