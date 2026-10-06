package dev.spacebod.aetherium.microbench;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.spacebod.aetherium.shaders.engine.CompositePass;
import dev.spacebod.aetherium.shaders.engine.ComputePass;
import dev.spacebod.aetherium.shaders.engine.ShadowSampling;
import dev.spacebod.aetherium.shaders.engine.StorageBindings;
import dev.spacebod.aetherium.shaders.engine.UniformBlock;
import dev.spacebod.aetherium.shaders.engine.WorldInterface;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTests;
import dev.spacebod.aetherium.shaders.pipeline.transform.PatchShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.VanillaInterface;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.lwjgl.util.shaderc.Shaderc;

/**
 * Edge cases for the Vulkan lowering: each fixture is a small program run through the engine's lowering,
 * compiled with vanilla's compiler options and checked on its SPIR-V interface; every fixture must compile and pass
 * every check. {@code gradlew :common:microbench -Ponly=lowering}.
 */
final class LoweringFixtures {
	private LoweringFixtures() {
	}

	/** A fixture: its stages (geometry and fragment may be null), how it is built, and checks over the reflected interface. */
	private record Fixture(String name, Kind kind, String vertex, String geometry, String fragment, List<Check> checks) {
	}

	/** {@code *_METAL}: lowered for a MoltenVK device (Metal: no geometry stages), as on macOS. */
	private enum Kind { FULLSCREEN, FULLSCREEN_METAL, WORLD, WORLD_METAL, WORLD_GLINT, WORLD_GRAYSCALE, WORLD_CLOUDS, COMPUTE }

	private record Check(String what, Predicate<Reflected> test) {
	}

	/**
	 * Reflection lines per stage, plus the lowered source (for checks on the text), the world resources handed to vanilla
	 * and each stage's SPIR-V words.
	 */
	private record Reflected(Map<PatchShaderType, List<String>> lines, Map<PatchShaderType, String> sources, UniformBlock.Layout layout,
			Map<String, com.mojang.renderpearl.api.pipeline.UniformType> resources, Map<PatchShaderType, int[]> words) {
		List<String> stage(PatchShaderType type) {
			return lines.getOrDefault(type, List.of());
		}

		/** The location of a stage input/output called {@code name}, or -1. */
		int location(PatchShaderType type, String direction, String name) {
			for (String l : stage(type)) {
				String[] p = l.split(" ");
				if (p[0].equals(direction) && p.length > 2 && p[2].equals(name)) {
					return Integer.parseInt(p[1].substring("location=".length()));
				}
			}
			return -1;
		}

		boolean has(PatchShaderType type, String prefix) {
			return stage(type).stream().anyMatch(l -> l.startsWith(prefix));
		}
	}

	private record Result(boolean ok, String problem, Map<PatchShaderType, String> sources) {
	}

	private static final PatchShaderType V = PatchShaderType.VERTEX;
	private static final PatchShaderType G = PatchShaderType.GEOMETRY;
	private static final PatchShaderType F = PatchShaderType.FRAGMENT;
	private static final String NL = "\n";

	static void run() {
		ShadowSampling.filterAllForCompileCheck();
		// Storage writes allowed in every stage (the device features a pack's programs need).
		dev.spacebod.aetherium.client.gpu.StorageFeatures.assumeForCompileCheck();
		// A desktop device unless a fixture says otherwise.
		dev.spacebod.aetherium.client.gpu.DeviceTraits.assumeForCompileCheck(false);
		dev.spacebod.aetherium.client.gpu.GeometryStages.assumeForCompileCheck(true);
		// One custom image with a sampler name (bindings 0, 1), one without (binding 2), an R32F image with a sampler
		// (bindings 4, 5), storage buffers, and targets bound as images in graphics programs.
		Map<String, StorageBindings.Image> images = Map.of(
				"colorimg", new StorageBindings.Image("colorimg", "colorimgSampler", 0, GpuFormat.RGBA16_FLOAT),
				"voxels", new StorageBindings.Image("voxels", null, 1, GpuFormat.R32_UINT),
				"lightVolume", new StorageBindings.Image("lightVolume", "lightSampler", 2, GpuFormat.R32_FLOAT));
		StorageBindings.configure(images, true, Map.of(), true);
		// The views the view fixtures' declarations ask for, from 320 on in declaration order (rg16f over RGBA16F: refused).
		StorageBindings.configureViews(dev.spacebod.aetherium.shaders.engine.ImageViews.scan(images, List.of(VIEW_DECLARATIONS,
				"layout(rg16f) uniform image2D colorimg;")));
		int passed = 0, failed = 0;
		for (Fixture f : fixtures()) {
			Result tree = run(f);
			if (tree.ok()) {
				passed++;
				System.out.printf(Locale.ROOT, "  ok    %s%n", f.name());
			} else {
				failed++;
				System.out.printf(Locale.ROOT, "  FAIL  %-52s %s%n", f.name(), tree.problem());
				tree.sources().forEach((k, v) -> System.out.println("    ----- " + k + NL + v));
			}
		}
		StorageBindings.reset();
		System.out.printf(Locale.ROOT, "%nlowering fixtures: %d passed, %d failed%n", passed, failed);
		directiveScans();
	}

	/** The text scans that stay text (they run before parsing): uniform declarations and comment directives. */
	private static void directiveScans() {
		record Case(String source, String name, boolean expected) {
		}
		List<Case> uniforms = List.of(
				new Case("uniform sampler2D gdepth;", "gdepth", true),
				new Case("uniform sampler2D a, gdepth;", "gdepth", true),
				new Case(NL.repeat(5000) + "   uniform\tvec4 gdepth ;", "gdepth", true),
				new Case("float x = gdepth;", "gdepth", false),
				new Case("uniform float gdepthFoo;", "gdepth", false),
				new Case("uniform float x; vec4 gdepth;", "gdepth", false),
				new Case("void f() { uniformish(gdepth); }", "gdepth", false));
		int bad = 0;
		for (Case c : uniforms) {
			if (dev.spacebod.aetherium.shaders.shaderpack.parsing.DispatchingDirectiveHolder.declaresUniform(c.source(), c.name()) != c.expected()) {
				bad++;
				System.out.println("  FAIL  uniform scan: " + c.source().strip() + " -> expected " + c.expected());
			}
		}
		record Comment(String source, String expected) {
		}
		List<Comment> comments = List.of(
				new Comment("/* DRAWBUFFERS:12 */", "12"),
				new Comment("/*DRAWBUFFERS:321*/ text", "321"),
				new Comment("/* DRAWBUFFERS:2 */ x /* DRAWBUFFERS:3 */", "3"),
				new Comment("/* DRAWBUFFERS:04 */ // DRAWBUFFERS:7", "04"),
				new Comment("/* DRAWBUFFERS:56 */" + NL.repeat(20000) + "float MYDRAWBUFFERS:", "56"),
				new Comment("/* DRAWBUFFERS: no end", null));
		for (Comment c : comments) {
			String found = dev.spacebod.aetherium.shaders.shaderpack.parsing.CommentDirectiveParser.findValue(c.source(), "DRAWBUFFERS").orElse(null);
			if (!java.util.Objects.equals(found, c.expected())) {
				bad++;
				System.out.println("  FAIL  comment directive: " + c.source().strip() + " -> " + found + ", expected " + c.expected());
			}
		}
		System.out.printf(Locale.ROOT, "directive scans: %d of %d cases right%n", uniforms.size() + comments.size() - bad, uniforms.size() + comments.size());
	}

