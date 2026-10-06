package dev.spacebod.aetherium.shaders.shaderpack.materialmap;

import java.util.Optional;

/**
 * Defines the possible render types for blocks.
 * Matches the render-type names of the block.properties "layer." keys in the shader-pack format.
 */
public enum BlockRenderType {
	/**
	 * Alpha test disabled, blending disabled, mipmaps enabled.
	 */
	SOLID,
	/**
	 * Alpha test enabled, blending disabled, mipmaps disabled.
	 * Used for tall grass, normal glass, etc.
	 */
	CUTOUT,
	/**
	 * Alpha test enabled, blending disabled, mipmaps enabled.
	 * Used for leaves.
	 */
	CUTOUT_MIPPED,
	/**
	 * Alpha test enabled (w/ low cutoff), blending enabled, mipmaps enabled.
	 * Used for stained glass, nether portals, and water.
	 */
	TRANSLUCENT;

	public static Optional<BlockRenderType> fromString(String name) {
		return switch (name) {
			case "solid" -> Optional.of(SOLID);
			case "cutout" -> Optional.of(CUTOUT);
			case "cutout_mipped" -> Optional.of(CUTOUT_MIPPED);
			case "translucent" -> Optional.of(TRANSLUCENT);
			default -> Optional.empty();
		};
	}
}
