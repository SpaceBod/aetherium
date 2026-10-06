package dev.spacebod.aetherium.shaders.shaderpack;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.helpers.StringPair;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.BlockEntry;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.BlockRenderType;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.Entry;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.LegacyIdMap;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.NamespacedId;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.TagEntry;
import dev.spacebod.aetherium.shaders.shaderpack.option.OrderBackedProperties;
import dev.spacebod.aetherium.shaders.shaderpack.option.ShaderPackOptions;
import dev.spacebod.aetherium.shaders.shaderpack.preprocessor.PropertiesPreprocessor;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Parses a pack's item.properties, block.properties and entity.properties ID maps.
 */
public class IdMap {
	/** Item ID to pack integer ID. */
	private final Object2IntMap<NamespacedId> itemIdMap;

	/** Entity ID to pack integer ID. */
	private final Object2IntMap<NamespacedId> entityIdMap;
	/** Block tags per block.properties ID. */
	private final Int2ObjectLinkedOpenHashMap<List<TagEntry>> blockTagMap;
	/** Block states per block.properties ID. */
	private Int2ObjectLinkedOpenHashMap<List<BlockEntry>> blockPropertiesMap;
	/** Per-block render type overrides ({@code layer.*} in block.properties). */
	private Map<NamespacedId, BlockRenderType> blockRenderTypeMap;

	IdMap(Path shaderPath, ShaderPackOptions shaderPackOptions, Iterable<StringPair> environmentDefines) {
		itemIdMap = loadProperties(shaderPath, "item.properties", shaderPackOptions, environmentDefines)
			.map(IdMap::parseItemIdMap).orElse(Object2IntMaps.emptyMap());

		entityIdMap = loadProperties(shaderPath, "entity.properties", shaderPackOptions, environmentDefines)
			.map(IdMap::parseEntityIdMap).orElse(Object2IntMaps.emptyMap());
		blockTagMap = new Int2ObjectLinkedOpenHashMap<>();

		loadProperties(shaderPath, "block.properties", shaderPackOptions, environmentDefines).ifPresent(blockProperties -> {
			blockPropertiesMap = parseBlockMap(blockProperties, "block.", "block.properties", blockTagMap);
			blockRenderTypeMap = parseRenderTypeMap(blockProperties, "layer.", "block.properties");
		});

		if (blockPropertiesMap == null) {
			// No block.properties: fall back to the legacy block IDs.
			blockPropertiesMap = new Int2ObjectLinkedOpenHashMap<>();
			LegacyIdMap.addLegacyValues(blockPropertiesMap);
		}

		if (blockRenderTypeMap == null) {
			blockRenderTypeMap = Collections.emptyMap();
		}
	}

	/**
	 * Loads and preprocesses a properties file from the pack root; empty if it is missing or unreadable.
	 */
	private static Optional<Properties> loadProperties(Path shaderPath, String name, ShaderPackOptions shaderPackOptions,
													   Iterable<StringPair> environmentDefines) {
		String fileContents = readProperties(shaderPath, name);
		if (fileContents == null) {
			return Optional.empty();
		}

		// Joins continued lines and puts each block.* entry on its own line before the properties are read.
		String processed = PropertiesPreprocessor.preprocessSource(fileContents, shaderPackOptions, environmentDefines).replaceAll("\\\\\\n\\s*\\n", " ").replaceAll("\\S *block\\.", "\nblock.");
		StringReader propertiesReader = new StringReader(processed);
		warnMissingBackslashInPropertiesFile(processed, name);

		// Order is significant: BlockMaterialMapping uses putIfAbsent, so the first mapping of a block wins.
		Properties properties = new OrderBackedProperties();
		try {
			properties.load(propertiesReader);
		} catch (IOException | IllegalArgumentException e) {
			AetheriumShaders.logger.error("could not read " + name + ": " + e.getMessage());

			return Optional.empty();
		}

		if (AetheriumShaders.getShaderConfig().areDebugOptionsEnabled()) {
			StringWriter patched = new StringWriter();
			try {
				properties.store(patched, "Patched version of properties");
			} catch (IOException e) {
				// A StringWriter does not fail.
			}
			PackFiles.dump("properties/" + name, patched.toString());
		}

		return Optional.of(properties);
	}

	private static String readProperties(Path shaderPath, String name) {
		try {
			// ID maps are read as ISO-8859-1 (any byte decodes).
			return PackFiles.readLatin1(shaderPath.resolve(name));
		} catch (NoSuchFileException e) {
			AetheriumShaders.logger.debug("An " + name + " file was not found in the current shaderpack");

			return null;
		} catch (IOException e) {
			AetheriumShaders.logger.error("An IOException occurred reading " + name + " from the current shaderpack", e);

			return null;
		}
	}

	private static Object2IntMap<NamespacedId> parseItemIdMap(Properties properties) {
		return parseIdMap(properties, "item.", "item.properties");
	}

	private static Object2IntMap<NamespacedId> parseEntityIdMap(Properties properties) {
		return parseIdMap(properties, "entity.", "entity.properties");
	}

