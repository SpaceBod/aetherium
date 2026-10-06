package dev.spacebod.aetherium.shaders;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import dev.spacebod.aetherium.shaders.platform.ShaderPlatform;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Process-wide shader-pack state: logger, debug-options flag, shaderpacks directory, pending option values and the
 * dimension lookup packs see.
 */
public final class AetheriumShaders {
	public static final Logger logger = LogManager.getLogger("Aetherium Shaders");
	private static final Config CONFIG = new Config();

	private AetheriumShaders() {
	}

	public static Config getShaderConfig() {
		return CONFIG;
	}

	public static final class Config {
		/** {@code -Daetherium.shaders.debug=true}: indented shader printing and patched sources dumped to disk. */
		public boolean areDebugOptionsEnabled() {
			return Boolean.getBoolean("aetherium.shaders.debug");
		}
	}

	private static final Map<String, String> SHADER_PACK_OPTION_QUEUE = new HashMap<>();

	/** {@code <game dir>/shaderpacks}. */
	public static Path getShaderpacksDirectory() {
		return ShaderPlatform.getInstance().getGameDir().resolve("shaderpacks");
	}

	/** Option values changed in the settings screen and not applied yet (read by the option menu model too). */
	public static Map<String, String> getShaderPackOptionQueue() {
		return SHADER_PACK_OPTION_QUEUE;
	}

	/** The level's dimension id (the pack's dimension.properties lookup is done by the engine). */
	public static NamespacedId getCurrentDimension() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return new NamespacedId("minecraft", "overworld");
		}
		return new NamespacedId(level.dimension().identifier().getNamespace(), level.dimension().identifier().getPath());
	}
}
