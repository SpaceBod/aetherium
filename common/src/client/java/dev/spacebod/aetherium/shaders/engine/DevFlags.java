package dev.spacebod.aetherium.shaders.engine;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Diagnostic switches for pack development, from environment variables read once when the class loads. All off unless
 * set; nothing on the frame's path checks more than one of these fields when they are.
 * <ul>
 *   <li>{@code AETHERIUM_SHADERS_DEBUG} and {@code AETHERIUM_SHADERS_SHOW}: the {@link DebugView} views;</li>
 *   <li>{@code AETHERIUM_SHADERS_TRACE=<text>[,<text>...]}: logs, once per distinct case, each bind of a vanilla pipeline
 *       whose location contains one of the texts, with the pass, the draw flags and the program it resolved to;</li>
 *   <li>{@code AETHERIUM_DUMP=<text>}: writes the converted stages of every program whose name contains the text to
 *       {@code shader-dump/}.</li>
 * </ul>
 */
public final class DevFlags {
	/** {@code AETHERIUM_SHADERS_DEBUG}, or null. */
	static final @Nullable String DEBUG = System.getenv("AETHERIUM_SHADERS_DEBUG");
	/** {@code AETHERIUM_SHADERS_SHOW}, or null. */
	static final @Nullable String SHOW = System.getenv("AETHERIUM_SHADERS_SHOW");
	/** Either debug view is on: the engine calls {@link DebugView} only then. */
	static final boolean DEBUG_VIEW = DEBUG != null || SHOW != null;
	/** {@code AETHERIUM_SHADERS_TRACE} split at commas, or null. */
	static final @Nullable List<String> TRACE = split(System.getenv("AETHERIUM_SHADERS_TRACE"));
	/** {@code AETHERIUM_DUMP}, or null. */
	public static final @Nullable String DUMP = System.getenv("AETHERIUM_DUMP");

	private DevFlags() {
	}

	private static @Nullable List<String> split(@Nullable String value) {
		return value == null ? null : List.of(value.split(","));
	}

	/** Whether {@code AETHERIUM_DUMP} asks for the program {@code name}. */
	public static boolean dumps(String name) {
		return DUMP != null && name.contains(DUMP);
	}
}
