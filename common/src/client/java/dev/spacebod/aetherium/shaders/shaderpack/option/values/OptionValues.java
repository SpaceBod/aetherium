package dev.spacebod.aetherium.shaders.shaderpack.option.values;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.helpers.OptionalBoolean;
import dev.spacebod.aetherium.shaders.shaderpack.option.OptionSet;

import java.util.Optional;

public interface OptionValues {
	OptionalBoolean getBooleanValue(String name);

	Optional<String> getStringValue(String name);

	default boolean getBooleanValueOrDefault(String name) {
		if ("0".equals(name)) {
			return false;
		} else if ("1".equals(name)) {
			return true;
		}

		return getBooleanValue(name).orElseGet(() -> {
			if (!getOptionSet().getBooleanOptions().containsKey(name)) {
				AetheriumShaders.logger.warn("Tried to get boolean value for unknown option: " + name + ", defaulting to true!");
				return true;
			}
			return getOptionSet().getBooleanOptions().get(name).getOption().getDefaultValue();
		});
	}

	default String getStringValueOrDefault(String name) {
		return getStringValue(name).orElseGet(() -> getOptionSet().getStringOptions().get(name).getOption().getDefaultValue());
	}

	int getOptionsChanged();

	MutableOptionValues mutableCopy();

	ImmutableOptionValues toImmutable();

	OptionSet getOptionSet();
}
