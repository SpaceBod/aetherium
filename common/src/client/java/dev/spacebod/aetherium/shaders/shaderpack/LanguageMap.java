package dev.spacebod.aetherium.shaders.shaderpack;

import com.google.common.collect.ImmutableMap;
import dev.spacebod.aetherium.shaders.AetheriumShaders;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/** A pack's {@code lang/*.lang} files: option, screen and value labels per language. */
public class LanguageMap {
	private final Map<String, Map<String, String>> translationMaps;

	public LanguageMap(Path root) throws IOException {
		this.translationMaps = new HashMap<>();

		if (!Files.exists(root)) {
			return;
		}

		// Only the files directly inside the lang folder; subdirectories are ignored.
		try (Stream<Path> stream = Files.list(root)) {
			stream.filter(path -> !Files.isDirectory(path)).forEach(path -> {
				// Packs use legacy properties-format files named like "en_US.lang", not vanilla's "en_us.json".
				String currentFileName = path.getFileName().toString().toLowerCase(Locale.ROOT);

				if (!currentFileName.endsWith(".lang")) {
					return;
				}

				String currentLangCode = currentFileName.substring(0, currentFileName.lastIndexOf("."));
				Properties properties = new Properties();

				// Pack language files are UTF-8; Properties.load(InputStream) would assume ISO-8859-1.
				try {
					properties.load(new StringReader(PackFiles.readText(path)));
				} catch (IOException | IllegalArgumentException e) {
					AetheriumShaders.logger.error("could not read the pack language file {}: {}", path, e.toString());
				}

				ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

				properties.forEach((key, value) -> builder.put(key.toString(), value.toString()));

				translationMaps.put(currentLangCode, builder.build());
			});
		}
	}

	/** The pack's translations for {@code language} (lower case, e.g. {@code en_us}); empty when it ships none. */
	public Map<String, String> getTranslations(String language) {
		return translationMaps.getOrDefault(language, Map.of());
	}
}
