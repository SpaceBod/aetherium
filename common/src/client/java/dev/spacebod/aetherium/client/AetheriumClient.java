package dev.spacebod.aetherium.client;

import dev.spacebod.aetherium.Aetherium;
import dev.spacebod.aetherium.core.AetheriumConfig;

/** Loader-independent client start-up, called from each loader's client entrypoint. */
public final class AetheriumClient {
	private AetheriumClient() {
	}

	public static void init() {
		AetheriumConfig config = AetheriumConfig.get();
		Aetherium.LOG.info("Aetherium starting (profile '{}')", config.profile);
		dev.spacebod.aetherium.shaders.compat.lod.LodCompat.init();
		dev.spacebod.aetherium.shaders.engine.ExtendedVertexSerializers.register();
	}
}
