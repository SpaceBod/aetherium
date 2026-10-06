package dev.spacebod.aetherium.shaders.gl.texture;

import dev.spacebod.aetherium.shaders.AetheriumShaders;

import java.util.Locale;
import java.util.Optional;

/** The component type of a raw texture's data, with its size in bytes. */
public enum PixelType {
	BYTE(1),
	SHORT(2),
	INT(4),
	HALF_FLOAT(2),
	FLOAT(4),
	UNSIGNED_BYTE(1),
	UNSIGNED_BYTE_3_3_2(1),
	UNSIGNED_BYTE_2_3_3_REV(1),
	UNSIGNED_SHORT(2),
	UNSIGNED_SHORT_5_6_5(2),
	UNSIGNED_SHORT_5_6_5_REV(2),
	UNSIGNED_SHORT_4_4_4_4(2),
	UNSIGNED_SHORT_4_4_4_4_REV(2),
	UNSIGNED_SHORT_5_5_5_1(2),
	UNSIGNED_SHORT_1_5_5_5_REV(2),
	UNSIGNED_INT(4),
	UNSIGNED_INT_8_8_8_8(4),
	UNSIGNED_INT_8_8_8_8_REV(4),
	UNSIGNED_INT_10_10_10_2(4),
	UNSIGNED_INT_2_10_10_10_REV(4),
	UNSIGNED_INT_10F_11F_11F_REV(4),
	UNSIGNED_INT_5_9_9_9_REV(4);

	private final int byteSize;

	PixelType(int byteSize) {
		this.byteSize = byteSize;
	}

	public static Optional<PixelType> fromString(String name) {
		try {
			return Optional.of(PixelType.valueOf(name.toUpperCase(Locale.US)));
		} catch (IllegalArgumentException e) {
			AetheriumShaders.logger.error("Failed to find pixel type " + name.toUpperCase(Locale.ROOT));
			return Optional.empty();
		}
	}

	public int getByteSize() {
		return byteSize;
	}
}
