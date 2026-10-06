package dev.spacebod.aetherium.shaders.gl.blending;

public class BlendModeOverride {
	public static final BlendModeOverride OFF = new BlendModeOverride(null);

	private final BlendMode blendMode;

	public BlendModeOverride(BlendMode blendMode) {
		this.blendMode = blendMode;
	}

	/** The override, or null for OFF. Applied through vanilla pipeline blend state, not GL calls. */
	public BlendMode blendMode() {
		return this.blendMode;
	}
}
