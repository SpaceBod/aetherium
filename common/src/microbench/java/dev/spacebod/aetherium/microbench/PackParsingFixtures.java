package dev.spacebod.aetherium.microbench;

import com.google.common.collect.ImmutableList;
import dev.spacebod.aetherium.client.gpu.StorageFeatures;
import dev.spacebod.aetherium.shaders.engine.DepthTargets;
import dev.spacebod.aetherium.shaders.engine.SamplerUsage;
import dev.spacebod.aetherium.shaders.features.FeatureFlags;
import dev.spacebod.aetherium.shaders.parsing.ExpressionFunctions;
import dev.spacebod.aetherium.shaders.parsing.ExpressionOperators;
import dev.spacebod.aetherium.shaders.parsing.VectorType;
import dev.spacebod.aetherium.shaders.shaderpack.DimensionId;
import dev.spacebod.aetherium.shaders.shaderpack.ShaderPack;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.BlockEntry;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.BlockMaterialMapping;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.BlockRenderType;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.NamespacedId;
import dev.spacebod.aetherium.shaders.shaderpack.preprocessor.JcppProcessor;
import dev.spacebod.aetherium.shaders.shaderpack.preprocessor.PropertiesPreprocessor;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import dev.spacebod.aetherium.shaders.shaderpack.properties.ProgramDirectives;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import kroppeb.stareval.function.FunctionReturn;
import kroppeb.stareval.function.Type;
import kroppeb.stareval.parser.Parser;
import kroppeb.stareval.resolver.ExpressionResolver;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector2f;

