package dev.spacebod.aetherium.shaders.gl.texture;

import dev.spacebod.aetherium.shaders.AetheriumShaders;

import java.util.Locale;
import java.util.Optional;

/** The channel layout of a raw texture's data. */
public enum PixelFormat {
	RED(1, false),
	RG(2, false),
	RGB(3, false),
	BGR(3, false),
	RGBA(4, false),
	BGRA(4, false),
	RED_INTEGER(1, true),
	RG_INTEGER(2, true),
	RGB_INTEGER(3, true),
	BGR_INTEGER(3, true),
	RGBA_INTEGER(4, true),
	BGRA_INTEGER(4, true);

	private final int componentCount;
	private final boolean isInteger;

	PixelFormat(int componentCount, boolean isInteger) {
		this.componentCount = componentCount;
		this.isInteger = isInteger;
	}

	public static Optional<PixelFormat> fromString(String name) {
		try {
			return Optional.of(PixelFormat.valueOf(name.toUpperCase(Locale.US)));
		} catch (IllegalArgumentException e) {
			AetheriumShaders.logger.error("Looking for an illegal pixel format: " + name.toUpperCase(Locale.US));
			return Optional.empty();
		}
	}

	public int getComponentCount() {
		return componentCount;
	}

	public boolean isInteger() {
		return isInteger;
	}
}
