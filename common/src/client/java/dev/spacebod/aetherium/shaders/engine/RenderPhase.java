package dev.spacebod.aetherium.shaders.engine;

import dev.spacebod.aetherium.shaders.pipeline.WorldRenderingPhase;
import org.jspecify.annotations.Nullable;

/**
 * The world-rendering phase packs read as {@code renderStage}; set by the Aetherium Shaders engine. Render thread only.
 * <p>
 * A program's own phase applies when it binds, unless the code drawing knows better and has set an override: the sky
 * (sky disc, sunset, sun, moon, stars, void all share a few vanilla pipelines) and the shadow pass (terrain, then
 * entities, then translucent terrain), as the pack format defines the stages.
 */
public final class RenderPhase {
	private static WorldRenderingPhase current = WorldRenderingPhase.NONE;
	private static @Nullable WorldRenderingPhase override;

	private RenderPhase() {
	}

	public static WorldRenderingPhase current() {
		return current;
	}

	public static void set(WorldRenderingPhase phase) {
		current = phase;
	}

	/** At program bind: the override when one is set, else the program's own phase. */
	static void bind(WorldRenderingPhase programPhase) {
		current = override != null ? override : programPhase;
	}

	/** The phase for whatever is drawn next (null clears it); also becomes current at once. */
	public static void override(@Nullable WorldRenderingPhase phase) {
		override = phase;
		if (phase != null) {
			current = phase;
		}
	}
}
