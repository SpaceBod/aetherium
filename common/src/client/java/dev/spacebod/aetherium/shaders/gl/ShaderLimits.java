package dev.spacebod.aetherium.shaders.gl;

public class ShaderLimits {
	/**
	 * Maximum number of colour textures a pack can read and write in gbuffer and composite programs.
	 */
	public static final int MAX_COLOR_BUFFERS = 32;
	/** Each custom image occupies two set-1 bindings; bindings 64 and above belong to storage buffers. */
	public static final int MAX_CUSTOM_IMAGES = 32;

}
