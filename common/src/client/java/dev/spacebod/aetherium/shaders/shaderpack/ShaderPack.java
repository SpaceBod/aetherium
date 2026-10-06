package dev.spacebod.aetherium.shaders.shaderpack;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.features.FeatureFlags;
import dev.spacebod.aetherium.shaders.gl.buffer.BuiltShaderStorageInfo;
import dev.spacebod.aetherium.shaders.gl.buffer.ShaderStorageInfo;
import dev.spacebod.aetherium.shaders.gl.shader.PackFacingNames;
import dev.spacebod.aetherium.shaders.gl.texture.TextureDefinition;
import dev.spacebod.aetherium.shaders.helpers.StringPair;
import dev.spacebod.aetherium.shaders.pathways.colorspace.ColorSpace;
import dev.spacebod.aetherium.shaders.shaderpack.error.RusticError;
import dev.spacebod.aetherium.shaders.shaderpack.include.AbsolutePackPath;
import dev.spacebod.aetherium.shaders.shaderpack.include.IncludeGraph;
import dev.spacebod.aetherium.shaders.shaderpack.include.IncludeProcessor;
import dev.spacebod.aetherium.shaders.shaderpack.include.ShaderPackSourceNames;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.NamespacedId;
import dev.spacebod.aetherium.shaders.shaderpack.option.OrderBackedProperties;
import dev.spacebod.aetherium.shaders.shaderpack.option.ProfileSet;
import dev.spacebod.aetherium.shaders.shaderpack.option.ShaderPackOptions;
import dev.spacebod.aetherium.shaders.shaderpack.option.menu.OptionMenuContainer;
import dev.spacebod.aetherium.shaders.shaderpack.option.values.MutableOptionValues;
import dev.spacebod.aetherium.shaders.shaderpack.option.values.OptionValues;
import dev.spacebod.aetherium.shaders.shaderpack.parsing.BooleanParser;
import dev.spacebod.aetherium.shaders.shaderpack.preprocessor.JcppProcessor;
import dev.spacebod.aetherium.shaders.shaderpack.preprocessor.PropertiesPreprocessor;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSetInterface;
import dev.spacebod.aetherium.shaders.shaderpack.properties.ShaderProperties;
import dev.spacebod.aetherium.shaders.shaderpack.texture.CustomTextureData;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureFilteringData;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import dev.spacebod.aetherium.shaders.uniforms.custom.CustomUniforms;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

public class ShaderPack {
	private static final Gson GSON = new Gson();
	public final CustomUniforms.Builder customUniforms;
	private final ProgramSet base;
	/** The folder the base program set is read from ("" = the shaders root). */
	private final String baseFolder;
	private final Map<NamespacedId, ProgramSetInterface> overrides;
	private final IdMap idMap;
	private final LanguageMap languageMap;
	private final EnumMap<TextureStage, Object2ObjectMap<String, CustomTextureData>> customTextureDataMap = new EnumMap<>(TextureStage.class);
	private final Object2ObjectMap<String, CustomTextureData> packCustomTextureDataMap = new Object2ObjectOpenHashMap<>();
	private final CustomTextureData customNoiseTexture;
	private final ShaderPackOptions shaderPackOptions;
	private final OptionMenuContainer menuContainer;
	private final ProfileSet.ProfileResult profile;
	private final String profileInfo;
	private final List<ImageInformation> packCustomImages;
	private final Set<FeatureFlags> activeFeatures;
	private final Function<AbsolutePackPath, String> sourceProvider;
	private final ShaderProperties shaderProperties;
	private final List<String> dimensionIds;
	private final Int2ObjectArrayMap<BuiltShaderStorageInfo> bufferObjects;
	private Map<NamespacedId, String> dimensionMap;

	public ShaderPack(Path root, ImmutableList<StringPair> environmentDefines, boolean isZip) throws IOException, IllegalStateException {
		this(root, Collections.emptyMap(), environmentDefines, isZip);
	}

	/**
	 * Reads a shader pack from disk. All disk I/O completes before the constructor returns; the path is not retained.
	 *
	 * @param root the "shaders" directory within the pack
	 * @throws IOException if there are any IO errors during shader pack loading.
	 */
	public ShaderPack(Path root, Map<String, String> changedConfigs, ImmutableList<StringPair> environmentDefines, boolean isZip) throws IOException, IllegalStateException {
		this(root, changedConfigs, environmentDefines, isZip, false);
	}