	private static Result run(Fixture f) {
		Map<PatchShaderType, String> stages;
		UniformBlock.Layout layout;
		Map<String, com.mojang.renderpearl.api.pipeline.UniformType> resources = Map.of();
		boolean metal = f.kind() == Kind.FULLSCREEN_METAL || f.kind() == Kind.WORLD_METAL;
		dev.spacebod.aetherium.client.gpu.DeviceTraits.assumeForCompileCheck(metal);
		dev.spacebod.aetherium.client.gpu.GeometryStages.assumeForCompileCheck(!metal);
		try {
			switch (f.kind()) {
				case FULLSCREEN, FULLSCREEN_METAL -> {
					CompositePass.Lowered l = CompositePass.lower(f.name(), f.vertex(), f.fragment(), TextureStage.COMPOSITE_AND_FINAL);
					stages = new EnumMap<>(PatchShaderType.class);
					stages.put(V, l.vertex());
					stages.put(F, l.fragment());
					layout = l.layout();
				}
				case COMPUTE -> {
					ComputePass.Lowered l = ComputePass.lower(f.name(), f.vertex(), TextureStage.COMPOSITE_AND_FINAL);
					stages = Map.of(PatchShaderType.COMPUTE, l.glsl());
					layout = l.layout();
				}
				default -> {
					boolean clouds = f.kind() == Kind.WORLD_CLOUDS;
					List<VertexFormat> formats = clouds ? List.of() : List.of(DefaultVertexFormat.ENTITY);
					WorldInterface.Prepared p = WorldInterface.prepare(f.name(), f.vertex(), f.geometry(), f.fragment(), formats,
							VanillaInterface.TransformSource.DYNAMIC, AlphaTests.ONE_TENTH_ALPHA, false, false, clouds, false, false,
							new int[]{0, 1}, new int[]{0, 1}, false, f.kind() == Kind.WORLD_GLINT
									? new dev.spacebod.aetherium.shaders.pipeline.transform.lowering.LoweringParameters.Glint(true, 0)
									: dev.spacebod.aetherium.shaders.pipeline.transform.lowering.LoweringParameters.Glint.NONE,
							f.kind() == Kind.WORLD_GRAYSCALE);
					stages = p.stages();
					layout = p.layout();
					resources = p.resources();
				}
			}
		} catch (RuntimeException e) {
			return new Result(false, "transform: " + e.getMessage() + (e.getCause() == null ? "" : " / " + e.getCause().getMessage()), Map.of());
		} finally {
			dev.spacebod.aetherium.client.gpu.DeviceTraits.assumeForCompileCheck(false);
			dev.spacebod.aetherium.client.gpu.GeometryStages.assumeForCompileCheck(true);
		}
		Map<PatchShaderType, List<String>> lines = new EnumMap<>(PatchShaderType.class);
		Map<PatchShaderType, int[]> words = new EnumMap<>(PatchShaderType.class);
		for (Map.Entry<PatchShaderType, String> e : stages.entrySet()) {
			ByteBuffer spirv = compile(e.getValue(), e.getKey());
			if (spirv == null) {
				return new Result(false, e.getKey() + ": " + firstError(lastError), stages);
			}
			int[] w = new int[spirv.remaining() / 4];
			spirv.duplicate().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer().get(w);
			words.put(e.getKey(), w);
			lines.put(e.getKey(), SpirvInterface.reflect(spirv, e.getKey() == PatchShaderType.COMPUTE));
		}
		Reflected r = new Reflected(lines, stages, layout, resources, words);
		for (Check c : f.checks()) {
			boolean ok;
			try {
				ok = c.test().test(r);
			} catch (RuntimeException e) {
				ok = false;
			}
			if (!ok) {
				return new Result(false, "check failed: " + c.what() + " " + lines, stages);
			}
		}
		return new Result(true, "", stages);
	}

	private static Check check(String what, Predicate<Reflected> test) {
		return new Check(what, test);
	}

	/** Every stage translates to Metal Shading Language with SPIRV-Cross, as MoltenVK does when a pipeline is created. */
	private static Check metalTranslates() {
		return check("translates to Metal", r -> r.words().values().stream().allMatch(w -> {
			MetalTranslation.Result metal = MetalTranslation.translate(spirv(w));
			if (metal.error() != null) {
				System.out.println("    Metal: " + metal.error());
			}
			return metal.error() == null;
		}));
	}

	/** GLSL source from lines. */
	private static String glsl(String... lines) {
		return String.join(NL, lines) + NL;
	}

	/** A full-screen vertex stage writing the quad, with extra declarations and statements in main. */
	private static String vertex(String declarations, String body) {
		return glsl("#version 330 core", declarations, "in vec3 vaPosition;", "void main() {", body, "gl_Position = vec4(vaPosition * 2.0 - 1.0, 1.0);", "}");
	}

	private static Fixture fullscreen(String name, String vertex, String fragment, Check... checks) {
		return new Fixture(name, Kind.FULLSCREEN, vertex, null, fragment, List.of(checks));
	}

	private static Fixture world(String name, String vertex, String geometry, String fragment, Check... checks) {
		return new Fixture(name, Kind.WORLD, vertex, geometry, fragment, List.of(checks));
	}

	private static final String WORLD_VERTEX = glsl("#version 330 core", "in vec3 vaPosition;", "in vec2 vaUV0;", "in vec4 vaColor;", "out vec2 uv;",
			"out vec4 tint;", "uniform mat4 modelViewMatrix;", "uniform mat4 projectionMatrix;",
			"void main() { uv = vaUV0; tint = vaColor; gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0); if (vaPosition.x > 1e6) return; }");

