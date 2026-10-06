package dev.spacebod.aetherium.shaders.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.shaderpack.ShadowOverrides;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Where Aetherium Shaders keeps its choices: the active pack, the shadow settings and the render scale in
 * {@code config/aetherium.json} ({@code "shaders": { "enabled", "pack", "shadowDistance", "shadowMapScale", "renderScale",
 * "upscaleSharpness" }}), and each pack's changed options in {@code shaderpacks/<pack>.txt}, the options file the pack
 * format defines.
 */
public final class ShaderPackSettings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ShaderPackSettings() {
	}

	public static Path packsDirectory() {
		return AetheriumShaders.getShaderpacksDirectory();
	}

	private static Path configFile() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("aetherium.json");
	}

	/** The configured pack when shaders are enabled, else empty. */
	public static Optional<String> activePack() {
		try {
			JsonObject root = readConfig();
			if (!root.has("shaders")) {
				return Optional.empty();
			}
			JsonObject shaders = root.getAsJsonObject("shaders");
			boolean enabled = !shaders.has("enabled") || shaders.get("enabled").getAsBoolean();
			return enabled && shaders.has("pack") ? Optional.of(shaders.get("pack").getAsString()) : Optional.empty();
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.warn("could not read {}", configFile(), e);
			return Optional.empty();
		}
	}

	/** The configured pack, whether or not shaders are enabled. */
	public static Optional<String> configuredPack() {
		try {
			JsonObject root = readConfig();
			JsonObject shaders = root.has("shaders") ? root.getAsJsonObject("shaders") : null;
			return shaders != null && shaders.has("pack") ? Optional.of(shaders.get("pack").getAsString()) : Optional.empty();
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.warn("could not read {}", configFile(), e);
			return Optional.empty();
		}
	}

	/** Sets the active pack (null = shaders off), keeping every other setting in the file. */
	public static void setActivePack(@Nullable String pack) {
		try {
			JsonObject root = readConfig();
			JsonObject shaders = root.has("shaders") ? root.getAsJsonObject("shaders") : new JsonObject();
			shaders.addProperty("enabled", pack != null);
			if (pack != null) {
				shaders.addProperty("pack", pack);
			}
			root.add("shaders", shaders);
			writeAtomically(configFile(), GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.error("could not write {}", configFile(), e);
		}
	}

	/**
	 * The player's shadow distance and shadow map scale ({@code "shaders": { "shadowDistance": chunks, "shadowMapScale":
	 * percent }}); the pack's own values when unset.
	 */
	public static ShadowOverrides shadowOverrides() {
		try {
			JsonObject root = readConfig();
			JsonObject shaders = root.has("shaders") ? root.getAsJsonObject("shaders") : null;
			if (shaders == null) {
				return ShadowOverrides.NONE;
			}
			int distance = shaders.has("shadowDistance") ? shaders.get("shadowDistance").getAsInt() : 0;
			int scale = shaders.has("shadowMapScale") ? shaders.get("shadowMapScale").getAsInt() : 100;
			return new ShadowOverrides(distance, scale);
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.warn("could not read {}", configFile(), e);
			return ShadowOverrides.NONE;
		}
	}

	/** Saves the shadow distance and map scale, keeping every other setting in the file. */
	public static void setShadowOverrides(ShadowOverrides shadows) {
		try {
			JsonObject root = readConfig();
			JsonObject shaders = root.has("shaders") ? root.getAsJsonObject("shaders") : new JsonObject();
			shaders.addProperty("shadowDistance", shadows.distanceChunks());
			shaders.addProperty("shadowMapScale", shadows.mapScale());
			root.add("shaders", shaders);
			writeAtomically(configFile(), GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.error("could not write {}", configFile(), e);
		}
	}

	/** The render scale in percent of the window ({@code "shaders": { "renderScale" }}); 100 (off) when unset. */
	public static int renderScale() {
		return readShaderInt("renderScale", 100);
	}

	public static void setRenderScale(int percent) {
		writeShaderInt("renderScale", percent);
	}

	/** The upscale's sharpness in hundredths of a stop, 0 = strongest ({@code "shaders": { "upscaleSharpness" }}). */
	public static int upscaleSharpness() {
		return readShaderInt("upscaleSharpness", 20);
	}

	public static void setUpscaleSharpness(int hundredths) {
		writeShaderInt("upscaleSharpness", hundredths);
	}

	/**
	 * One of the interface settings, {@code "<section>": { key }}: under {@code zoom}, {@code corner} (top_right, top_left,
	 * bottom_right or bottom_left), {@code coordinates} and {@code target} (whether each panel shows), and {@code min},
	 * {@code max} and {@code nominal} (magnifications in tenths); under {@code crosshair}, {@code centred}.
	 */
	public static JsonElement interfaceSetting(String section, String key, JsonElement fallback) {
		try {
			JsonObject root = readConfig();
			JsonObject group = root.has(section) ? root.getAsJsonObject(section) : null;
			return group != null && group.has(key) ? group.get(key) : fallback;
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.warn("could not read {}", configFile(), e);
			return fallback;
		}
	}

	public static void setInterfaceSetting(String section, String key, JsonElement value) {
		try {
			JsonObject root = readConfig();
			JsonObject group = root.has(section) ? root.getAsJsonObject(section) : new JsonObject();
			group.add(key, value);
			root.add(section, group);
			writeAtomically(configFile(), GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.error("could not write {}", configFile(), e);
		}
	}

	private static int readShaderInt(String key, int fallback) {
		try {
			JsonObject root = readConfig();
			JsonObject shaders = root.has("shaders") ? root.getAsJsonObject("shaders") : null;
			return shaders != null && shaders.has(key) ? shaders.get(key).getAsInt() : fallback;
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.warn("could not read {}", configFile(), e);
			return fallback;
		}
	}

	private static void writeShaderInt(String key, int value) {
		try {
			JsonObject root = readConfig();
			JsonObject shaders = root.has("shaders") ? root.getAsJsonObject("shaders") : new JsonObject();
			shaders.addProperty(key, value);
			root.add("shaders", shaders);
			writeAtomically(configFile(), GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.error("could not write {}", configFile(), e);
		}
	}

	private static JsonObject readConfig() throws IOException {
		Path file = configFile();
		if (!Files.isRegularFile(file)) {
			return new JsonObject();
		}
		return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	/** Every pack in the shaderpacks folder: zips and folders that contain a {@code shaders} directory, sorted by name. */
	public static List<String> listPacks() {
		List<String> packs = new ArrayList<>();
		Path dir = packsDirectory();
		if (!Files.isDirectory(dir)) {
			return packs;
		}
		try (Stream<Path> entries = Files.list(dir)) {
			entries.forEach(p -> {
				String name = p.getFileName().toString();
				if (Files.isDirectory(p) ? Files.isDirectory(p.resolve("shaders")) : name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
					packs.add(name);
				}
			});
		} catch (IOException e) {
			AetheriumShaders.logger.warn("could not list {}", dir, e);
		}
		packs.sort(String.CASE_INSENSITIVE_ORDER);
		return packs;
	}

	/** A pack's changed options ({@code shaderpacks/<pack>.txt}), empty when the pack runs on defaults. */
	public static Map<String, String> readOptions(String pack) {
		Map<String, String> values = new TreeMap<>();
		Path file = packsDirectory().resolve(pack + ".txt");
		if (!Files.isRegularFile(file)) {
			return values;
		}
		Properties properties = new Properties();
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
			properties.load(r);
		} catch (IOException | IllegalArgumentException e) {
			AetheriumShaders.logger.warn("could not read {}", file, e);
		}
		properties.forEach((k, v) -> values.put(k.toString(), v.toString()));
		return values;
	}

	/** Writes a pack's changed options; an empty map removes the file (the pack runs on its defaults). */
	public static void writeOptions(String pack, Map<String, String> changed) {
		Path file = packsDirectory().resolve(pack + ".txt");
		try {
			if (changed.isEmpty()) {
				Files.deleteIfExists(file);
				return;
			}
			writeOptionsTo(file, changed);
		} catch (IOException e) {
			AetheriumShaders.logger.error("could not write {}", file, e);
		}
	}

	/** Options in the pack format's {@code .txt} form (what the packs folder holds, and what export writes). */
	public static void writeOptionsTo(Path file, Map<String, String> options) throws IOException {
		Properties properties = new Properties();
		options.forEach(properties::setProperty);
		StringWriter text = new StringWriter();
		properties.store(text, null);
		writeAtomically(file, text.toString().getBytes(StandardCharsets.ISO_8859_1));
	}

	/**
	 * Replaces {@code file} with {@code data} through a temporary file next to it, so a crash mid-write never leaves a
	 * truncated file. Falls back to a plain replacing move where the file system cannot move atomically.
	 */
	private static void writeAtomically(Path file, byte[] data) throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path temp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
		try {
			Files.write(temp, data);
			try {
				Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	/** An opened pack: its {@code shaders} root and the zip file system to close afterwards (null for folders). */
	public record OpenPack(Path root, @Nullable FileSystem fileSystem) implements AutoCloseable {
		public boolean isZip() {
			return fileSystem != null;
		}

		@Override
		public void close() {
			if (fileSystem != null) {
				try {
					fileSystem.close();
				} catch (IOException e) {
					AetheriumShaders.logger.debug("could not close a pack zip", e);
				}
			}
		}
	}

	/**
	 * Opens a pack by file name. In a zip the {@code shaders} directory is taken from the root, else the shallowest
	 * nested one (at most three folders deep; the first by name among equally deep ones).
	 */
	public static OpenPack open(String pack) throws IOException {
		Path path = packsDirectory().resolve(pack);
		if (Files.isDirectory(path)) {
			return new OpenPack(path.resolve("shaders"), null);
		}
		FileSystem zip = FileSystems.newFileSystem(path, ShaderPackSettings.class.getClassLoader());
		try {
			Path direct = zip.getPath("shaders");
			if (Files.isDirectory(direct)) {
				return new OpenPack(direct, zip);
			}
			try (Stream<Path> stream = Files.walk(zip.getRootDirectories().iterator().next(), 3)) {
				Optional<Path> nested = stream.filter(p -> p.getFileName() != null && p.getFileName().toString().equals("shaders"))
					.filter(Files::isDirectory)
					.min(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString));
				if (nested.isPresent()) {
					return new OpenPack(nested.get(), zip);
				}
			}
		} catch (IOException | RuntimeException e) {
			zip.close();
			throw e;
		}
		zip.close();
		throw new IOException("no shaders/ folder in " + pack);
	}

	/** A pack's display name: file name without {@code .zip} and without formatting codes. */
	public static String displayName(String pack) {
		String name = pack.toLowerCase(Locale.ROOT).endsWith(".zip") ? pack.substring(0, pack.length() - 4) : pack;
		return name.replaceAll("§.", "").replace('_', ' ');
	}
}