	/**
	 * Reads only what the settings screen shows when {@code optionsOnly}: options, profiles, the option menu and the
	 * translations. Programs, ID maps, textures and storage data are left out (much faster: no program is preprocessed),
	 * so only {@link #getShaderPackOptions}, {@link #getMenuContainer} and {@link #getLanguageMap} may be used then.
	 */
	public ShaderPack(Path root, Map<String, String> changedConfigs, ImmutableList<StringPair> environmentDefines, boolean isZip, boolean optionsOnly)
			throws IOException, IllegalStateException {
		this(root, changedConfigs, environmentDefines, isZip, optionsOnly, ShadowOverrides.NONE);
	}

	/** {@code shadows}: the player's shadow distance and map scale, written into every program source ({@link ShadowOverrides}). */
	public ShaderPack(Path root, Map<String, String> changedConfigs, ImmutableList<StringPair> environmentDefines, boolean isZip, boolean optionsOnly,
			ShadowOverrides shadows) throws IOException, IllegalStateException {
		Objects.requireNonNull(root);

		ArrayList<StringPair> envDefines1 = new ArrayList<>(environmentDefines);
		envDefines1.addAll(PackDefines.createDefines());
		environmentDefines = ImmutableList.copyOf(envDefines1);
		ImmutableList.Builder<AbsolutePackPath> starts = ImmutableList.builder();
		ImmutableList<String> potentialFileNames = ShaderPackSourceNames.POTENTIAL_STARTS;

		ShaderPackSourceNames.findPresentSources(starts, root, AbsolutePackPath.fromAbsolutePath("/"),
			potentialFileNames);

		dimensionIds = new ArrayList<>();
		bufferObjects = new Int2ObjectArrayMap<>();

		final boolean[] hasDimensionIds = {false};

		// Not in IdMap: this runs before the include graph (and so the pack options) exists.
		List<String> dimensionIdCreator = loadProperties(root, "dimension.properties", environmentDefines).map(dimensionProperties -> {
			hasDimensionIds[0] = !dimensionProperties.isEmpty();
			dimensionMap = parseDimensionMap(dimensionProperties, "dimension.", "dimension.properties");
			return parseDimensionIds(dimensionProperties, "dimension.");
		}).orElse(new ArrayList<>());

		if (!hasDimensionIds[0]) {
			dimensionMap = new Object2ObjectArrayMap<>();

			if (Files.exists(root.resolve("world0"))) {
				dimensionIdCreator.add("world0");
				dimensionMap.putIfAbsent(DimensionId.OVERWORLD, "world0");
				dimensionMap.putIfAbsent(new NamespacedId("*", "*"), "world0");
			}
			if (Files.exists(root.resolve("world-1"))) {
				dimensionIdCreator.add("world-1");
				dimensionMap.putIfAbsent(DimensionId.NETHER, "world-1");
			}
			if (Files.exists(root.resolve("world1"))) {
				dimensionIdCreator.add("world1");
				dimensionMap.putIfAbsent(DimensionId.END, "world1");
			}
		}

		for (String id : dimensionIdCreator) {
			if (ShaderPackSourceNames.findPresentSources(starts, root, AbsolutePackPath.fromAbsolutePath("/" + id),
				potentialFileNames)) {
				dimensionIds.add(id);
			}
		}

		// Every program file and everything it includes.
		IncludeGraph graph = new IncludeGraph(root, starts.build(), isZip);

		if (!graph.getFailures().isEmpty()) {
			throw new IOException(String.join("\n", graph.getFailures().values().stream().map(RusticError::toString).toArray(String[]::new)));
		}

		this.languageMap = new LanguageMap(root.resolve("lang"));

		// Discover, merge and apply the pack's options.
		this.shaderPackOptions = new ShaderPackOptions(graph, changedConfigs);
		graph = this.shaderPackOptions.getIncludes();

		List<StringPair> finalEnvironmentDefines = new ArrayList<>(List.copyOf(environmentDefines));
		for (FeatureFlags flag : FeatureFlags.values()) {
			if (flag.isUsable()) {
				finalEnvironmentDefines.add(new StringPair(PackFacingNames.FEATURE_MACRO_PREFIX + flag.name(), ""));
			}
		}
		this.shaderProperties = loadProperties(root, "shaders.properties")
			.map(source -> new ShaderProperties(source, shaderPackOptions, finalEnvironmentDefines))
			.orElseGet(ShaderProperties::empty);

		for (Int2ObjectMap.Entry<ShaderStorageInfo> shaderStorageInfoEntry : optionsOnly ? List.<Int2ObjectMap.Entry<ShaderStorageInfo>>of()
				: shaderProperties.getBufferObjects().int2ObjectEntrySet()) {
			ShaderStorageInfo info = shaderStorageInfoEntry.getValue();

			if (info.name() == null) {
				bufferObjects.put(shaderStorageInfoEntry.getIntKey(), new BuiltShaderStorageInfo(info.size(), info.relative(), info.scaleX(), info.scaleY(), null));
			} else {
				String path = info.name();
				byte[] data = null;

				try {
					// The file fills the start of the buffer, so it may be no larger than the buffer.
					Path file = PackFiles.resolve(root, path).orElseThrow(() -> new IOException("the path leaves the pack"));
					data = PackFiles.readBytes(file, Math.min(info.size(), PackFiles.MAX_DATA_BYTES));
				} catch (IOException e) {
					// The buffer is still created, zero-filled.
					AetheriumShaders.logger.error("Shader storage buffer with index " + shaderStorageInfoEntry.getIntKey() + " and path " + path + " could not be read: " + e.getMessage());
				}

				bufferObjects.put(shaderStorageInfoEntry.getIntKey(), new BuiltShaderStorageInfo(info.size(), info.relative(), info.scaleX(), info.scaleY(), data));
			}
		}

		activeFeatures = new HashSet<>();
		for (int i = 0; i < shaderProperties.getRequiredFeatureFlags().size(); i++) {
			activeFeatures.add(FeatureFlags.getValue(shaderProperties.getRequiredFeatureFlags().get(i)));
		}
		for (int i = 0; i < shaderProperties.getOptionalFeatureFlags().size(); i++) {
			activeFeatures.add(FeatureFlags.getValue(shaderProperties.getOptionalFeatureFlags().get(i)));
		}

		if (!activeFeatures.contains(FeatureFlags.SSBO) && !shaderProperties.getBufferObjects().isEmpty()) {
			throw new IllegalStateException("An SSBO is being used, but the feature flag for SSBO's hasn't been set! Please set either a requirement or check for the SSBO feature using the required or optional features property (ssbo).");
		}

		if (!activeFeatures.contains(FeatureFlags.CUSTOM_IMAGES) && !shaderProperties.getCustomImages().isEmpty()) {
			throw new IllegalStateException("Custom images are being used, but the feature flag for custom images hasn't been set! Please set either a requirement or check for custom images' feature flag using the required or optional features property (CUSTOM_IMAGES).");
		}

		List<String> missing = shaderProperties.getRequiredFeatureFlags().stream().filter(FeatureFlags::isInvalid).toList();
		if (!missing.isEmpty()) {
			// Refused before any program is built; the engine keeps vanilla visuals and shows this message.
			throw new MissingFeaturesException(missing);
		}
		List<StringPair> newEnvDefines = new ArrayList<>(environmentDefines);

		if (shaderProperties.supportsColorCorrection().orElse(false)) {
			for (ColorSpace space : ColorSpace.values()) {
				newEnvDefines.add(new StringPair("COLOR_SPACE_" + space.name(), String.valueOf(space.ordinal())));
			}
		}

		List<String> optionalFeatureFlags = shaderProperties.getOptionalFeatureFlags().stream().filter(flag -> !FeatureFlags.isInvalid(flag)).toList();

		if (!optionalFeatureFlags.isEmpty()) {
			optionalFeatureFlags.forEach(flag -> newEnvDefines.add(new StringPair(PackFacingNames.FEATURE_MACRO_PREFIX + flag, "")));
		}

		environmentDefines = ImmutableList.copyOf(newEnvDefines);

		ProfileSet profiles = ProfileSet.fromTree(shaderProperties.getProfiles(), this.shaderPackOptions.getOptionSet());
		this.profile = profiles.scan(this.shaderPackOptions.getOptionSet(), this.shaderPackOptions.getOptionValues());

		// Get programs that should be disabled from the detected profile
		List<String> disabledPrograms = new ArrayList<>();
		this.profile.current.ifPresent(profile -> disabledPrograms.addAll(profile.disabledPrograms));
		// Add programs that are disabled by shader options
		shaderProperties.getConditionallyEnabledPrograms().forEach((program, shaderOption) -> {
			if (!BooleanParser.parse(shaderOption, this.shaderPackOptions.getOptionValues())) {
				disabledPrograms.add(program);
			}
		});

		this.menuContainer = new OptionMenuContainer(shaderProperties, this.shaderPackOptions, profiles);

		{
			String profileName = getCurrentProfileName();
			OptionValues profileOptions = new MutableOptionValues(
				this.shaderPackOptions.getOptionSet(), this.profile.current.map(p -> p.optionValues).orElse(new HashMap<>()));

			int userOptionsChanged = this.shaderPackOptions.getOptionValues().getOptionsChanged() - profileOptions.getOptionsChanged();

			this.profileInfo = "Profile: " + profileName + " (+" + userOptionsChanged + " option" + (userOptionsChanged == 1 ? "" : "s") + " changed by user)";
		}

		if (optionsOnly) {
			this.sourceProvider = path -> null;
			this.base = null;
			this.baseFolder = "";
			this.overrides = Map.of();
			this.idMap = null;
			this.customNoiseTexture = null;
			this.packCustomImages = List.of();
			this.customUniforms = shaderProperties.getCustomUniforms();
			return;
		}

		AetheriumShaders.logger.info(this.profileInfo);

		IncludeProcessor includeProcessor = new IncludeProcessor(graph);

		Iterable<StringPair> finalEnvironmentDefines1 = environmentDefines;
		this.sourceProvider = (path) -> {
			String pathString = path.getPathString();
			// Program name: the path without its leading "/" and file extension.
			String programString = pathString.substring(pathString.indexOf("/") == 0 ? 1 : 0, pathString.lastIndexOf("."));

			// Disabled by the current profile or by a shader option.
			if (disabledPrograms.contains(programString)) {
				return null;
			}

			ImmutableList<String> lines = includeProcessor.getIncludedFile(path);

			if (lines == null) {
				return null;
			}

			StringBuilder builder = new StringBuilder();

			for (String line : lines) {
				builder.append(line);
				builder.append('\n');
			}

			// Environment defines are passed to the preprocessor directly rather than inserted as #define lines,
			// which keeps error line numbers closer to the pack's source.
			String source = builder.toString();
			source = JcppProcessor.glslPreprocessSource(source, finalEnvironmentDefines1);
			source = shadows.apply(source);

			return source;
		};

		this.baseFolder = dimensionMap.getOrDefault(new NamespacedId("*", "*"), "");
		this.base = new ProgramSet(AbsolutePackPath.fromAbsolutePath("/" + baseFolder), sourceProvider, shaderProperties, this);

		this.overrides = new HashMap<>();

		this.idMap = new IdMap(root, shaderPackOptions, environmentDefines);

		customNoiseTexture = shaderProperties.getNoiseTexturePath().map(path -> {
			try {
				return readTexture(root, new TextureDefinition.PNGDefinition(path));
			} catch (IOException | RuntimeException e) {
				AetheriumShaders.logger.error("Unable to read the custom noise texture at " + path + ": " + e.getMessage());

				return null;
			}
		}).orElse(null);

		shaderProperties.getCustomTextures().forEach((textureStage, customTexturePropertiesMap) -> {
			Object2ObjectMap<String, CustomTextureData> innerCustomTextureDataMap = new Object2ObjectOpenHashMap<>();
			customTexturePropertiesMap.forEach((samplerName, path) -> {
				try {
					innerCustomTextureDataMap.put(samplerName, readTexture(root, path));
				} catch (IOException | RuntimeException e) {
					AetheriumShaders.logger.error("Unable to read the custom texture at " + path.getName() + ": " + e.getMessage());
				}
			});

			customTextureDataMap.put(textureStage, innerCustomTextureDataMap);
		});

		this.packCustomImages = shaderProperties.getCustomImages();

		this.customUniforms = shaderProperties.getCustomUniforms();

		shaderProperties.getNamedCustomTextures().forEach((name, texture) -> {
			try {
				packCustomTextureDataMap.put(name, readTexture(root, texture));
			} catch (IOException | RuntimeException e) {
				AetheriumShaders.logger.error("Unable to read the custom texture at " + texture.getName() + ": " + e.getMessage());
			}
		});
	}