	/**
	 * Parses a {@code prefix.<id>=<names...>} ID map into NamespacedId to integer ID.
	 */
	private static Object2IntMap<NamespacedId> parseIdMap(Properties properties, String keyPrefix, String fileName) {
		Object2IntMap<NamespacedId> idMap = new Object2IntOpenHashMap<>();
		idMap.defaultReturnValue(-1);

		DuplicateTracker duplicateTracker = new DuplicateTracker(fileName, "entry");

		properties.forEach((keyObject, valueObject) -> {
			String key = (String) keyObject;
			String value = (String) valueObject;

			if (!key.startsWith(keyPrefix)) {
				// Not a key of this map.
				return;
			}

			int intId;

			try {
				intId = Integer.parseInt(key.substring(keyPrefix.length()));
			} catch (NumberFormatException e) {
				// Not a valid property line
				AetheriumShaders.logger.warn("Failed to parse line in " + fileName + ": invalid key " + key);
				return;
			}

			for (String part : value.split("\\s+")) {
				if (part.contains("=")) {
					AetheriumShaders.logger.warn("Failed to parse an Identifier in " + fileName + " for the key " + key + ": state properties are currently not supported: " + part);
					continue;
				}

				duplicateTracker.checkAndRecord(part, key, part);

				// NamespacedId does no validation; that happens when converting to Identifiers.
				idMap.put(new NamespacedId(part), intId);
			}
		});

		duplicateTracker.reportSummary();

		return Object2IntMaps.unmodifiable(idMap);
	}

	private static Int2ObjectLinkedOpenHashMap<List<BlockEntry>> parseBlockMap(Properties properties, String keyPrefix, String fileName, Int2ObjectLinkedOpenHashMap<List<TagEntry>> blockTagMap) {
		Int2ObjectLinkedOpenHashMap<List<BlockEntry>> blockEntriesById = new Int2ObjectLinkedOpenHashMap<>();
		Int2ObjectLinkedOpenHashMap<List<TagEntry>> tagEntriesById = new Int2ObjectLinkedOpenHashMap<>();

		DuplicateTracker duplicateTracker = new DuplicateTracker(fileName, "block entry");

		properties.forEach((keyObject, valueObject) -> {
			String key = (String) keyObject;
			String value = (String) valueObject;

			if (!key.startsWith(keyPrefix)) {
				// Not a key of this map.
				return;
			}

			int intId;

			try {
				intId = Integer.parseInt(key.substring(keyPrefix.length()));
			} catch (NumberFormatException e) {
				// Not a valid property line
				AetheriumShaders.logger.warn("Failed to parse line in " + fileName + ": invalid key " + key);
				return;
			}

			List<BlockEntry> blockEntries = new ArrayList<>();
			List<TagEntry> tagEntries = new ArrayList<>();

			for (String part : value.split("\\s+")) {
				if (part.isEmpty()) {
					continue;
				}

				try {
					Entry entry = BlockEntry.parse(part);
					if (entry instanceof BlockEntry be) {
						// Create a unique identifier that includes blockstates
						String uniqueBlockIdentifier = DuplicateTracker.getUniqueBlockIdentifier(be);
						duplicateTracker.checkAndRecord(uniqueBlockIdentifier, key, part); // Check for duplicates

						blockEntries.add(be);
					} else if (entry instanceof TagEntry te) {
						tagEntries.add(te);
					}
				} catch (Exception e) {
					AetheriumShaders.logger.warn("Unexpected error while parsing an entry from " + fileName + " for the key " + key + ":", e);
				}
			}

			blockEntriesById.put(intId, Collections.unmodifiableList(blockEntries));
			tagEntriesById.put(intId, Collections.unmodifiableList(tagEntries));
		});

		duplicateTracker.reportSummary();

		blockTagMap.putAll(tagEntriesById);

		return blockEntriesById;
	}

	/** Parses the {@code layer.*} render type overrides. */
	private static Map<NamespacedId, BlockRenderType> parseRenderTypeMap(Properties properties, String keyPrefix, String fileName) {
		Map<NamespacedId, BlockRenderType> overrides = new HashMap<>();

		DuplicateTracker duplicateTracker = new DuplicateTracker(fileName, "render type map");

		properties.forEach((keyObject, valueObject) -> {
			String key = (String) keyObject;
			String value = (String) valueObject;

			if (!key.startsWith(keyPrefix)) {
				// Not a key of this map.
				return;
			}

			// fromString expects "cutout", not "layer.cutout".
			String keyWithoutPrefix = key.substring(keyPrefix.length());

			BlockRenderType renderType = BlockRenderType.fromString(keyWithoutPrefix).orElse(null);

			if (renderType == null) {
				AetheriumShaders.logger.warn("Failed to parse line in " + fileName + ": invalid block render type: " + key);
				return;
			}

			for (String part : value.split("\\s+")) {
				if (part.startsWith("%")) {
					AetheriumShaders.logger.error("Cannot use a tag in the render type map: " + key + " = " + value);
					continue;
				}

				duplicateTracker.checkAndRecord(part, key, part);

				// NamespacedId does no validation; that happens when converting to Identifiers.
				overrides.put(new NamespacedId(part), renderType);
			}
		});

		duplicateTracker.reportSummary();

		return overrides;
	}

