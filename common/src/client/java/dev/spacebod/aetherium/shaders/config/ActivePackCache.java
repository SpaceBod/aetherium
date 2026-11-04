package dev.spacebod.aetherium.shaders.config;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The saved pack choice, including shaders off, without file access during steady-state rendering. */
final class ActivePackCache {
	private volatile @Nullable Optional<String> value;

	Optional<String> get(Path file) throws IOException {
		Optional<String> cached = value;
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			if (value == null) {
				JsonObject root = ShaderPackSettings.readConfig(file);
				JsonObject shaders = root.has("shaders") ? root.getAsJsonObject("shaders") : null;
				boolean enabled = shaders != null && (!shaders.has("enabled") || shaders.get("enabled").getAsBoolean());
				value = enabled && shaders.has("pack") ? Optional.of(shaders.get("pack").getAsString()) : Optional.empty();
			}
			return value;
		}
	}

	/** Publish only after the new choice has been saved successfully. */
	synchronized void saved(@Nullable String pack) {
		value = Optional.ofNullable(pack);
	}

	/** The next lookup reads edits made outside the settings screen. */
	synchronized void invalidate() {
		value = null;
	}
}
