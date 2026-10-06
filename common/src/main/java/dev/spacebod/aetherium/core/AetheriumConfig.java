package dev.spacebod.aetherium.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.spacebod.aetherium.Aetherium;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code config/aetherium.json}. Read once, before any mixin is applied, because most optimisations are mixin
 * packages that are either applied or not (see {@link Optimisations}): turning one of those on takes a restart.
 * Optimisations with a runtime switch can also be flipped while the game runs.
 *
 * <pre>
 * {
 *   "profile": "default",
 *   "optimisations": { "mem.palette_lock": false },
 *   "bench": { "gpuTimers": false, "passTimers": false }
 * }
 * </pre>
 *
 * {@code profile} is {@code default} (every optimisation at its default) or {@code baseline} (everything
 * off, what benchmarks compare against). {@code optimisations} overrides single ids on top of the profile.
 * {@code bench} is read only by the development tooling's benchmark driver.
 */
public final class AetheriumConfig {
	public static final String PROFILE_DEFAULT = "default";
	public static final String PROFILE_BASELINE = "baseline";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static volatile AetheriumConfig instance;

	public final String profile;
	public final Map<String, Boolean> overrides;
	public final boolean gpuTimers;
	public final boolean passTimers;

	private AetheriumConfig(String profile, Map<String, Boolean> overrides, boolean gpuTimers, boolean passTimers) {
		this.profile = profile;
		this.overrides = overrides;
		this.gpuTimers = gpuTimers;
		this.passTimers = passTimers;
	}

	/** The game directory is the working directory on both loaders, in dev runs and from launchers. */
	public static Path file() {
		return Path.of("config", "aetherium.json");
	}

	public static AetheriumConfig get() {
		AetheriumConfig config = instance;
		if (config == null) {
			synchronized (AetheriumConfig.class) {
				if (instance == null) {
					instance = load(file());
				}
				config = instance;
			}
		}
		return config;
	}

	/**
	 * The config as saved now, which may differ from {@link #get()} (what this session started with) after the settings
	 * screen saved changes that take effect on the next start.
	 */
	public static AetheriumConfig readSaved() {
		return load(file());
	}

	/**
	 * Saves these settings for the next start, keeping every other section of the file (the shader pack choice lives
	 * there too). Overrides equal to the profile's value are dropped so the file only lists real choices.
	 */
	public static void save(String profile, Map<String, Boolean> overrides, boolean gpuTimers, boolean passTimers) {
		Path path = file();
		JsonObject root = new JsonObject();
		if (Files.isRegularFile(path)) {
			try {
				root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (IOException | RuntimeException e) {
				Aetherium.LOG.warn("Could not read {} before saving; rewriting it", path, e);
			}
		}
		root.addProperty("profile", profile);
		JsonObject opts = new JsonObject();
		new TreeMap<>(overrides).forEach((id, on) -> {
			if (on != Optimisations.profileDefault(profile, id)) {
				opts.addProperty(id, on);
			}
		});
		root.add("optimisations", opts);
		JsonObject bench = new JsonObject();
		bench.addProperty("gpuTimers", gpuTimers);
		bench.addProperty("passTimers", passTimers);
		root.add("bench", bench);
		try {
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Aetherium.LOG.error("Could not save {}", path, e);
		}
	}

	private static AetheriumConfig load(Path path) {
		String profile = PROFILE_DEFAULT;
		Map<String, Boolean> overrides = new TreeMap<>();
		boolean gpuTimers = false;
		boolean passTimers = false;
		if (Files.isRegularFile(path)) {
			try {
				JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
				if (root.has("profile")) {
					profile = root.get("profile").getAsString();
					if (!PROFILE_BASELINE.equals(profile) && !PROFILE_DEFAULT.equals(profile)) {
						Aetherium.LOG.warn("{}: unknown profile '{}' (known: {}, {}); optimisations use their defaults", path, profile,
								PROFILE_DEFAULT, PROFILE_BASELINE);
					}
				}
				if (root.has("optimisations")) {
					for (var e : root.getAsJsonObject("optimisations").entrySet()) {
						// Only true/false: gson reads "on", "yes" or 1 as false without complaint.
						if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isBoolean()) {
							Aetherium.LOG.warn("{}: optimisation '{}' must be true or false, got {}; ignored", path, e.getKey(), e.getValue());
							continue;
						}
						if (Optimisations.ALL.stream().noneMatch(o -> o.id().equals(e.getKey()))) {
							Aetherium.LOG.warn("{}: unknown optimisation '{}' (typo?); it has no effect", path, e.getKey());
						}
						overrides.put(e.getKey(), e.getValue().getAsBoolean());
					}
				}
				if (root.has("bench")) {
					JsonObject bench = root.getAsJsonObject("bench");
					if (bench.has("gpuTimers")) {
						gpuTimers = bench.get("gpuTimers").getAsBoolean();
					}
					if (bench.has("passTimers")) {
						passTimers = bench.get("passTimers").getAsBoolean();
					}
				}
			} catch (IOException | RuntimeException e) {
				Aetherium.LOG.error("Could not read {}, using defaults", path, e);
			}
		} else {
			write(path, profile, overrides, gpuTimers, passTimers);
		}
		return new AetheriumConfig(profile, overrides, gpuTimers, passTimers);
	}

	private static void write(Path path, String profile, Map<String, Boolean> overrides, boolean gpuTimers, boolean passTimers) {
		JsonObject root = new JsonObject();
		root.addProperty("profile", profile);
		JsonObject opts = new JsonObject();
		overrides.forEach(opts::addProperty);
		root.add("optimisations", opts);
		JsonObject bench = new JsonObject();
		bench.addProperty("gpuTimers", gpuTimers);
		bench.addProperty("passTimers", passTimers);
		root.add("bench", bench);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Aetherium.LOG.warn("Could not write default {}", path, e);
		}
	}
}