public final class PackParsingFixtures {
	private PackParsingFixtures() {
	}

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		materialMaps();
		computeSamplerUsage();
		propertyComments();
		fragmentOutputScan();
		defaultDrawBuffers();
		customExpressions();
		fractionalExpressions();
		imageDeclarationsAndFeatures();
	}

	private static void fractionalExpressions() {
		try {
			ExpressionResolver resolver = new ExpressionResolver(ExpressionFunctions.functions, name -> null, true);
			FunctionReturn value = new FunctionReturn();
			resolver.resolveExpression(Type.Float, Parser.parse("fract(-1.25)", ExpressionOperators.options)).evaluateTo(null, value);
			if (Math.abs(value.floatReturn - 0.75f) > 1e-7f) {
				throw new AssertionError("scalar fractional part did not use floor");
			}
			resolver.resolveExpression(VectorType.VEC2, Parser.parse("fract(vec2(-1.25, 2.5))", ExpressionOperators.options)).evaluateTo(null, value);
			Vector2f vector = (Vector2f) value.objectReturn;
			if (Math.abs(vector.x - 0.75f) > 1e-7f || Math.abs(vector.y - 0.5f) > 1e-7f) {
				throw new AssertionError("vector fractional part lost component-wise evaluation");
			}
		} catch (Exception e) {
			throw new AssertionError("fractional expression could not be resolved", e);
		}
		System.out.println("fractional expressions: scalar and vector negative operands pass");
	}

	private static void imageDeclarationsAndFeatures() {
		StorageFeatures.assumeForCompileCheck();
		try {
			Path directory = Files.createTempDirectory("aetherium-pack-properties-");
			try {
				StringBuilder properties = new StringBuilder("iris.features.required=CUSTOM_IMAGES\n"
						+ "iris.features.optional=TESSELLATION_SHADERS UNKNOWN_FEATURE TEXTURE_FILTERING\n");
				for (int image = 0; image < 33; image++) {
					properties.append("image.test").append(image).append("=none   RGBA\tRGBA8   UNSIGNED_BYTE true false 4 4\n");
				}
				Files.writeString(directory.resolve("shaders.properties"), properties);
				ShaderPack pack = new ShaderPack(directory, ImmutableList.of(), false);
				if (pack.getCustomImages().size() != 32) {
					throw new AssertionError("whitespace-separated image declarations did not stop at 32 images");
				}
				if (pack.hasFeature(FeatureFlags.TESSELLATION_SHADERS) || pack.hasFeature(FeatureFlags.UNKNOWN)
						|| !pack.hasFeature(FeatureFlags.TEXTURE_FILTERING)) {
					throw new AssertionError("optional feature flags did not follow feature availability");
				}
			} finally {
				Files.deleteIfExists(directory.resolve("shaders.properties"));
				Files.delete(directory);
			}
		} catch (IOException e) {
			throw new AssertionError("cannot prepare image/feature parsing fixture", e);
		}
		System.out.println("pack properties: whitespace, 32-image limit and optional feature availability pass");
	}

	private static void materialMaps() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		var entries = new Int2ObjectLinkedOpenHashMap<List<BlockEntry>>();
		var mixedCase = new NamespacedId("minecraft", "polished_Diorite_slab");
		var invalid = new NamespacedId("minecraft", "invalid block");
		entries.put(9, List.of(new BlockEntry(invalid, Map.of()),
				new BlockEntry(mixedCase, Map.of()),
				new BlockEntry(
						new NamespacedId("minecraft", "stone"), Map.of())));
		var ids = BlockMaterialMapping.createBlockStateIdMap(entries,
				new Int2ObjectLinkedOpenHashMap<>());
		if (ids.getInt(Blocks.POLISHED_DIORITE_SLAB.defaultBlockState()) != 9
				|| ids.getInt(Blocks.STONE.defaultBlockState()) != 9) {
			throw new AssertionError("a malformed block ID discarded valid entries in the same material group");
		}
		var type = BlockRenderType.CUTOUT;
		var types = BlockMaterialMapping.createBlockTypeMap(Map.of(mixedCase, type, invalid, type));
		if (types.size() != 1 || types.get(Blocks.POLISHED_DIORITE_SLAB) != type) {
			throw new AssertionError("block layer IDs did not use the same tolerant lookup");
		}
		System.out.println("material maps: mixed-case IDs resolved; invalid entries skipped; valid entries retained");
	}

	/** Compute-only readers must keep their depth copies, even without a fragment program reading the same map. */
	private static void computeSamplerUsage() {
		var files = Map.of(
				"setup7.csh", "uniform sampler2D setupOnly;",
				"shadow_a.csh", "uniform sampler2D shadowOnly;",
				"final_a.csh", "uniform sampler2D depthtex2;",
				"begin3_a.csh", "uniform sampler2D beginOnly;",
				"prepare4_a.csh", "uniform sampler2D prepareOnly;",
				"shadowcomp5_a.csh", "uniform sampler2D shadowcompOnly;",
				"deferred6_a.csh", "uniform sampler2D deferredOnly;",
				"composite7_a.csh", "uniform sampler2DShadow shadowtex1HW;");
		try {
			var directory = Files.createTempDirectory("aetherium-compute-usage-");
			try {
				for (var file : files.entrySet()) {
					Files.writeString(directory.resolve(file.getKey()),
							"#version 430\nlayout(local_size_x=1) in;\n" + file.getValue() + "\nvoid main() {}\n");
				}
				var pack = new ShaderPack(directory,
						ImmutableList.of(), false);
				var usage = SamplerUsage.of(pack.getProgramSet(
						DimensionId.OVERWORLD));
				for (String name : List.of("setupOnly", "shadowOnly", "depthtex2", "beginOnly", "prepareOnly", "shadowcompOnly", "deferredOnly", "shadowtex1HW")) {
					if (!usage.declares(name)) {
						throw new AssertionError("compute-only sampler was missed: " + name);
					}
				}
				if (!usage.samplesOpaqueShadowDepth() || !usage.samplesDepth(DepthTargets.NO_HAND)) {
					throw new AssertionError("compute-only readers lost their opaque shadow or no-hand depth copy");
				}
			} finally {
				for (String file : files.keySet()) {
					Files.deleteIfExists(directory.resolve(file));
				}
				Files.delete(directory);
			}
		} catch (IOException e) {
			throw new AssertionError("cannot prepare compute sampler fixture", e);
		}
		System.out.println("sampler usage: compute-only readers retained across setup, shadow, final and all frame stages");
	}

	private static void propertyComments() {
		String processed = PropertiesPreprocessor.preprocessSource(
				"# Non-English comment bytes: \u0085 comment tail\r\nitem.1=minecraft:torch\r# another \u0085 tail\nitem.2=minecraft:lantern\n", List.of());
		if (!processed.contains("item.1=minecraft:torch") || !processed.contains("item.2=minecraft:lantern")
				|| processed.contains("tail")) {
			throw new AssertionError("non-English property comments truncated or contaminated the ID map: " + processed);
		}
		System.out.println("property comments: CR, LF and CRLF retained; embedded Latin-1 NEL bytes ignored inside comments");
	}

	private static void fragmentOutputScan() {
		for (String source : List.of("void main() {}", "void main() { discard; }", "// gl_FragColor out\nvoid main() {}")) {
			if (ProgramDirectives.hasFragmentOutput(source)) {
				throw new AssertionError("placeholder added a phantom colour target");
			}
		}
		for (String source : List.of("void main() { gl_FragColor = vec4(1); }", "void main() { gl_FragData[0] = vec4(1); }",
				"layout(location=0) out vec4 color; void main() { color=vec4(1); }")) {
			if (!ProgramDirectives.hasFragmentOutput(source)) {
				throw new AssertionError("real fragment output lost its default target");
			}
		}
		System.out.println("fragment outputs: empty placeholders use no targets; legacy and modern colour outputs kept");
	}

	/** World placeholders reserve no colour targets; full-screen passes retain a drawable fallback. */
	private static void defaultDrawBuffers() {
		record Fragment(String source, int[] world, int[] shadow, int[] fullscreen) { }
		int[] none = {};
		int[] one = {0};
		int[] two = {0, 1};
		String disabled = JcppProcessor.glslPreprocessSource("#version 330\n#define COLOUR_ENABLED 0\nvoid main() {\n"
				+ "#if COLOUR_ENABLED\ngl_FragData[0] = vec4(1);\n#endif\n}\n", List.of());
		String second = "void main() { gl_FragData[0] = vec4(1); gl_FragData[1] = vec4(0.5); }";
		List<Fragment> fragments = List.of(
				new Fragment("void main() {}", none, none, one),
				new Fragment("void main() { discard; }", none, none, one),
				new Fragment("// gl_FragData[1] out\nvoid main() {}", none, none, one),
				new Fragment("void main() { gl_FragDepth = 0.5; }", none, none, one),
				new Fragment(disabled, none, none, one),
				new Fragment("void main() { gl_FragColor = vec4(1); }", one, one, one),
				new Fragment("void main() { gl_FragData[0] = vec4(1); }", one, one, one),
				new Fragment("layout(location=0) out vec4 color; void main() { color=vec4(1); }", one, one, one),
				new Fragment("out vec4 outColor0; void main() { outColor0=vec4(1); }", one, one, one),
				new Fragment("/* gl_FragData[1] = vec4(1); */\nvoid main() { gl_FragColor=vec4(1); }", one, one, one),
				new Fragment("/* layout(location=1) out vec4 ignored; */\nvoid main() { gl_FragColor=vec4(1); }", one, one, one),
				new Fragment("layout(location=1) in vec2 uv; layout(location=0) out vec4 color;"
						+ "void main() { color=vec4(uv, 0, 1); }", one, one, one),
				new Fragment("void helper(out vec4 outColor1) { outColor1=vec4(1); }"
						+ "void main() { gl_FragColor=vec4(1); }", one, one, one),
				new Fragment("layout(location=0) out vec4 outColor1; void main() { outColor1=vec4(1); }", one, one, one),
				new Fragment("layout(location=0,index=1) out vec4 color; void main() { color=vec4(1); }", one, one, one),
				new Fragment(second, one, two, one),
				new Fragment("#version 330\nlayout(location=0) out vec4 color; layout(location=1) out vec4 material;"
						+ "void main() { color=vec4(1); material=vec4(0.5); }", one, two, one),
				new Fragment("layout(index=0,location=1) out vec4 material; void main() { material=vec4(0.5); }", one, two, one),
				new Fragment("out vec4 outColor0, outColor1; void main() { outColor0=vec4(1); outColor1=vec4(0.5); }", one, two, one),
				new Fragment("layout(location=0) out vec4 colors[2]; void main() { colors[0]=vec4(1); colors[1]=vec4(0.5); }", one, two, one),
				new Fragment("out vec4 color; out vec4 material; void main() { color=vec4(1); material=vec4(0.5); }", one, two, one),
				new Fragment("layout(location=0) out vec4 color; out vec4 material; void main() { color=vec4(1); material=vec4(0.5); }", one, two, one),
				new Fragment("out vec4 outColor2; void main() { outColor2=vec4(1); }", one, one, one),
				new Fragment("/* DRAWBUFFERS:0 */\n" + second, one, one, one),
				new Fragment("/* DRAWBUFFERS:10 */\n" + second, new int[]{1, 0}, new int[]{1, 0}, new int[]{1, 0}),
				new Fragment("/* RENDERTARGETS:7,3 */\n" + second, new int[]{7, 3}, new int[]{7, 3}, new int[]{7, 3}),
				new Fragment("/* DRAWBUFFERS:1 */\nvoid main() { discard; }", new int[]{1}, new int[]{1}, new int[]{1}));
		List<String> worlds = List.of("gbuffers_basic", "gbuffers_terrain", "gbuffers_water", "dh_terrain", "dh_water", "dh_generic");
		List<String> shadows = List.of("shadow", "shadow_solid", "shadow_cutout", "shadow_water", "shadow_entities", "dh_shadow");
		List<String> fullscreen = List.of("begin", "prepare", "deferred", "composite", "composite3", "shadowcomp", "shadowcomp2", "final");
		int checked = 0;
		for (List<String> group : List.of(worlds, shadows, fullscreen)) {
			for (String name : group) {
				for (Fragment fragment : fragments) {
					int[] expected = shadows.contains(name) ? fragment.shadow() : worlds.contains(name) ? fragment.world() : fragment.fullscreen();
					var source = new ProgramSource(name, "void main() { gl_Position=vec4(0); }", null, null, null,
							fragment.source(), null, null, null);
					int[] actual = source.getDirectives().getDrawBuffers();
					if (!Arrays.equals(expected, actual)) {
						throw new AssertionError(name + " colour targets: expected " + Arrays.toString(expected) + ", got " + Arrays.toString(actual)
								+ " for " + fragment.source());
					}
					checked++;
				}
			}
		}
		System.out.println("draw-buffer defaults: " + checked + " cases pass for disabled full-screen passes, world placeholders, shadow outputs and explicit overrides");
	}

	private static void customExpressions() {
		try {
			var resolver = new ExpressionResolver(
					ExpressionFunctions.functions, name -> null, true);
			for (String text : List.of("vec2(0.01, 0.02) * 0.51", "0.51 * vec2(0.01, 0.02)",
					"(vec2(0.01, 0.02) * 0.5) * 1.02")) {
				var parsed = Parser.parse(text, ExpressionOperators.options);
				var expression = resolver.resolveExpression(VectorType.VEC2, parsed);
				var value = new FunctionReturn();
				expression.evaluateTo(null, value);
				var vector = (Vector2f) value.objectReturn;
				if (Math.abs(vector.x - 0.0051f) > 1e-7f || Math.abs(vector.y - 0.0102f) > 1e-7f) {
					throw new AssertionError("incorrect scalar/vector multiplication: " + text + " -> " + vector);
				}
			}
		} catch (Exception e) {
			throw new AssertionError("scalar/vector expression could not be resolved", e);
		}
		System.out.println("custom expressions: vector/scalar multiplication works in both orders and nested calls");
	}
}