	/**
	 * Loads and preprocesses a properties file from the pack root; empty if it is missing or unreadable.
	 */
	private static Optional<Properties> loadProperties(Path shaderPath, String name,
													   Iterable<StringPair> environmentDefines) {
		String fileContents = readProperties(shaderPath, name);
		if (fileContents == null) {
			return Optional.empty();
		}

		String processed = PropertiesPreprocessor.preprocessSource(fileContents, environmentDefines);

		StringReader propertiesReader = new StringReader(processed);

		// Order is significant: the first matching entry wins.
		Properties properties = new OrderBackedProperties();
		try {
			properties.load(propertiesReader);
		} catch (IOException e) {
			AetheriumShaders.logger.error("Error loading " + name + " at " + shaderPath, e);

			return Optional.empty();
		}

		return Optional.of(properties);
	}

	private static Map<NamespacedId, String> parseDimensionMap(Properties properties, String keyPrefix, String fileName) {
		Map<NamespacedId, String> overrides = new Object2ObjectArrayMap<>();

		properties.forEach((keyObject, valueObject) -> {
			String key = (String) keyObject;
			String value = (String) valueObject;

			if (!key.startsWith(keyPrefix)) {
				// Not a valid line, ignore it
				return;
			}

			key = key.substring(keyPrefix.length());

			for (String part : value.split("\\s+")) {
				if (part.equals("*")) {
					overrides.put(new NamespacedId("*", "*"), key);
				}
				overrides.put(new NamespacedId(part), key);
			}
		});

		return overrides;
	}

