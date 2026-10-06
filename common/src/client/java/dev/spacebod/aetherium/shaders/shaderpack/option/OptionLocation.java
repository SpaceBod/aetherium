package dev.spacebod.aetherium.shaders.shaderpack.option;

import dev.spacebod.aetherium.shaders.shaderpack.include.AbsolutePackPath;

/**
 * Encapsulates a single location of an option.
 */
public record OptionLocation(AbsolutePackPath filePath, int lineIndex) {


	/**
	 * Zero-based line index of the option.
	 */
	@Override
	public int lineIndex() {
		return lineIndex;
	}
}
