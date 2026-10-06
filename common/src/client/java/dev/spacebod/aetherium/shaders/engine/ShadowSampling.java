package dev.spacebod.aetherium.shaders.engine;

import dev.spacebod.aetherium.client.gpu.DeviceTraits;
import dev.spacebod.aetherium.core.Optimisations;
import dev.spacebod.aetherium.shaders.helpers.OptionalBoolean;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.LoweringParameters;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramGroup;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramFallbackResolver;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackShadowDirectives;
import java.util.Collection;
import java.util.List;

/**
 * How the active pack samples its two shadow depth maps ({@code shadowtex0}, {@code shadowtex1}), from its
 * {@code shadowtexNNearest} and {@code shadowHardwareFilteringN} settings, as the pack format defines them:
 * <ul>
 *   <li>plain reads are linearly filtered unless the map is set to nearest;</li>
 *   <li>depth-comparison reads of a hardware-filtered, linear map return the bilinear blend of the four nearest
 *       comparisons (OpenGL's filtered comparison); otherwise one nearest comparison.</li>
 * </ul>
 * Set when a pack loads, before its programs are built: the shader conversion and the sampler bindings both read it.
 * <p>
 * Comparisons ({@code sampler2DShadow}) on the depth maps run on a hardware depth-comparison sampler
 * ({@code shaders.hw_shadow_compare}) when the pack has a shadow pass: the program keeps the comparison type under its
 * own binding name ({@link #COMPARE_PREFIX}), and the binding gets a comparison sampler filtered as above. Without a
 * shadow pass, or with the switch off, the comparisons are made in the shader.
 */
public final class ShadowSampling {
	/** The binding name a comparison sampler on a depth map gets ({@code shadowtex0} -> {@code aeth_cmp_shadowtex0}). */
	public static final String COMPARE_PREFIX = "aeth_cmp_";
	public static final String HW_COMPARE = "shaders.hw_shadow_compare";
	/** The {@code maxLod} a sampler is created with to make it a depth-comparison sampler (no vanilla sampler uses it). */
	public static final double COMPARE_LOD = 1000.75;

	private static volatile boolean[] nearest = {false, false};
	private static volatile boolean[] filteredCompare = {false, false};
	private static volatile boolean shadowPass;

	private ShadowSampling() {
	}

	/** {@code shadowPass}: the pack has a shadow pass ({@link #hasShadowPass}), so the depth maps exist as depth images. */
	public static void configure(PackShadowDirectives directives, boolean shadowPass) {
		configure(directives);
		ShadowSampling.shadowPass = shadowPass;
	}

	/** Whether the pack draws a shadow pass: shadows not disabled, and some program of the shadow group exists. */
	public static boolean hasShadowPass(ProgramSet programs, PackDirectives pack) {
		if (pack.getShadowDirectives().isShadowEnabled() == OptionalBoolean.FALSE) {
			return false;
		}
		ProgramFallbackResolver resolver = new ProgramFallbackResolver(programs);
		for (ProgramId id : ProgramId.values()) {
			if (id.getGroup() == ProgramGroup.Shadow && resolver.resolve(id).isPresent()) {
				return true;
			}
		}
		return false;
	}

	private static void configure(PackShadowDirectives directives) {
		List<PackShadowDirectives.DepthSamplingSettings> settings = directives.getDepthSamplingSettings();
		boolean[] n = new boolean[2];
		boolean[] f = new boolean[2];
		for (int i = 0; i < 2; i++) {
			PackShadowDirectives.DepthSamplingSettings s = i < settings.size() ? settings.get(i) : null;
			n[i] = s != null && s.getNearest();
			f[i] = s != null && s.getHardwareFiltering() && !n[i];
		}
		nearest = n;
		filteredCompare = f;
	}

	/** Compile checks: every comparison filtered (the longer code path), with a shadow pass. */
	public static void filterAllForCompileCheck() {
		filteredCompare = new boolean[]{true, true};
		shadowPass = true;
	}

	/** Back to defaults (no pack). */
	public static void reset() {
		nearest = new boolean[]{false, false};
		filteredCompare = new boolean[]{false, false};
		shadowPass = false;
	}

	/**
	 * Whether comparisons on the depth maps run on a hardware comparison sampler for programs built now: also needs a
	 * device that accepts comparison samplers in descriptors (a portability-subset device only once it enabled them).
	 */
	public static boolean hardwareCompare() {
		return shadowPass && Optimisations.isEnabled(HW_COMPARE) && DeviceTraits.comparisonSamplers();
	}

	/** The pack's sampler name behind a binding name ({@code aeth_cmp_shadowtex0} -> {@code shadowtex0}). */
	public static String samplerName(String binding) {
		return binding.startsWith(COMPARE_PREFIX) ? binding.substring(COMPARE_PREFIX.length()) : binding;
	}

	/** Whether a program's sampler bindings include {@code watershadow}, as a plain or a comparison sampler. */
	public static boolean declaresWatershadow(Collection<String> bindings) {
		return bindings.contains("watershadow") || bindings.contains(COMPARE_PREFIX + "watershadow");
	}

	/**
	 * Which depth map a sampler name reads: 0 ({@code shadowtex0}, {@code watershadow}, {@code shadowtex0HW}), 1
	 * ({@code shadowtex1}, {@code shadowtex1HW}); {@code shadow} is map 1 when the program also declares
	 * {@code watershadow}, else map 0. -1 for anything else.
	 */
	public static int depthIndex(String name, boolean watershadow) {
		return switch (name) {
			case "shadowtex0", "watershadow", "shadowtex0HW" -> 0;
			case "shadowtex1", "shadowtex1HW" -> 1;
			case "shadow" -> watershadow ? 1 : 0;
			default -> -1;
		};
	}

	/** The configuration as lowering parameters (a value: part of the transform cache key). */
	public static LoweringParameters.Shadow snapshot() {
		boolean[] n = nearest;
		boolean[] f = filteredCompare;
		return new LoweringParameters.Shadow(n[0], n[1], f[0], f[1], hardwareCompare());
	}

	public static boolean nearest(int index) {
		return nearest[index];
	}

	public static boolean filteredCompare(int index) {
		return filteredCompare[index];
	}
}