	private static Optional<String> loadProperties(Path shaderPath, String name) {
		String fileContents = readProperties(shaderPath, name);
		if (fileContents == null) {
			return Optional.empty();
		}

		return Optional.of(fileContents);
	}

	private static String readProperties(Path shaderPath, String name) {
		try {
			// Property files are read as ISO-8859-1 (any byte decodes).
			return PackFiles.readLatin1(shaderPath.resolve(name));
		} catch (NoSuchFileException e) {
			AetheriumShaders.logger.debug("An " + name + " file was not found in the current shaderpack");

			return null;
		} catch (IOException e) {
			AetheriumShaders.logger.error("An IOException occurred reading " + name + " from the current shaderpack", e);

			return null;
		}
	}

	private List<String> parseDimensionIds(Properties dimensionProperties, String keyPrefix) {
		List<String> names = new ArrayList<>();

		dimensionProperties.forEach((keyObject, value) -> {
			String key = (String) keyObject;
			if (!key.startsWith(keyPrefix)) {
				// Not a valid line, ignore it
				return;
			}

			key = key.substring(keyPrefix.length());

			names.add(key);
		});

		return names;
	}

	private String getCurrentProfileName() {
		return profile.current.map(p -> p.name).orElse("Custom");
	}

	public String getProfileInfo() {
		return profileInfo;
	}

