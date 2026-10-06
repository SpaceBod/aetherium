package dev.spacebod.aetherium.shaders.shaderpack.option.menu;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.shaderpack.option.values.MutableOptionValues;
import dev.spacebod.aetherium.shaders.shaderpack.option.values.OptionValues;
import dev.spacebod.aetherium.shaders.shaderpack.properties.ShaderProperties;

public abstract class OptionMenuOptionElement extends OptionMenuElement {
	public final boolean slider;
	public final OptionMenuContainer container;
	public final String optionId;

	private final OptionValues packAppliedValues;

	public OptionMenuOptionElement(String elementString, OptionMenuContainer container, ShaderProperties shaderProperties, OptionValues packAppliedValues) {
		this.slider = shaderProperties.getSliderOptions().contains(elementString);
		this.container = container;
		this.optionId = elementString;
		this.packAppliedValues = packAppliedValues;
	}

	/**
	 * @return the {@link OptionValues} currently in use by the shader pack
	 */
	public OptionValues getAppliedOptionValues() {
		return packAppliedValues;
	}

	/**
	 * @return an {@link OptionValues} that also contains values currently
	 * pending application.
	 */
	public OptionValues getPendingOptionValues() {
		MutableOptionValues values = getAppliedOptionValues().mutableCopy();
		values.addAll(AetheriumShaders.getShaderPackOptionQueue());

		return values;
	}
}
