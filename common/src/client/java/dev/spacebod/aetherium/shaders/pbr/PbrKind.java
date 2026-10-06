package dev.spacebod.aetherium.shaders.pbr;

import org.jspecify.annotations.Nullable;
import org.joml.Vector4f;

/**
 * The two material textures a resource pack can ship next to a colour texture: {@code <name>_n.png} (normal map) and
 * {@code <name>_s.png} (specular map). Where a pack has none, programs read the default: a flat normal, or no specular.
 */
public enum PbrKind {
	NORMAL("_n", 0xFF7F7FFF, "normals"),
	SPECULAR("_s", 0x00000000, "specular");

	private static final PbrKind[] VALUES = values();

	private final String suffix;
	/** The value where a texture has no map, ARGB. */
	private final int defaultArgb;
	/** The sampler pack programs read it through. */
	private final String sampler;

	PbrKind(String suffix, int defaultArgb, String sampler) {
		this.suffix = suffix;
		this.defaultArgb = defaultArgb;
		this.sampler = sampler;
	}

	public String suffix() {
		return suffix;
	}

	public int defaultArgb() {
		return defaultArgb;
	}

	public String sampler() {
		return sampler;
	}

	/** The default colour as a clear colour (0..1 per channel, RGBA). */
	public Vector4f defaultColour() {
		return new Vector4f((defaultArgb >> 16 & 255) / 255f, (defaultArgb >> 8 & 255) / 255f, (defaultArgb & 255) / 255f, (defaultArgb >>> 24) / 255f);
	}

	/** {@code textures/block/stone.png} -> {@code textures/block/stone_n.png}; no extension: the suffix is appended. */
	public String withSuffix(String path) {
		int dot = extensionIndex(path);
		return dot < 0 ? path + suffix : path.substring(0, dot) + suffix + path.substring(dot);
	}

	/** The kind {@code sampler} names ({@code normals}, {@code specular}), or null. */
	public static @Nullable PbrKind ofSampler(String sampler) {
		for (PbrKind kind : VALUES) {
			if (kind.sampler.equals(sampler)) {
				return kind;
			}
		}
		return null;
	}

	/** {@code textures/block/stone_n.png} -> {@code textures/block/stone.png}; null when the name has no material suffix. */
	public static @Nullable String baseOf(String path) {
		int dot = extensionIndex(path);
		String stem = dot < 0 ? path : path.substring(0, dot);
		for (PbrKind kind : VALUES) {
			if (stem.endsWith(kind.suffix)) {
				return stem.substring(0, stem.length() - kind.suffix.length()) + (dot < 0 ? "" : path.substring(dot));
			}
		}
		return null;
	}

	private static int extensionIndex(String path) {
		int dot = path.lastIndexOf('.');
		return dot > path.lastIndexOf('/') ? dot : -1;
	}
}
