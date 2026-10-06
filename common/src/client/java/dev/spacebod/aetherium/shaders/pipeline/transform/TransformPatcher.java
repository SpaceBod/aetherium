package dev.spacebod.aetherium.shaders.pipeline.transform;

import io.github.douira.glsl_transformer.ast.node.Profile;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.VersionStatement;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.EnumASTTransformer;
import io.github.douira.glsl_transformer.ast.transform.TransformationException;
import io.github.douira.glsl_transformer.parser.ParsingException;
import io.github.douira.glsl_transformer.token_filter.ChannelFilter;
import io.github.douira.glsl_transformer.token_filter.TokenChannel;
import io.github.douira.glsl_transformer.token_filter.TokenFilter;
import io.github.douira.glsl_transformer.util.LRUCache;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import dev.spacebod.aetherium.client.gpu.FloatControls;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.engine.LoadTimings;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTest;
import dev.spacebod.aetherium.shaders.gl.shader.ShaderCompileException;
import dev.spacebod.aetherium.shaders.gl.state.ShaderAttributeInputs;
import dev.spacebod.aetherium.shaders.gl.texture.TextureType;
import dev.spacebod.aetherium.shaders.helpers.Tri;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.ComputeParameters;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.LodParameters;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.Parameters;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.TextureStageParameters;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.VanillaParameters;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.LoweredProgram;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.LoweringParameters;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.VulkanLowering;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.CommonTransformer;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.CompatibilityTransformer;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.CompositeCoreTransformer;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.CompositeTransformer;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.LodGenericTransformer;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.LodTerrainTransformer;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.TextureTransformer;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.VanillaCoreTransformer;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.VanillaTransformer;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.VanillaInterface;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import org.antlr.v4.runtime.Token;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Patches pack GLSL into the form the pipeline compiles, using glsl-transformer's AST transformer, then lowers it for
 * Vulkan on the same trees ({@link VulkanLowering}).
 * <p>
 * Results are cached on the source strings, the parameters, the lowering parameters and the debug-options switch (print
 * layout, unused-function removal), so every object held by a parameter must implement {@code equals} and must not be
 * mutated after it has been used for patching. NaN preservation is applied to the cached result on every request, so
 * switching it never serves a stale program. Behind the in-memory cache, {@link TransformDiskCache} keeps results across
 * launches.
 * <p>
 * Input must already be preprocessed: only {@code #extension} and {@code #pragma} are allowed (they are parsed
 * directives); any other directive throws.
 */
public class TransformPatcher {
	static final TokenFilter<Parameters> parseTokenFilter = new ChannelFilter<>(TokenChannel.PREPROCESSOR) {
		@Override
		public boolean isTokenAllowed(Token token) {
			if (!super.isTokenAllowed(token)) {
				throw new IllegalArgumentException("Unparsed preprocessor directives such as '" + token.getText()
					+ "' may not be present at this stage of shader processing!");
			}
			return true;
		}
	};
	/** Guarded by itself, not by {@link #TRANSFORM_LOCK}: a cache hit never waits for another program's transform. */
	private static final Map<CacheKey, Transformed> cache = new LRUCache<>(400);

	/** A transform's output: the stages, and the lowering's interface data. */
	record Transformed(Map<PatchShaderType, String> stages, VulkanLowering.Result lowered) {
	}

	/**
	 * Held while the transformer runs. glsl-transformer keeps parser and index-building state in statics
	 * ({@code Root.activeBuildRoots}, {@code ASTBuilder.tokenStream}), so transforms cannot run concurrently even with
	 * separate transformer instances. Everything around them (SPIR-V compilation, pipeline builds) runs in parallel.
	 */
	private static final Object TRANSFORM_LOCK = new Object();

	/** Drops every cached transform (a pack was unloaded: its sources would otherwise stay in memory). */
	public static void clearCache() {
		synchronized (cache) {
			cache.clear();
		}
	}

	private static Transformed cached(CacheKey key) {
		synchronized (cache) {
			return cache.get(key);
		}
	}

	private static void store(CacheKey key, Transformed result) {
		synchronized (cache) {
			cache.put(key, result);
		}
	}

	private static final List<String> internalPrefixes = List.of("aeth_", "aethMain", "moj_import");
	private static final Pattern versionPattern = Pattern.compile("#version\\s+(\\d+)", Pattern.DOTALL);
	private static final EnumASTTransformer<Parameters, PatchShaderType> transformer;

