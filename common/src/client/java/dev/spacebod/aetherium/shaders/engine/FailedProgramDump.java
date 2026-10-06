package dev.spacebod.aetherium.shaders.engine;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.pipeline.transform.PatchShaderType;
import dev.spacebod.aetherium.shaders.platform.ShaderPlatform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * A program the driver compiler rejected: its converted stages are written to
 * {@code <game dir>/shader-dump/failed/<program>.<stage>.glsl}, so the error's line numbers can be looked up.
 */
final class FailedProgramDump {
	private FailedProgramDump() {
	}

	static void write(String name, Map<PatchShaderType, String> stages) {
		Path dir = ShaderPlatform.getInstance().getGameDir().resolve("shader-dump").resolve("failed");
		try {
			Files.createDirectories(dir);
			String base = name.replaceAll("[^A-Za-z0-9_.-]", "_");
			for (Map.Entry<PatchShaderType, String> e : stages.entrySet()) {
				if (e.getValue() != null) {
					Files.writeString(dir.resolve(base + "." + e.getKey().name().toLowerCase(Locale.ROOT) + ".glsl"), e.getValue());
				}
			}
			AetheriumShaders.logger.info("[{}] converted source written to {}", name, dir.toAbsolutePath());
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.warn("[{}] could not write the converted source", name, e);
		}
	}
}
