package dev.spacebod.aetherium.shaders.shaderpack.materialmap;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.platform.ShaderPlatform;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BlockMaterialMapping {
	public static Object2IntMap<BlockState> createBlockStateIdMap(Int2ObjectLinkedOpenHashMap<List<BlockEntry>> blockPropertiesMap, Int2ObjectLinkedOpenHashMap<List<TagEntry>> tagPropertiesMap) {
		Object2IntMap<BlockState> blockStateIds = new Object2IntLinkedOpenHashMap<>();
		blockStateIds.defaultReturnValue(-1);
		blockPropertiesMap.forEach((intId, entries) -> {
			for (BlockEntry entry : entries) {
				addBlockStates(entry, blockStateIds, intId);
			}
		});

		tagPropertiesMap.forEach((intId, entries) -> {
			for (TagEntry entry : entries) {
				addTag(entry, blockStateIds, intId);
			}
		});

		return blockStateIds;
	}

	private static void addTag(TagEntry tagEntry, Object2IntMap<BlockState> idMap, int intId) {
		List<HolderSet.Named<Block>> compatibleTags = BuiltInRegistries.BLOCK.getTags().filter(t -> t.key().location().getNamespace().equalsIgnoreCase(tagEntry.id().getNamespace()) &&
			t.key().location().getPath().equalsIgnoreCase(tagEntry.id().getName())).toList();

		if (compatibleTags.isEmpty()) {
			if (ShaderPlatform.getInstance().isDevelopmentEnvironment()) {
				AetheriumShaders.logger.warn("Failed to find the tag " + tagEntry.id());
			}
		} else if (compatibleTags.size() > 1) {
			AetheriumShaders.logger.warn("the block tag " + tagEntry.id() + " matches more than one tag (names differ only in case); ignoring it");
		} else {
			BuiltInRegistries.BLOCK.getTagOrEmpty(compatibleTags.getFirst().key()).forEach((block) -> {
					Map<String, String> propertyPredicates = tagEntry.propertyPredicates();

					if (propertyPredicates.isEmpty()) {
						// No predicates: every state.
						for (BlockState state : block.value().getStateDefinition().getPossibleStates()) {
							// putIfAbsent: the first matching entry wins, as packs expect.
							idMap.putIfAbsent(state, intId);
						}

						return;
					}

					// Resolve predicate keys to Property objects: validates they exist and avoids string key lookups per state.
					Map<Property<?>, String> properties = new LinkedHashMap<>();
					StateDefinition<Block, BlockState> stateManager = block.value().getStateDefinition();

					propertyPredicates.forEach((key, value) -> {
						Property<?> property = stateManager.getProperty(key);

						if (property == null) {
							AetheriumShaders.logger.warn("Error while parsing the block ID map entry for tag \"" + "block." + intId + "\":");
							AetheriumShaders.logger.warn("- The block " + block.unwrapKey().get().identifier() + " has no property with the name " + key + ", ignoring!");

							return;
						}

						properties.put(property, value);
					});

					// Linear scan over every state of the block.
					for (BlockState state : stateManager.getPossibleStates()) {
						if (checkState(state, properties)) {
							// putIfAbsent: the first matching entry wins, as packs expect.
							idMap.putIfAbsent(state, intId);
						}
					}
				}
			);
		}
	}

	public static Map<Block, BlockRenderType> createBlockTypeMap(Map<NamespacedId, BlockRenderType> blockPropertiesMap) {
		if (blockPropertiesMap.isEmpty()) return Object2ObjectMaps.emptyMap();

		Map<Block, BlockRenderType> blockTypeIds = new Reference2ReferenceOpenHashMap<>();

		blockPropertiesMap.forEach((id, blockType) -> {
			Identifier identifier = Identifier.fromNamespaceAndPath(id.getNamespace(), id.getName());

			Block block = BuiltInRegistries.BLOCK.get(identifier).map(Holder::value).orElse(Blocks.AIR);

			blockTypeIds.put(block, blockType);
		});

		return blockTypeIds;
	}

	private static void addBlockStates(BlockEntry entry, Object2IntMap<BlockState> idMap, int intId) {
		NamespacedId id = entry.id();
		Identifier identifier;
		try {
			identifier = Identifier.fromNamespaceAndPath(id.getNamespace(), id.getName());
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to get entry for " + intId, exception);
		}

		Block block = BuiltInRegistries.BLOCK.get(identifier).map(Holder::value).orElse(Blocks.AIR);

		if (block == Blocks.AIR) {
			return;
		}

		Map<String, String> propertyPredicates = entry.propertyPredicates();

		if (propertyPredicates.isEmpty()) {
			// No predicates: every state.
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				// putIfAbsent: the first matching entry wins, as packs expect.
				idMap.putIfAbsent(state, intId);
			}

			return;
		}

		// Resolve predicate keys to Property objects: validates they exist and avoids string key lookups per state.
		Map<Property<?>, String> properties = new LinkedHashMap<>();
		StateDefinition<Block, BlockState> stateManager = block.getStateDefinition();

		propertyPredicates.forEach((key, value) -> {
			Property<?> property = stateManager.getProperty(key);

			if (property == null) {
				AetheriumShaders.logger.warn("Error while parsing the block ID map entry for \"" + "block." + intId + "\":");
				AetheriumShaders.logger.warn("- The block " + identifier + " has no property with the name " + key + ", ignoring!");

				return;
			}

			properties.put(property, value);
		});

		// Linear scan over every state of the block.
		for (BlockState state : stateManager.getPossibleStates()) {
			if (checkState(state, properties)) {
				// putIfAbsent: the first matching entry wins, as packs expect.
				idMap.putIfAbsent(state, intId);
			}
		}
	}

	// Raw types: values are only compared by their string name, so the property's value type is irrelevant.
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static boolean checkState(BlockState state, Map<Property<?>, String> expectedValues) {
		for (Map.Entry<Property<?>, String> condition : expectedValues.entrySet()) {
			Property property = condition.getKey();
			String expectedValue = condition.getValue();

			String actualValue = property.getName(state.getValue(property));

			if (!expectedValue.equals(actualValue)) {
				return false;
			}
		}

		return true;
	}
}
