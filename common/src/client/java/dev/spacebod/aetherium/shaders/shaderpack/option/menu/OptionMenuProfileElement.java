package dev.spacebod.aetherium.shaders.shaderpack.option.menu;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.shaderpack.option.OptionSet;
import dev.spacebod.aetherium.shaders.shaderpack.option.ProfileSet;
import dev.spacebod.aetherium.shaders.shaderpack.option.values.MutableOptionValues;
import dev.spacebod.aetherium.shaders.shaderpack.option.values.OptionValues;

public class OptionMenuProfileElement extends OptionMenuElement {
	public final ProfileSet profiles;
	public final OptionSet options;

	private final OptionValues packAppliedValues;

	public OptionMenuProfileElement(ProfileSet profiles, OptionSet options, OptionValues packAppliedValues) {
		this.profiles = profiles;
		this.options = options;
		this.packAppliedValues = packAppliedValues;
	}

	/**
	 * @return an {@link OptionValues} that also contains values currently
	 * pending application.
	 */
	public OptionValues getPendingOptionValues() {
		MutableOptionValues values = packAppliedValues.mutableCopy();
		values.addAll(AetheriumShaders.getShaderPackOptionQueue());

		return values;
	}
}