	private static List<Fixture> fixtures() {
		List<Fixture> out = new ArrayList<>();
		out.add(fullscreen("multi-declarator varyings",
				vertex("flat out vec3 a, b, c;" + NL + "out vec2 uv, lm[2], st;", "a = vec3(1); b = a; c = b; uv = vec2(0); lm[0] = uv; lm[1] = uv; st = uv;"),
				glsl("#version 330 core", "flat in vec3 a, b, c;", "in vec2 uv, lm[2], st;", "layout(location = 0) out vec4 color;",
						"void main() { color = vec4(a + b + c, uv.x + lm[1].y + st.x); }"),
				check("one location per name, matched across stages, arrays take their length", r -> {
					Set<Integer> seen = new HashSet<>();
					for (String n : new String[]{"a", "b", "c", "uv", "lm", "st"}) {
						int written = r.location(V, "out", n);
						if (written < 0 || written != r.location(F, "in", n) || !seen.add(written)) {
							return false;
						}
					}
					int lm = r.location(V, "out", "lm");
					return !seen.contains(lm + 1);
				})));
		out.add(fullscreen("explicit and implicit locations mixed",
				vertex("layout(location = 0) out vec4 first;" + NL + "out vec4 second;" + NL + "layout(location = 2) out vec4 third;", "first = vec4(0); second = first; third = first;"),
				glsl("#version 330 core", "in vec4 first;", "layout(location = 1) in vec4 second;", "in vec4 third;", "layout(location = 0) out vec4 color;",
						"void main() { color = first + second + third; }"),
				check("explicit locations kept on both sides, no overlap", r -> r.location(V, "out", "first") == 0 && r.location(F, "in", "first") == 0
						&& r.location(V, "out", "third") == 2 && r.location(F, "in", "second") == 1 && r.location(V, "out", "second") == 1)));
		out.add(fullscreen("interface block varyings",
				vertex("out VertexData { vec2 uv; flat int id; vec4 tint[2]; } v;", "v.uv = vec2(0); v.id = 1; v.tint[0] = vec4(1); v.tint[1] = vec4(0);"),
				glsl("#version 330 core", "in VertexData { vec2 uv; flat int id; vec4 tint[2]; } v;", "layout(location = 0) out vec4 color;",
						"void main() { color = v.tint[1] + vec4(v.uv, float(v.id), 0); }"),
				check("block compiled with a location", r -> r.has(V, "out location=0 v") || r.has(V, "out location=0 VertexData"))));
		out.add(fullscreen("fragment input with no vertex output",
				vertex("out vec2 uv;", "uv = vec2(0);"),
				glsl("#version 330 core", "in vec2 uv;", "flat in int neverWritten;", "noperspective in vec3 alsoMissing;", "layout(location = 0) out vec4 color;",
						"void main() { color = vec4(uv, float(neverWritten), alsoMissing.x); }"),
				check("vertex stage gets the outputs", r -> r.location(V, "out", "neverWritten") == r.location(F, "in", "neverWritten")
						&& r.location(V, "out", "alsoMissing") >= 0)));
		out.add(fullscreen("uniforms: declarators, initialisers, two stages",
				vertex("uniform float a, b = 1.0;" + NL + "uniform highp vec3 sharedValue;" + NL + "uniform mat4 m[2];" + NL + "out vec4 x;",
						"x = m[1] * vec4(sharedValue * a * b, 1.0);"),
				glsl("#version 330 core", "in vec4 x;", "uniform vec3 sharedValue;", "uniform int frameCounter;", "layout(location = 0) out vec4 color;",
						"void main() { color = x + vec4(sharedValue, float(frameCounter)); }"),
				check("one block with every member", r -> r.layout().byName().keySet().containsAll(List.of("a", "b", "sharedValue", "m", "frameCounter"))),
				check("b's initialiser kept", r -> r.layout().byName().get("b").initializer().startsWith("1.0")),
				check("identical block in both stages", r -> blockLine(r, V).equals(blockLine(r, F)) && !blockLine(r, V).isEmpty())));
		out.add(fullscreen("global initialiser reading uniforms",
				vertex("out vec2 uv;", "uv = vec2(0);"),
				glsl("#version 330 core", "uniform float viewWidth;", "uniform float viewHeight;", "in vec2 uv;", "vec2 texel = 1.0 / vec2(viewWidth, viewHeight);",
						"layout(location = 0) out vec4 color;", "void main() { color = vec4(texel, uv); }"),
				check("compiles with the block", r -> r.layout().byName().containsKey("viewWidth"))));
		out.add(fullscreen("shadow sampler through a function parameter",
				vertex("out vec2 uv;", "uv = vec2(0);"),
				glsl("#version 330 core", "uniform sampler2DShadow shadowtex0;", "in vec2 uv;", "layout(location = 0) out vec4 color;",
						"float pcf(sampler2DShadow s, vec3 p) { return texture(s, p) + textureOffset(s, p, ivec2(1, 0)) + textureLod(s, p, 0.0); }",
						"void main() { vec4 p4 = vec4(uv, 0.5, 1.0); color = vec4(pcf(shadowtex0, vec3(uv, 0.5)), textureProj(shadowtex0, p4), 1.0, 1.0); }"),
				check("hardware comparison: kept a depth image under its comparison binding", r -> r.has(F, "sampler set=0 aeth_cmp_shadowtex0")
						&& r.stage(F).stream().anyMatch(l -> l.startsWith("sampler set=0 aeth_cmp_shadowtex0") && l.endsWith(" depth")))));
		out.add(fullscreen("shadow samplers through a parameter, one not a depth map (all emulated)",
				vertex("out vec2 uv;", "uv = vec2(0);"),
				glsl("#version 330 core", "uniform sampler2DShadow shadowtex0;", "uniform sampler2DShadow depthtex1;", "in vec2 uv;", "layout(location = 0) out vec4 color;",
						"float pcf(sampler2DShadow s, vec3 p) { return texture(s, p) + textureOffset(s, p, ivec2(1, 0)); }",
						"void main() { color = vec4(pcf(shadowtex0, vec3(uv, 0.5)), pcf(depthtex1, vec3(uv, 0.5)), textureProj(shadowtex0, vec4(uv, 0.5, 1.0)), 1.0); }"),
				check("both plain samplers, compared in the shader", r -> r.has(F, "sampler set=0 shadowtex0 ") && r.has(F, "sampler set=0 depthtex1 ")
						&& r.stage(F).stream().noneMatch(l -> l.endsWith(" depth")) && r.sources().get(F).contains("aeth_shadowFiltered("))));
		out.add(fullscreen("hardware comparison: projective, explicit level, gather, watershadow",
				vertex("out vec2 uv;", "uv = vec2(0);"),
				glsl("#version 400 core", "uniform sampler2DShadow shadow;", "uniform sampler2DShadow watershadow;", "in vec2 uv;", "layout(location = 0) out vec4 color;",
						"void main() { vec4 g = textureGather(shadow, uv, 0.5) + textureGatherOffset(watershadow, uv, 0.5, ivec2(1, 0));",
						"color = g + vec4(textureProj(shadow, vec4(uv, 0.5, 1.0)), textureLod(watershadow, vec3(uv, 0.5), 0.0), textureGrad(shadow, vec3(uv, 0.5), vec2(0), vec2(0)), 1.0); }"),
				check("both depth images under comparison bindings, lookups native", r -> r.has(F, "sampler set=0 aeth_cmp_shadow ") && r.has(F, "sampler set=0 aeth_cmp_watershadow ")
						&& r.stage(F).stream().filter(l -> l.startsWith("sampler")).allMatch(l -> l.endsWith(" depth")) && !r.sources().get(F).contains("aeth_shadow"))));
		out.add(new Fixture("compute: shadow comparisons stay in the shader", Kind.COMPUTE,
				glsl("#version 430", "layout(local_size_x = 8, local_size_y = 8) in;", "uniform sampler2DShadow shadowtex0;",
						"void main() { float lit = texture(shadowtex0, vec3(0.5)); }"),
				null, null,
				List.of(check("plain sampler at binding 1", r -> r.has(PatchShaderType.COMPUTE, "sampler set=0 binding=1 shadowtex0 ")
						&& r.stage(PatchShaderType.COMPUTE).stream().noneMatch(l -> l.endsWith(" depth"))))));
		out.add(fullscreen("uninitialised local read on one path: zeroed, written ones left",
				vertex("out vec2 uv;", "uv = vec2(0);"),
				glsl("#version 330 core", "in vec2 uv;", "layout(location = 0) out vec4 color;", "vec4 lastColor;",
						"vec4 tint(vec2 p) { vec4 t; if (p.y > 0.5) { t = vec4(p, 0.0, 1.0); } return t; }",
						"void main() { vec4 acc; vec4 written = vec4(1.0); float always; always = uv.x;",
						"if (uv.x > 0.5) { acc = vec4(1.0); } lastColor = written; color = acc + written * always + tint(uv) + lastColor; }"),
				check("acc and t zeroed (2 locals), written/always/lastColor left alone", r -> {
					dev.spacebod.aetherium.client.gpu.LocalZeroes.Result z = dev.spacebod.aetherium.client.gpu.LocalZeroes.apply(r.words().get(F));
					return z.variables() == 2 && z.undefs() == 0 && !SpirvInterface.reflect(spirv(z.words()), false).isEmpty();
				}),
				check("a stage that writes before reading is unchanged", r -> !dev.spacebod.aetherium.client.gpu.LocalZeroes.apply(r.words().get(V)).changed())));
		out.add(fullscreen("compatibility-era shadow2D",
				glsl("#version 120", "varying vec2 texcoord;", "void main() { gl_Position = ftransform(); texcoord = gl_MultiTexCoord0.st; }"),
				glsl("#version 120", "uniform sampler2DShadow shadow;", "varying vec2 texcoord;",
						"void main() { gl_FragData[0] = vec4(shadow2D(shadow, vec3(texcoord, 0.5)).r, shadow2DLod(shadow, vec3(texcoord, 0.5), 0.0).r, 0.0, 1.0); }"),
				check("hardware comparison under its comparison binding", r -> r.has(F, "sampler set=0 aeth_cmp_shadow "))));
		out.add(fullscreen("legacy lookups: cube, projective level, offsets, projective shadow",
				glsl("#version 120", "varying vec2 texcoord;", "void main() { gl_Position = ftransform(); texcoord = gl_MultiTexCoord0.st; }"),
				glsl("#version 120", "uniform sampler2D gcolor;", "uniform samplerCube sky;", "uniform sampler2DShadow shadow;", "uniform sampler2DShadow depthtex2;",
						"varying vec2 texcoord;",
						"void main() { vec4 p = vec4(texcoord, 0.5, 1.0); gl_FragData[0] = textureCube(sky, vec3(texcoord, 1.0)) + textureCubeLod(sky, vec3(1.0), 0.0)",
						"+ texture2DProjLod(gcolor, p, 0.0) + texture2DOffset(gcolor, texcoord, ivec2(1)) + texture2DLodOffset(gcolor, texcoord, 0.0, ivec2(1))",
						"+ shadow2DProj(shadow, p) + shadow2DProjLod(shadow, p, 0.0) + shadow2DProj(depthtex2, p); }"),
				check("compiles, no legacy name left", r -> !r.sources().get(F).matches("(?s).*(textureCube|texture2D|shadow2D).*"))));
		out.add(fullscreen("pack #extension after declarations, require relaxed",
				vertex("out vec2 uv;", "uv = vec2(0);"),
				glsl("#version 330 core", "uniform sampler2D colortex0;", "in vec2 uv;", "#extension GL_ARB_gpu_shader5 : require",
						"#extension GL_AMD_not_a_real_extension : require", "layout(location = 0) out vec4 color;",
						"void main() { color = texture(colortex0, uv); }"),
				check("hoisted above the declarations as enable", r -> {
					String f = r.sources().get(F);
					return !f.contains("require") && f.indexOf("GL_ARB_gpu_shader5") < f.indexOf("colortex0") && f.indexOf("GL_AMD_not_a_real_extension") < f.indexOf("colortex0");
				})));
		out.add(fullscreen("const with non-constant initialisers",
				vertex("out vec2 uv;", "uv = vec2(0);"),
				glsl("#version 330 core", "uniform float viewWidth;", "in vec2 uv;", "const float aspect = viewWidth * 0.5;", "const float twice = aspect * 2.0;",
						"const float PI = 3.14159;", "const int N = 4;", "const vec3 up = normalize(vec3(0.0, 1.0, 0.0));", "float arr[N];",
						"float f(float x) { return x * PI; }", "const float viaCall = f(1.0);", "layout(location = 0) out vec4 color;",
						"void main() { const float local = uv.x * 2.0; const vec2 both = vec2(local, PI); arr[0] = 1.0;",
						"color = vec4(aspect + twice + viaCall + both.x + up.y, arr[0], PI, float(N)); }"),
				check("non-constant ones demoted, constant ones kept", r -> {
					String f = r.sources().get(F);
					return f.contains("const float PI") && f.contains("const int N") && f.contains("const vec3 up") && !f.contains("const float aspect")
							&& !f.contains("const float twice") && !f.contains("const float viaCall") && !f.contains("const float local") && !f.contains("const vec2 both");
				})));
		out.add(fullscreen("reach pruning: samplers and uniforms only dead code reads",
				vertex("out vec2 uv;", "uv = vec2(0);"),
				glsl("#version 330 core", "uniform sampler2D colortex0;", "uniform sampler2D colortex5;", "uniform sampler2D depthtex1;", "uniform sampler2DShadow shadowtex1;",
						"uniform float deadOnly;", "uniform float viaGlobal;", "uniform float frameTimeCounter;", "in vec2 uv;", "float scaled = viaGlobal * 2.0;",
						"layout(location = 0) out vec4 color;",
						"vec4 leaf() { return texture(colortex5, uv) * deadOnly + vec4(texture(shadowtex1, vec3(uv, 0.5))); }", "vec4 unused() { return leaf(); }",
						"vec4 live() { return texture(depthtex1, uv) * frameTimeCounter; }",
						"void main() { color = texture(colortex0, uv) + live() + scaled; }"),
				check("dead-only samplers and uniform gone, live ones kept", r -> r.has(F, "sampler set=0 colortex0 ") && r.has(F, "sampler set=0 depthtex1 ")
						&& !r.has(F, "sampler set=0 colortex5") && r.stage(F).stream().noneMatch(l -> l.contains("shadowtex1"))
						&& !r.layout().byName().containsKey("deadOnly") && r.layout().byName().containsKey("viaGlobal")
						&& r.layout().byName().containsKey("frameTimeCounter"))));
		out.add(fullscreen("compatibility-era GLSL 120",
				glsl("#version 120", "varying vec2 texcoord;", "void main() { gl_Position = ftransform(); texcoord = gl_MultiTexCoord0.st; }"),
				glsl("#version 120", "uniform sampler2D gcolor;", "varying vec2 texcoord;", "void main() { gl_FragData[0] = texture2D(gcolor, texcoord); }"),
				check("texcoord matched", r -> r.location(V, "out", "texcoord") == r.location(F, "in", "texcoord"))));
		out.add(fullscreen("compatibility profile version",
				glsl("#version 150 compatibility", "out vec2 uv;", "void main() { uv = gl_MultiTexCoord0.st; gl_Position = ftransform(); }"),
				glsl("#version 150 compatibility", "uniform sampler2D colortex0;", "in vec2 uv;", "void main() { gl_FragData[0] = texture(colortex0, uv); }"),
				check("uv matched", r -> r.location(V, "out", "uv") == r.location(F, "in", "uv"))));
		out.add(fullscreen("version 450 with explicit bindings",
				glsl("#version 450 core", "layout(location = 0) in vec3 vaPosition;", "layout(location = 0) out vec2 uv;",
						"void main() { uv = vaPosition.xy; gl_Position = vec4(vaPosition, 1.0); }"),
				glsl("#version 450", "layout(binding = 3) uniform sampler2D colortex3;", "layout(location = 0) in vec2 uv;", "layout(location = 0) out vec4 color;",
						"void main() { color = texture(colortex3, uv); }"),
				check("sampler kept", r -> r.has(F, "sampler set=0 colortex3"))));
		out.add(fullscreen("custom images and storage buffers (set 1)",
				vertex("out vec2 uv;", "uv = vec2(0);"),
				glsl("#version 330 core", "uniform image2D colorimg;", "uniform sampler2D colorimgSampler;", "writeonly uniform uimage3D voxels;",
						"layout(std430, binding = 2) buffer Data { uint count; uint values[]; } data;", "in vec2 uv;", "layout(location = 0) out vec4 color;",
						"void main() { imageStore(colorimg, ivec2(0), vec4(1)); imageStore(voxels, ivec3(0), uvec4(data.values[0])); color = texture(colorimgSampler, uv); }"),
				check("images, image sampler and buffer in set 1", r -> r.has(F, "image set=1 binding=0 colorimg") && r.has(F, "sampler set=1 binding=1 colorimgSampler")
						&& r.has(F, "image set=1 binding=2 voxels") && r.has(F, "ssbo set=1 binding=66 "))));
		out.add(world("local named like the albedo sampler (world)", WORLD_VERTEX, null,
				glsl("#version 330 core", "uniform sampler2D gtexture;", "in vec2 uv;", "in vec4 tint;", "layout(location = 0) out vec4 color;",
						"vec4 sampleIt(vec2 tex) { return texture(gtexture, tex); }", "void main() { vec2 tex = uv; color = sampleIt(tex) * tint; }"),
				check("albedo bound as Sampler0, local tex untouched", r -> r.has(F, "sampler set=0 Sampler0") && r.sources().get(F).contains("vec2 tex = "))));
		out.add(world("declared tex sampler, parameter tex elsewhere (world)", WORLD_VERTEX, null,
				glsl("#version 330 core", "uniform sampler2D tex;", "in vec2 uv;", "in vec4 tint;", "layout(location = 0) out vec4 color;",
						"vec2 shift(vec2 tex) { return tex + 0.5; }", "void main() { color = texture(tex, shift(uv)) * tint; }"),
				check("sampler renamed, parameter kept", r -> r.has(F, "sampler set=0 Sampler0") && r.sources().get(F).contains("return tex + "))));
		out.add(world("fragment depth and gl_FragCoord in a global (world)", WORLD_VERTEX, null,
				glsl("#version 330 core", "in vec2 uv;", "in vec4 tint;", "layout(location = 0) out vec4 color;", "ivec2 texel = ivec2(gl_FragCoord.xy);",
						"float depth0 = gl_FragCoord.z;",
						"void main() { color = vec4(texel, depth0, 1.0) * tint; if (texel.x > 4) { gl_FragDepth = 0.5; return; } gl_FragDepth = gl_FragCoord.z; }"),
				check("compiles; depth flipped in the global", r -> r.sources().get(F).contains(" - gl_FragCoord.z"))));
		out.add(world("geometry stage: several EmitVertex, early return (world)",
				glsl("#version 330 core", "in vec3 vaPosition;", "in vec2 vaUV0;", "out vec2 vuv;", "uniform mat4 modelViewMatrix;", "uniform mat4 projectionMatrix;",
						"void main() { vuv = vaUV0; gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0); }"),
				glsl("#version 330 core", "layout(triangles) in;", "layout(triangle_strip, max_vertices = 3) out;", "in vec2 vuv[];", "out vec2 uv;",
						"void main() { if (vuv[0].x > 2.0) return; for (int i = 0; i < 2; i++) { uv = vuv[i]; gl_Position = gl_in[i].gl_Position; EmitVertex(); }",
						"uv = vuv[2]; gl_Position = gl_in[2].gl_Position; EmitVertex(); EndPrimitive(); }"),
				glsl("#version 330 core", "in vec2 uv;", "layout(location = 0) out vec4 color;", "void main() { color = vec4(uv, 0.0, 1.0); }"),
				check("geometry compiled, varyings matched", r -> r.location(G, "out", "uv") == r.location(F, "in", "uv")
						&& r.location(V, "out", "vuv") == r.location(G, "in", "vuv"))));
		out.add(new Fixture("single-pass glint (world)", Kind.WORLD_GLINT,
				glsl("#version 330 core", "in vec3 vaPosition;", "in vec2 vaUV0;", "out vec2 uv;", "uniform mat4 modelViewMatrix;", "uniform mat4 projectionMatrix;",
						"uniform mat4 textureMatrix;", "void main() { uv = (textureMatrix * vec4(vaUV0, 0.0, 1.0)).xy; gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0); }"),
				null,
				glsl("#version 330 core", "uniform sampler2D gtexture;", "in vec2 uv;", "layout(location = 0) out vec4 albedo;", "layout(location = 1) out vec4 data;",
						"void main() { albedo = texture(gtexture, uv); data = vec4(1); }"),
				List.of(check("glint coordinate passed, sampler declared", r -> r.has(F, "sampler set=0 GlintSampler")
						&& r.location(V, "out", "aeth_glintCoord") == r.location(F, "in", "aeth_glintCoord")))));
		out.add(new Fixture("intensity text: albedo texel reads swizzled", Kind.WORLD_GRAYSCALE,
				glsl("#version 330 core", "in vec3 vaPosition;", "in vec2 vaUV0;", "out vec2 uv;", "uniform mat4 modelViewMatrix;", "uniform mat4 projectionMatrix;",
						"void main() { uv = vaUV0; gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0); }"),
				null,
				glsl("#version 330 core", "uniform sampler2D gtexture;", "in vec2 uv;", "layout(location = 0) out vec4 albedo;", "layout(location = 1) out vec4 data;",
						"vec4 own(sampler2D gtexture) { return texture(gtexture, uv); }",
						"void main() { albedo = texture(gtexture, uv) * texelFetch(gtexture, ivec2(0), 0); data = vec4(textureSize(gtexture, 0), 0, 1) + own(gtexture); }"),
				List.of(check("texel reads .rrrr, size query and shadowing parameter untouched", r -> r.sources().get(F).contains("(texture(Sampler0, uv)).rrrr") && r.sources().get(F).contains("(texelFetch(Sampler0, ivec2(0), 0)).rrrr")
						&& !r.sources().get(F).contains("(textureSize(Sampler0, 0)).rrrr") && r.sources().get(F).contains("return texture(gtexture, uv);")))));
		out.add(new Fixture("clouds: injected isamplerBuffer stays a texel buffer (world)", Kind.WORLD_CLOUDS,
				// Version 120 as the packs write it: the parser only knows isamplerBuffer as a type from 140 on.
				glsl("#version 120", "varying vec2 uv;", "varying vec4 tint;",
						"void main() { uv = gl_MultiTexCoord0.st; tint = gl_Color; gl_Position = ftransform(); }"),
				null,
				glsl("#version 120", "varying vec2 uv;", "varying vec4 tint;", "uniform sampler2D gtexture;",
						"void main() { gl_FragData[0] = texture2D(gtexture, uv) * tint; }"),
				List.of(check("CloudFaces handed to vanilla as a texel buffer and bound in the vertex stage",
						r -> r.resources().get("CloudFaces") == com.mojang.renderpearl.api.pipeline.UniformType.TEXEL_BUFFER
								&& r.has(V, "sampler set=0 CloudFaces")))));
		out.add(fullscreen("struct and matrix varyings",
				vertex("struct Fog { vec3 a; vec3 b; vec3 c; };" + NL + "struct Outer { Fog f; float g[2]; };" + NL
								+ "flat out Outer outer;" + NL + "flat out mat2x3 colors;" + NL + "flat out vec3 sh[3];",
						"outer.f.a = vec3(0); outer.f.b = vec3(0); outer.f.c = vec3(0); outer.g[0] = 0.0; outer.g[1] = 0.0; colors = mat2x3(1.0); sh[0] = vec3(0); sh[1] = sh[0]; sh[2] = sh[0];"),
				glsl("#version 330 core", "struct Fog { vec3 a; vec3 b; vec3 c; };", "struct Outer { Fog f; float g[2]; };", "flat in Outer outer;",
						"flat in mat2x3 colors;", "flat in vec3 sh[3];", "layout(location = 0) out vec4 color;",
						"void main() { color = vec4(outer.f.c + colors[1] + sh[2], outer.g[1]); }"),
				check("compiles: no overlapping locations", r -> r.location(F, "in", "sh") >= 0),
				check("struct varyings split (vanilla's pipeline builder takes no struct varyings), members matched",
						r -> r.location(V, "out", "outer") < 0 && r.location(F, "in", "aeth_v_outer_f_c") >= 0
								&& r.location(F, "in", "aeth_v_outer_f_c") == r.location(V, "out", "aeth_v_outer_f_c")
								&& r.location(F, "in", "aeth_v_outer_g") == r.location(V, "out", "aeth_v_outer_g"))));
		out.add(fullscreen("one stage's uniform, the other's global of the same name",
				vertex("uniform vec3 sunPosition;" + NL + "vec3 sunVec = normalize(sunPosition);" + NL + "flat out vec3 dir;", "dir = sunVec;"),
				glsl("#version 330 core", "uniform vec3 sunVec;", "flat in vec3 dir;", "layout(location = 0) out vec4 color;",
						"void main() { color = vec4(sunVec + dir, 1.0); }"),
				check("identical block in both stages (offsets), sunVec a member", r -> r.layout().byName().containsKey("sunVec")
						&& blockLine(r, V).replace("aeth_hidden_", "").equals(blockLine(r, F)))));
		out.add(fullscreen("GLSL 120 pack defining a newer built-in (fma)",
				glsl("#version 120", "varying vec2 texcoord;", "void main() { gl_Position = ftransform(); texcoord = gl_MultiTexCoord0.st; }"),
				glsl("#version 120", "uniform sampler2D gcolor;", "varying vec2 texcoord;", "float fma(float a, float b, float c) { return a * b + c; }",
						"void main() { gl_FragData[0] = texture2D(gcolor, texcoord) * fma(2.0, 0.5, 0.0); }"),
				check("compiles, own fma renamed", r -> r.sources().get(F).contains("aeth_n_fma("))));
		out.add(fullscreen("parameter named sampler (a Vulkan GLSL type)",
				vertex("out vec2 uv;", "uv = vec2(0);"),
				glsl("#version 330 core", "uniform sampler2D colortex0;", "in vec2 uv;", "layout(location = 0) out vec4 color;",
						"vec4 fetch(sampler2D sampler, vec2 coord) { return texture(sampler, coord); }",
						"void main() { vec2 texture2D = uv; color = fetch(colortex0, texture2D); }"),
				check("compiles, colortex0 still bound", r -> r.has(F, "sampler set=0 colortex0"))));
		out.add(world("pack function named like a vanilla block (world)", WORLD_VERTEX, null,
				glsl("#version 330 core", "uniform sampler2D gtexture;", "in vec2 uv;", "in vec4 tint;", "layout(location = 0) out vec4 color;",
						"void Fog(inout vec3 c) { c *= 0.5; }", "float Globals = 1.0;",
						"void main() { color = texture(gtexture, uv) * tint; Fog(color.rgb); color.a *= Globals; }"),
				check("compiles, vanilla's Fog block kept", r -> r.has(F, "ubo set=0 Fog") && r.sources().get(F).contains("aeth_n_Fog("))));
		out.add(new Fixture("compute: samplers and block in set 0", Kind.COMPUTE,
				glsl("#version 430", "layout(local_size_x = 8, local_size_y = 8) in;", "uniform sampler2D colortex0;", "uniform usampler2D colortex5;",
						"uniform float frameTime;", "void main() { vec4 c = texture(colortex0, vec2(0)) * frameTime + vec4(texelFetch(colortex5, ivec2(0), 0)); }"),
				null, null,
				List.of(check("block at binding 0, samplers 1 and 2", r -> r.has(PatchShaderType.COMPUTE, "ubo set=0 binding=0 AetheriumUniforms")
						&& r.has(PatchShaderType.COMPUTE, "sampler set=0 binding=1 colortex0") && r.has(PatchShaderType.COMPUTE, "sampler set=0 binding=2 colortex5")))));
		out.add(new Fixture("image format views: r32ui over R32F, integer sampler, rg32ui over RGBA16F", Kind.COMPUTE,
				glsl("#version 430", "layout(local_size_x = 8) in;", VIEW_DECLARATIONS,
						"void main() { imageAtomicAdd(lightVolume, ivec3(0), 1u); uvec4 v = texelFetch(lightSampler, ivec3(0), 0);",
						"imageStore(colorimg, ivec2(0), uvec4(v.x, 0u, 0u, 0u)); }"),
				null, null,
				List.of(check("each declaration bound to its view", r -> r.has(PatchShaderType.COMPUTE, "image set=1 binding=320 lightVolume")
						&& r.has(PatchShaderType.COMPUTE, "sampler set=1 binding=321 lightSampler")
						&& r.has(PatchShaderType.COMPUTE, "image set=1 binding=322 colorimg")))));
		out.add(fullscreen("image format of another texel size: left as declared",
				vertex("out vec2 uv;", "uv = vec2(0);"),
				glsl("#version 330 core", "layout(rg16f) uniform image2D colorimg;", "in vec2 uv;", "layout(location = 0) out vec4 color;",
						"void main() { imageStore(colorimg, ivec2(0), vec4(uv, 0, 0)); color = vec4(uv, 0, 1); }"),
				check("image's own binding", r -> r.has(F, "image set=1 binding=0 colorimg"))));
		out.add(world("gbuffers writing a custom image, a target image and a storage buffer (world)", WORLD_VERTEX, null,
				glsl("#version 430 core", "in vec2 uv;", "in vec4 tint;", "layout(location = 0) out vec4 color;", "uniform image2D colorimg;",
						"layout(rgba8) uniform image2D colorimg3;", "layout(std430, binding = 2) buffer Data { uint count; uint values[]; } data;",
						"void main() { imageStore(colorimg, ivec2(gl_FragCoord.xy), tint); imageStore(colorimg3, ivec2(0), vec4(uv, 0, 1));",
						"atomicAdd(data.count, 1u); color = tint; }"),
				check("image, target image and buffer in set 1", r -> r.has(F, "image set=1 binding=0 colorimg") && r.has(F, "image set=1 binding=259 colorimg3")
						&& r.has(F, "ssbo set=1 binding=66 "))));
		String passThrough = glsl("#version 330 core", "layout(triangles) in;", "layout(triangle_strip, max_vertices = 3) out;", "in vec2 uv[];",
				"in vec4 tint[];", "out vec2 gUv;", "out vec4 gTint;",
				"void main() { for (int i = 0; i < 3; i++) { gl_Position = gl_in[i].gl_Position; gUv = uv[i]; gTint = tint[i]; EmitVertex(); } EndPrimitive(); }");
		String passThroughFragment = glsl("#version 330 core", "in vec2 gUv;", "in vec4 gTint;", "uniform sampler2D gtexture;",
				"layout(location = 0) out vec4 color;", "void main() { color = texture(gtexture, gUv) * gTint; }");
		out.add(new Fixture("metal: pass-through geometry stage folded (loop)", Kind.WORLD_METAL, WORLD_VERTEX, passThrough, passThroughFragment, List.of(
				check("no geometry stage; the fragment reads the vertex outputs", r -> !r.sources().containsKey(PatchShaderType.GEOMETRY)
						&& r.location(V, "out", "uv") >= 0 && r.location(V, "out", "uv") == r.location(F, "in", "uv")
						&& r.location(V, "out", "tint") == r.location(F, "in", "tint")),
				metalTranslates())));
		out.add(new Fixture("metal: pass-through geometry stage folded (unrolled)", Kind.WORLD_METAL, WORLD_VERTEX,
				glsl("#version 330 core", "layout(triangles) in;", "layout(max_vertices = 3, triangle_strip) out;", "in vec2 uv[];", "in vec4 tint[];",
						"out vec2 gUv;", "out vec4 gTint;", "void main() {",
						"gl_Position = gl_in[0].gl_Position; gUv = uv[0]; gTint = tint[0]; EmitVertex();",
						"gl_Position = gl_in[1].gl_Position; gUv = uv[1]; gTint = tint[1]; EmitVertex();",
						"gl_Position = gl_in[2].gl_Position; gUv = uv[2]; gTint = tint[2]; EmitVertex();", "EndPrimitive(); }"),
				passThroughFragment, List.of(
				check("no geometry stage", r -> !r.sources().containsKey(PatchShaderType.GEOMETRY) && r.location(F, "in", "uv") >= 0),
				metalTranslates())));
		out.add(new Fixture("metal: geometry stage that computes is kept", Kind.WORLD_METAL, WORLD_VERTEX,
				glsl("#version 330 core", "layout(triangles) in;", "layout(triangle_strip, max_vertices = 3) out;", "in vec2 uv[];", "in vec4 tint[];",
						"out vec2 gUv;", "out vec4 gTint;",
						"void main() { for (int i = 0; i < 3; i++) { gl_Position = gl_in[i].gl_Position; gUv = uv[i] * 2.0; gTint = tint[i]; EmitVertex(); } EndPrimitive(); }"),
				passThroughFragment, List.of(
				check("geometry stage kept", r -> r.sources().containsKey(PatchShaderType.GEOMETRY)))));
		out.add(world("desktop: pass-through geometry stage kept", WORLD_VERTEX, passThrough, passThroughFragment,
				check("geometry stage kept", r -> r.sources().containsKey(PatchShaderType.GEOMETRY))));
		out.add(new Fixture("metal: array-of-matrices varying split per element", Kind.FULLSCREEN_METAL,
				vertex("flat out mat2x3 coeff[2];", "coeff[0] = mat2x3(1.0); coeff[1] = mat2x3(vaPosition, vaPosition);"), null,
				glsl("#version 400 compatibility", "flat in mat2x3 coeff[2];", "layout(location = 0) out vec4 color;",
						"void main() { color = vec4(coeff[0][0] + coeff[1][1], 1.0); }"),
				List.of(check("no matrix array in either interface", r -> !r.sources().get(V).contains("out mat2x3 coeff[")
						&& !r.sources().get(F).contains("in mat2x3 coeff[") && r.location(F, "in", "aeth_v_coeff_1") == r.location(V, "out", "aeth_v_coeff_1")),
						metalTranslates())));
		out.add(new Fixture("metal: private names dropped, interface names kept", Kind.FULLSCREEN_METAL,
				vertex("out vec2 uv;", "uv = vaPosition.xy;"), null,
				glsl("#version 330 core", "in vec2 uv;", "uniform sampler2D colortex0;", "uniform float frameTimeCounter;",
						"layout(location = 0) out vec4 color;", "float level(float x) { float bias = x * 0.5; return bias + frameTimeCounter; }",
						"void main() { color = texture(colortex0, uv) * level(uv.x); }"),
				List.of(check("no private name reaches Metal; reflection unchanged", r -> {
					int[] words = r.words().get(F);
					int[] stripped = dev.spacebod.aetherium.client.gpu.DebugNames.apply(words);
					String msl = MetalTranslation.translate(spirv(stripped)).msl();
					return stripped != words && msl != null && !msl.contains("bias") && !msl.contains("level(")
							&& SpirvInterface.reflect(spirv(stripped), false).equals(SpirvInterface.reflect(spirv(words), false));
				}))));
		out.add(new Fixture("metal: packing built-ins as arithmetic, invariant position", Kind.FULLSCREEN_METAL,
				vertex("out vec2 uv;", "uv = vaPosition.xy;"), null,
				glsl("#version 330 core", "in vec2 uv;", "layout(location = 0) out vec4 color;",
						"void main() { uint a = packUnorm4x8(vec4(uv, 0.5, 1.0)); uint b = packSnorm2x16(uv - 0.5);",
						"color = unpackUnorm4x8(a) + vec4(unpackSnorm2x16(b), unpackUnorm2x16(packUnorm2x16(uv)))"
								+ " + unpackSnorm4x8(packSnorm4x8(vec4(-uv, uv))); }"),
				List.of(
				check("no packing built-in called; helpers defined; position invariant", r -> {
					String fragment = r.sources().get(F);
					return !java.util.regex.Pattern.compile("\\bpackUnorm4x8\\(").matcher(fragment).find() && fragment.contains("aeth_packSnorm4x8")
							&& fragment.contains("aeth_unpackSnorm2x16") && r.sources().get(V).contains("invariant gl_Position");
				}),
				metalTranslates())));
		return out;
	}