	private static void warnMissingBackslashInPropertiesFile(String processedSource, String propertiesFileName) {
		if (propertiesFileName.equals("shaders.properties")) {
			return;
		}
		String[] fileNameSections = propertiesFileName.split("\\.");
		String entryName = "entry";
		if (fileNameSections.length >= 2) {
			entryName = fileNameSections[0] + " entry";
		}
		Matcher matcher = PropertiesPreprocessor.BACKSLASH_MATCHER.matcher(processedSource);
		while (matcher.find()) {
			AetheriumShaders.logger.warn("Found missing \"\\\" in file \"{}\" in {}: \"{}\"", propertiesFileName, entryName, matcher.group(0));
			for (int i = 1; i <= matcher.groupCount(); i++) {
				String match = matcher.group(i);
				if (match == null) {
					continue;
				}
				AetheriumShaders.logger.warn("At ID: \"{}\"", match);
			}
		}
	}

	public Int2ObjectLinkedOpenHashMap<List<BlockEntry>> getBlockProperties() {
		return blockPropertiesMap;
	}

	public Int2ObjectLinkedOpenHashMap<List<TagEntry>> getTagEntries() {
		return blockTagMap;
	}

	public Object2IntFunction<NamespacedId> getItemIdMap() {
		return itemIdMap;
	}

	public Object2IntFunction<NamespacedId> getEntityIdMap() {
		return entityIdMap;
	}

	public Map<NamespacedId, BlockRenderType> getBlockRenderTypeMap() {
		return blockRenderTypeMap;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}

		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		IdMap idMap = (IdMap) o;

		return Objects.equals(itemIdMap, idMap.itemIdMap)
			&& Objects.equals(entityIdMap, idMap.entityIdMap)
			&& Objects.equals(blockPropertiesMap, idMap.blockPropertiesMap)
			&& Objects.equals(blockTagMap, idMap.blockTagMap)
			&& Objects.equals(blockRenderTypeMap, idMap.blockRenderTypeMap);
	}

	@Override
	public int hashCode() {
		return Objects.hash(itemIdMap, entityIdMap, blockPropertiesMap, blockTagMap, blockRenderTypeMap);
	}

	private static class DuplicateTracker {
		private final Map<String, String> identifierToKeyMap = new HashMap<>();
		private final Set<String> reportedDuplicates = new HashSet<>(); // Tracks which duplicates have already been reported
		private final Map<String, Integer> duplicateCounts = new HashMap<>();

		private final String fileName;
		private final String itemType;
		private final boolean debugEnabled;

		public DuplicateTracker(String fileName, String itemType) {
			this.fileName = fileName;
			this.itemType = itemType;
			this.debugEnabled = AetheriumShaders.getShaderConfig().areDebugOptionsEnabled();
		}

		/**
		 * Creates a unique identifier for a block entry that includes both the block ID and its blockstates.
		 * This ensures we don't flag blocks with different states as duplicates.
		 */
		public static String getUniqueBlockIdentifier(BlockEntry entry) {
			StringBuilder sb = new StringBuilder();
			sb.append(entry.id().toString());

			Map<String, String> properties = entry.propertyPredicates();
			if (properties != null && !properties.isEmpty()) {
				// Sorted so the identifier doesn't depend on declaration order.
				List<String> propertyKeys = new ArrayList<>(properties.keySet());
				Collections.sort(propertyKeys);

				for (String propKey : propertyKeys) {
					sb.append(':').append(propKey).append('=').append(properties.get(propKey));
				}
			}

			return sb.toString();
		}

		public void checkAndRecord(String identifier, String key, String displayValue) {
			if (identifierToKeyMap.containsKey(identifier)) {
				String previousKey = identifierToKeyMap.get(identifier);

				String duplicateKey = identifier + "|" + previousKey + "|" + key;

				duplicateCounts.put(duplicateKey, duplicateCounts.getOrDefault(duplicateKey, 0) + 1);

				// Log each distinct duplicate once, and only with debug options enabled.
				if (debugEnabled && !reportedDuplicates.contains(duplicateKey)) {
					AetheriumShaders.logger.warn("Duplicate {} in {}: '{}' in '{}' and '{}'",
						itemType, fileName, displayValue, previousKey, key);
					reportedDuplicates.add(duplicateKey);
				} else reportedDuplicates.add(duplicateKey); // Still add to avoid double counting
			}

			identifierToKeyMap.put(identifier, key);

		}

		public void reportSummary() {
			if (debugEnabled && !duplicateCounts.isEmpty()) {
				int totalDuplicates = duplicateCounts.values().stream().mapToInt(Integer::intValue).sum();
				AetheriumShaders.logger.warn("Found {} duplicate {} in {}.",
					totalDuplicates,
					itemType + (totalDuplicates > 1 ? "s" : ""),
					fileName);
			}
		}
	}
}
