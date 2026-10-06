package dev.spacebod.aetherium.shaders.platform;

import java.nio.file.Path;
import net.minecraft.client.Minecraft;

/**
 * Platform service for the shader pipeline: the game directory (debug output of patched shaders) and the
 * development-environment flag.
 */
public interface ShaderPlatform {
	/** The game directory; the working directory without a running game (the compile harness). */
	ShaderPlatform INSTANCE = () -> Minecraft.getInstance() == null ? Path.of(".") : Minecraft.getInstance().gameDirectory.toPath();

	static ShaderPlatform getInstance() {
		return INSTANCE;
	}

	Path getGameDir();

	default boolean isDevelopmentEnvironment() {
		return Boolean.getBoolean("aetherium.dev");
	}
}
