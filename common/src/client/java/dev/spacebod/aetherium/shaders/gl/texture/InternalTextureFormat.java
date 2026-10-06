package dev.spacebod.aetherium.shaders.gl.texture;

import java.util.Locale;
import java.util.Optional;

/** The texture formats packs can request ({@code colortexNFormat}, custom images), with their pixel layout. */
public enum InternalTextureFormat {
	// Default: not a format name packs use, but the format of a target that requests none.
	RGBA(PixelFormat.RGBA),
	// 8-bit normalized
	R8(PixelFormat.RED),
	RG8(PixelFormat.RG),
	RGB8(PixelFormat.RGB),
	RGBA8(PixelFormat.RGBA),
	// 8-bit signed normalized
	R8_SNORM(PixelFormat.RED),
	RG8_SNORM(PixelFormat.RG),
	RGB8_SNORM(PixelFormat.RGB),
	RGBA8_SNORM(PixelFormat.RGBA),
	// 16-bit normalized
	R16(PixelFormat.RED),
	RG16(PixelFormat.RG),
	RGB16(PixelFormat.RGB),
	RGBA16(PixelFormat.RGBA),
	// 16-bit signed normalized
	R16_SNORM(PixelFormat.RED),
	RG16_SNORM(PixelFormat.RG),
	RGB16_SNORM(PixelFormat.RGB),
	RGBA16_SNORM(PixelFormat.RGBA),
	// 16-bit float
	R16F(PixelFormat.RED),
	RG16F(PixelFormat.RG),
	RGB16F(PixelFormat.RGB),
	RGBA16F(PixelFormat.RGBA),
	// 32-bit float
	R32F(PixelFormat.RED),
	RG32F(PixelFormat.RG),
	RGB32F(PixelFormat.RGB),
	RGBA32F(PixelFormat.RGBA),
	// 8-bit integer
	R8I(PixelFormat.RED_INTEGER),
	RG8I(PixelFormat.RG_INTEGER),
	RGB8I(PixelFormat.RGB_INTEGER),
	RGBA8I(PixelFormat.RGBA_INTEGER),
	// 8-bit unsigned integer
	R8UI(PixelFormat.RED_INTEGER),
	RG8UI(PixelFormat.RG_INTEGER),
	RGB8UI(PixelFormat.RGB_INTEGER),
	RGBA8UI(PixelFormat.RGBA_INTEGER),
	// 16-bit integer
	R16I(PixelFormat.RED_INTEGER),
	RG16I(PixelFormat.RG_INTEGER),
	RGB16I(PixelFormat.RGB_INTEGER),
	RGBA16I(PixelFormat.RGBA_INTEGER),
	// 16-bit unsigned integer
	R16UI(PixelFormat.RED_INTEGER),
	RG16UI(PixelFormat.RG_INTEGER),
	RGB16UI(PixelFormat.RGB_INTEGER),
	RGBA16UI(PixelFormat.RGBA_INTEGER),
	// 32-bit integer
	R32I(PixelFormat.RED_INTEGER),
	RG32I(PixelFormat.RG_INTEGER),
	RGB32I(PixelFormat.RGB_INTEGER),
	RGBA32I(PixelFormat.RGBA_INTEGER),
	// 32-bit unsigned integer
	R32UI(PixelFormat.RED_INTEGER),
	RG32UI(PixelFormat.RG_INTEGER),
	RGB32UI(PixelFormat.RGB_INTEGER),
	RGBA32UI(PixelFormat.RGBA_INTEGER),
	// 2-bit normalized
	RGBA2(PixelFormat.RGBA),
	// 4-bit normalized
	RGBA4(PixelFormat.RGBA),
	// Mixed
	R3_G3_B2(PixelFormat.RGB),
	RGB5_A1(PixelFormat.RGBA),
	RGB565(PixelFormat.RGB),
	RGB10_A2(PixelFormat.RGBA),
	RGB10_A2UI(PixelFormat.RGBA_INTEGER),
	R11F_G11F_B10F(PixelFormat.RGB),
	RGB9_E5(PixelFormat.RGB);

	private final PixelFormat expectedPixelFormat;

	InternalTextureFormat(PixelFormat expectedPixelFormat) {
		this.expectedPixelFormat = expectedPixelFormat;
	}

	public static Optional<InternalTextureFormat> fromString(String name) {
		try {
			return Optional.of(InternalTextureFormat.valueOf(name.toUpperCase(Locale.US)));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	public PixelFormat getPixelFormat() {
		return expectedPixelFormat;
	}
}
