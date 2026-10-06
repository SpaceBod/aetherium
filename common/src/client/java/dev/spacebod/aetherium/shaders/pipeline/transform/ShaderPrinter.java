package dev.spacebod.aetherium.shaders.pipeline.transform;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.platform.ShaderPlatform;
import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Dumps patched shader sources to {@code <gameDir>/patched_shaders} when debug options are enabled.
 */
public class ShaderPrinter {
	private static final Path debugOutDir = ShaderPlatform.getInstance().getGameDir().resolve("patched_shaders");
	private static boolean outputLocationCleared = false;
	private static int programCounter = 0;

	public static ProgramPrintBuilder printProgram(String name) {
		return new ProgramPrintBuilder(name);
	}

	public static class ProgramPrintBuilder {
		// Snapshot of the debug flag, so a mid-build toggle doesn't produce a partial dump.
		private final boolean isActive = AetheriumShaders.getShaderConfig().areDebugOptionsEnabled();

		// Fixed at construction so every source of this program shares one counter prefix.
		private final String prefix = isActive ? String.format("%03d_", ++programCounter) : null;

		// Alternating file name, content; null when debug is disabled.
		private final List<String> sources = isActive ? new ArrayList<>(PatchShaderType.values().length * 2) : null;

		private String name;
		private boolean done = false; // makes the print function idempotent

		public ProgramPrintBuilder(String name) {
			setName(name);
		}

		public ProgramPrintBuilder setName(String name) {
			this.name = name;
			return this;
		}

		private void addItem(String extension, String content) {
			if (content != null && sources != null) {
				sources.add(prefix + name + extension);
				sources.add(content);
			}
		}

		public ProgramPrintBuilder addSource(PatchShaderType type, String source) {
			if (sources == null) {
				return this;
			}
			addItem(type.extension, source);
			return this;
		}

		public ProgramPrintBuilder addSources(Map<PatchShaderType, String> sources) {
			if (sources == null) {
				return this;
			}
			for (Map.Entry<PatchShaderType, String> entry : sources.entrySet()) {
				addSource(entry.getKey(), entry.getValue());
			}
			return this;
		}

		public void print() {
			if (done) {
				return;
			}
			done = true;
			if (isActive) {
				if (!outputLocationCleared) {
					try {
						if (Files.exists(debugOutDir)) {
							try (Stream<Path> stream = Files.list(debugOutDir).filter(s -> !FilenameUtils.getExtension(s.toString()).contains("properties"))) {
								stream.forEach(path -> {
									try {
										Files.delete(path);
									} catch (IOException e) {
										throw new RuntimeException(e);
									}
								});
							}
						}

						Files.createDirectories(debugOutDir);
					} catch (IOException e) {
						AetheriumShaders.logger.warn("Failed to initialize debug patched shader source location", e);
					}
					outputLocationCleared = true;
				}

				try {
					for (int i = 0; i < sources.size(); i += 2) {
						Files.writeString(debugOutDir.resolve(sources.get(i)), sources.get(i + 1));
					}
				} catch (IOException e) {
					AetheriumShaders.logger.warn("Failed to write debug patched shader source", e);
				}
			}
		}
	}
}