	static {
		transformer = new EnumASTTransformer<>(PatchShaderType.class) {
			{
				setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
				setParsingCacheStrategy(ParsingCacheStrategy.TWO_TIER);
			}

			@Override
			public TranslationUnit parseTranslationUnit(Root rootInstance, String input) {
				// Read #version with a regex first so the lexer is set to the right GLSL version.
				Matcher matcher = versionPattern.matcher(input);
				if (!matcher.find()) {
					throw new IllegalArgumentException(
						"No #version directive found in source code");
				}
				transformer.getLexer().version = Version.fromNumber(Integer.parseInt(matcher.group(1)));

				return super.parseTranslationUnit(rootInstance, input);
			}
		};
		transformer.setTransformation((trees, parameters) -> {
			for (PatchShaderType type : PatchShaderType.values()) {
				TranslationUnit tree = trees.get(type);
				if (tree == null) {
					continue;
				}
				tree.outputOptions.enablePrintInfo();

				parameters.type = type;
				Root root = tree.getRoot();

				// Packs must not reference our internal identifiers.
				internalPrefixes.stream()
					.flatMap(root.getPrefixIdentifierIndex()::prefixQueryFlat)
					.findAny()
					.ifPresent(id -> {
							throw new IllegalArgumentException(
								"The pack names an identifier reserved for Aetherium Shaders' own interface (aeth_, aethMain, moj_import): " + id.getName());
					});

				root.indexBuildSession(() -> {
					VersionStatement versionStatement = tree.getVersionStatement();
					if (versionStatement == null) {
						throw new IllegalStateException("Missing the version statement!");
					}
					Profile profile = versionStatement.profile;
					Version version = versionStatement.version;
					if (Objects.requireNonNull(parameters.patch) == Patch.COMPUTE) {// compute implies GLSL 400+
						versionStatement.profile = Profile.CORE;
						CommonTransformer.transform(transformer, tree, root, parameters, true);
					} else {// the pack format treats core profile, or 150+ without a profile, as core-profile mode
						boolean isLine = (parameters.patch == Patch.VANILLA && ((VanillaParameters) parameters).isLines());

						if (profile == Profile.CORE || version.number >= 150 && profile == null || isLine) {
							if (version.number < 330) {
								versionStatement.version = Version.GLSL33;
							}

							switch (parameters.patch) {
								case COMPOSITE:
									CompositeCoreTransformer.transform(transformer, tree, root, parameters);
									break;
								case VANILLA:
									VanillaCoreTransformer.transform(transformer, tree, root, (VanillaParameters) parameters);
									break;
								default:
									throw new UnsupportedOperationException("Unknown patch type: " + parameters.patch);
							}

							if (parameters.type == PatchShaderType.FRAGMENT) {
								CompatibilityTransformer.transformFragmentCore(transformer, tree, root, parameters);
							}
						} else {
							if (version.number < 330) {
								versionStatement.version = Version.GLSL33;
							}
							versionStatement.profile = Profile.CORE;

							switch (parameters.patch) {
								case COMPOSITE:
									CompositeTransformer.transform(transformer, tree, root, parameters);
									break;
								case VANILLA:
									VanillaTransformer.transform(transformer, tree, root, (VanillaParameters) parameters);
									break;
								case LOD_TERRAIN:
									LodTerrainTransformer.transform(transformer, tree, root, parameters);
									break;
								case LOD_GENERIC:
									LodGenericTransformer.transform(transformer, tree, root, parameters);
									break;
								default:
									throw new UnsupportedOperationException("Unknown patch type: " + parameters.patch);
							}
						}
					}
					TextureTransformer.transform(transformer, tree, root,
						parameters.getTextureStage(), parameters.getTextureMap());
					CompatibilityTransformer.transformEach(transformer, tree, root, parameters);
				});
			}

			CompatibilityTransformer.transformGrouped(transformer, trees, parameters);

			// The Vulkan lowering, on the trees, before anything is printed.
			parameters.lowered = VulkanLowering.transformGrouped(transformer, trees, parameters.lowering);
		});
		transformer.setTokenFilter(parseTokenFilter);
	}

	private static Map<PatchShaderType, String> transformInternal(
		String name,
		Map<PatchShaderType, String> inputs,
		Parameters parameters) {
		try {
			parameters.name = name;
			return transformer.transform(inputs, parameters);
		} catch (TransformationException | ParsingException | IllegalStateException | IllegalArgumentException e) {
			// Dump the offending sources, then abort the pack load.
			ShaderPrinter.printProgram("errored_" + name).addSources(inputs).print();
			throw new ShaderCompileException(name, e);
		}
	}

