package dev.spacebod.aetherium.client.gpu;

import dev.spacebod.aetherium.core.Optimisations;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import java.nio.ByteBuffer;

/**
 * What vanilla's GLSL compiler does differently for Aetherium Shaders' own modules (pipeline ids in the
 * {@code aetherium_shaders} namespace: pack programs and the engine's helper passes). The game's own shaders and every
 * other mod's are compiled exactly as vanilla compiles them.
 * <ul>
 *   <li>no debug information (source text, names, line table): modules come out a fraction of the size, so shaderc,
 *       the driver and the pipeline cache all have less to read. Kept in the pack's debug mode, where a driver message
 *       or a capture can then point at pack lines;</li>
 *   <li>{@code shaders.zero_locals}: variables a path reads before writing get the zero OpenGL drivers give
 *       ({@link LocalZeroes}). Applied as each module compiles; compute programs compiled outside vanilla's
 *       compiler take it from {@link ShaderCompiler#packCompute}.</li>
 *   <li>on MoltenVK, the names of private code dropped ({@link DebugNames}), so none reaches Metal's compiler.</li>
 * </ul>
 */
public final class PackModules {
	public static final String NAMESPACE = "aetherium_shaders:";
	public static final String ZERO_LOCALS = "shaders.zero_locals";

	private PackModules() {
	}

	/** Whether vanilla's compiler is compiling one of our modules ({@code name}: the shader id it was handed). */
	public static boolean ours(String name) {
		return name != null && name.startsWith(NAMESPACE);
	}

	/** Whether our modules get shaderc's debug information: only in the pack's debug mode. */
	public static boolean debugInfo() {
		return AetheriumShaders.getShaderConfig().areDebugOptionsEnabled();
	}

	/**
	 * One of our compiled modules as the pipeline receives it: read-before-written variables zeroed when switched on,
	 * and on MoltenVK the names of its private code dropped ({@link DebugNames}) unless the pack is in debug mode.
	 */
	public static ByteBuffer patch(ByteBuffer spirv) {
		ByteBuffer out = Optimisations.isEnabled(ZERO_LOCALS) ? LocalZeroes.patch(spirv) : spirv;
		return DeviceTraits.moltenVk() && !debugInfo() ? DebugNames.strip(out) : out;
	}
}
