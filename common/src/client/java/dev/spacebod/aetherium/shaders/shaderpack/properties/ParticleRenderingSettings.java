package dev.spacebod.aetherium.shaders.shaderpack.properties;

import dev.spacebod.aetherium.shaders.AetheriumShaders;

public enum ParticleRenderingSettings {
	UNSET,
	BEFORE,
	MIXED,
	AFTER;

	public static ParticleRenderingSettings fromString(String name) {
		try {
			return ParticleRenderingSettings.valueOf(name);
		} catch (IllegalArgumentException e) {
			AetheriumShaders.logger.error("Invalid particle rendering settings! " + name);
			return ParticleRenderingSettings.UNSET;
		}
	}
}
