package dev.spacebod.aetherium.shaders.shaderpack.option.menu;

import dev.spacebod.aetherium.shaders.shaderpack.option.MergedBooleanOption;
import dev.spacebod.aetherium.shaders.shaderpack.option.MergedStringOption;
import dev.spacebod.aetherium.shaders.shaderpack.option.ShaderPackOptions;
import dev.spacebod.aetherium.shaders.shaderpack.properties.ShaderProperties;

import java.util.Map;

public abstract class OptionMenuElement {
	public static final OptionMenuElement EMPTY = new OptionMenuElement() {
	};

	private static final String ELEMENT_EMPTY = "<empty>";
	private static final String ELEMENT_PROFILE = "<profile>";

	public static OptionMenuElement create(String elementString, OptionMenuContainer container, ShaderProperties shaderProperties, ShaderPackOptions shaderPackOptions) throws IllegalArgumentException {
		if (ELEMENT_EMPTY.equals(elementString)) {
			return EMPTY;
		}
		// A profile element is omitted when the pack defines no profiles, even if requested.
		if (ELEMENT_PROFILE.equals(elementString)) {
			// null means the element is dropped entirely (not replaced by an empty slot)
			return container.getProfiles().size() > 0 ? new OptionMenuProfileElement(container.getProfiles(), shaderPackOptions.getOptionSet(), shaderPackOptions.getOptionValues()) : null;
		}
		// "[name]" links to a sub-screen
		if (elementString.startsWith("[") && elementString.endsWith("]")) {
			return new OptionMenuLinkElement(elementString.substring(1, elementString.length() - 1));
		}

		Map<String, MergedBooleanOption> booleanOptions = shaderPackOptions.getOptionSet().getBooleanOptions();
		Map<String, MergedStringOption> stringOptions = shaderPackOptions.getOptionSet().getStringOptions();

		// Option elements resolve only if the option is defined in the shader source
		if (booleanOptions.containsKey(elementString)) {
			return new OptionMenuBooleanOptionElement(elementString, container, shaderProperties, shaderPackOptions.getOptionValues(), booleanOptions.get(elementString).getOption());
		} else if (stringOptions.containsKey(elementString)) {
			return new OptionMenuStringOptionElement(elementString, container, shaderProperties, shaderPackOptions.getOptionValues(), stringOptions.get(elementString).getOption());
		}

		// Callers log this and substitute an empty element
		throw new IllegalArgumentException("Unable to resolve shader pack option menu element \"" + elementString + "\" defined in shaders.properties");
	}
}
