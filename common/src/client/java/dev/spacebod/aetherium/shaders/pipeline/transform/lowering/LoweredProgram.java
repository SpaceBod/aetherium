package dev.spacebod.aetherium.shaders.pipeline.transform.lowering;

import com.mojang.renderpearl.api.pipeline.UniformType;
import dev.spacebod.aetherium.shaders.engine.GbufferLayout;
import dev.spacebod.aetherium.shaders.engine.ShadowSampling;
import dev.spacebod.aetherium.shaders.engine.UniformBlock;
import dev.spacebod.aetherium.shaders.pipeline.transform.PatchShaderType;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A program after the Vulkan lowering: its stages as GLSL vanilla's compiler accepts, and its interface as data, read
 * from the syntax tree rather than re-scanned from the text.
 *
 * @param stages       the GLSL of each stage (absent stages are missing)
 * @param layout       the {@code AetheriumUniforms} block: members, std140 offsets, initial values
 * @param resources    what the pipeline binds per draw, in declaration order: uniform blocks and samplers by name
 *                     (custom images and storage buffers live in the shared set 1 and are not listed); only samplers
 *                     some path from the entry point reads ({@link #reachedTargets()})
 * @param samplerTypes the GLSL type of each listed sampler ({@code sampler2D}, {@code usampler2D}, ...)
 * @param notes        what the lowering adapted (attributes defaulted or converted ...), for the debug log
 */
public record LoweredProgram(Map<PatchShaderType, String> stages, UniformBlock.Layout layout, Map<String, UniformType> resources,
		Map<String, String> samplerTypes, List<String> notes) {

	/** The samplers in binding order (compute programs bind them at set 0, bindings 1..n in this order). */
	public List<String> samplers() {
		return samplerTypes.keySet().stream().toList();
	}

	/**
	 * The colour, depth and shadow targets the program reads, by the pack's names: the lowering removes every sampler no
	 * path from the entry point reads, so each listed sampler is read on some path (whether it is read in a given frame
	 * depends on the program's branches). For deciding which targets a pass needs copied or kept.
	 */
	public Set<String> reachedTargets() {
		return reachedTargets(samplerTypes.keySet());
	}

	/** {@link #reachedTargets()} over the sampler bindings of a lowered program ({@code aeth_cmp_} ones under the pack's name). */
	public static Set<String> reachedTargets(Collection<String> samplerBindings) {
		Set<String> out = new LinkedHashSet<>();
		for (String binding : samplerBindings) {
			String name = ShadowSampling.samplerName(binding);
			if (GbufferLayout.targetIndex(name) >= 0 || DEPTH_TARGETS.contains(name) || ShadowSampling.depthIndex(name, true) >= 0
					|| name.startsWith("shadowcolor")) {
				out.add(name);
			}
		}
		return out;
	}

	private static final Set<String> DEPTH_TARGETS = Set.of("depthtex0", "depthtex1", "depthtex2", "gdepthtex", "dhDepthTex", "dhDepthTex0", "dhDepthTex1");
}
