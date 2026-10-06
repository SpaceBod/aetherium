package dev.spacebod.aetherium.shaders.gl.state;

/**
 * Update notifiers for tracked render state (fog, blending, textures, phase).
 */
public class StateUpdateNotifiers {
	/**
	 * Nothing fires these: BlockProgramUniforms re-evaluates dynamic uniforms on every bind, so an unset notifier
	 * accepts a listener and never calls it.
	 */
	private static final ValueUpdateNotifier NONE = listener -> {
	};

	public static ValueUpdateNotifier fogStartNotifier = NONE;
	public static ValueUpdateNotifier fogEndNotifier = NONE;
	public static ValueUpdateNotifier blendFuncNotifier = NONE;
	public static ValueUpdateNotifier bindTextureNotifier = NONE;
	public static ValueUpdateNotifier phaseChangeNotifier = NONE;
	public static ValueUpdateNotifier fallbackEntityNotifier = NONE;
}