	// The transformer is shared (see TRANSFORM_LOCK); world programs are built on worker threads too.
	private static Transformed transform(String name, Map<PatchShaderType, String> inputs, Parameters parameters, LoweringParameters lowering) {
		boolean debug = AetheriumShaders.getShaderConfig().areDebugOptionsEnabled();
		CacheKey key = new CacheKey(parameters, lowering, Map.copyOf(withoutNulls(inputs)), debug);
		Transformed result = cached(key);
		byte[] diskKey = null;
		if (result == null) {
			// The disk cache is read outside the transform lock, so programs found there load in parallel.
			long started = System.nanoTime();
			diskKey = TransformDiskCache.key(parameters, lowering, key.sources(), debug);
			result = diskKey == null ? null : TransformDiskCache.read(diskKey);
			if (result != null) {
				LoadTimings.addCached(LoadTimings.Kind.TRANSFORM, System.nanoTime() - started);
				store(key, result);
			}
		}
		if (result == null) {
			synchronized (TRANSFORM_LOCK) {
				long started = System.nanoTime();
				transformer.setPrintType(debug ? PrintType.INDENTED : PrintType.SIMPLE);
				parameters.lowering = lowering;
				parameters.lowered = null;
				try {
					Map<PatchShaderType, String> stages = transformInternal(name, inputs, parameters);
					result = new Transformed(stages, Objects.requireNonNull(parameters.lowered, "lowering did not run"));
				} finally {
					parameters.lowering = null;
					parameters.lowered = null;
					LoadTimings.add(LoadTimings.Kind.TRANSFORM, System.nanoTime() - started);
				}
			}
			store(key, result);
			if (diskKey != null) {
				TransformDiskCache.write(diskKey, result);
			}
		}
		if (result.lowered().testsNaN() && FloatControls.preserveNaN()) {
			// The program's NaN checks must work as on OpenGL (see FloatControls); only programs that have them pay for it.
			Map<PatchShaderType, String> preserving = new EnumMap<>(PatchShaderType.class);
			result.stages().forEach((type, glsl) -> preserving.put(type, glsl == null ? null : FloatControls.preservingNaN(glsl)));
			AetheriumShaders.logger.debug("[{}] tests for NaN: IEEE NaN handling requested", name);
			return new Transformed(preserving, result.lowered());
		}
		return result;
	}

	private static Map<PatchShaderType, String> withoutNulls(Map<PatchShaderType, String> inputs) {
		Map<PatchShaderType, String> out = new EnumMap<>(PatchShaderType.class);
		inputs.forEach((k, v) -> {
			if (v != null) {
				out.put(k, v);
			}
		});
		return out;
	}

	private static Map<PatchShaderType, String> graphicsInputs(String vertex, String geometry, String tessControl, String tessEval, String fragment) {
		EnumMap<PatchShaderType, String> inputs = new EnumMap<>(PatchShaderType.class);
		inputs.put(PatchShaderType.VERTEX, vertex);
		inputs.put(PatchShaderType.GEOMETRY, geometry);
		inputs.put(PatchShaderType.TESS_CONTROL, tessControl);
		inputs.put(PatchShaderType.TESS_EVAL, tessEval);
		inputs.put(PatchShaderType.FRAGMENT, fragment);
		return inputs;
	}

	private static LoweredProgram lowered(String name, Map<PatchShaderType, String> inputs, Parameters parameters, LoweringParameters lowering) {
		Transformed t = transform(name, inputs, parameters, lowering);
		VulkanLowering.Result r = t.lowered();
		Map<PatchShaderType, String> stages = withoutNulls(t.stages());
		if (r.geometryFolded()) {
			// The geometry stage only handed each corner on: the fragment stage now reads the vertex stage's outputs.
			stages.remove(PatchShaderType.GEOMETRY);
		}
		return new LoweredProgram(stages, r.layout(), r.resources(), r.samplerTypes(), r.notes());
	}

	/** A world program (gbuffers / shadow for a vanilla pipeline), then the Vulkan lowering on the tree. */
	public static LoweredProgram patchVanillaLowered(
		String name, String vertex, String geometry, String tessControl, String tessEval, String fragment,
		AlphaTest alpha, boolean isLines, boolean isClouds,
		ShaderAttributeInputs inputs, VanillaInterface.TransformSource source,
		Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap, LoweringParameters lowering) {
		return lowered(name, graphicsInputs(vertex, geometry, tessControl, tessEval, fragment),
			new VanillaParameters(Patch.VANILLA, textureMap, alpha, isLines, isClouds, inputs, source, geometry != null, tessControl != null || tessEval != null),
			lowering);
	}

	/** A LOD program ({@code generic}: the generic-object variant), then the Vulkan lowering on the tree. */
	public static LoweredProgram patchLodLowered(String name, String vertex, String fragment, boolean generic,
												 Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap, LoweringParameters lowering) {
		return lowered(name, graphicsInputs(vertex, null, null, null, fragment),
			new LodParameters(generic ? Patch.LOD_GENERIC : Patch.LOD_TERRAIN, textureMap), lowering);
	}

	/** A full-screen program, then the Vulkan lowering on the tree. */
	public static LoweredProgram patchCompositeLowered(String name, String vertex, String geometry, String fragment, TextureStage stage,
													   Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap, LoweringParameters lowering) {
		return lowered(name, graphicsInputs(vertex, geometry, null, null, fragment), new TextureStageParameters(Patch.COMPOSITE, stage, textureMap), lowering);
	}

	/** A compute program, then the Vulkan lowering on the tree. */
	public static LoweredProgram patchComputeLowered(String name, String compute, TextureStage stage,
													 Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap, LoweringParameters lowering) {
		EnumMap<PatchShaderType, String> inputs = new EnumMap<>(PatchShaderType.class);
		inputs.put(PatchShaderType.COMPUTE, compute);
		return lowered(name, inputs, new ComputeParameters(Patch.COMPUTE, stage, textureMap), lowering);
	}

	/** Everything a cached transform depends on: parameters, lowering parameters, sources and the debug-options switch. */
	private record CacheKey(Parameters parameters, LoweringParameters lowering, Map<PatchShaderType, String> sources, boolean debugOptions) {
	}
}
