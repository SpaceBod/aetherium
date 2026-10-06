package dev.spacebod.aetherium.shaders.engine;

import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramArrayId;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every sampler name any program of the pack declares (world, shadow and full-screen programs), so per-frame work that
 * only feeds a sampler (a depth conversion, the shadowtex1 copy) can be skipped when nothing reads it. Declared is not
 * the same as read, so this over-includes, which is the safe direction.
 */
public final class SamplerUsage {
	/** {@code uniform [layout(...)] [precision] [iu]samplerX a[, b ...];}: group 1 is the declarator list. */
	private static final Pattern SAMPLER = Pattern.compile(
			"\\buniform\\s+(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:lowp|mediump|highp)\\s+)?[iu]?sampler\\w*\\s+([^;]+);");
	private static final Pattern NAME = Pattern.compile("^\\s*(\\w+)");
	private final Set<String> names;

	/** Every sampler name {@code source} declares, multi-declarator lines ({@code uniform sampler2D a, b;}) included. */
	public static Set<String> declaredSamplers(String source) {
		Set<String> names = new HashSet<>();
		Matcher m = SAMPLER.matcher(source);
		while (m.find()) {
			for (String declarator : m.group(1).split(",")) {
				Matcher name = NAME.matcher(declarator);
				if (name.find()) {
					names.add(name.group(1));
				}
			}
		}
		return names;
	}

	private SamplerUsage(Set<String> names) {
		this.names = names;
	}

	public static SamplerUsage of(ProgramSet programs) {
		Set<String> names = new HashSet<>();
		for (ProgramId id : ProgramId.values()) {
			programs.get(id).ifPresent(source -> scan(source, names));
		}
		for (ProgramArrayId stage : ProgramArrayId.values()) {
			for (ProgramSource source : programs.getComposite(stage)) {
				if (source != null) {
					scan(source, names);
				}
			}
		}
		return new SamplerUsage(names);
	}

	private static void scan(ProgramSource source, Set<String> names) {
		for (Optional<String> text : List.of(source.getVertexSource(), source.getGeometrySource(), source.getTessControlSource(),
				source.getTessEvalSource(), source.getFragmentSource())) {
			if (text.isEmpty()) {
				continue;
			}
			names.addAll(declaredSamplers(text.get()));
		}
	}

	public boolean declares(String name) {
		return names.contains(name);
	}

	/** Whether {@code depthtex<which>} (or an alias of it) is sampled anywhere. */
	public boolean samplesDepth(int which) {
		return switch (which) {
			case DepthTargets.ALL -> declares("depthtex0") || declares("gdepthtex");
			case DepthTargets.NO_TRANSLUCENTS -> declares("depthtex1");
			case DepthTargets.NO_HAND -> declares("depthtex2");
			case DepthTargets.LOD_ALL -> declares("dhDepthTex") || declares("dhDepthTex0");
			case DepthTargets.LOD_NO_TRANSLUCENTS -> declares("dhDepthTex1");
			default -> throw new IllegalArgumentException("depth target " + which);
		};
	}

	/** Whether the opaque-only shadow depth ({@code shadowtex1}, or {@code shadow} next to {@code watershadow}) is sampled. */
	public boolean samplesOpaqueShadowDepth() {
		return declares("shadowtex1") || declares("shadowtex1HW") || declares("shadow") && declares("watershadow");
	}
}
