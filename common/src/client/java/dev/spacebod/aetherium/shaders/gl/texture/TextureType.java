package dev.spacebod.aetherium.shaders.gl.texture;

import java.util.Optional;

/** The dimensionality of a raw texture or a custom image. */
public enum TextureType {
	TEXTURE_1D,
	TEXTURE_2D,
	TEXTURE_3D,
	TEXTURE_RECTANGLE;

	public static Optional<TextureType> fromString(String name) {
		try {
			return Optional.of(TextureType.valueOf(name));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