	/**
	 * A custom texture: {@code namespace:path} names a game resource (or the lightmap), anything else a file in the pack
	 * (PNG, or raw data per the definition), with an optional {@code .mcmeta} for blur and clamp.
	 */
	public CustomTextureData readTexture(Path root, TextureDefinition definition) throws IOException {
		CustomTextureData customTextureData;
		String path = definition.getName();
		if (path.contains(":")) {
			String[] parts = path.split(":");

			if (parts.length > 2) {
				AetheriumShaders.logger.warn("Resource location " + path + " contained more than two parts?");
			}

			if (parts[0].equals("minecraft") && (parts[1].equals("dynamic/lightmap_1") || parts[1].equals("dynamic/light_map_1"))) {
				customTextureData = new CustomTextureData.LightmapMarker();
			} else {
				customTextureData = new CustomTextureData.ResourceData(parts[0], parts[1]);
			}
		} else {
			Path file = PackFiles.resolve(root, path).orElseThrow(() -> new IOException("the path leaves the pack"));

			boolean blur = definition instanceof TextureDefinition.RawDefinition;
			boolean clamp = definition instanceof TextureDefinition.RawDefinition;

			String mcMetaPath = path + ".mcmeta";
			Path mcMetaResolvedPath = file.resolveSibling(file.getFileName() + ".mcmeta");

			if (Files.exists(mcMetaResolvedPath)) {
				try {
					JsonObject meta = loadMcMeta(mcMetaResolvedPath);
					if (meta.get("texture") != null) {
						if (meta.get("texture").getAsJsonObject().get("blur") != null) {
							blur = meta.get("texture").getAsJsonObject().get("blur").getAsBoolean();
						}
						if (meta.get("texture").getAsJsonObject().get("clamp") != null) {
							clamp = meta.get("texture").getAsJsonObject().get("clamp").getAsBoolean();
						}
					}
				} catch (IOException | RuntimeException e) {
					AetheriumShaders.logger.error("Unable to read the custom texture mcmeta at " + mcMetaPath + ", ignoring: " + e);
				}
			}

			byte[] content = PackFiles.readBytes(file, PackFiles.MAX_DATA_BYTES);

			if (definition instanceof TextureDefinition.PNGDefinition) {
				customTextureData = new CustomTextureData.PngData(new TextureFilteringData(blur, clamp), content);
			} else if (definition instanceof TextureDefinition.RawDefinition rawDefinition) {
				customTextureData = switch (rawDefinition.getTarget()) {
					case TEXTURE_1D ->
						new CustomTextureData.RawData1D(content, new TextureFilteringData(blur, clamp), rawDefinition.getInternalFormat(), rawDefinition.getFormat(), rawDefinition.getPixelType(), rawDefinition.getSizeX());
					case TEXTURE_2D ->
						new CustomTextureData.RawData2D(content, new TextureFilteringData(blur, clamp), rawDefinition.getInternalFormat(), rawDefinition.getFormat(), rawDefinition.getPixelType(), rawDefinition.getSizeX(), rawDefinition.getSizeY());
					case TEXTURE_3D ->
						new CustomTextureData.RawData3D(content, new TextureFilteringData(blur, clamp), rawDefinition.getInternalFormat(), rawDefinition.getFormat(), rawDefinition.getPixelType(), rawDefinition.getSizeX(), rawDefinition.getSizeY(), rawDefinition.getSizeZ());
					case TEXTURE_RECTANGLE ->
						new CustomTextureData.RawDataRect(content, new TextureFilteringData(blur, clamp), rawDefinition.getInternalFormat(), rawDefinition.getFormat(), rawDefinition.getPixelType(), rawDefinition.getSizeX(), rawDefinition.getSizeY());
				};
			} else {
				customTextureData = null;
			}
		}
		return customTextureData;
	}

