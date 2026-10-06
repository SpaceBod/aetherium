package dev.spacebod.aetherium.shaders.shaderpack.texture;

import java.util.Optional;

public enum TextureStage {
	/**
	 * The setup passes. An extension; not in the original pack format.
	 */
	SETUP,
	/**
	 * The begin pass. An extension; not in the original pack format.
	 */
	BEGIN,
	/**
	 * The shadowcomp passes. Undocumented in shaders.txt but a valid custom-texture stage.
	 */
	SHADOWCOMP,
	/**
	 * The prepare passes. Undocumented in shaders.txt but a valid custom-texture stage.
	 */
	PREPARE,
	/**
	 * All of the gbuffer passes, as well as the shadow passes.
	 */
	GBUFFERS_AND_SHADOW,
	/**
	 * The deferred pass.
	 */
	DEFERRED,
	/**
	 * The composite pass and final pass.
	 */
	COMPOSITE_AND_FINAL;

	public static Optional<TextureStage> parse(String name) {
		return switch (name) {
			case "setup" -> Optional.of(SETUP);
			case "begin" -> Optional.of(BEGIN);
			case "shadowcomp" -> Optional.of(SHADOWCOMP);
			case "prepare" -> Optional.of(PREPARE);
			case "gbuffers" -> Optional.of(GBUFFERS_AND_SHADOW);
			case "deferred" -> Optional.of(DEFERRED);
			case "composite" -> Optional.of(COMPOSITE_AND_FINAL);
			default -> Optional.empty();
		};
	}
}
