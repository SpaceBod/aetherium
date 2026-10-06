package dev.spacebod.aetherium.shaders.shaderpack.materialmap;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public record BlockEntry(NamespacedId id, Map<String, String> propertyPredicates) implements Entry {

	/**
	 * Parses a block ID entry.
	 *
	 * @param entry The string representation of the entry. Must not be empty.
	 */
	@NotNull
	public static Entry parse(@NotNull String entry) {
		if (entry.isEmpty()) {
			throw new IllegalArgumentException("Called BlockEntry::parse with an empty string");
		}

		if (entry.startsWith("%")) {
			entry = entry.replace("%", "");
			// Tag entry.
			String[] splitStates = entry.split(":");

			if (splitStates.length == 1) {
				return new TagEntry(new NamespacedId("minecraft", entry), Map.of());
			} else if (splitStates.length == 2 && !splitStates[1].contains("=")) {
				return new TagEntry(new NamespacedId(splitStates[0], splitStates[1]), Map.of());
			} else {
				// One or more state predicates.
				int statesStart;
				NamespacedId id;

				if (splitStates[1].contains("=")) {
					// "tall_grass:half=upper"
					statesStart = 1;
					id = new NamespacedId("minecraft", splitStates[0]);
				} else {
					// "minecraft:tall_grass:half=upper"
					statesStart = 2;
					id = new NamespacedId(splitStates[0], splitStates[1]);
				}

				Map<String, String> map = new HashMap<>();

				for (int index = statesStart; index < splitStates.length; index++) {
					String[] propertyParts = splitStates[index].split("=");

					if (propertyParts.length != 2) {
						AetheriumShaders.logger.warn("Warning: the block ID map entry \"" + entry + "\" could not be fully parsed:");
						AetheriumShaders.logger.warn("- Block state property filters must be of the form \"key=value\", but "
							+ splitStates[index] + " is not of that form!");

						continue;
					}

					map.put(propertyParts[0], propertyParts[1]);
				}

				return new TagEntry(id, map);
			}
		}

		// Non-empty input, so at least one element.
		String[] splitStates = entry.split(":");

		// No states, no namespace.
		if (splitStates.length == 1) {
			return new BlockEntry(new NamespacedId("minecraft", entry), Collections.emptyMap());
		}

		// Namespace and name, no states.
		if (splitStates.length == 2 && !splitStates[1].contains("=")) {
			return new BlockEntry(new NamespacedId(splitStates[0], splitStates[1]), Collections.emptyMap());
		}

		// One or more state predicates.
		int statesStart;
		NamespacedId id;

		if (splitStates[1].contains("=")) {
			// "tall_grass:half=upper"
			statesStart = 1;
			id = new NamespacedId("minecraft", splitStates[0]);
		} else {
			// "minecraft:tall_grass:half=upper"
			statesStart = 2;
			id = new NamespacedId(splitStates[0], splitStates[1]);
		}

		// key=value pairs are filters, not a full state: "lantern:hanging=false" must also match the waterlogged
		// variant, so unspecified properties match anything.
		Map<String, String> map = new HashMap<>();

		for (int index = statesStart; index < splitStates.length; index++) {
			String[] propertyParts = splitStates[index].split("=");

			if (propertyParts.length != 2) {
				AetheriumShaders.logger.warn("Warning: the block ID map entry \"" + entry + "\" could not be fully parsed:");
				AetheriumShaders.logger.warn("- Block state property filters must be of the form \"key=value\", but "
					+ splitStates[index] + " is not of that form!");

				continue;
			}

			map.put(propertyParts[0], propertyParts[1]);
		}

		return new BlockEntry(id, map);
	}


}