	private JsonObject loadMcMeta(Path mcMetaPath) throws IOException, JsonParseException {
		JsonReader jsonReader = new JsonReader(new StringReader(PackFiles.readText(mcMetaPath)));
		return GSON.getAdapter(JsonObject.class).read(jsonReader);
	}

	/**
	 * The folder whose programs serve {@code dimension}: its own world folder, else the base set's (what
	 * {@link #getProgramSet} returns, without building it). Two dimensions with the same folder share one program set.
	 */
	public String programFolder(NamespacedId dimension) {
		String name = dimensionMap.get(dimension);
		return name != null && dimensionIds.contains(name) ? name : baseFolder;
	}

	public ProgramSet getProgramSet(NamespacedId dimension) {
		ProgramSetInterface overrides;

		overrides = this.overrides.computeIfAbsent(dimension, dim -> {
			if (dimensionMap.containsKey(dimension)) {
				String name = dimensionMap.get(dimension);
				if (name.equals(baseFolder)) {
					// The base set is read from this folder already.
					return ProgramSetInterface.Empty.INSTANCE;
				}
				if (dimensionIds.contains(name)) {
					return new ProgramSet(AbsolutePackPath.fromAbsolutePath("/" + name), sourceProvider, shaderProperties, this);
				} else {
					AetheriumShaders.logger.error("Attempted to load dimension folder " + name + " for dimension " + dimension + ", but it does not exist!");
					return ProgramSetInterface.Empty.INSTANCE;
				}
			} else {
				return ProgramSetInterface.Empty.INSTANCE;
			}
		});

		// A dimension override directory fully replaces the base program set; nothing is merged from the parent.
		// Merging would make it impossible for an override to drop a pass the base defines (e.g. a composite).
		if (overrides instanceof ProgramSet) {
			return (ProgramSet) overrides;
		} else {
			return base;
		}
	}

	public IdMap getIdMap() {
		return idMap;
	}

	public EnumMap<TextureStage, Object2ObjectMap<String, CustomTextureData>> getCustomTextureDataMap() {
		return customTextureDataMap;
	}

	public List<ImageInformation> getCustomImages() {
		return packCustomImages;
	}

	public Object2ObjectMap<String, CustomTextureData> getNamedCustomTextureData() {
		return packCustomTextureDataMap;
	}

	public CustomTextureData getCustomNoiseTexture() {
		return customNoiseTexture;
	}

	public LanguageMap getLanguageMap() {
		return languageMap;
	}

	public ShaderPackOptions getShaderPackOptions() {
		return shaderPackOptions;
	}

	public OptionMenuContainer getMenuContainer() {
		return menuContainer;
	}

	public boolean hasFeature(FeatureFlags feature) {
		return activeFeatures.contains(feature);
	}

	public Int2ObjectArrayMap<BuiltShaderStorageInfo> getBufferObjects() {
		return bufferObjects;
	}
}
