package dev.spacebod.aetherium.neoforge;

import dev.spacebod.aetherium.Aetherium;
import dev.spacebod.aetherium.client.AetheriumClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = Aetherium.MOD_ID, dist = Dist.CLIENT)
public final class AetheriumNeoForge {
	public AetheriumNeoForge() {
		AetheriumClient.init();
	}
}