	/** Declarations in other formats than their images' ({@code ImageViews}). */
	private static final String VIEW_DECLARATIONS = String.join(NL, "layout(r32ui) uniform uimage3D lightVolume;", "uniform usampler3D lightSampler;",
			"layout(rg32ui) uniform uimage2D colorimg;");

	/** SPIR-V words as the native-order buffer the reflection reads. */
	private static ByteBuffer spirv(int[] words) {
		ByteBuffer bytes = ByteBuffer.allocateDirect(words.length * 4).order(java.nio.ByteOrder.nativeOrder());
		bytes.asIntBuffer().put(words);
		return bytes;
	}

	private static String blockLine(Reflected r, PatchShaderType type) {
		return r.stage(type).stream().filter(l -> l.startsWith("ubo") && l.contains("AetheriumUniforms")).findFirst().orElse("");
	}

	private static String lastError;

	private static ByteBuffer compile(String source, PatchShaderType type) {
		int kind = switch (type) {
			case VERTEX -> Shaderc.shaderc_vertex_shader;
			case FRAGMENT -> Shaderc.shaderc_fragment_shader;
			case GEOMETRY -> Shaderc.shaderc_geometry_shader;
			case TESS_CONTROL -> Shaderc.shaderc_tess_control_shader;
			case TESS_EVAL -> Shaderc.shaderc_tess_evaluation_shader;
			case COMPUTE -> Shaderc.shaderc_compute_shader;
		};
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
			long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, "fixture", "main", options);
			try {
				if (Shaderc.shaderc_result_get_compilation_status(result) == Shaderc.shaderc_compilation_status_success) {
					ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
					ByteBuffer copy = ByteBuffer.allocateDirect(bytes.remaining()).order(java.nio.ByteOrder.nativeOrder());
					return copy.put(bytes).flip();
				}
				lastError = Shaderc.shaderc_result_get_error_message(result);
				return null;
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}

	private static String firstError(String message) {
		if (message == null) {
			return "null";
		}
		for (String line : message.split(NL)) {
			if (line.contains("error")) {
				return line.trim();
			}
		}
		return message.split(NL)[0];
	}
}
