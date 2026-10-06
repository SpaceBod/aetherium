package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla's loaded core shader sources ({@code ShaderManager.Configs}, captured on every resource reload), so vanilla
 * pipelines can be rebuilt with different colour targets (see {@link WorldPrograms}).
 */
public final class VanillaShaderSources {
	private static volatile @Nullable ShaderSource current;

	private VanillaShaderSources() {
	}

	public static void set(ShaderSource source) {
		current = source;
	}

	public static @Nullable ShaderSource get() {
		return current;
	}
}
