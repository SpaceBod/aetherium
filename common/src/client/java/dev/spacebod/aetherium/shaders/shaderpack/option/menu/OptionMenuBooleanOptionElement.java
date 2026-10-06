package dev.spacebod.aetherium.shaders.shaderpack.option.menu;

import dev.spacebod.aetherium.shaders.shaderpack.option.BooleanOption;
import dev.spacebod.aetherium.shaders.shaderpack.option.values.OptionValues;
import dev.spacebod.aetherium.shaders.shaderpack.properties.ShaderProperties;

public class OptionMenuBooleanOptionElement extends OptionMenuOptionElement {
	public final BooleanOption option;

	public OptionMenuBooleanOptionElement(String elementString, OptionMenuContainer container, ShaderProperties shaderProperties, OptionValues values, BooleanOption option) {
		super(elementString, container, shaderProperties, values);
		this.option = option;
	}
}
