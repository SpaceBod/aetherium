package dev.spacebod.aetherium.shaders.shaderpack.programs;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.features.FeatureFlags;
import dev.spacebod.aetherium.shaders.gl.blending.BlendModeOverride;
import dev.spacebod.aetherium.shaders.shaderpack.ShaderPack;
import dev.spacebod.aetherium.shaders.shaderpack.include.AbsolutePackPath;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramArrayId;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import dev.spacebod.aetherium.shaders.shaderpack.parsing.ComputeDirectiveParser;
import dev.spacebod.aetherium.shaders.shaderpack.parsing.ConstDirectiveParser;
import dev.spacebod.aetherium.shaders.shaderpack.parsing.DispatchingDirectiveHolder;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackRenderTargetDirectives;
import dev.spacebod.aetherium.shaders.shaderpack.properties.ShaderProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

public class ProgramSet implements ProgramSetInterface {
	/** Shared by every program set: preprocessing the gbuffer programs is CPU-bound and independent per program. */
	private static final ExecutorService READERS = Executors.newFixedThreadPool(
		Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())), runnable -> {
			Thread thread = new Thread(runnable, "Aetherium Shaders program reader");
			thread.setDaemon(true);
			thread.setPriority(Thread.NORM_PRIORITY - 1);
			return thread;
		});

	private final PackDirectives packDirectives;

	private final ComputeSource[] shadowCompute;
	private final ComputeSource[] finalCompute;

	private final ComputeSource[] setup;

	private final EnumMap<ProgramId, ProgramSource> gbufferPrograms = new EnumMap<>(ProgramId.class);
	private final EnumMap<ProgramArrayId, ProgramSource[]> compositePrograms = new EnumMap<>(ProgramArrayId.class);
	private final EnumMap<ProgramArrayId, ComputeSource[][]> computePrograms = new EnumMap<>(ProgramArrayId.class);

	public ProgramSet(AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider,
					  ShaderProperties shaderProperties, ShaderPack pack) {
		this.packDirectives = new PackDirectives(PackRenderTargetDirectives.BASELINE_SUPPORTED_RENDER_TARGETS, shaderProperties);

		boolean readTesselation = pack.hasFeature(FeatureFlags.TESSELLATION_SHADERS);

		this.shadowCompute = readComputeArray(directory, sourceProvider, "shadow", shaderProperties);
		this.setup = readProgramArray(directory, sourceProvider, "setup", shaderProperties);

		for (ProgramArrayId id : ProgramArrayId.values()) {
			ProgramSource[] sources = readProgramArray(directory, sourceProvider, id.getSourcePrefix(), shaderProperties, readTesselation);
			compositePrograms.put(id, sources);
			ComputeSource[][] computes = new ComputeSource[id.getNumPrograms()][];
			boolean hasNoComputes = true;
			for (int i = 0; i < id.getNumPrograms(); i++) {
				computes[i] = readComputeArray(directory, sourceProvider, id.getSourcePrefix() + (i == 0 ? "" : i), shaderProperties);
				if (computes[i].length > 0) {
					hasNoComputes = false;
				}
			}
			computePrograms.put(id, hasNoComputes ? new ComputeSource[0][] : computes);
		}

		// The gbuffer programs are preprocessed in parallel. Each program's own default blend mode (shadow programs:
		// blending off) is attached here; ProgramFallbackResolver applies the default of the program asked for when
		// another program stands in for it.
		List<Future<ProgramSource>> sources = new ArrayList<>();
		for (ProgramId programId : ProgramId.values()) {
			sources.add(READERS.submit(() -> readProgramSource(directory, sourceProvider, programId.getSourceName(), this, shaderProperties,
				programId.getBlendModeOverride(), readTesselation)));
		}
		try {
			for (ProgramId id : ProgramId.values()) {
				gbufferPrograms.put(id, sources.get(id.ordinal()).get());
			}
		} catch (InterruptedException e) {
			sources.forEach(future -> future.cancel(true));
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while reading the pack's programs", e);
		} catch (ExecutionException e) {
			sources.forEach(future -> future.cancel(true));
			if (e.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (e.getCause() instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException(e.getCause());
		}

		this.finalCompute = readComputeArray(directory, sourceProvider, "final", shaderProperties);

		locateDirectives();
	}

	private static ProgramSource readProgramSource(AbsolutePackPath directory,
												   Function<AbsolutePackPath, String> sourceProvider, String program,
												   ProgramSet programSet, ShaderProperties properties, boolean readTesselation) {
		return readProgramSource(directory, sourceProvider, program, programSet, properties, null, readTesselation);
	}

	private static ProgramSource readProgramSource(AbsolutePackPath directory,
												   Function<AbsolutePackPath, String> sourceProvider, String program,
												   ProgramSet programSet, ShaderProperties properties,
												   BlendModeOverride defaultBlendModeOverride, boolean readTesselation) {
		AbsolutePackPath vertexPath = directory.resolve(program + ".vsh");
		String vertexSource = sourceProvider.apply(vertexPath);

		AbsolutePackPath geometryPath = directory.resolve(program + ".gsh");
		String geometrySource = sourceProvider.apply(geometryPath);

		String tessControlSource = null;
		String tessEvalSource = null;

		if (readTesselation) {
			AbsolutePackPath tessControlPath = directory.resolve(program + ".tcs");
			tessControlSource = sourceProvider.apply(tessControlPath);

			AbsolutePackPath tessEvalPath = directory.resolve(program + ".tes");
			tessEvalSource = sourceProvider.apply(tessEvalPath);
		}

		AbsolutePackPath fragmentPath = directory.resolve(program + ".fsh");
		String fragmentSource = sourceProvider.apply(fragmentPath);

		if (vertexSource == null && fragmentSource != null) {
			// Very old packs ship fragment-only programs; synthesise a legacy fixed-function vertex shader.
			AetheriumShaders.logger.warn("Found a program (" + program + ") that has a fragment shader but no vertex shader? This is very legacy behavior and might not work right.");
			vertexSource = """
				#version 120

				varying vec4 aeth_vTexCoords[3];
				varying vec4 aeth_vColor;

				void main() {
					gl_Position = ftransform();
					aeth_vTexCoords[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
					aeth_vTexCoords[1] = gl_TextureMatrix[1] * gl_MultiTexCoord1;
					aeth_vTexCoords[2] = gl_TextureMatrix[1] * gl_MultiTexCoord2;
					aeth_vColor = gl_Color;
				}
				""";
		}

		return new ProgramSource(program, vertexSource, geometrySource, tessControlSource, tessEvalSource, fragmentSource, programSet, properties,
			defaultBlendModeOverride);
	}

	private static ComputeSource readComputeSource(AbsolutePackPath directory,
												   Function<AbsolutePackPath, String> sourceProvider, String program,
												   ProgramSet programSet, ShaderProperties properties) {
		AbsolutePackPath computePath = directory.resolve(program + ".csh");
		String computeSource = sourceProvider.apply(computePath);

		if (computeSource == null) {
			return null;
		}

		return new ComputeSource(program, computeSource, programSet, properties);
	}

	private ProgramSource[] readProgramArray(AbsolutePackPath directory,
											 Function<AbsolutePackPath, String> sourceProvider, String name,
											 ShaderProperties shaderProperties, boolean readTesselation) {
		ProgramSource[] programs = new ProgramSource[100];

		for (int i = 0; i < programs.length; i++) {
			String suffix = i == 0 ? "" : Integer.toString(i);

			programs[i] = readProgramSource(directory, sourceProvider, name + suffix, this, shaderProperties, readTesselation);
		}

		return programs;
	}

	private ComputeSource[] readProgramArray(AbsolutePackPath directory,
											 Function<AbsolutePackPath, String> sourceProvider, String name, ShaderProperties properties) {
		ComputeSource[] programs = new ComputeSource[100];

		for (int i = 0; i < programs.length; i++) {
			String suffix = i == 0 ? "" : Integer.toString(i);

			programs[i] = readComputeSource(directory, sourceProvider, name + suffix, this, properties);
		}

		return programs;
	}

	private ComputeSource[] readComputeArray(AbsolutePackPath directory,
											 Function<AbsolutePackPath, String> sourceProvider, String name, ShaderProperties properties) {
		ComputeSource[] programs = new ComputeSource[27];

		programs[0] = readComputeSource(directory, sourceProvider, name, this, properties);

		for (char c = 'a'; c <= 'z'; ++c) {
			String suffix = "_" + c;

			programs[c - 96] = readComputeSource(directory, sourceProvider, name + suffix, this, properties);

			if (programs[c - 96] == null) {
				break;
			}
		}

		if (Arrays.stream(programs).allMatch(Objects::isNull)) {
			return new ComputeSource[0];
		}

		return programs;
	}

	private void locateDirectives() {
		List<ProgramSource> programs = new ArrayList<>();
		List<ComputeSource> computes = new ArrayList<>();

		programs.addAll(Arrays.asList(getComposite(ProgramArrayId.ShadowComposite)));
		programs.addAll(Arrays.asList(getComposite(ProgramArrayId.Begin)));
		programs.addAll(Arrays.asList(getComposite(ProgramArrayId.Prepare)));

		for (ComputeSource[][] sources : computePrograms.values()) {
			for (ComputeSource[] source : sources) {
				computes.addAll(Arrays.asList(source));
			}
		}

		programs.addAll(gbufferPrograms.values());

		for (ComputeSource computeSource : setup) {
			if (computeSource != null) {
				computes.add(computeSource);
			}
		}

		programs.addAll(Arrays.asList(getComposite(ProgramArrayId.Deferred)));
		programs.addAll(Arrays.asList(getComposite(ProgramArrayId.Composite)));

		Collections.addAll(computes, finalCompute);
		Collections.addAll(computes, shadowCompute);

		for (ComputeSource source : computes) {
			if (source != null) {
				source.getSource().map(ConstDirectiveParser::findDirectives).ifPresent(constDirectives -> {
					for (ConstDirectiveParser.ConstDirective directive : constDirectives) {
						if (directive.getType() == ConstDirectiveParser.Type.IVEC3 && directive.getKey().equals("workGroups")) {
							ComputeDirectiveParser.setComputeWorkGroups(source, directive);
						} else if (directive.getType() == ConstDirectiveParser.Type.VEC2 && directive.getKey().equals("workGroupsRender")) {
							ComputeDirectiveParser.setComputeWorkGroupsRelative(source, directive);
						}
					}
				});
			}
		}

		DispatchingDirectiveHolder packDirectiveHolder = new DispatchingDirectiveHolder();

		packDirectives.acceptDirectivesFrom(packDirectiveHolder);

		for (ProgramSource source : programs) {
			if (source == null) {
				continue;
			}

			// Source-level directives first so an explicit const directive in the same program wins.
			for (Optional<String> stage : List.of(source.getVertexSource(), source.getTessControlSource(),
				source.getTessEvalSource(), source.getGeometrySource(), source.getFragmentSource())) {
				stage.ifPresent(packDirectiveHolder::processSource);
			}

			source.getFragmentSource().map(ConstDirectiveParser::findDirectives).ifPresent(directives -> {
				for (ConstDirectiveParser.ConstDirective directive : directives) {
					packDirectiveHolder.processDirective(directive);
				}
			});
		}

		packDirectives.getRenderTargetDirectives().getRenderTargetSettings().forEach((index, settings) ->
			AetheriumShaders.logger.debug("Render target settings for colortex" + index + ": " + settings));
	}

	public ComputeSource[] getSetup() {
		return setup;
	}

	public Optional<ProgramSource> get(ProgramId programId) {
		ProgramSource source = gbufferPrograms.getOrDefault(programId, null);
		if (source != null) {
			return source.requireValid();
		} else {
			return Optional.empty();
		}
	}

	public ComputeSource[] getShadowCompute() {
		return shadowCompute;
	}

	public ComputeSource[] getFinalCompute() {
		return finalCompute;
	}

	public PackDirectives getPackDirectives() {
		return packDirectives;
	}

	public ProgramSource[] getComposite(ProgramArrayId programArrayId) {
		return compositePrograms.getOrDefault(programArrayId, new ProgramSource[programArrayId.getNumPrograms()]);
	}

	public ComputeSource[][] getCompute(ProgramArrayId programArrayId) {
		return computePrograms.getOrDefault(programArrayId, new ComputeSource[0][0]);
	}
}
