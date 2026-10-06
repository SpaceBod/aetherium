package dev.spacebod.aetherium.shaders.engine;

import dev.spacebod.aetherium.core.Optimisations;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime state of the shader-engine optimisations ({@code shaders.*} in {@link Optimisations}).
 * Read once per frame by the engine, so {@link #set} (the settings screen, the benchmark's in-run A/B) can flip them
 * between frames.
 * Starts from the configured profile.
 */
public final class EngineSwitches {
	public static final String LEAN_FRAME = "shaders.lean_frame";
	public static final String SHADOW_CACHE = "shaders.shadow_cache";
	public static final String EARLY_HAND = "shaders.early_hand";
	public static final String REACH_SNAPSHOTS = "shaders.reach_snapshots";
	public static final String SETTLED_BINDINGS = "shaders.settled_bindings";
	public static final String FROZEN_FULLSCREEN = "shaders.frozen_fullscreen";
	public static final String BLIT_MIPS = "shaders.blit_mips";
	public static final String LOADOP_CLEARS = "shaders.loadop_clears";
	public static final String NARROW_BARRIER = "shaders.narrow_barrier";
	/** Applies when the colour targets are (re)created (pack load, resize). */
	public static final String FLIP_ONLY_DOUBLES = "shaders.flip_only_doubles";
	public static final String SHADOW_CACHE_BIND = "shaders.shadow_cache_bind";

	private static final Map<String, Boolean> OVERRIDES = new ConcurrentHashMap<>();

	private EngineSwitches() {
	}

	public static boolean enabled(String id) {
		Boolean on = OVERRIDES.get(id);
		return on != null ? on : Optimisations.isEnabled(id);
	}

	/** Whether {@code id} can be flipped while running. */
	public static boolean isRuntime(String id) {
		return id.equals(LEAN_FRAME) || id.equals(SHADOW_CACHE) || id.equals(EARLY_HAND)
				|| id.equals("shaders.inline_compute") || id.equals(REACH_SNAPSHOTS) || id.equals(SETTLED_BINDINGS) || id.equals(FROZEN_FULLSCREEN)
				|| id.equals(BLIT_MIPS) || id.equals(LOADOP_CLEARS) || id.equals(NARROW_BARRIER) || id.equals(SHADOW_CACHE_BIND);
	}

	public static void set(String id, boolean on) {
		OVERRIDES.put(id, on);
	}

	/** Back to the configured profile's value. */
	public static void reset(String id) {
		OVERRIDES.remove(id);
	}
}
