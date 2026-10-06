package dev.spacebod.aetherium.shaders.shaderpack.option.menu;

import dev.spacebod.aetherium.shaders.shaderpack.option.StringOption;
import dev.spacebod.aetherium.shaders.shaderpack.option.values.OptionValues;
import dev.spacebod.aetherium.shaders.shaderpack.properties.ShaderProperties;

public class OptionMenuStringOptionElement extends OptionMenuOptionElement {
	public final StringOption option;

	public OptionMenuStringOptionElement(String elementString, OptionMenuContainer container, ShaderProperties shaderProperties, OptionValues values, StringOption option) {
		super(elementString, container, shaderProperties, values);
		this.option = option;
	}
}
