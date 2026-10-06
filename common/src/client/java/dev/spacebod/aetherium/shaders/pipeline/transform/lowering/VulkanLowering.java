package dev.spacebod.aetherium.shaders.pipeline.transform.lowering;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.UniformType;
import dev.spacebod.aetherium.shaders.engine.ShadowSampling;
import dev.spacebod.aetherium.shaders.chunks.ChunkMeshFormat;
import dev.spacebod.aetherium.shaders.engine.UniformBlock;
import dev.spacebod.aetherium.shaders.pipeline.transform.PatchShaderType;
import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.Profile;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.VersionStatement;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.Declaration;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.FunctionDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.FunctionParameter;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AdditionAssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.ArrayAccessExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.DivisionAssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.MultiplicationAssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.SubtractionAssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.EqualityExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.InequalityExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.DecrementPostfixExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.DecrementPrefixExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.IncrementPostfixExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.IncrementPrefixExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.MemberAccessExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExtensionDirective;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.node.type.initializer.Initializer;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.InterpolationQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.LayoutQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.LayoutQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.NamedLayoutQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.specifier.ArraySpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.TypeReference;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier.BuiltinType;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.FunctionPrototype;
import io.github.douira.glsl_transformer.ast.node.type.specifier.TypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.struct.StructDeclarator;
import io.github.douira.glsl_transformer.ast.node.type.struct.StructMember;
import io.github.douira.glsl_transformer.ast.node.type.struct.StructSpecifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.util.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Lowers a program's GLSL to what vanilla 26.3's Vulkan pipeline accepts, on the syntax tree the transformer built
 * (before it is printed), so nothing depends on how the pack formatted its code. One call per program, all stages at
 * once (interface matching and the uniform block need every stage):
 * <ol>
 *   <li>every global declaration with several declarators is split, one name per declaration; {@code const}
 *       declarations whose initialiser is not a constant expression lose {@code const};</li>
 *   <li>world programs: LOD-mod interface adoption, albedo/lightmap/overlay sampler renames (scoped: a local of the same
 *       name is left alone), vertex attributes adapted to the pipeline's vertex format (defaults for missing ones,
 *       conversions for packed or narrower ones, as global initialisers), the clip-space depth remap after the last
 *       vertex stage, OpenGL's depth convention in the fragment stage ({@code gl_FragCoord} references replaced by
 *       their flipped value, valid in global initialisers; {@code gl_FragDepth} written through a global), the
 *       constant alpha test;</li>
 *   <li>custom images, image samplers and storage buffers bound in set 1;</li>
 *   <li>{@code sampler2DShadow}: on the shadow depth maps, with hardware comparison on (graphics programs), kept and
 *       renamed to its comparison binding ({@code aeth_cmp_<name>}, bound with a depth-comparison sampler); otherwise
 *       (globals, arrays and function parameters) retyped to {@code sampler2D}, every lookup on one replaced by an
 *       explicit comparison (nearest, or OpenGL's filtered comparison per map; a gather, the four);</li>
 *   <li>inputs the stage before lacks (vertex, geometry, fragment) become zeroed outputs there;</li>
 *   <li>explicit locations on every stage input and output (one location per varying name across stages, interface
 *       blocks included), {@code gl_VertexID}/{@code gl_InstanceID} as Vulkan's built-ins; world programs: fragment
 *       outputs moved to their render pass attachment, outputs without one become plain globals;</li>
 *   <li>functions the entry point cannot reach, and the samplers, images and loose uniforms only they read, removed
 *       (so they become neither descriptors nor block members);</li>
 *   <li>loose uniforms into one std140 {@code AetheriumUniforms} block, identical in every stage;</li>
 *   <li>compute programs: set 0 bindings for the block and samplers;</li>
 *   <li>version: GLSL 4.60 core at least (the newest language rules: implicit conversions, local consts from
 *       variables, every built-in), the pack's {@code #extension} directives first, {@code require} relaxed to
 *       {@code enable}.</li>
 * </ol>
 * The program's interface (block layout, samplers, uniform blocks) comes back as data.
 */
public final class VulkanLowering {
	public static final String BLOCK_NAME = UniformBlock.BLOCK_NAME;
	private static final Set<String> BLOCK_TYPES = Set.of("float", "int", "uint", "bool", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4",
			"uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4");
	/** Lookups whose first argument may be a shadow sampler, and where their offset argument is (-1 = none). */
	private static final Map<String, Integer> SHADOW_LOOKUPS = Map.ofEntries(
			Map.entry("texture", -1), Map.entry("textureLod", -1), Map.entry("textureGrad", -1),
			Map.entry("textureOffset", 2), Map.entry("textureLodOffset", 3), Map.entry("textureGradOffset", 4),
			Map.entry("textureProj", -1), Map.entry("textureProjLod", -1), Map.entry("textureProjGrad", -1),
			Map.entry("textureProjOffset", 2), Map.entry("textureProjLodOffset", 3), Map.entry("textureProjGradOffset", 4),
			// Comparison gathers: (sampler, coordinate, reference[, offset]).
			Map.entry("textureGather", -1), Map.entry("textureGatherOffset", 3));
	/**
	 * Comparison helpers: {@code c.z} against the stored depth at {@code c.xy} (OpenGL's LEQUAL: 1 when the reference is
	 * at or in front). Nearest = the texel under the coordinate; filtered = the bilinear blend of the four nearest
	 * comparisons (OpenGL's hardware-filtered comparison). Offsets go in as values, so they need not be constants.
	 */
	private static final String[] SHADOW_HELPERS = {
			"""
			float aeth_shadowNearest(sampler2D s, vec3 c, ivec2 o) {
				ivec2 size = textureSize(s, 0);
				ivec2 i = clamp(ivec2(floor(c.xy * vec2(size))) + o, ivec2(0), size - 1);
				return step(c.z, texelFetch(s, i, 0).r);
			}""",
			"""
			float aeth_shadowFiltered(sampler2D s, vec3 c, ivec2 o) {
				ivec2 size = textureSize(s, 0);
				vec2 st = c.xy * vec2(size) - 0.5 + vec2(o);
				vec2 f = fract(st);
				ivec2 i0 = clamp(ivec2(floor(st)), ivec2(0), size - 1);
				ivec2 i1 = clamp(ivec2(floor(st)) + 1, ivec2(0), size - 1);
				float d00 = step(c.z, texelFetch(s, i0, 0).r);
				float d10 = step(c.z, texelFetch(s, ivec2(i1.x, i0.y), 0).r);
				float d01 = step(c.z, texelFetch(s, ivec2(i0.x, i1.y), 0).r);
				float d11 = step(c.z, texelFetch(s, i1, 0).r);
				return mix(mix(d00, d10, f.x), mix(d01, d11, f.x), f.y);
			}""",
			"float aeth_shadowNearestProj(sampler2D s, vec4 c, ivec2 o) { return aeth_shadowNearest(s, c.xyz / c.w, o); }",
			// The four comparisons around p, in gather order: (i0, j1), (i1, j1), (i1, j0), (i0, j0).
			"""
			vec4 aeth_shadowGather(sampler2D s, vec2 p, float r, ivec2 o) {
				ivec2 size = textureSize(s, 0);
				ivec2 i = ivec2(floor(p * vec2(size) - 0.5)) + o;
				ivec2 a = clamp(i, ivec2(0), size - 1);
				ivec2 b = clamp(i + 1, ivec2(0), size - 1);
				return vec4(step(r, texelFetch(s, ivec2(a.x, b.y), 0).r), step(r, texelFetch(s, b, 0).r),
						step(r, texelFetch(s, ivec2(b.x, a.y), 0).r), step(r, texelFetch(s, a, 0).r));
			}""",
			"float aeth_shadowFilteredProj(sampler2D s, vec4 c, ivec2 o) { return aeth_shadowFiltered(s, c.xyz / c.w, o); }"
	};
	/** The GLSL version every stage is compiled as at least. */
	private static final int VERSION_FLOOR = 460;
	private static final PatchShaderType[] GRAPHICS_ORDER = {PatchShaderType.VERTEX, PatchShaderType.TESS_CONTROL, PatchShaderType.TESS_EVAL,
			PatchShaderType.GEOMETRY, PatchShaderType.FRAGMENT};

	/**
	 * The interface data the lowering hands back (the stages themselves are printed by the transformer).
	 *
	 * @param testsNaN       a stage tests for NaN or infinity ({@link #testsNaN}): it needs IEEE NaN handling to work on Vulkan
	 * @param geometryFolded the geometry stage was folded away ({@link #foldGeometry}): the program has no geometry stage */
	public record Result(UniformBlock.Layout layout, Map<String, UniformType> resources, Map<String, String> samplerTypes, List<String> notes,
			boolean testsNaN, boolean geometryFolded) {
	}

	private final ASTParser t;
	private final Map<PatchShaderType, TranslationUnit> trees;
	private final LoweringParameters p;
	private final List<String> notes = new ArrayList<>();
	/** Code run before / after the pack's main, per stage (emitted once, as a wrapper). */
	private final Map<PatchShaderType, List<String>> before = new EnumMap<>(PatchShaderType.class);
	private final Map<PatchShaderType, List<String>> after = new EnumMap<>(PatchShaderType.class);

	private VulkanLowering(ASTParser t, Map<PatchShaderType, TranslationUnit> trees, LoweringParameters p) {
		this.t = t;
		this.trees = new EnumMap<>(PatchShaderType.class);
		trees.forEach((k, v) -> {
			if (v != null) {
				this.trees.put(k, v);
			}
		});
		this.p = p;
	}

	/** Lowers every stage of one program in place. */
	public static Result transformGrouped(ASTParser t, Map<PatchShaderType, TranslationUnit> trees, LoweringParameters p) {
		return new VulkanLowering(t, trees, p).run();
	}

	private Result run() {
		reservedNames();
		each((type, tree, root) -> splitDeclarators(tree, root));
		each((type, tree, root) -> demoteNonConstant(tree, root));
		if (p.kind() == LoweringParameters.Kind.WORLD && !p.platform().geometryStages() && trees.containsKey(PatchShaderType.GEOMETRY)) {
			foldGeometry();
		}
		if (p.kind() == LoweringParameters.Kind.COMPUTE) {
			each(this::sharedVec3);
		}
		if (p.kind() != LoweringParameters.Kind.COMPUTE) {
			structVaryings();
			if (p.platform().metal()) {
				matrixArrayVaryings();
			}
		}
		if (p.kind() == LoweringParameters.Kind.WORLD) {
			if (p.lodVariant() != LoweringParameters.LodVariant.NONE) {
				each((type, tree, root) -> lodInterface(type, tree, root));
			}
			for (LoweringParameters.Rename rename : p.samplerRenames()) {
				each((type, tree, root) -> renameSampler(tree, root, rename.from(), rename.to()));
			}
			stage(PatchShaderType.VERTEX, (type, tree, root) -> {
				chunkVertex(tree, root);
				vertexAttributes(tree, root);
			});
			PatchShaderType last = lastVertexStage();
			String clipZ = p.glDepth() ? "0.5 * (gl_Position.z + gl_Position.w)" : "0.5 * (gl_Position.w - gl_Position.z)";
			if (last == PatchShaderType.GEOMETRY) {
				stage(last, (type, tree, root) -> geometryClipDepth(tree, root, clipZ));
			} else if (last != null) {
				after(last).add("gl_Position.z = " + clipZ + ";");
			}
			if (!p.glDepth()) {
				stage(PatchShaderType.FRAGMENT, this::fragmentDepth);
			}
			if (p.alphaReference() != null) {
				each((type, tree, root) -> constantAlphaTest(tree, root, p.alphaReference()));
			}
			if (p.glint().identityTextureMatrix()) {
				each((type, tree, root) -> identityTextureMatrix(root));
			}
			glint = p.glint().attachment() >= 0 && glintVaryings();
			if (p.grayscaleAlbedo()) {
				each((type, tree, root) -> grayscaleAlbedo(root));
			}
		}
		if (p.storage().active()) {
			each(this::storage);
		}
		each(this::shadowSamplers);
		if (p.kind() != LoweringParameters.Kind.COMPUTE) {
			if (trees.containsKey(PatchShaderType.VERTEX) && trees.containsKey(PatchShaderType.FRAGMENT) && !trees.containsKey(PatchShaderType.TESS_EVAL)) {
				missingVaryings();
			}
			locations();
			if (p.outputAttachments() != null) {
				stage(PatchShaderType.FRAGMENT, (type, tree, root) -> remapOutputs(tree, root));
			}
			if (glint) {
				stage(PatchShaderType.FRAGMENT, (type, tree, root) -> glintTerm(tree));
			}
		}
		each(this::pruneUnreached);
		if (p.platform().metal()) {
			each(this::metalPacking);
			if (p.kind() != LoweringParameters.Kind.COMPUTE) {
				stage(PatchShaderType.VERTEX, this::invariantPosition);
			}
		}
		UniformBlock.Layout layout = uniformBlock();
		each(this::wrapMain);
		// After the entry wrapper, whose copies (struct varyings, written inputs) name varyings too.
		packedReferences();
		if (p.kind() == LoweringParameters.Kind.COMPUTE) {
			each((type, tree, root) -> computeBindings(tree, root));
		}
		each((type, tree, root) -> versionAndExtensions(type, tree, root));
		Map<String, UniformType> resources = new LinkedHashMap<>();
		Map<String, String> samplerTypes = new LinkedHashMap<>();
		collectResources(resources, samplerTypes);
		return new Result(layout, resources, samplerTypes, notes, testsNaN(), geometryFolded);
	}

	// ------------------------------------------------------------------ plumbing

	private interface StagePass {
		void run(PatchShaderType type, TranslationUnit tree, Root root);
	}

	private void each(StagePass pass) {
		for (Map.Entry<PatchShaderType, TranslationUnit> e : trees.entrySet()) {
			TranslationUnit tree = e.getValue();
			tree.getRoot().indexBuildSession(() -> pass.run(e.getKey(), tree, tree.getRoot()));
		}
	}

	private void stage(PatchShaderType type, StagePass pass) {
		TranslationUnit tree = trees.get(type);
		if (tree != null) {
			tree.getRoot().indexBuildSession(() -> pass.run(type, tree, tree.getRoot()));
		}
	}

	private List<String> before(PatchShaderType type) {
		return before.computeIfAbsent(type, k -> new ArrayList<>());
	}

	private List<String> after(PatchShaderType type) {
		return after.computeIfAbsent(type, k -> new ArrayList<>());
	}

	private @Nullable PatchShaderType lastVertexStage() {
		for (PatchShaderType type : new PatchShaderType[]{PatchShaderType.GEOMETRY, PatchShaderType.TESS_EVAL, PatchShaderType.VERTEX}) {
			if (trees.containsKey(type)) {
				return type;
			}
		}
		return null;
	}

	/** A global variable declaration: its node, the declaration, and its (single, after splitting) member. */
	private record Global(DeclarationExternalDeclaration node, TypeAndInitDeclaration declaration, DeclarationMember member) {
		String name() {
			return member.getName().getName();
		}

		@Nullable TypeQualifier qualifier() {
			return declaration.getType().getTypeQualifier();
		}

		TypeSpecifier specifier() {
			return declaration.getType().getTypeSpecifier();
		}
	}

	/** Global variable declarations in source order (one member each once {@link #splitDeclarators} ran). */
	private static List<Global> globals(TranslationUnit tree) {
		List<Global> out = new ArrayList<>();
		for (ExternalDeclaration e : tree.getChildren()) {
			if (e instanceof DeclarationExternalDeclaration d && d.getDeclaration() instanceof TypeAndInitDeclaration decl) {
				for (DeclarationMember member : decl.getMembers()) {
					out.add(new Global(d, decl, member));
				}
			}
		}
		return out;
	}

	private static List<InterfaceBlockDeclaration> blocks(TranslationUnit tree) {
		List<InterfaceBlockDeclaration> out = new ArrayList<>();
		for (ExternalDeclaration e : tree.getChildren()) {
			if (e instanceof DeclarationExternalDeclaration d && d.getDeclaration() instanceof InterfaceBlockDeclaration b) {
				out.add(b);
			}
		}
		return out;
	}

	private static boolean has(@Nullable TypeQualifier qualifier, StorageType storage) {
		if (qualifier == null) {
			return false;
		}
		for (TypeQualifierPart part : qualifier.getParts()) {
			if (part instanceof StorageQualifier s && s.storageType == storage) {
				return true;
			}
		}
		return false;
	}

	/** The integer value of {@code layout(name = N)}, or -1. */
	private static int layoutValue(@Nullable TypeQualifier qualifier, String name) {
		if (qualifier == null) {
			return -1;
		}
		for (TypeQualifierPart part : qualifier.getParts()) {
			if (part instanceof LayoutQualifier layout) {
				for (LayoutQualifierPart lp : layout.getParts()) {
					if (lp instanceof NamedLayoutQualifierPart named && named.getName().getName().equals(name)
							&& named.getExpression() instanceof LiteralExpression literal && literal.isInteger()) {
						return (int) literal.getInteger();
					}
				}
			}
		}
		return -1;
	}

	private static boolean hasLayout(@Nullable TypeQualifier qualifier, String name) {
		if (qualifier == null) {
			return false;
		}
		for (TypeQualifierPart part : qualifier.getParts()) {
			if (part instanceof LayoutQualifier layout) {
				for (LayoutQualifierPart lp : layout.getParts()) {
					if (lp instanceof NamedLayoutQualifierPart named && named.getName().getName().equals(name)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Sets {@code layout(name = value)} on {@code qualifier}, replacing an existing value. */
	private static void setLayout(TypeQualifier qualifier, String name, int value) {
		LayoutQualifier layout = null;
		for (TypeQualifierPart part : qualifier.getParts()) {
			if (part instanceof LayoutQualifier l) {
				layout = l;
				for (LayoutQualifierPart lp : l.getParts()) {
					if (lp instanceof NamedLayoutQualifierPart named && named.getName().getName().equals(name)) {
						named.getExpression().replaceByAndDelete(new LiteralExpression(Type.INT32, value));
						return;
					}
				}
			}
		}
		NamedLayoutQualifierPart part = new NamedLayoutQualifierPart(new Identifier(name), new LiteralExpression(Type.INT32, value));
		if (layout == null) {
			qualifier.getChildren().add(0, new LayoutQualifier(Stream.of(part)));
		} else {
			layout.getParts().add(part);
		}
	}

	/**
	 * Whether a stage tests for NaN or infinity: {@code isnan}/{@code isinf}, or a comparison of an expression with itself
	 * ({@code x != x}, {@code x == x}). Vulkan drivers may compile such tests away unless the shader asks for IEEE NaN
	 * handling, which costs speed in every program that has it (see {@code FloatControls}).
	 */
	private boolean testsNaN() {
		for (TranslationUnit tree : trees.values()) {
			Root root = tree.getRoot();
			if (root.identifierIndex.has("isnan") || root.identifierIndex.has("isinf")) {
				return true;
			}
			for (var equal : root.nodeIndex.get(EqualityExpression.class)) {
				if (print(equal.getLeft()).equals(print(equal.getRight()))) {
					return true;
				}
			}
			for (var unequal : root.nodeIndex.get(InequalityExpression.class)) {
				if (print(unequal.getLeft()).equals(print(unequal.getRight()))) {
					return true;
				}
			}
		}
		return false;
	}

	private static String print(ASTNode node) {
		return ASTPrinter.printSimple(node).trim();
	}

	/** A type's GLSL name without array dimensions ({@code vec3}, {@code sampler2D}, a struct name). */
	private static String typeName(TypeSpecifier specifier) {
		String s = print(specifier);
		int bracket = s.indexOf('[');
		return (bracket < 0 ? s : s.substring(0, bracket)).trim();
	}

	/** Array length of a declaration ({@code T a[n]} or {@code T[n] a}): 0 = not an array, -1 = unsized or not a literal. */
	private static int arrayLength(Global g) {
		ArraySpecifier spec = g.member().getArraySpecifier() != null ? g.member().getArraySpecifier() : g.specifier().getArraySpecifier();
		return arrayLength(spec);
	}

	private static int arrayLength(@Nullable ArraySpecifier spec) {
		if (spec == null || spec.getDimensions().isEmpty()) {
			return 0;
		}
		int length = 1;
		for (Expression dimension : spec.getDimensions()) {
			if (dimension instanceof LiteralExpression literal && literal.isInteger()) {
				length *= (int) literal.getInteger();
			} else {
				return -1;
			}
		}
		return length;
	}

	// ------------------------------------------------------------------ shared memory

	/** Threadgroup memory a compute pipeline may use on every device: Apple GPUs (Metal, MoltenVK) allow 32 KiB. */
	private static final int SHARED_MEMORY_BUDGET = 32768;

	/**
	 * Metal lays a shared {@code vec3} out in 16 bytes ({@code float3}), GLSL in 12, so a compute program near the budget
	 * fails to build on MoltenVK (Photon's sky lighting, {@code shared vec3[256][9]}: 27 KiB in GLSL, 36 KiB in Metal).
	 * When the Metal size of a program's shared memory is over {@link #SHARED_MEMORY_BUDGET}, each shared vec3 array
	 * becomes a float array with a last dimension of 3: a read of an element becomes a vec3 constructor, an assignment
	 * to one ({@code = += -= *= /=}, as a statement) three component stores, and a row read whole (passed to a function)
	 * an array constructor. A program using one any other way is left as it is.
	 */
	private void sharedVec3(PatchShaderType type, TranslationUnit tree, Root root) {
		int metalBytes = 0;
		Map<Global, int[]> vec3s = new LinkedHashMap<>();
		for (Global g : globals(tree)) {
			if (!has(g.qualifier(), StorageType.SHARED)) {
				continue;
			}
			if (g.specifier().getArraySpecifier() != null) {
				return;
			}
			ArraySpecifier spec = g.member().getArraySpecifier();
			int[] dims = spec == null ? new int[0] : new int[spec.getDimensions().size()];
			int count = 1;
			for (int i = 0; i < dims.length; i++) {
				if (!(spec.getDimensions().get(i) instanceof LiteralExpression literal) || !literal.isInteger()) {
					return; // sizes not known here
				}
				dims[i] = (int) literal.getInteger();
				count *= dims[i];
			}
			String name = typeName(g.specifier());
			int components = GlslTypes.components(name);
			metalBytes += count * (name.startsWith("mat") || name.startsWith("dmat") ? 64 : components == 1 ? 4 : components == 2 ? 8 : 16);
			if (name.equals("vec3") && dims.length > 0) {
				vec3s.put(g, dims);
			}
		}
		if (metalBytes <= SHARED_MEMORY_BUDGET || vec3s.isEmpty()) {
			return;
		}
		List<Runnable> reads = new ArrayList<>();
		List<Runnable> writes = new ArrayList<>();
		for (Map.Entry<Global, int[]> entry : vec3s.entrySet()) {
			String name = entry.getKey().name();
			int[] dims = entry.getValue();
			Map<FunctionDefinition, Boolean> shadowed = new IdentityHashMap<>();
			for (Identifier id : root.identifierIndex.get(name)) {
				if (id == entry.getKey().member().getName()) {
					continue;
				}
				FunctionDefinition function = id.getAncestor(FunctionDefinition.class);
				if (function != null && shadowed.computeIfAbsent(function, fn -> declaresLocal(fn, name))) {
					continue;
				}
				if (!(id.getParent() instanceof ReferenceExpression reference)) {
					return;
				}
				Expression e = reference;
				int depth = 0;
				while (e.getParent() instanceof ArrayAccessExpression access && access.getLeft() == e) {
					e = access;
					depth++;
				}
				Expression element = e;
				if (SIDE_EFFECT.matcher(print(element)).find()) {
					return; // an index would run three times
				}
				ASTNode parent = element.getParent();
				boolean assigned = parent instanceof BinaryExpression b && b.getLeft() == element && ASSIGNMENTS.containsKey(b.getClass());
				if (depth == dims.length && assigned && parent.getParent() instanceof ExpressionStatement statement) {
					BinaryExpression assignment = (BinaryExpression) parent;
					String op = ASSIGNMENTS.get(assignment.getClass());
					writes.add(() -> {
						String path = print(assignment.getLeft());
						String value = op.isEmpty() ? "(" + print(assignment.getRight()) + ")"
								: sharedRead(path) + " " + op + " (" + print(assignment.getRight()) + ")";
						statement.replaceByAndDelete(t.parseStatement(root, "{ vec3 aeth_shared = " + value + "; " + path + "[0] = aeth_shared.x; "
								+ path + "[1] = aeth_shared.y; " + path + "[2] = aeth_shared.z; }"));
					});
				} else if (assigned || parent instanceof MemberAccessExpression || depth < dims.length - 1
						|| parent instanceof IncrementPrefixExpression || parent instanceof IncrementPostfixExpression
						|| parent instanceof DecrementPrefixExpression || parent instanceof DecrementPostfixExpression) {
					return; // written another way, or more than one dimension left
				} else if (depth == dims.length) {
					reads.add(() -> element.replaceByAndDelete(t.parseExpression(root, sharedRead(print(element)))));
				} else {
					int last = dims[dims.length - 1];
					reads.add(() -> {
						String path = print(element);
						StringBuilder row = new StringBuilder("vec3[" + last + "](");
						for (int i = 0; i < last; i++) {
							row.append(i == 0 ? "" : ", ").append(sharedRead(path + "[" + i + "]"));
						}
						element.replaceByAndDelete(t.parseExpression(root, row.append(')').toString()));
					});
				}
			}
		}
		// Reads first: a write's value is printed after the reads inside it are rewritten.
		reads.forEach(Runnable::run);
		writes.forEach(Runnable::run);
		for (Map.Entry<Global, int[]> entry : vec3s.entrySet()) {
			StringBuilder dims = new StringBuilder();
			for (int d : entry.getValue()) {
				dims.append('[').append(d).append(']');
			}
			replaceDeclaration(tree, root, entry.getKey().node(), "shared float " + entry.getKey().name() + dims + "[3];");
			notes.add("shared vec3 " + entry.getKey().name() + " stored as floats (Metal pads vec3 to 16 bytes: " + metalBytes + " over the "
					+ SHARED_MEMORY_BUDGET + " byte budget)");
		}
	}

	private static String sharedRead(String path) {
		return "vec3(" + path + "[0], " + path + "[1], " + path + "[2])";
	}

	/** Assignment expressions {@link #sharedVec3} rewrites, by the operator they apply ("" = plain assignment). */
	private static final Map<Class<?>, String> ASSIGNMENTS = Map.of(AssignmentExpression.class, "", AdditionAssignmentExpression.class, "+",
			SubtractionAssignmentExpression.class, "-", MultiplicationAssignmentExpression.class, "*", DivisionAssignmentExpression.class, "/");

	/** Increments, decrements and assignments: an index expression with one cannot be evaluated more than once. */
	private static final Pattern SIDE_EFFECT = Pattern.compile("\\+\\+|--|[^=!<>]=[^=]");

	// ------------------------------------------------------------------ Metal (MoltenVK)

	/**
	 * The float packing built-ins, as integer arithmetic ({@link #metalPacking}): the definitions of GLSL 4.60 section 8.4.
	 * A unorm pack clamps to [0, 1], a snorm pack to [-1, 1], scales by 255, 65535, 127 or 32767, rounds, and puts the
	 * first component in the least significant bits (a snorm component as two's complement); an unpack divides by the
	 * same figure, a snorm unpack clamping back to [-1, 1]. A signed byte is sign-extended by flipping its top bit and
	 * taking the midpoint off.
	 */
	private static final Map<String, String> PACKING = Map.of(
			"packUnorm4x8", "uint aeth_packUnorm4x8(vec4 v) { uvec4 b = uvec4(round(clamp(v, 0.0, 1.0) * 255.0));"
					+ " return b.x | (b.y << 8u) | (b.z << 16u) | (b.w << 24u); }",
			"unpackUnorm4x8", "vec4 aeth_unpackUnorm4x8(uint p) { return vec4(uvec4(p, p >> 8u, p >> 16u, p >> 24u) & 255u) / 255.0; }",
			"packSnorm4x8", "uint aeth_packSnorm4x8(vec4 v) { uvec4 b = uvec4(ivec4(round(clamp(v, -1.0, 1.0) * 127.0))) & 255u;"
					+ " return b.x | (b.y << 8u) | (b.z << 16u) | (b.w << 24u); }",
			"unpackSnorm4x8", "vec4 aeth_unpackSnorm4x8(uint p) { ivec4 b = (ivec4(uvec4(p, p >> 8u, p >> 16u, p >> 24u) & 255u) ^ 128) - 128;"
					+ " return clamp(vec4(b) / 127.0, -1.0, 1.0); }",
			"packUnorm2x16", "uint aeth_packUnorm2x16(vec2 v) { uvec2 b = uvec2(round(clamp(v, 0.0, 1.0) * 65535.0)); return b.x | (b.y << 16u); }",
			"unpackUnorm2x16", "vec2 aeth_unpackUnorm2x16(uint p) { return vec2(uvec2(p, p >> 16u) & 65535u) / 65535.0; }",
			"packSnorm2x16", "uint aeth_packSnorm2x16(vec2 v) { uvec2 b = uvec2(ivec2(round(clamp(v, -1.0, 1.0) * 32767.0))) & 65535u;"
					+ " return b.x | (b.y << 16u); }",
			"unpackSnorm2x16", "vec2 aeth_unpackSnorm2x16(uint p) { ivec2 b = (ivec2(uvec2(p, p >> 16u) & 65535u) ^ 32768) - 32768;"
					+ " return clamp(vec2(b) / 32767.0, -1.0, 1.0); }");

	/**
	 * Metal: calls to the float packing built-ins go to {@link #PACKING} helpers. Through MoltenVK the built-ins store
	 * and read wrong words when a stage makes several of them (a pack writing its material as two packed words got the
	 * float bits of a component in the first). Pack functions of the same names were renamed by {@link #reservedNames},
	 * so every call left here is a built-in.
	 */
	private void metalPacking(PatchShaderType type, TranslationUnit tree, Root root) {
		Set<String> used = new java.util.TreeSet<>();
		for (FunctionCallExpression call : new ArrayList<>(root.nodeIndex.get(FunctionCallExpression.class))) {
			Identifier fn = call.getFunctionName();
			if (fn != null && PACKING.containsKey(fn.getName())) {
				used.add(fn.getName());
				fn.setName("aeth_" + fn.getName());
			}
		}
		if (!used.isEmpty()) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS, used.stream().map(PACKING::get).toArray(String[]::new));
			notes.add(type.name().toLowerCase(Locale.ROOT) + ": " + used + " as integer arithmetic (Metal)");
		}
	}

	/**
	 * Metal: the vertex position is {@code invariant}. MoltenVK compiles with fast math, so two programs placing the same
	 * vertex with the same code can land it at different depths (a banner's pattern, tested against the depth its base
	 * wrote through another program, loses in patches). Every pack vertex stage gets it, so all of them compute
	 * positions the same way.
	 */
	private void invariantPosition(PatchShaderType type, TranslationUnit tree, Root root) {
		for (ExternalDeclaration e : tree.getChildren()) {
			if (squash(print(e)).startsWith("invariant gl_Position")) {
				return;
			}
		}
		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "invariant gl_Position;");
	}

	/** Whether {@link #foldGeometry} took the geometry stage out of the program. */
	private boolean geometryFolded;

	/** Copies from the first corner, then a loop over the three corners, then the end of the primitive. */
	private static final Pattern GEOMETRY_LOOP = Pattern.compile(
			"\\{((?:\\w+=\\w+\\[0];)*)for\\(int (\\w+)=0;\\2<3;(?:\\2\\+\\+|\\+\\+\\2|\\2\\+=1|\\2=\\2\\+1)\\)\\{(.*)}EndPrimitive\\(\\);}");
	private static final Pattern GEOMETRY_UNROLLED = Pattern.compile("\\{(.*)EndPrimitive\\(\\);}");
	private static final Pattern CORNER_COPY = Pattern.compile("(\\w+)=(\\w+)\\[(\\w+)]");
	private static final Pattern CORNER_POSITION = Pattern.compile("gl_Position=gl_in\\[(\\w+)]\\.gl_Position");

	/**
	 * A device without geometry stages (Metal has none): a geometry stage that only hands each triangle corner on is
	 * taken out of the program. The shape is read strictly: triangles in, one strip of three vertices out, and a
	 * {@code main} that for each corner (a loop over the three, or the three written out) sets {@code gl_Position} from
	 * {@code gl_in} and each output from the same corner of one input, emits the vertex, and ends the primitive. The
	 * fragment stage then declares the vertex stage's names as its inputs and fills the names it read from them before
	 * its {@code main}; a varying copied corner by corner and interpolated after the copy is that varying interpolated.
	 * Any other geometry stage stays, and the program is refused where the device has none.
	 */
	private void foldGeometry() {
		String refused = geometryFold();
		if (refused != null) {
			notes.add("geometry stage not folded (" + refused + ")");
		}
	}

	private @Nullable String geometryFold() {
		TranslationUnit geometry = trees.get(PatchShaderType.GEOMETRY);
		TranslationUnit vertex = trees.get(PatchShaderType.VERTEX);
		TranslationUnit fragment = trees.get(PatchShaderType.FRAGMENT);
		if (vertex == null || fragment == null || trees.containsKey(PatchShaderType.TESS_CONTROL) || trees.containsKey(PatchShaderType.TESS_EVAL)) {
			return "not a vertex, geometry, fragment program";
		}
		Set<String> inputs = new HashSet<>();
		Set<String> outputs = new LinkedHashSet<>();
		boolean triangles = false;
		boolean strip = false;
		FunctionDefinition main = null;
		for (ExternalDeclaration e : geometry.getChildren()) {
			if (e instanceof FunctionDefinition f) {
				if (!f.getFunctionPrototype().getName().getName().equals("main") || main != null) {
					return "functions besides main";
				}
				main = f;
			} else if (e instanceof DeclarationExternalDeclaration d) {
				String text = squash(print(e));
				if (d.getDeclaration() instanceof FunctionDeclaration f) {
					if (!f.getFunctionPrototype().getName().getName().equals("main")) {
						return "functions besides main";
					}
				} else if (text.matches("layout\\(triangles\\) ?in;")) {
					triangles = true;
				} else if (text.matches("layout\\((triangle_strip,max_vertices=3|max_vertices=3,triangle_strip)\\) ?out;")) {
					strip = true;
				} else if (d.getDeclaration() instanceof InterfaceBlockDeclaration block) {
					if (!has(block.getTypeQualifier(), StorageType.UNIFORM)) {
						return "an interface block";
					}
				} else if (d.getDeclaration() instanceof TypeAndInitDeclaration decl) {
					TypeQualifier q = decl.getType().getTypeQualifier();
					for (DeclarationMember m : decl.getMembers()) {
						if (has(q, StorageType.IN)) {
							inputs.add(m.getName().getName());
						} else if (has(q, StorageType.OUT)) {
							outputs.add(m.getName().getName());
						}
					}
				}
			}
		}
		if (!triangles || !strip) {
			return "not triangles in, one strip of three out";
		}
		if (main == null) {
			return "no main";
		}
		// Each corner's statements, by the index they read (a loop variable or the literal corner), and the outputs set
		// once per primitive from the first corner (the provoking vertex: the entity data the transformer hands on).
		List<List<String>> corners = new ArrayList<>();
		List<String> indices = new ArrayList<>();
		Map<String, String> once = new LinkedHashMap<>();
		String body = squash(print(main.getBody()));
		Matcher loop = GEOMETRY_LOOP.matcher(body);
		if (loop.matches()) {
			for (String s : statements(loop.group(1))) {
				Matcher copy = CORNER_COPY.matcher(s);
				if (!copy.matches() || !copy.group(3).equals("0") || !outputs.contains(copy.group(1)) || !inputs.contains(copy.group(2))
						|| once.putIfAbsent(copy.group(1), copy.group(2)) != null) {
					return "main computes something before the corners (" + s + ")";
				}
			}
			List<String> statements = statements(loop.group(3));
			if (statements.isEmpty() || !statements.getLast().equals("EmitVertex()")) {
				return "the loop does more than emit a corner";
			}
			corners.add(statements.subList(0, statements.size() - 1));
			indices.add(loop.group(2));
		} else {
			Matcher unrolled = GEOMETRY_UNROLLED.matcher(body);
			if (!unrolled.matches()) {
				return "main does more than emit three corners";
			}
			List<String> corner = new ArrayList<>();
			for (String s : statements(unrolled.group(1))) {
				if (s.equals("EmitVertex()")) {
					indices.add(String.valueOf(corners.size()));
					corners.add(corner);
					corner = new ArrayList<>();
				} else {
					corner.add(s);
				}
			}
			if (corners.size() != 3 || !corner.isEmpty()) {
				return "main does more than emit three corners";
			}
		}
		Map<String, String> copies = null; // output -> the input it hands on, in every corner
		for (int c = 0; c < corners.size(); c++) {
			String index = indices.get(c);
			boolean position = false;
			Map<String, String> corner = new LinkedHashMap<>();
			for (String s : corners.get(c)) {
				Matcher pos = CORNER_POSITION.matcher(s);
				Matcher copy = CORNER_COPY.matcher(s);
				if (pos.matches() && pos.group(1).equals(index)) {
					position = true;
				} else if (!copy.matches() || !copy.group(3).equals(index) || !outputs.contains(copy.group(1)) || !inputs.contains(copy.group(2))
						|| corner.putIfAbsent(copy.group(1), copy.group(2)) != null) {
					return "a corner computes something (" + s + ")";
				}
			}
			if (!position) {
				return "a corner does not hand on the position";
			}
			if (copies == null) {
				copies = corner;
			} else if (c == 1 && copies.keySet().containsAll(corner.keySet())) {
				// Written out: what only the first corner sets is set once per primitive.
				for (String output : new ArrayList<>(copies.keySet())) {
					if (!corner.containsKey(output)) {
						once.put(output, copies.remove(output));
					}
				}
			}
			if (!corner.equals(copies)) {
				return "the corners do not hand on the same outputs";
			}
		}
		Map<String, String> all = new LinkedHashMap<>(once);
		for (Map.Entry<String, String> e : copies.entrySet()) {
			if (all.putIfAbsent(e.getKey(), e.getValue()) != null) {
				return "an output is set both per corner and per primitive";
			}
		}
		if (!all.keySet().equals(outputs)) {
			return "an output is never set";
		}
		copies = all;
		Map<String, Global> vertexOutputs = new HashMap<>();
		for (Global g : globals(vertex)) {
			if (has(g.qualifier(), StorageType.OUT)) {
				vertexOutputs.put(g.name(), g);
			}
		}
		Set<String> fragmentGlobals = new HashSet<>();
		List<Global> fragmentInputs = new ArrayList<>();
		for (Global g : globals(fragment)) {
			fragmentGlobals.add(g.name());
			if (has(g.qualifier(), StorageType.IN)) {
				fragmentInputs.add(g);
			}
		}
		for (String source : copies.values()) {
			Global out = vertexOutputs.get(source);
			if (out == null || out.member().getArraySpecifier() != null || out.specifier().getArraySpecifier() != null) {
				return "input " + source + " is not a plain vertex stage output";
			}
		}
		List<Global> renamed = new ArrayList<>();
		for (Global in : fragmentInputs) {
			String source = copies.get(in.name());
			if (source == null || source.equals(in.name())) {
				continue;
			}
			if (fragmentGlobals.contains(source) || hasLayout(in.qualifier(), "location") || hasLayout(vertexOutputs.get(source).qualifier(), "location")
					|| in.member().getArraySpecifier() != null || in.specifier().getArraySpecifier() != null) {
				return "fragment input " + in.name() + " cannot take " + source + "'s place";
			}
			renamed.add(in);
		}
		Map<String, String> finalCopies = copies;
		stage(PatchShaderType.FRAGMENT, (type, tree, root) -> {
			List<String> declarations = new ArrayList<>();
			for (Global in : renamed) {
				String name = in.name();
				String source = finalCopies.get(name);
				declarations.add(print(in.specifier()) + " " + name + ";");
				in.member().getName().setName(source);
				before(PatchShaderType.FRAGMENT).add(name + " = " + source + ";");
			}
			if (!declarations.isEmpty()) {
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS, declarations.toArray(String[]::new));
			}
		});
		trees.remove(PatchShaderType.GEOMETRY);
		geometryFolded = true;
		notes.add("geometry stage folded (it only hands each corner on; the device has no geometry stages)");
		return null;
	}

	/** The statements of squashed code, without their semicolons; blocks are not split. */
	private static List<String> statements(String code) {
		List<String> out = new ArrayList<>();
		for (String s : code.split(";")) {
			if (!s.isEmpty()) {
				out.add(s);
			}
		}
		return out;
	}

	/** Printed code with whitespace only where two words meet ({@code int i}), for matching. */
	private static String squash(String code) {
		return code.replaceAll("\\s+", " ").replaceAll(" ?([^\\w ]) ?", "$1").trim();
	}

	/** Opaque types parsed as a plain name: declarations injected as text (e.g. {@code isamplerBuffer}) come out that way. */
	private static final Pattern SAMPLER_NAME = Pattern.compile("[iu]?sampler\\w+");
	private static final Pattern IMAGE_NAME = Pattern.compile("[iu]?image\\w+");

	private static boolean isSampler(TypeSpecifier specifier) {
		return specifier instanceof BuiltinFixedTypeSpecifier fixed ? fixed.type.name().contains("SAMPLER")
				: specifier instanceof TypeReference ref && SAMPLER_NAME.matcher(ref.getReference().getName()).matches();
	}

	private static boolean isImage(TypeSpecifier specifier) {
		return specifier instanceof BuiltinFixedTypeSpecifier fixed ? fixed.type.name().contains("IMAGE")
				: specifier instanceof TypeReference ref && IMAGE_NAME.matcher(ref.getReference().getName()).matches();
	}

	/** Replaces {@code node} (an external declaration) by the declarations parsed from {@code text}. */
	private void replaceDeclaration(TranslationUnit tree, Root root, ExternalDeclaration node, String... text) {
		int index = tree.getChildren().indexOf(node);
		List<ExternalDeclaration> parsed = t.parseExternalDeclarations(root, text);
		node.detachAndDelete();
		tree.getChildren().addAll(index, parsed);
	}

	// ------------------------------------------------------------------ names Vulkan GLSL takes

	/**
	 * Type names Vulkan GLSL adds (GL_KHR_vulkan_glsl): ordinary identifiers in OpenGL GLSL, which packs use (a
	 * {@code sampler2D sampler} parameter, for one).
	 */
	private static final Set<String> VULKAN_TYPE_NAMES = vulkanTypeNames();

	private static Set<String> vulkanTypeNames() {
		Set<String> names = new HashSet<>(List.of("sampler", "samplerShadow", "subpassInput", "isubpassInput", "usubpassInput",
				"subpassInputMS", "isubpassInputMS", "usubpassInputMS"));
		for (String prefix : new String[]{"", "i", "u"}) {
			for (String dim : new String[]{"1D", "1DArray", "2D", "2DArray", "2DMS", "2DMSArray", "2DRect", "3D", "Buffer", "Cube", "CubeArray"}) {
				names.add(prefix + "texture" + dim);
			}
		}
		return names;
	}

	/**
	 * Built-in functions newer than GLSL 1.20. Packs written for 1.20 define some themselves ({@code fma},
	 * {@code tanh}, {@code inverse}); the version the lowering emits has them built in, and since 1.30 a shader may not
	 * redefine a built-in, so a pack that does targets a version without it: its function is renamed with its calls.
	 */
	private static final Set<String> NEWER_BUILTINS = Set.of("round", "roundEven", "trunc", "sinh", "cosh", "tanh", "asinh", "acosh",
			"atanh", "isnan", "isinf", "inverse", "determinant", "floatBitsToInt", "floatBitsToUint", "intBitsToFloat", "uintBitsToFloat",
			"fma", "frexp", "ldexp", "bitfieldExtract", "bitfieldInsert", "bitfieldReverse", "bitCount", "findLSB", "findMSB", "uaddCarry",
			"usubBorrow", "umulExtended", "imulExtended", "packUnorm2x16", "packSnorm2x16", "packUnorm4x8", "packSnorm4x8",
			"unpackUnorm2x16", "unpackSnorm2x16", "unpackUnorm4x8", "unpackSnorm4x8", "packHalf2x16", "unpackHalf2x16", "dFdxFine",
			"dFdyFine", "dFdxCoarse", "dFdyCoarse", "fwidthFine", "fwidthCoarse");

	/**
	 * C++ and Metal Shading Language keywords that are legal GLSL identifiers. On macOS, MoltenVK translates SPIR-V to
	 * MSL with SPIRV-Cross, which keeps the pack's names, and Metal then rejects a local such as BSL's {@code bool new}.
	 * Renamed on every platform, so a pack lowers the same everywhere.
	 */
	private static final Set<String> MSL_KEYWORDS = Set.of("alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor",
			"catch", "char", "char8_t", "char16_t", "char32_t", "compl", "concept", "const_cast", "consteval", "constexpr", "constinit",
			"co_await", "co_return", "co_yield", "decltype", "delete", "dynamic_cast", "explicit", "export", "friend", "mutable", "new",
			"noexcept", "not", "not_eq", "nullptr", "operator", "or", "or_eq", "private", "protected", "public", "register",
			"reinterpret_cast", "requires", "signed", "static_assert", "static_cast", "thread_local", "throw", "try", "typeid",
			"typename", "virtual", "wchar_t", "xor", "xor_eq", "NULL",
			// Metal address spaces, function qualifiers and types
			"device", "constant", "thread", "threadgroup", "threadgroup_imageblock", "ray_data", "object_data", "kernel", "vertex",
			"fragment", "visible", "stage_in", "metal", "uchar", "ushort", "ulong", "size_t", "ptrdiff_t", "bfloat", "atomic");

	/**
	 * Metal standard library functions that GLSL lacks. SPIRV-Cross emits MSL under {@code using namespace metal}, so a
	 * pack function of the same name (Photon's {@code length_squared}) is an ambiguous overload there: renamed like
	 * {@link #NEWER_BUILTINS}.
	 */
	private static final Set<String> MSL_FUNCTIONS = Set.of("saturate", "length_squared", "distance_squared", "select", "median3",
			"max3", "min3", "fmin", "fmax", "fmin3", "fmax3", "fmedian3", "fmod", "rsqrt", "divide", "powr", "rint", "copysign", "fdim",
			"ilogb", "logb", "log10", "exp10", "atan2", "sincos", "sinpi", "cospi", "tanpi", "asinpi", "acospi", "atanpi", "atan2pi",
			"cbrt", "nextafter", "absdiff", "abs_diff", "clz", "ctz", "popcount", "rotate", "extract_bits", "insert_bits", "reverse_bits",
			"mad", "mulhi", "madhi", "madsat", "addsat", "subsat", "hadd", "rhadd", "isordered", "isunordered", "isnormal", "isfinite",
			"signbit", "fast_normalize", "as_type", "dot_squared", "pack_float_to_unorm4x8", "unpack_unorm4x8_to_float",
			// metal_type_traits (Photon's disjunction)
			"conjunction", "disjunction", "negation", "conditional", "enable_if", "declval", "is_same", "is_scalar", "is_vector",
			"make_signed", "make_unsigned", "bit_cast");

	private static final Set<String> RENAMED_FUNCTIONS = union(NEWER_BUILTINS, MSL_FUNCTIONS);

	private static Set<String> union(Set<String> a, Set<String> b) {
		Set<String> u = new HashSet<>(a);
		u.addAll(b);
		return Set.copyOf(u);
	}

	/** The block names the lowering adds (vanilla's blocks are already in the tree; ours comes later). */
	private static final Set<String> ADDED_BLOCK_NAMES = Set.of(UniformBlock.BLOCK_NAME);

	/**
	 * Pack declarations (variables, parameters, functions, structs) named like a Vulkan GLSL type or like a uniform
	 * block of the program (vanilla's {@code Fog}, {@code Globals} ...; Vulkan GLSL puts block names in the namespace
	 * functions use) are renamed {@code aeth_n_<name>}, every use with them; the block names themselves stay, vanilla
	 * binds by them. Pack declarations named like an {@link #MSL_KEYWORDS MSL keyword}, and pack functions named like a
	 * {@link #NEWER_BUILTINS newer built-in} or {@link #MSL_FUNCTIONS Metal function}, likewise. Every stage gets the same rename, so varyings still match by name.
	 */
	private void reservedNames() {
		Set<String> taken = new HashSet<>(VULKAN_TYPE_NAMES);
		taken.addAll(ADDED_BLOCK_NAMES);
		taken.addAll(MSL_KEYWORDS);
		Set<Identifier> blockNames = Collections.newSetFromMap(new IdentityHashMap<>());
		trees.values().forEach(tree -> tree.getChildren().forEach(e -> {
			if (e instanceof DeclarationExternalDeclaration d && d.getDeclaration() instanceof InterfaceBlockDeclaration block) {
				taken.add(block.getBlockName().getName());
				blockNames.add(block.getBlockName());
			}
		}));
		Set<String> clashing = new HashSet<>();
		trees.values().forEach(tree -> {
			for (String name : taken) {
				for (Identifier id : tree.getRoot().identifierIndex.get(name)) {
					if (!blockNames.contains(id) && declares(id)) {
						clashing.add(name);
					}
				}
			}
			for (String name : RENAMED_FUNCTIONS) {
				for (Identifier id : tree.getRoot().identifierIndex.get(name)) {
					if (id.getParent() instanceof FunctionPrototype) {
						clashing.add(name);
					}
				}
			}
		});
		for (String name : clashing) {
			trees.values().forEach(tree -> {
				for (Identifier id : new ArrayList<>(tree.getRoot().identifierIndex.get(name))) {
					if (!blockNames.contains(id)) {
						id.setName("aeth_n_" + name);
					}
				}
			});
			notes.add("renamed " + name + ": a Vulkan GLSL type, uniform block, newer built-in or C++/Metal keyword");
		}
	}

	/** Whether {@code id} names something the pack declares (not a use, not a builtin). */
	private static boolean declares(Identifier id) {
		ASTNode parent = id.getParent();
		return parent instanceof DeclarationMember || parent instanceof FunctionParameter
				|| parent instanceof FunctionPrototype
				|| parent instanceof StructDeclarator
				|| parent instanceof StructSpecifier;
	}

	// ------------------------------------------------------------------ struct varyings

	/**
	 * Struct-typed varyings, which vanilla's pipeline builder does not accept, become one varying per member
	 * ({@code aeth_v_<name>_<member>...}, the declaration's interpolation qualifiers kept). The pack keeps a global of
	 * the struct type under its own name, so its code is untouched: the writing stage copies the members out after the
	 * pack's main, the reading stage copies them in before it. Both stages derive the same names, so they match.
	 * Programs with geometry or tessellation stages are left alone (their per-vertex arrays would need more).
	 */
	private void structVaryings() {
		if (trees.containsKey(PatchShaderType.GEOMETRY) || trees.containsKey(PatchShaderType.TESS_CONTROL) || trees.containsKey(PatchShaderType.TESS_EVAL)) {
			return;
		}
		each((type, tree, root) -> {
			if (type != PatchShaderType.VERTEX && type != PatchShaderType.FRAGMENT) {
				return;
			}
			Map<String, StructSpecifier> structs = new HashMap<>();
			for (StructSpecifier s : root.nodeIndex.get(StructSpecifier.class)) {
				if (s.getName() != null) {
					structs.putIfAbsent(s.getName().getName(), s);
				}
			}
			StorageType direction = type == PatchShaderType.VERTEX ? StorageType.OUT : StorageType.IN;
			for (Global g : globals(tree)) {
				if (!has(g.qualifier(), direction) || arrayLength(g) != 0) {
					continue;
				}
				String structName = typeName(g.specifier());
				if (!structs.containsKey(structName)) {
					continue;
				}
				List<String[]> leaves = new ArrayList<>(); // {type, path, flat name, array suffix}
				structLeaves(structs, structName, g.name(), "aeth_v_" + g.name(), new HashSet<>(), leaves);
				StringBuilder interpolation = new StringBuilder();
				for (String word : print(g.qualifier()).replaceAll("layout\\s*\\([^)]*\\)", " ").trim().split("\\s+")) {
					if (!word.isEmpty() && !word.equals("in") && !word.equals("out") && !word.equals("varying")) {
						interpolation.append(word).append(' ');
					}
				}
				String io = direction == StorageType.OUT ? "out " : "in ";
				List<String> declarations = new ArrayList<>();
				if (g.specifier() instanceof StructSpecifier inline) {
					declarations.add(print(inline) + ";"); // declared on the varying itself: keep the type
				}
				declarations.add(structName + " " + g.name() + ";");
				for (String[] leaf : leaves) {
					declarations.add(interpolation + io + leaf[0] + " " + leaf[2] + leaf[3] + ";");
					if (direction == StorageType.OUT) {
						after(type).add(leaf[2] + " = " + leaf[1] + ";");
					} else {
						before(type).add(leaf[1] + " = " + leaf[2] + ";");
					}
				}
				replaceDeclaration(tree, root, g.node(), declarations.toArray(String[]::new));
				notes.add("struct varying " + g.name() + " split into " + leaves.size() + " varyings");
			}
		});
	}

	/**
	 * Metal: arrays of matrices as varyings, which Metal's stage interfaces cannot hold, become one matrix varying per
	 * element ({@code aeth_v_<name>_<i>}, the declaration's interpolation qualifiers kept). The pack keeps a global array
	 * under its own name: the writing stage copies the elements out after the pack's main, the reading stage copies them
	 * in before it. Programs with geometry or tessellation stages are left alone, as for struct varyings.
	 */
	private void matrixArrayVaryings() {
		if (trees.containsKey(PatchShaderType.GEOMETRY) || trees.containsKey(PatchShaderType.TESS_CONTROL) || trees.containsKey(PatchShaderType.TESS_EVAL)) {
			return;
		}
		each((type, tree, root) -> {
			if (type != PatchShaderType.VERTEX && type != PatchShaderType.FRAGMENT) {
				return;
			}
			StorageType direction = type == PatchShaderType.VERTEX ? StorageType.OUT : StorageType.IN;
			for (Global g : globals(tree)) {
				String matrix = typeName(g.specifier());
				int length = arrayLength(g);
				if (!has(g.qualifier(), direction) || length <= 0 || !(matrix.startsWith("mat") || matrix.startsWith("dmat"))) {
					continue;
				}
				StringBuilder interpolation = new StringBuilder();
				for (String word : print(g.qualifier()).replaceAll("layout\\s*\\([^)]*\\)", " ").trim().split("\\s+")) {
					if (!word.isEmpty() && !word.equals("in") && !word.equals("out") && !word.equals("varying")) {
						interpolation.append(word).append(' ');
					}
				}
				String io = direction == StorageType.OUT ? "out " : "in ";
				List<String> declarations = new ArrayList<>();
				declarations.add(matrix + " " + g.name() + "[" + length + "];");
				for (int i = 0; i < length; i++) {
					String element = "aeth_v_" + g.name() + "_" + i;
					declarations.add(interpolation + io + matrix + " " + element + ";");
					if (direction == StorageType.OUT) {
						after(type).add(element + " = " + g.name() + "[" + i + "];");
					} else {
						before(type).add(g.name() + "[" + i + "] = " + element + ";");
					}
				}
				replaceDeclaration(tree, root, g.node(), declarations.toArray(String[]::new));
				notes.add("matrix array varying " + g.name() + " split into " + length + " varyings (Metal)");
			}
		});
	}

	/** The non-struct members under {@code struct}, depth first: {type, access path, flat name, array suffix}. */
	private static void structLeaves(Map<String, StructSpecifier> structs, String struct, String path, String flat, Set<String> visiting,
			List<String[]> out) {
		if (!visiting.add(struct)) {
			return;
		}
		for (StructMember member : structs.get(struct).getStructBody().getMembers()) {
			String type = typeName(member.getType().getTypeSpecifier());
			for (var declarator : member.getDeclarators()) {
				String name = declarator.getName().getName();
				int length = arrayLength(declarator.getArraySpecifier());
				if (structs.containsKey(type) && length == 0) {
					structLeaves(structs, type, path + "." + name, flat + "_" + name, visiting, out);
				} else {
					out.add(new String[]{type, path + "." + name, flat + "_" + name, length > 0 ? "[" + length + "]" : ""});
				}
			}
		}
		visiting.remove(struct);
	}

	// ------------------------------------------------------------------ one declarator per declaration

	private void splitDeclarators(TranslationUnit tree, Root root) {
		for (int i = 0; i < tree.getChildren().size(); i++) {
			if (!(tree.getChildren().get(i) instanceof DeclarationExternalDeclaration d) || !(d.getDeclaration() instanceof TypeAndInitDeclaration decl)
					|| decl.getMembers().size() < 2) {
				continue;
			}
			String type = print(decl.getType());
			List<String> split = new ArrayList<>();
			for (DeclarationMember member : decl.getMembers()) {
				split.add(type + " " + print(member) + ";");
			}
			replaceDeclaration(tree, root, d, split.toArray(String[]::new));
			i += split.size() - 1;
		}
	}

	// ------------------------------------------------------------------ const declarations that are not constant

	/** Built-in constants (every other {@code gl_} name is a built-in variable). */
	private static final Pattern BUILTIN_CONSTANT = Pattern.compile("gl_(Max|Min)\\w+|gl_WorkGroupSize");
	/** Built-in lookups and derivatives: never constant expressions, whatever their arguments. */
	private static final Pattern NON_CONSTANT_BUILTIN = Pattern.compile(
			"texture\\w*|texelFetch\\w*|shadow[12]D\\w*|image(Load|Store|Size|Samples|Atomic\\w+)|atomic\\w+|dFd\\w+|fwidth\\w*|interpolateAt\\w+|noise[1-4]");

	/**
	 * {@code const} declarations (global or local) whose initialiser is not a constant expression lose {@code const}:
	 * an initialiser that reads a variable (a uniform, an input, a plain global, a parameter, a non-const local), calls a
	 * function of the pack or a texture lookup or derivative, or reads a const that lost it. OpenGL drivers that take
	 * such a const treat it as a read-only variable, which is what it becomes; the compiler refuses the const.
	 * Constructors, other built-ins and literals keep it.
	 */
	private void demoteNonConstant(TranslationUnit tree, Root root) {
		Set<String> globalVariables = new HashSet<>();
		Map<String, TypeAndInitDeclaration> globalConsts = new HashMap<>();
		for (ExternalDeclaration e : tree.getChildren()) {
			if (!(e instanceof DeclarationExternalDeclaration d)) {
				continue;
			}
			if (d.getDeclaration() instanceof TypeAndInitDeclaration decl) {
				boolean constant = has(decl.getType().getTypeQualifier(), StorageType.CONST);
				for (DeclarationMember member : decl.getMembers()) {
					if (constant) {
						globalConsts.put(member.getName().getName(), decl);
					} else {
						globalVariables.add(member.getName().getName());
					}
				}
			} else if (d.getDeclaration() instanceof InterfaceBlockDeclaration block) {
				if (block.getVariableName() != null) {
					globalVariables.add(block.getVariableName().getName());
				} else {
					for (StructMember member : block.getStructBody().getMembers()) {
						member.getDeclarators().forEach(declarator -> globalVariables.add(declarator.getName().getName()));
					}
				}
			}
		}
		Set<String> functions = new HashSet<>();
		for (FunctionDefinition f : root.nodeIndex.get(FunctionDefinition.class)) {
			functions.add(f.getFunctionPrototype().getName().getName());
		}
		// Per const declaration: whether its initialiser reads something non-constant, and the consts it reads.
		Map<TypeAndInitDeclaration, Set<TypeAndInitDeclaration>> reads = new IdentityHashMap<>();
		Set<TypeAndInitDeclaration> demoted = Collections.newSetFromMap(new IdentityHashMap<>());
		Map<FunctionDefinition, Map<String, TypeAndInitDeclaration>> localsByFunction = new IdentityHashMap<>();
		for (ReferenceExpression ref : root.nodeIndex.get(ReferenceExpression.class)) {
			TypeAndInitDeclaration decl = constInitialised(ref);
			if (decl == null) {
				continue;
			}
			String name = ref.getIdentifier().getName();
			FunctionDefinition function = decl.getAncestor(FunctionDefinition.class);
			if (function != null) {
				Map<String, TypeAndInitDeclaration> locals = localsByFunction.computeIfAbsent(function, VulkanLowering::localDeclarations);
				if (locals.containsKey(name)) {
					TypeAndInitDeclaration local = locals.get(name);
					if (local == null || !has(local.getType().getTypeQualifier(), StorageType.CONST)) {
						demoted.add(decl); // a parameter or a variable
					} else if (local != decl) {
						reads.computeIfAbsent(decl, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(local);
					}
					continue;
				}
			}
			TypeAndInitDeclaration global = globalConsts.get(name);
			if (global != null) {
				if (global != decl) {
					reads.computeIfAbsent(decl, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(global);
				}
			} else if (globalVariables.contains(name) || name.startsWith("gl_") && !BUILTIN_CONSTANT.matcher(name).matches()) {
				demoted.add(decl);
			}
		}
		for (FunctionCallExpression call : root.nodeIndex.get(FunctionCallExpression.class)) {
			Identifier fn = call.getFunctionName();
			if (fn == null) {
				continue;
			}
			TypeAndInitDeclaration decl = constInitialised(call);
			if (decl != null && (functions.contains(fn.getName()) || NON_CONSTANT_BUILTIN.matcher(fn.getName()).matches())) {
				demoted.add(decl);
			}
		}
		boolean moved = !demoted.isEmpty();
		while (moved) {
			moved = false;
			for (Map.Entry<TypeAndInitDeclaration, Set<TypeAndInitDeclaration>> e : reads.entrySet()) {
				if (!demoted.contains(e.getKey()) && e.getValue().stream().anyMatch(demoted::contains)) {
					demoted.add(e.getKey());
					moved = true;
				}
			}
		}
		for (TypeAndInitDeclaration decl : demoted) {
			TypeQualifier qualifier = decl.getType().getTypeQualifier();
			for (TypeQualifierPart part : new ArrayList<>(qualifier.getParts())) {
				if (part instanceof StorageQualifier storage && storage.storageType == StorageType.CONST) {
					part.detachAndDelete();
				}
			}
			if (qualifier.getParts().isEmpty()) {
				qualifier.detachAndDelete();
			}
			notes.add("const dropped (initialiser not constant): " + decl.getMembers().stream().map(m -> m.getName().getName()).toList());
		}
	}

	/** The const declaration whose initialiser holds {@code node}, or null. */
	private static @Nullable TypeAndInitDeclaration constInitialised(ASTNode node) {
		ExpressionInitializer initializer = node.getAncestor(ExpressionInitializer.class);
		TypeAndInitDeclaration decl = initializer == null ? null : initializer.getAncestor(TypeAndInitDeclaration.class);
		return decl != null && has(decl.getType().getTypeQualifier(), StorageType.CONST) ? decl : null;
	}

	/** A function's parameters (mapped to null) and local declarations by name. */
	private static Map<String, TypeAndInitDeclaration> localDeclarations(FunctionDefinition function) {
		Map<String, TypeAndInitDeclaration> out = new HashMap<>();
		for (FunctionParameter parameter : function.getFunctionPrototype().getParameters()) {
			if (parameter.getName() != null) {
				out.put(parameter.getName().getName(), null);
			}
		}
		List<DeclarationMember> members = new ArrayList<>();
		collect(function.getBody(), DeclarationMember.class, members);
		for (DeclarationMember member : members) {
			if (member.getParent() instanceof TypeAndInitDeclaration decl) {
				out.putIfAbsent(member.getName().getName(), decl);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ world programs

	/** The LOD mod's own uniform blocks and vertex layout (see WorldInterface.prepareLod / prepareLodGeneric). */
	private void lodInterface(PatchShaderType type, TranslationUnit tree, Root root) {
		if (p.lodVariant() == LoweringParameters.LodVariant.TERRAIN) {
			for (Global g : globals(tree)) {
				if (g.name().equals("modelOffset") && has(g.qualifier(), StorageType.UNIFORM) && typeName(g.specifier()).equals("vec3")) {
					// The camera-relative origin packs read: the mod's per-buffer origin minus the camera.
					List<Identifier> uses = new ArrayList<>(root.identifierIndex.get("modelOffset"));
					replaceDeclaration(tree, root, g.node(), "layout(std140) uniform vertUniqueUniformBlock { vec3 aeth_lodOrigin; };",
							"uniform vec3 aeth_lodCamera;");
					List<Expression> references = new ArrayList<>();
					for (Identifier id : uses) {
						if (id.getParent() instanceof ReferenceExpression ref) {
							references.add(ref);
						}
					}
					Root.replaceExpressionsConcurrent(t, references, "(aeth_lodOrigin - aeth_lodCamera)");
					break;
				}
			}
			return;
		}
		// Generic objects: the mod's per-group block replaces these loose uniforms; per-instance inputs are constants.
		Set<String> blockMembers = Set.of("uOffsetChunk", "uOffsetSubChunk", "uCameraPosChunk", "uCameraPosSubChunk", "uSkyLight", "uBlockLight");
		for (Global g : globals(tree)) {
			if (has(g.qualifier(), StorageType.UNIFORM) && blockMembers.contains(g.name())) {
				g.node().detachAndDelete();
			}
		}
		if (type != PatchShaderType.VERTEX) {
			return;
		}
		Map<String, String> constants = Map.of("aScale", "const vec3 aScale = vec3(1.0);", "aTranslateChunk", "const ivec3 aTranslateChunk = ivec3(0);",
				"aTranslateSubChunk", "const vec3 aTranslateSubChunk = vec3(0.0);");
		for (Global g : globals(tree)) {
			if (has(g.qualifier(), StorageType.IN) && constants.containsKey(g.name())) {
				replaceDeclaration(tree, root, g.node(), constants.get(g.name()));
			}
		}
		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS, """
				layout(std140) uniform vertUniformBlock {
					ivec3 uOffsetChunk;
					vec3 uOffsetSubChunk;
					ivec3 uCameraPosChunk;
					vec3 uCameraPosSubChunk;
					mat4 aeth_lodProjectionMvm;
					int uSkyLight;
					int uBlockLight;
					float aeth_lodNorthShading;
					float aeth_lodSouthShading;
					float aeth_lodEastShading;
					float aeth_lodWestShading;
					float aeth_lodTopShading;
					float aeth_lodBottomShading;
				};""");
	}

	/**
	 * Renames a global sampler and the references that resolve to it. A function with a parameter or local of the same
	 * name keeps its own. When {@code to} is already a declared sampler, {@code from}'s declaration goes.
	 */
	private void renameSampler(TranslationUnit tree, Root root, String from, String to) {
		Global declared = null;
		boolean targetExists = false;
		for (Global g : globals(tree)) {
			if (has(g.qualifier(), StorageType.UNIFORM) && isSampler(g.specifier())) {
				if (g.name().equals(from)) {
					declared = g;
				} else if (g.name().equals(to)) {
					targetExists = true;
				}
			}
		}
		if (declared == null) {
			return;
		}
		Map<FunctionDefinition, Boolean> shadowed = new IdentityHashMap<>();
		for (Identifier id : new ArrayList<>(root.identifierIndex.get(from))) {
			if (id == declared.member().getName()) {
				continue;
			}
			FunctionDefinition function = id.getAncestor(FunctionDefinition.class);
			if (function != null && shadowed.computeIfAbsent(function, f -> declaresLocal(f, from))) {
				continue;
			}
			id.setName(to);
		}
		if (targetExists) {
			declared.node().detachAndDelete();
		} else {
			declared.member().getName().setName(to);
		}
	}

	/** Whether {@code function} declares a parameter or local variable named {@code name}. */
	private static boolean declaresLocal(FunctionDefinition function, String name) {
		for (FunctionParameter parameter : function.getFunctionPrototype().getParameters()) {
			if (parameter.getName() != null && parameter.getName().getName().equals(name)) {
				return true;
			}
		}
		List<DeclarationMember> members = new ArrayList<>();
		collect(function.getBody(), DeclarationMember.class, members);
		for (DeclarationMember member : members) {
			if (member.getName().getName().equals(name)) {
				return true;
			}
		}
		return false;
	}

	/** Every node of {@code type} under {@code node}, through the root's node index (filtered by ancestry). */
	private static <N extends ASTNode> void collect(ASTNode node, Class<N> type, List<N> out) {
		for (N candidate : node.getRoot().nodeIndex.get(type)) {
			if (candidate.hasAncestor(node)) {
				out.add(candidate);
			}
		}
	}


	/**
	 * The chunk mesh's compact vertex ({@code ChunkMeshFormat}): position, texture and lightmap coordinates are packed
	 * integers, so the inputs the pack's code reads ({@code aeth_Position}, {@code aeth_UV0}, {@code aeth_UV2},
	 * {@code aeth_Color}) become globals decoded from them. The position is section-relative (20 bits per axis over
	 * [-8, 24)); the texture coordinate was stored one step towards its quad's centre with the step's direction in bit 15,
	 * and is moved back to within a sub-texel of where it was; the lightmap coordinate was stored 8 higher.
	 */
	private void chunkVertex(TranslationUnit tree, Root root) {
		if (p.attributes().get(ChunkMeshFormat.POSITION) != GpuFormat.RG32_UINT) {
			return;
		}
		Map<String, String> decoded = Map.of(
				"aeth_Position", "vec3:vec3(((uvec3(a_Position.x) >> uvec3(0u, 10u, 20u)) & 1023u) << 10u | ((uvec3(a_Position.y) >> uvec3(0u, 10u, 20u)) & 1023u))"
						+ " * (32.0 / 1048576.0) - 8.0",
				"aeth_UV0", "vec2:vec2(a_TexCoord & 32767u) * (1.0 / 32768.0) + mix(vec2(-1.0), vec2(1.0), bvec2(a_TexCoord >> 15u)) * " + CHUNK_TEXTURE_STEP,
				"aeth_UV2", "ivec2:ivec2(a_LightAndData.xy) - 8",
				"aeth_Color", "vec4:a_Color");
		Set<String> inputs = Set.of(ChunkMeshFormat.POSITION, ChunkMeshFormat.COLOR, ChunkMeshFormat.TEXTURE, ChunkMeshFormat.LIGHT_AND_DATA);
		for (Global g : globals(tree)) {
			if (inputs.contains(g.name()) && has(g.qualifier(), StorageType.IN)) {
				g.node().detachAndDelete();
			}
		}
		for (Global g : globals(tree)) {
			String rule = decoded.get(g.name());
			if (rule == null || !has(g.qualifier(), StorageType.IN) || arrayLength(g) != 0) {
				continue;
			}
			int colon = rule.indexOf(':');
			String type = typeName(g.specifier());
			replaceDeclaration(tree, root, g.node(), type + " " + g.name() + " = "
					+ GlslTypes.convert("(" + rule.substring(colon + 1) + ")", rule.substring(0, colon), type) + ";");
		}
		// At the top, ahead of every global initialised from them.
		tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "in uvec2 " + ChunkMeshFormat.POSITION + ";",
				"in vec4 " + ChunkMeshFormat.COLOR + ";", "in uvec2 " + ChunkMeshFormat.TEXTURE + ";", "in uvec4 " + ChunkMeshFormat.LIGHT_AND_DATA + ";");
	}

	/**
	 * What the chunk renderer's own shader adds back after undoing the stored step: one step (1/32768) less a sub-texel
	 * margin, which keeps the coordinate inside its texel for a 4096-texel atlas at 8 bits of sub-texel precision (and
	 * further inside for smaller atlases).
	 */
	private static final String CHUNK_TEXTURE_STEP = "(1.0 / 32768.0 - 1.0 / 1048576.0)";

	/**
	 * Vertex inputs adapted to the vertex format: missing ones become globals holding OpenGL's default attribute value;
	 * ones the format provides with fewer components or another base type are read under the element name as the
	 * format's own type, and the pack's code reads a converted global.
	 */
	private void vertexAttributes(TranslationUnit tree, Root root) {
		for (Global g : globals(tree)) {
			if (!has(g.qualifier(), StorageType.IN) || g.name().startsWith("gl_")) {
				continue;
			}
			String name = g.name();
			String type = typeName(g.specifier());
			int length = arrayLength(g);
			GpuFormat format = p.attributes().get(name);
			if (format == null) {
				notes.add("attribute " + name + " not in the vertex format: default value");
				String value = length > 0 ? type + "[" + length + "](" + GlslTypes.defaultValue(name, type) + ")" : GlslTypes.defaultValue(name, type);
				replaceDeclaration(tree, root, g.node(), type + " " + name + (length > 0 ? "[" + length + "]" : "") + " = " + value + ";");
				continue;
			}
			if (length != 0) {
				continue;
			}
			String natural = GlslTypes.naturalType(format);
			if (!natural.equals(type) && (GlslTypes.components(type) > format.componentCount() || GlslTypes.baseOf(type) != GlslTypes.baseOf(natural))) {
				notes.add("attribute " + name + " declared " + type + ", provided as " + natural);
				String converted = "aeth_attr_" + name;
				root.rename(name, converted);
				replaceDeclaration(tree, root, g.node(), "in " + natural + " " + name + ";",
						type + " " + converted + " = " + GlslTypes.convert(name, natural, type) + ";");
			}
		}
	}

	/** Every emitted vertex gets the depth remap (gl_Position is undefined after EmitVertex, so converting it is safe). */
	private void geometryClipDepth(TranslationUnit tree, Root root, String clipZ) {
		if (!root.identifierIndex.has("EmitVertex")) {
			return;
		}
		root.rename("EmitVertex", "aeth_emitVertex");
		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
				"void aeth_emitVertex() { gl_Position.z = " + clipZ + "; EmitVertex(); }");
	}

	/**
	 * Packs see OpenGL's depth convention: every {@code gl_FragCoord} reference reads the flipped depth (reads of only
	 * x/y are left alone), and {@code gl_FragDepth} is written through a global flipped back after the pack's main.
	 */
	private void fragmentDepth(PatchShaderType type, TranslationUnit tree, Root root) {
		boolean coord = root.identifierIndex.has("gl_FragCoord");
		if (coord) {
			List<Expression> flipped = new ArrayList<>();
			for (Identifier id : new ArrayList<>(root.identifierIndex.get("gl_FragCoord"))) {
				if (!(id.getParent() instanceof ReferenceExpression ref)) {
					continue;
				}
				if (ref.getParent() instanceof MemberAccessExpression access && access.getMember().getName().matches("[xyrgst]{1,4}")) {
					continue;
				}
				flipped.add(ref);
			}
			Root.replaceExpressionsConcurrent(t, flipped, "vec4(gl_FragCoord.xy, 1.0 - gl_FragCoord.z, gl_FragCoord.w)");
		}
		if (root.identifierIndex.has("gl_FragDepth")) {
			root.rename("gl_FragDepth", "aeth_FragDepth");
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "float aeth_FragDepth;");
			before(type).add("aeth_FragDepth = 1.0 - gl_FragCoord.z;");
			after(type).add("gl_FragDepth = 1.0 - aeth_FragDepth;");
		}
	}

	/** Single-pass glint: the pack's {@code aeth_transforms.TextureMat} reads become the identity (the per-draw matrix scrolls the glint). */
	private void identityTextureMatrix(Root root) {
		List<Expression> reads = new ArrayList<>();
		for (MemberAccessExpression access : root.nodeIndex.get(MemberAccessExpression.class)) {
			if (access.getMember().getName().equals("TextureMat") && access.getOperand() instanceof ReferenceExpression ref
					&& ref.getIdentifier().getName().equals("aeth_transforms")) {
				reads.add(access);
			}
		}
		Root.replaceExpressionsConcurrent(t, reads, "mat4(1.0)");
	}

	/** Texture reads that return the texel (not its size or level): the ones an intensity texture's {@code .rrrr} applies to. */
	private static final Set<String> TEXEL_READS = Set.of("texture", "texture2D", "textureLod", "texture2DLod", "textureGrad", "textureOffset",
			"textureLodOffset", "textureGradOffset", "textureProj", "texture2DProj", "textureProjLod", "texture2DProjLod", "textureProjGrad",
			"textureProjOffset", "textureProjLodOffset", "textureProjGradOffset", "texelFetch", "texelFetchOffset");

	/**
	 * Intensity glyph textures hold one channel: vanilla's text shader reads them {@code .rrrr} (white text, coverage as
	 * alpha). Every texel read of the albedo gets the same swizzle, unless a local of that name shadows it.
	 */
	private void grayscaleAlbedo(Root root) {
		for (FunctionCallExpression call : new ArrayList<>(root.nodeIndex.get(FunctionCallExpression.class))) {
			Identifier fn = call.getFunctionName();
			if (fn == null || !TEXEL_READS.contains(fn.getName()) || call.getParameters().isEmpty() || call.getAncestor(TranslationUnit.class) == null
					|| !"Sampler0".equals(baseName(call.getParameters().get(0)))) {
				continue;
			}
			FunctionDefinition function = call.getAncestor(FunctionDefinition.class);
			if (function != null && declaresLocal(function, "Sampler0")) {
				continue;
			}
			call.replaceByAndDelete(t.parseExpression(root, "(" + print(call) + ").rrrr"));
		}
	}

	/** Set when the glint varyings were added; the fragment term follows once outputs have their attachments. */
	private boolean glint;

	/**
	 * Vanilla's glint coordinate ({@code TextureMat * UV0}) carried to the fragment stage, and its sampler. Only for a
	 * plain vertex + fragment program whose vertex format has UV0 and which does not declare the names itself.
	 */
	private boolean glintVaryings() {
		TranslationUnit vertex = trees.get(PatchShaderType.VERTEX);
		TranslationUnit fragment = trees.get(PatchShaderType.FRAGMENT);
		if (vertex == null || fragment == null || trees.containsKey(PatchShaderType.GEOMETRY) || trees.containsKey(PatchShaderType.TESS_EVAL)) {
			return false;
		}
		for (Global g : globals(fragment)) {
			if (g.name().equals("GlintSampler") || g.name().equals("aeth_glintCoord")) {
				return false;
			}
		}
		Global uv = null;
		for (Global g : globals(vertex)) {
			if (g.name().equals("aeth_UV0")) {
				uv = g;
			}
		}
		if (uv == null && !p.attributes().containsKey("aeth_UV0")
				|| uv != null && (!has(uv.qualifier(), StorageType.IN) || !typeName(uv.specifier()).equals("vec2"))) {
			notes.add("no glint: the program has no UV0 input");
			return false;
		}
		boolean declareUv = uv == null;
		stage(PatchShaderType.VERTEX, (type, tree, root) -> {
			if (declareUv) {
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "in vec2 aeth_UV0;");
			}
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "out vec2 aeth_glintCoord;");
		});
		after(PatchShaderType.VERTEX).add("aeth_glintCoord = (aeth_transforms.TextureMat * vec4(aeth_UV0, 0.0, 1.0)).xy;");
		stage(PatchShaderType.FRAGMENT, (type, tree, root) -> tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"in vec2 aeth_glintCoord;", "uniform sampler2D GlintSampler;"));
		return true;
	}

	/** The glint term added to the output in the glint attachment, as vanilla's item shader adds it. */
	private void glintTerm(TranslationUnit tree) {
		for (Global g : globals(tree)) {
			if (has(g.qualifier(), StorageType.OUT) && layoutValue(g.qualifier(), "location") == p.glint().attachment()
					&& typeName(g.specifier()).equals("vec4") && arrayLength(g) == 0) {
				after(PatchShaderType.FRAGMENT).add("{ vec4 aeth_glint = texture(GlintSampler, aeth_glintCoord); "
						+ g.name() + ".rgb += aeth_glint.rgb * aeth_glint.rgb; }");
				return;
			}
		}
		notes.add("no glint: no vec4 output in the glint attachment");
	}

	private void constantAlphaTest(TranslationUnit tree, Root root, float reference) {
		for (Global g : globals(tree)) {
			if (g.name().equals("aeth_currentAlphaTest") && has(g.qualifier(), StorageType.UNIFORM)) {
				replaceDeclaration(tree, root, g.node(), "const float aeth_currentAlphaTest = " + String.format(Locale.ROOT, "%.8f", reference) + ";");
			}
		}
	}

	// ------------------------------------------------------------------ storage (set 1)

	private void storage(PatchShaderType type, TranslationUnit tree, Root root) {
		LoweringParameters.Storage storage = p.storage();
		// Stages the device lets write storage: elsewhere the storage is declared read-only (a program writing it does not build).
		boolean readOnly = switch (type) {
			case VERTEX, GEOMETRY, TESS_CONTROL, TESS_EVAL -> !storage.vertexWrites();
			case FRAGMENT -> !storage.fragmentWrites();
			default -> false;
		};
		for (Global g : globals(tree)) {
			if (!has(g.qualifier(), StorageType.UNIFORM)) {
				continue;
			}
			if (isImage(g.specifier())) {
				LoweringParameters.Image image = storage.images().get(g.name());
				if (image == null) {
					// Graphics programs: a target bound as an image, at its fixed set-1 binding (compute: set 0, computeBindings).
					Matcher target = TargetImages.matcher(g.name());
					if (storage.targetImages() && p.kind() != LoweringParameters.Kind.COMPUTE && target.matches()) {
						TypeQualifier q = g.qualifier();
						removeLayout(q, "binding");
						setLayout(q, "set", 1);
						setLayout(q, "binding", (TargetImages.shadow(target) ? storage.shadowTargetImageBase() : storage.targetImageBase())
								+ TargetImages.index(target));
						markReadOnly(q, readOnly);
					}
					continue;
				}
				TypeQualifier q = g.qualifier();
				String declared = formatQualifier(q);
				// Another format than the image's: the image's view in that format, when one is configured.
				LoweringParameters.View view = declared != null ? (declared.equals(image.glslFormat()) ? null : image.views().get(declared))
						: image.views().get(imageKind(typeName(g.specifier())));
				if (declared != null && !declared.equals(image.glslFormat()) && view == null) {
					notes.add("image " + g.name() + " declared " + declared + " over a " + image.glslFormat() + " image: no view in that format");
				}
				removeLayout(q, "binding");
				setLayout(q, "set", 1);
				setLayout(q, "binding", view != null ? view.binding() : image.binding());
				if (declared == null) {
					addLayoutName(q, view != null ? view.glslFormat() : image.glslFormat());
				}
				markReadOnly(q, readOnly);
			} else if (isSampler(g.specifier())) {
				Integer binding = storage.samplerBindings().get(g.name());
				if (binding != null) {
					// An integer sampler of an image of another kind reads the image's view of that kind.
					Integer view = storage.samplerViews().getOrDefault(g.name(), Map.of()).get(imageKind(typeName(g.specifier())));
					setLayout(g.qualifier(), "set", 1);
					setLayout(g.qualifier(), "binding", view != null ? view : binding);
				}
			}
		}
		specialiseImageFunctions(tree, root);
		if (storage.buffers()) {
			for (InterfaceBlockDeclaration block : blocks(tree)) {
				TypeQualifier q = block.getTypeQualifier();
				int binding = layoutValue(q, "binding");
				if (has(q, StorageType.BUFFER) && binding >= 0) {
					setLayout(q, "set", 1);
					setLayout(q, "binding", storage.bufferBase() + binding);
					markReadOnly(q, readOnly);
				}
			}
		}
	}

	/**
	 * Functions that take a storage image as a parameter get one copy per combination of global images they are called
	 * with, the image parameters replaced by those globals. The format is part of a SPIR-V image type and a function
	 * parameter cannot carry one, so passing a {@code layout(rgba16f) image3D} to a plain {@code image3D} parameter makes
	 * a module that fails validation. A function also called with something other than a global image stays as written.
	 */
	private void specialiseImageFunctions(TranslationUnit tree, Root root) {
		Set<String> images = new HashSet<>();
		for (Global g : globals(tree)) {
			if (isImage(g.specifier())) {
				images.add(g.name());
			}
		}
		if (images.isEmpty()) {
			return;
		}
		// A copy can pass an image on to another function, which the next round specialises in turn.
		for (int round = 0; round < 8; round++) {
			boolean changed = false;
			for (FunctionDefinition function : new ArrayList<>(root.nodeIndex.get(FunctionDefinition.class))) {
				if (function.getParent() == null) {
					continue;
				}
				changed |= specialise(tree, root, function, images);
			}
			if (!changed) {
				return;
			}
		}
	}

	/** Specialises one function (see {@link #specialiseImageFunctions}); whether anything changed. */
	private boolean specialise(TranslationUnit tree, Root root, FunctionDefinition function, Set<String> images) {
		String name = function.getFunctionPrototype().getName().getName();
		List<FunctionParameter> parameters = function.getFunctionPrototype().getParameters();
		List<Integer> imageParameters = new ArrayList<>();
		for (int i = 0; i < parameters.size(); i++) {
			if (isImage(parameters.get(i).getType().getTypeSpecifier()) && parameters.get(i).getName() != null) {
				imageParameters.add(i);
			}
		}
		if (imageParameters.isEmpty()) {
			return false;
		}
		List<FunctionCallExpression> calls = new ArrayList<>();
		for (FunctionCallExpression call : root.nodeIndex.get(FunctionCallExpression.class)) {
			Identifier fn = call.getFunctionName();
			if (fn == null || !fn.getName().equals(name)) {
				continue;
			}
			for (int index : imageParameters) {
				if (call.getParameters().size() <= index || !(call.getParameters().get(index) instanceof ReferenceExpression ref)
						|| !images.contains(ref.getIdentifier().getName())) {
					return false;
				}
			}
			calls.add(call);
		}
		if (calls.isEmpty()) {
			return false;
		}
		int at = tree.getChildren().indexOf(function);
		Map<String, String> copies = new LinkedHashMap<>();
		for (FunctionCallExpression call : calls) {
			List<String> passed = new ArrayList<>();
			for (int index : imageParameters) {
				passed.add(((ReferenceExpression) call.getParameters().get(index)).getIdentifier().getName());
			}
			String copyName = copies.computeIfAbsent(String.join(",", passed), key -> {
				String copy = name + "_aeth_" + String.join("_", passed);
				FunctionDefinition clone = function.cloneInto(root);
				clone.getFunctionPrototype().getName().setName(copy);
				Map<String, String> renames = new HashMap<>();
				List<FunctionParameter> cloneParameters = clone.getFunctionPrototype().getParameters();
				for (int k = imageParameters.size() - 1; k >= 0; k--) {
					FunctionParameter parameter = cloneParameters.get(imageParameters.get(k));
					renames.put(parameter.getName().getName(), passed.get(k));
					parameter.detachAndDelete();
				}
				List<ReferenceExpression> references = new ArrayList<>();
				collect(clone.getBody(), ReferenceExpression.class, references);
				for (ReferenceExpression reference : references) {
					String to = renames.get(reference.getIdentifier().getName());
					if (to != null) {
						reference.getIdentifier().setName(to);
					}
				}
				tree.getChildren().add(at + 1, clone);
				return copy;
			});
			call.getFunctionName().setName(copyName);
			for (int k = imageParameters.size() - 1; k >= 0; k--) {
				call.getParameters().get(imageParameters.get(k)).detachAndDelete();
			}
		}
		function.detachAndDelete();
		notes.add("image function " + name + " specialised for " + copies.keySet());
		return true;
	}

	/** {@code kind:uint}, {@code kind:int} or {@code kind:float}: what an image or sampler type ({@code uimage2D} ...) reads. */
	private static String imageKind(String type) {
		return type.startsWith("u") ? "kind:uint" : type.startsWith("i") && !type.startsWith("image") ? "kind:int" : "kind:float";
	}

	/** Adds {@code readonly} to a storage declaration when {@code readOnly} (and it has none). */
	private static void markReadOnly(TypeQualifier qualifier, boolean readOnly) {
		if (readOnly && !has(qualifier, StorageType.READONLY)) {
			qualifier.getParts().add(new StorageQualifier(StorageType.READONLY));
		}
	}

	/** The format qualifier among a declaration's layout parts ({@code rgba16f}, {@code r32ui} ...), lower case, or null. */
	private static @Nullable String formatQualifier(TypeQualifier qualifier) {
		for (TypeQualifierPart part : qualifier.getParts()) {
			if (part instanceof LayoutQualifier layout) {
				for (LayoutQualifierPart lp : layout.getParts()) {
					if (lp instanceof NamedLayoutQualifierPart named && named.getExpression() == null) {
						String name = named.getName().getName().toLowerCase(Locale.ROOT);
						if (!name.equals("shared") && !name.equals("packed") && !name.startsWith("std") && !name.equals("row_major")
								&& !name.equals("column_major")) {
							return name;
						}
					}
				}
			}
		}
		return null;
	}

	private static void removeLayout(TypeQualifier qualifier, String name) {
		for (TypeQualifierPart part : qualifier.getParts()) {
			if (part instanceof LayoutQualifier layout) {
				for (LayoutQualifierPart lp : new ArrayList<>(layout.getParts())) {
					if (lp instanceof NamedLayoutQualifierPart named && named.getName().getName().equals(name)) {
						lp.detachAndDelete();
					}
				}
			}
		}
	}

	private static void addLayoutName(TypeQualifier qualifier, String name) {
		for (TypeQualifierPart part : qualifier.getParts()) {
			if (part instanceof LayoutQualifier layout) {
				layout.getParts().add(new NamedLayoutQualifierPart(new Identifier(name)));
				return;
			}
		}
		qualifier.getChildren().add(0, new LayoutQualifier(Stream.of(new NamedLayoutQualifierPart(new Identifier(name)))));
	}

	// ------------------------------------------------------------------ shadow samplers

	/**
	 * {@code sampler2DShadow} globals on the shadow depth maps (single, uniform) keep their type when hardware comparison
	 * is on for a graphics program: renamed to their comparison binding, every lookup stays as written. The rest
	 * (other names, arrays; all of a stage when its function parameters would have to take both kinds; compute
	 * programs) becomes {@code sampler2D} wherever it appears (globals, arrays, function parameters), and every lookup
	 * through one an explicit comparison. Which comparison follows the map a global names; a parameter (whose map is
	 * unknown) gets the filtered one when any map of the pack is filtered.
	 */
	private void shadowSamplers(PatchShaderType type, TranslationUnit tree, Root root) {
		boolean watershadow = false;
		for (Global g : globals(tree)) {
			watershadow |= g.name().equals("watershadow");
		}
		List<Global> hardware = new ArrayList<>();
		List<Global> emulated = new ArrayList<>();
		for (Global g : globals(tree)) {
			if (g.specifier() instanceof BuiltinFixedTypeSpecifier fixed && fixed.type == BuiltinType.SAMPLER2DSHADOW) {
				boolean depthMap = has(g.qualifier(), StorageType.UNIFORM) && arrayLength(g) == 0 && ShadowSampling.depthIndex(g.name(), watershadow) >= 0;
				(depthMap && p.shadow().hardware() && p.kind() != LoweringParameters.Kind.COMPUTE ? hardware : emulated).add(g);
			}
		}
		boolean shadowParameters = false;
		for (FunctionParameter parameter : root.nodeIndex.get(FunctionParameter.class)) {
			shadowParameters |= parameter.getType().getTypeSpecifier() instanceof BuiltinFixedTypeSpecifier fixed && fixed.type == BuiltinType.SAMPLER2DSHADOW;
		}
		if (shadowParameters && !emulated.isEmpty()) {
			// A parameter takes one sampler kind: every comparison of the stage is made in the shader.
			emulated.addAll(hardware);
			hardware.clear();
		}
		for (Global g : hardware) {
			renameSampler(tree, root, g.name(), ShadowSampling.COMPARE_PREFIX + g.name());
		}
		if (!hardware.isEmpty()) {
			notes.add(type + ": hardware shadow comparison on " + hardware.stream().map(Global::name).toList());
		}
		if (emulated.isEmpty() && !hardware.isEmpty()) {
			return; // function parameters keep their comparison type
		}
		Map<String, Boolean> globalsFiltered = new HashMap<>();
		for (Global g : emulated) {
			int map = ShadowSampling.depthIndex(g.name(), watershadow);
			globalsFiltered.put(g.name(), map >= 0 ? p.shadow().filtered(map) : p.shadow().filtered0() || p.shadow().filtered1());
			retype((BuiltinFixedTypeSpecifier) g.specifier());
		}
		Map<FunctionDefinition, Set<String>> parameters = new IdentityHashMap<>();
		for (FunctionParameter parameter : new ArrayList<>(root.nodeIndex.get(FunctionParameter.class))) {
			if (parameter.getType().getTypeSpecifier() instanceof BuiltinFixedTypeSpecifier fixed && fixed.type == BuiltinType.SAMPLER2DSHADOW) {
				retype(fixed);
				FunctionDefinition function = parameter.getAncestor(FunctionDefinition.class);
				if (function != null && parameter.getName() != null) {
					parameters.computeIfAbsent(function, f -> new HashSet<>()).add(parameter.getName().getName());
				}
			}
		}
		if (globalsFiltered.isEmpty() && parameters.isEmpty()) {
			return;
		}
		boolean anyFiltered = p.shadow().filtered0() || p.shadow().filtered1();
		boolean used = false;
		for (FunctionCallExpression call : new ArrayList<>(root.nodeIndex.get(FunctionCallExpression.class))) {
			Identifier fn = call.getFunctionName();
			if (fn == null || !SHADOW_LOOKUPS.containsKey(fn.getName()) || call.getParameters().size() < 2) {
				continue;
			}
			String sampler = baseName(call.getParameters().get(0));
			if (sampler == null) {
				continue;
			}
			FunctionDefinition function = call.getAncestor(FunctionDefinition.class);
			Boolean filtered = null;
			if (function != null && parameters.getOrDefault(function, Set.of()).contains(sampler)) {
				filtered = anyFiltered;
			} else if (globalsFiltered.containsKey(sampler) && (function == null || !declaresLocal(function, sampler))) {
				filtered = globalsFiltered.get(sampler);
			}
			if (filtered == null) {
				continue;
			}
			String name = fn.getName();
			int offsetAt = SHADOW_LOOKUPS.get(name);
			if (name.startsWith("textureGather")) {
				if (call.getParameters().size() < 3) {
					continue;
				}
				String gatherOffset = offsetAt >= 0 && offsetAt < call.getParameters().size() ? print(call.getParameters().get(offsetAt)) : "ivec2(0)";
				call.replaceByAndDelete(t.parseExpression(root, "aeth_shadowGather(" + print(call.getParameters().get(0)) + ", vec2(("
						+ print(call.getParameters().get(1)) + ").xy), float(" + print(call.getParameters().get(2)) + "), ivec2(" + gatherOffset + "))"));
				used = true;
				continue;
			}
			boolean proj = name.startsWith("textureProj");
			String s = print(call.getParameters().get(0));
			String c = print(call.getParameters().get(1));
			String offset = offsetAt >= 0 && offsetAt < call.getParameters().size() ? print(call.getParameters().get(offsetAt)) : "ivec2(0)";
			String helper = (filtered ? "aeth_shadowFiltered" : "aeth_shadowNearest") + (proj ? "Proj" : "");
			String coordinate = proj ? "vec4(" + c + ")" : "vec3((" + c + ").xyz)";
			call.replaceByAndDelete(t.parseExpression(root, helper + "(" + s + ", " + coordinate + ", ivec2(" + offset + "))"));
			used = true;
		}
		if (used) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS, SHADOW_HELPERS);
		}
	}

	private static void retype(BuiltinFixedTypeSpecifier fixed) {
		ArraySpecifier array = fixed.getArraySpecifier();
		BuiltinFixedTypeSpecifier sampler2D = new BuiltinFixedTypeSpecifier(BuiltinType.SAMPLER2D, array == null ? null : array.cloneInto(fixed.getRoot()));
		fixed.replaceByAndDelete(sampler2D);
	}

	/** The variable an expression names ({@code s}, {@code s[i]}), or null. */
	private static @Nullable String baseName(Expression expression) {
		if (expression instanceof ReferenceExpression ref) {
			return ref.getIdentifier().getName();
		}
		if (expression instanceof ArrayAccessExpression access) {
			return baseName(access.getLeft());
		}
		return null;
	}

	// ------------------------------------------------------------------ interface

	/**
	 * Every input the stage before lacks becomes an output of that stage, zeroed: vertex to geometry to fragment, or
	 * vertex to fragment.
	 */
	private void missingVaryings() {
		PatchShaderType previous = null;
		for (PatchShaderType stage : new PatchShaderType[]{PatchShaderType.VERTEX, PatchShaderType.GEOMETRY, PatchShaderType.FRAGMENT}) {
			if (!trees.containsKey(stage)) {
				continue;
			}
			if (previous != null) {
				missingVaryings(previous, stage);
			}
			previous = stage;
		}
	}

	/**
	 * {@code to}'s inputs that {@code from} does not write become {@code from}'s outputs: a vertex stage zeroes them
	 * before the pack's main, a geometry stage before every vertex it emits (its outputs are undefined after each).
	 */
	private void missingVaryings(PatchShaderType from, PatchShaderType to) {
		Set<String> outputs = new HashSet<>();
		for (Global g : globals(trees.get(from))) {
			if (has(g.qualifier(), StorageType.OUT)) {
				outputs.add(g.name());
			}
		}
		List<String> declarations = new ArrayList<>();
		List<String> zeroes = new ArrayList<>();
		for (Global g : globals(trees.get(to))) {
			if (!has(g.qualifier(), StorageType.IN) || g.name().startsWith("gl_") || outputs.contains(g.name())) {
				continue;
			}
			StringBuilder qualifiers = new StringBuilder();
			for (TypeQualifierPart part : g.qualifier().getParts()) {
				if (!(part instanceof StorageQualifier) && !(part instanceof LayoutQualifier)) {
					qualifiers.append(print(part)).append(' ');
				}
			}
			String type = typeName(g.specifier());
			// A geometry stage's inputs are per-vertex arrays; the stage before writes one value.
			int length = perVertex(to, true) ? 0 : arrayLength(g);
			String array = length > 0 ? "[" + length + "]" : "";
			declarations.add(qualifiers + "out " + type + " " + g.name() + array + ";");
			if (length == 0 && g.specifier() instanceof BuiltinNumericTypeSpecifier) {
				zeroes.add(g.name() + " = " + type + "(0);");
			}
			notes.add("varying " + g.name() + " not written by the " + from.name().toLowerCase(Locale.ROOT) + " stage: zero");
		}
		if (declarations.isEmpty()) {
			return;
		}
		stage(from, (type, tree, root) -> tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS, declarations.toArray(String[]::new)));
		if (from == PatchShaderType.VERTEX) {
			before(PatchShaderType.VERTEX).addAll(zeroes);
		} else if (!zeroes.isEmpty()) {
			stage(from, (type, tree, root) -> {
				if (root.identifierIndex.has("EmitVertex")) {
					// Every EmitVertex call (the clip-depth wrapper's too) goes through this one.
					root.rename("EmitVertex", "aeth_emitZeroed");
					tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
							"void aeth_emitZeroed() { " + String.join(" ", zeroes) + " EmitVertex(); }");
				}
			});
		}
	}

	/** A stage interface declaration: a variable or an interface block. */
	/** {@code type}: the declared type name, null for an interface block; {@code array}: declared as an array. */
	private record Port(String name, @Nullable TypeQualifier qualifier, boolean in, int slots, boolean block, @Nullable String type, boolean array) {
	}

	private List<Port> ports(PatchShaderType stage, TranslationUnit tree) {
		List<Port> out = new ArrayList<>();
		Map<String, Integer> structs = structSlots(tree);
		for (ExternalDeclaration e : tree.getChildren()) {
			if (!(e instanceof DeclarationExternalDeclaration d)) {
				continue;
			}
			Declaration declaration = d.getDeclaration();
			if (declaration instanceof TypeAndInitDeclaration decl) {
				TypeQualifier q = decl.getType().getTypeQualifier();
				boolean in = has(q, StorageType.IN);
				if (!in && !has(q, StorageType.OUT)) {
					continue;
				}
				for (DeclarationMember member : decl.getMembers()) {
					String name = member.getName().getName();
					if (name.startsWith("gl_")) {
						continue;
					}
					var arraySpecifier = member.getArraySpecifier() != null ? member.getArraySpecifier() : decl.getType().getTypeSpecifier().getArraySpecifier();
					int length = perVertex(stage, in) ? 1 : Math.max(1, arrayLength(arraySpecifier));
					String type = typeName(decl.getType().getTypeSpecifier());
					out.add(new Port(name, q, in, slots(type, structs) * length, false, type, arraySpecifier != null));
				}
			} else if (declaration instanceof InterfaceBlockDeclaration block) {
				TypeQualifier q = block.getTypeQualifier();
				boolean in = has(q, StorageType.IN);
				if (!in && !has(q, StorageType.OUT) || block.getBlockName().getName().startsWith("gl_")) {
					continue;
				}
				int slots = 0;
				for (StructMember member : block.getStructBody().getMembers()) {
					int per = slots(typeName(member.getType().getTypeSpecifier()), structs);
					for (var declarator : member.getDeclarators()) {
						slots += per * Math.max(1, arrayLength(declarator.getArraySpecifier()));
					}
				}
				int count = perVertex(stage, in) ? 1 : Math.max(1, arrayLength(block.getArraySpecifier()));
				out.add(new Port(block.getBlockName().getName(), q, in, slots * count, true, null, block.getArraySpecifier() != null));
			}
		}
		return out;
	}

	/** Locations a varying of {@code type} takes: built-in types by GLSL's rules, structs the sum of their members. */
	private static int slots(String type, Map<String, Integer> structs) {
		Integer struct = structs.get(type);
		return struct != null ? struct : GlslTypes.slots(type);
	}

	/** Every named struct of {@code tree}: the locations one value of it takes (members in order, arrays multiplied). */
	private static Map<String, Integer> structSlots(TranslationUnit tree) {
		Map<String, StructSpecifier> byName = new HashMap<>();
		for (StructSpecifier s : tree.getRoot().nodeIndex.get(StructSpecifier.class)) {
			if (s.getName() != null) {
				byName.putIfAbsent(s.getName().getName(), s);
			}
		}
		Map<String, Integer> out = new HashMap<>();
		for (String name : byName.keySet()) {
			structSlots(name, byName, out, new HashSet<>());
		}
		return out;
	}

	private static int structSlots(String name, Map<String, StructSpecifier> byName, Map<String, Integer> out, Set<String> visiting) {
		Integer known = out.get(name);
		if (known != null) {
			return known;
		}
		StructSpecifier s = byName.get(name);
		if (s == null || !visiting.add(name)) {
			return GlslTypes.slots(name);
		}
		int total = 0;
		for (StructMember member : s.getStructBody().getMembers()) {
			String type = typeName(member.getType().getTypeSpecifier());
			int per = byName.containsKey(type) ? structSlots(type, byName, out, visiting) : GlslTypes.slots(type);
			for (var declarator : member.getDeclarators()) {
				total += per * Math.max(1, arrayLength(declarator.getArraySpecifier()));
			}
		}
		out.put(name, Math.max(1, total));
		return out.get(name);
	}

	/** Geometry and tessellation inputs (and tessellation-control outputs) are per-vertex arrays: not extra locations. */
	private static boolean perVertex(PatchShaderType stage, boolean in) {
		return stage == PatchShaderType.GEOMETRY && in || stage == PatchShaderType.TESS_EVAL && in || stage == PatchShaderType.TESS_CONTROL;
	}

	private static boolean varying(PatchShaderType stage, Port port) {
		return !(stage == PatchShaderType.VERTEX && port.in()) && !(stage == PatchShaderType.FRAGMENT && !port.in());
	}

	/**
	 * Explicit locations: varyings one per name across all stages (explicit ones reserved first), vertex inputs and
	 * fragment outputs per stage. Interface blocks take one location range by block name.
	 */
	private void locations() {
		Map<String, Integer> varyings = new HashMap<>();
		BitSet varyingUsed = new BitSet();
		for (PatchShaderType stage : GRAPHICS_ORDER) {
			TranslationUnit tree = trees.get(stage);
			if (tree == null) {
				continue;
			}
			for (Port port : ports(stage, tree)) {
				int location = layoutValue(port.qualifier(), "location");
				if (location >= 0 && varying(stage, port)) {
					varyings.putIfAbsent(port.name(), location);
					varyingUsed.set(location, location + port.slots());
				}
			}
		}
		packVaryings(packedVaryings(varyings.keySet(), varyingUsed));
		for (PatchShaderType stage : GRAPHICS_ORDER) {
			if (!trees.containsKey(stage)) {
				continue;
			}
			stage(stage, (type, tree, root) -> {
				BitSet vertexInputs = new BitSet();
				BitSet fragmentOutputs = new BitSet();
				List<Port> ports = ports(type, tree);
				for (Port port : ports) {
					int location = layoutValue(port.qualifier(), "location");
					if (location >= 0 && type == PatchShaderType.VERTEX && port.in()) {
						vertexInputs.set(location, location + port.slots());
					}
					if (location >= 0 && type == PatchShaderType.FRAGMENT && !port.in()) {
						fragmentOutputs.set(location);
					}
				}
				for (Port port : ports) {
					if (port.qualifier() == null || hasLayout(port.qualifier(), "location")) {
						continue;
					}
					int location;
					if (type == PatchShaderType.VERTEX && port.in()) {
						location = allocate(vertexInputs, port.slots());
					} else if (type == PatchShaderType.FRAGMENT && !port.in()) {
						location = allocate(fragmentOutputs, 1);
					} else {
						Integer shared = varyings.get(port.name());
						if (shared == null) {
							shared = allocate(varyingUsed, port.slots());
							varyings.put(port.name(), shared);
						}
						location = shared;
					}
					setLayout(port.qualifier(), "location", location);
				}
				if (root.identifierIndex.has("gl_VertexID")) {
					root.rename("gl_VertexID", "gl_VertexIndex");
				}
				if (root.identifierIndex.has("gl_InstanceID")) {
					root.rename("gl_InstanceID", "gl_InstanceIndex");
				}
			});
		}
	}

	/**
	 * Varying locations a program may use on every device: MoltenVK allows 124 output components per stage (NVIDIA 128),
	 * 7 of them taken by built-ins.
	 */
	private static final int VARYING_LOCATION_BUDGET = 29;

	/**
	 * One location per varying ({@link #locations}) wastes most of each location on a pack passing many scalars (Photon's
	 * sky map: 30 locations for 55 components, over MoltenVK's limit). A vertex-fragment program whose varyings would
	 * not fit {@link #VARYING_LOCATION_BUDGET} gets its small ones packed, as a GL driver packs them: float, int and uint
	 * scalars, vec2 and vec3 of one base type and the same interpolation qualifiers in both stages share a location,
	 * largest first ({@link #packVaryings}: vanilla rejects {@code layout(component)}, so they become swizzles of one
	 * vector varying). Programs that fit keep the plain assignment. Locations are taken from {@code used}.
	 */
	private Map<String, PackedVarying> packedVaryings(Set<String> explicit, BitSet used) {
		for (PatchShaderType stage : trees.keySet()) {
			if (stage != PatchShaderType.VERTEX && stage != PatchShaderType.FRAGMENT) {
				return Map.of();
			}
		}
		Map<String, Port> byName = new LinkedHashMap<>();
		Map<String, String> qualifiers = new HashMap<>();
		for (PatchShaderType stage : new PatchShaderType[]{PatchShaderType.VERTEX, PatchShaderType.FRAGMENT}) {
			TranslationUnit tree = trees.get(stage);
			if (tree == null) {
				continue;
			}
			for (Port port : ports(stage, tree)) {
				if (varying(stage, port) && port.qualifier() != null && !hasLayout(port.qualifier(), "location") && !explicit.contains(port.name())) {
					byName.putIfAbsent(port.name(), port);
					qualifiers.merge(port.name(), interpolation(port.qualifier()), (a, b) -> a + "/" + b);
				}
			}
		}
		int needed = used.cardinality();
		for (Port port : byName.values()) {
			needed += port.slots();
		}
		if (needed <= VARYING_LOCATION_BUDGET) {
			return Map.of();
		}
		Map<String, List<Port>> groups = new LinkedHashMap<>();
		for (Port port : byName.values()) {
			int components = port.block() || port.array() || port.type() == null ? 0 : packedComponents(port.type());
			if (components > 0) {
				groups.computeIfAbsent(baseType(port.type()) + qualifiers.get(port.name()), k -> new ArrayList<>()).add(port);
			}
		}
		Map<String, PackedVarying> out = new HashMap<>();
		for (List<Port> group : groups.values()) {
			group.sort(Comparator.comparingInt((Port p) -> packedComponents(p.type())).reversed());
			List<int[]> bins = new ArrayList<>(); // {location, components used}
			for (Port port : group) {
				int components = packedComponents(port.type());
				int[] bin = bins.stream().filter(b -> b[1] + components <= 4).findFirst().orElse(null);
				if (bin == null) {
					bin = new int[]{allocate(used, 1), 0};
					bins.add(bin);
				}
				out.put(port.name(), new PackedVarying(bin[0], bin[1], components, baseType(port.type())));
				bin[1] += components;
			}
		}
		return out;
	}

	/** Where {@link #packedVaryings} put a varying: its location, first component, size, and base type ({@code f i u}). */
	private record PackedVarying(int location, int component, int components, char base) {
	}

	/**
	 * Rewrites both stages for {@link #packedVaryings}: each packed location becomes one varying {@code aeth_pk<location>}
	 * (a vector of the components used, declared with the qualifiers its varyings share), the packed declarations go, and
	 * every use of one is a swizzle of it ({@code rainbow_amount} -> {@code aeth_pk12.y}), an l-value where the varying
	 * was one. Parameters and locals of the same name are left alone.
	 */
	private void packVaryings(Map<String, PackedVarying> packed) {
		if (packed.isEmpty()) {
			return;
		}
		this.packed = packed;
		Map<Integer, Integer> sizes = packedSizes;
		packed.values().forEach(v -> sizes.merge(v.location(), v.component() + v.components(), Math::max));
		for (PatchShaderType stage : new PatchShaderType[]{PatchShaderType.VERTEX, PatchShaderType.FRAGMENT}) {
			if (!trees.containsKey(stage)) {
				continue;
			}
			stage(stage, (type, tree, root) -> {
				Map<Integer, String> declarations = new java.util.TreeMap<>();
				for (ExternalDeclaration e : new ArrayList<>(tree.getChildren())) {
					if (!(e instanceof DeclarationExternalDeclaration d) || !(d.getDeclaration() instanceof TypeAndInitDeclaration decl)) {
						continue;
					}
					TypeQualifier q = decl.getType().getTypeQualifier();
					boolean in = has(q, StorageType.IN);
					// Vertex outputs and fragment inputs: the only varyings of a vertex-fragment program.
					if (q == null || (type == PatchShaderType.VERTEX ? !has(q, StorageType.OUT) : !in)) {
						continue;
					}
					for (DeclarationMember member : new ArrayList<>(decl.getMembers())) {
						PackedVarying v = packed.get(member.getName().getName());
						if (v == null) {
							continue;
						}
						int size = sizes.get(v.location());
						String vector = size == 1 ? (v.base() == 'f' ? "float" : v.base() == 'i' ? "int" : "uint")
								: (v.base() == 'f' ? "" : String.valueOf(v.base())) + "vec" + size;
						declarations.putIfAbsent(v.location(), "layout(location = " + v.location() + ") " + interpolationText(q) + (in ? "in " : "out ")
								+ vector + " aeth_pk" + v.location() + ";");
						if (decl.getMembers().size() == 1) {
							e.detachAndDelete();
						} else {
							member.detachAndDelete();
						}
					}
				}
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS, declarations.values().toArray(String[]::new));
			});
		}
		notes.add("packed " + packed.size() + " varyings into " + sizes.size() + " locations (over the device's varying budget)");
	}

	/** {@link #packVaryings}: the packed varyings, and the components used at each of their locations. */
	private Map<String, PackedVarying> packed = Map.of();
	private final Map<Integer, Integer> packedSizes = new HashMap<>();

	/** Every use of a {@link #packVaryings packed varying} becomes its swizzle (run once all code is in the trees). */
	private void packedReferences() {
		if (packed.isEmpty()) {
			return;
		}
		for (PatchShaderType stage : new PatchShaderType[]{PatchShaderType.VERTEX, PatchShaderType.FRAGMENT}) {
			if (!trees.containsKey(stage)) {
				continue;
			}
			stage(stage, (type, tree, root) -> {
				for (Map.Entry<String, PackedVarying> entry : packed.entrySet()) {
					PackedVarying v = entry.getValue();
					String access = "aeth_pk" + v.location() + (packedSizes.get(v.location()) == 1 ? ""
							: "." + "xyzw".substring(v.component(), v.component() + v.components()));
					// A function with a parameter or local of the same name keeps its own.
					Map<FunctionDefinition, Boolean> shadowed = new IdentityHashMap<>();
					for (Identifier id : new ArrayList<>(root.identifierIndex.get(entry.getKey()))) {
						FunctionDefinition function = id.getAncestor(FunctionDefinition.class);
						if (id.getParent() instanceof ReferenceExpression reference
								&& (function == null || !shadowed.computeIfAbsent(function, fn -> declaresLocal(fn, entry.getKey())))) {
							reference.replaceByAndDelete(t.parseExpression(root, access));
						}
					}
				}
			});
		}
	}

	/** The interpolation and auxiliary qualifiers of a varying as GLSL text ({@code "flat "}, {@code "centroid "} ...). */
	private static String interpolationText(TypeQualifier qualifier) {
		return interpolation(qualifier).toLowerCase(Locale.ROOT);
	}

	/** The base type of a scalar or vector type name: {@code f}, {@code i} or {@code u}. */
	private static char baseType(String type) {
		return type.startsWith("i") ? 'i' : type.startsWith("u") ? 'u' : 'f';
	}

	/** Components of a varying type {@link #packedVaryings} packs, else 0. */
	private static int packedComponents(String type) {
		return switch (type) {
			case "float", "int", "uint" -> 1;
			case "vec2", "ivec2", "uvec2" -> 2;
			case "vec3", "ivec3", "uvec3" -> 3;
			default -> 0;
		};
	}

	/** The interpolation and auxiliary storage qualifiers of a varying, as a key ({@code flat}, {@code centroid} ...). */
	private static String interpolation(TypeQualifier qualifier) {
		StringBuilder key = new StringBuilder();
		for (TypeQualifierPart part : qualifier.getParts()) {
			if (part instanceof InterpolationQualifier i) {
				key.append(i.interpolationType).append(' ');
			} else if (part instanceof StorageQualifier st && (st.storageType == StorageType.CENTROID || st.storageType == StorageType.SAMPLE)) {
				key.append(st.storageType).append(' ');
			}
		}
		return key.toString();
	}

	private static int allocate(BitSet used, int slots) {
		int at = 0;
		while (true) {
			int free = used.nextClearBit(at);
			int nextUsed = used.nextSetBit(free);
			if (nextUsed < 0 || nextUsed - free >= slots) {
				used.set(free, free + slots);
				return free;
			}
			at = nextUsed;
		}
	}

	/** Fragment output location i -> its attachment; an output with none becomes a plain global (writes dropped). */
	private void remapOutputs(TranslationUnit tree, Root root) {
		List<Integer> map = p.outputAttachments();
		for (Global g : globals(tree)) {
			TypeQualifier q = g.qualifier();
			if (!has(q, StorageType.OUT) || g.name().startsWith("gl_")) {
				continue;
			}
			int location = layoutValue(q, "location");
			int attachment = location >= 0 && location < map.size() ? map.get(location) : -1;
			if (attachment >= 0) {
				setLayout(q, "location", attachment);
			} else {
				int length = arrayLength(g);
				replaceDeclaration(tree, root, g.node(), typeName(g.specifier()) + " " + g.name() + (length > 0 ? "[" + length + "]" : "") + ";");
			}
		}
	}

	// ------------------------------------------------------------------ reach pruning

	private static final Pattern WORD = Pattern.compile("[A-Za-z_]\\w*");

	/**
	 * Functions no path from {@code main} calls (directly, through other functions, or from a global initialiser) are
	 * removed, and with them every uniform sampler, image and loose uniform of a plain type that nothing reachable reads
	 * any more: the declarations alone would still become descriptors and block members, bound and uploaded per draw.
	 * Reads counted are those in reachable functions, in any global declaration (initialisers run on entry) and in the
	 * entry wrapper's own code. Only applied when something can be removed; nothing the program executes changes.
	 */
	private void pruneUnreached(PatchShaderType type, TranslationUnit tree, Root root) {
		Map<String, List<FunctionDefinition>> definitions = new HashMap<>();
		for (FunctionDefinition f : root.nodeIndex.get(FunctionDefinition.class)) {
			definitions.computeIfAbsent(f.getFunctionPrototype().getName().getName(), k -> new ArrayList<>()).add(f);
		}
		if (!definitions.containsKey("main")) {
			return;
		}
		Set<String> wrapper = new HashSet<>();
		for (String code : before.getOrDefault(type, List.of())) {
			WORD.matcher(code).results().forEach(m -> wrapper.add(m.group()));
		}
		for (String code : after.getOrDefault(type, List.of())) {
			WORD.matcher(code).results().forEach(m -> wrapper.add(m.group()));
		}
		// Call graph by name (overloads share a node: kept or removed together).
		Map<String, Set<String>> calls = new HashMap<>();
		Deque<String> queue = new ArrayDeque<>();
		queue.add("main");
		for (String name : definitions.keySet()) {
			if (wrapper.contains(name)) {
				queue.add(name);
			}
			for (Identifier id : root.identifierIndex.get(name)) {
				if (id.getParent() instanceof FunctionPrototype) {
					continue;
				}
				FunctionDefinition caller = id.getAncestor(FunctionDefinition.class);
				if (caller == null) {
					queue.add(name);
				} else {
					calls.computeIfAbsent(caller.getFunctionPrototype().getName().getName(), k -> new HashSet<>()).add(name);
				}
			}
		}
		Set<String> reached = new HashSet<>();
		while (!queue.isEmpty()) {
			String name = queue.poll();
			if (reached.add(name)) {
				queue.addAll(calls.getOrDefault(name, Set.of()));
			}
		}
		List<Global> unread = new ArrayList<>();
		for (Global g : globals(tree)) {
			if (!has(g.qualifier(), StorageType.UNIFORM) || wrapper.contains(g.name())
					|| !(isSampler(g.specifier()) || isImage(g.specifier()) || g.specifier() instanceof BuiltinNumericTypeSpecifier)) {
				continue;
			}
			boolean read = false;
			for (Identifier id : root.identifierIndex.get(g.name())) {
				if (id == g.member().getName()) {
					continue;
				}
				FunctionDefinition function = id.getAncestor(FunctionDefinition.class);
				if (function == null || reached.contains(function.getFunctionPrototype().getName().getName())) {
					read = true;
					break;
				}
			}
			if (!read) {
				unread.add(g);
			}
		}
		if (unread.isEmpty()) {
			return;
		}
		int removed = 0;
		for (Map.Entry<String, List<FunctionDefinition>> e : definitions.entrySet()) {
			if (!reached.contains(e.getKey())) {
				e.getValue().forEach(ASTNode::detachAndDelete);
				removed += e.getValue().size();
			}
		}
		for (Global g : unread) {
			g.node().detachAndDelete();
		}
		notes.add(type + ": " + unread.size() + " unreached uniforms/samplers and " + removed + " unreached functions removed: "
				+ unread.stream().map(Global::name).toList());
	}

	// ------------------------------------------------------------------ uniform block

	/**
	 * Loose uniforms of plain types (scalars, vectors, matrices and arrays of them) move into one std140 block shared by
	 * every stage; members keep their names. Declared initial values are handed to the engine.
	 */
	private UniformBlock.Layout uniformBlock() {
		Map<String, String[]> declared = new TreeMap<>(); // name -> {type, length, initializer}; sorted: a canonical order
		each((type, tree, root) -> {
			for (Global g : globals(tree)) {
				if (!has(g.qualifier(), StorageType.UNIFORM) || !(g.specifier() instanceof BuiltinNumericTypeSpecifier)) {
					continue;
				}
				String typeName = typeName(g.specifier());
				int length = arrayLength(g);
				if (!BLOCK_TYPES.contains(typeName) || length < 0) {
					continue;
				}
				Initializer init = g.member().getInitializer();
				String initializer = length == 0 && init instanceof ExpressionInitializer e ? print(e.getExpression()) : null;
				declared.putIfAbsent(g.name(), new String[]{typeName, Integer.toString(length), initializer});
				g.node().detachAndDelete();
			}
		});
		List<UniformBlock.Member> members = new ArrayList<>();
		int offset = 0;
		for (Map.Entry<String, String[]> e : declared.entrySet()) {
			String type = e.getValue()[0];
			int length = Integer.parseInt(e.getValue()[1]);
			int align = length > 0 ? 16 : GlslTypes.std140Alignment(type);
			int size = length > 0 ? GlslTypes.std140ArrayStride(type) * length : GlslTypes.std140Size(type);
			offset = (offset + align - 1) / align * align;
			members.add(new UniformBlock.Member(type, e.getKey(), length, offset, e.getValue()[2]));
			offset += size;
		}
		UniformBlock.Layout layout = new UniformBlock.Layout(members, (offset + 15) / 16 * 16);
		if (!members.isEmpty()) {
			each((type, tree, root) -> {
				// One layout in every stage. A stage with its own global of a member's name (stages are separate
				// namespaces in OpenGL: one stage's uniform, another's variable) gets the member under a hidden name;
				// Vulkan matches block members by offset, not name.
				StringBuilder block = new StringBuilder("layout(std140) uniform ").append(BLOCK_NAME).append(" {\n");
				for (UniformBlock.Member m : members) {
					String name = declaresGlobal(root, m.name()) ? "aeth_hidden_" + m.name() : m.name();
					block.append('\t').append(m.type()).append(' ').append(name);
					if (m.arrayLength() > 0) {
						block.append('[').append(m.arrayLength()).append(']');
					}
					block.append(";\n");
				}
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, block.append("};").toString());
			});
		}
		return layout;
	}

	/** Whether the stage declares {@code name} at global scope (a variable or a function), not as a local. */
	private static boolean declaresGlobal(Root root, String name) {
		for (Identifier id : root.identifierIndex.get(name)) {
			ASTNode parent = id.getParent();
			if (parent instanceof FunctionPrototype
					|| declares(id) && !(parent instanceof FunctionParameter) && id.getAncestor(FunctionDefinition.class) == null) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ entry wrapper, compute bindings, version

	/** The pack's main runs inside a new main with this stage's before/after code. */
	private void wrapMain(PatchShaderType type, TranslationUnit tree, Root root) {
		List<String> pre = before.getOrDefault(type, List.of());
		List<String> post = after.getOrDefault(type, List.of());
		if (pre.isEmpty() && post.isEmpty()) {
			return;
		}
		String inner = "aeth_packMain";
		boolean found = false;
		for (FunctionDefinition f : new ArrayList<>(root.nodeIndex.get(FunctionDefinition.class))) {
			if (f.getFunctionPrototype().getName().getName().equals("main")) {
				f.getFunctionPrototype().getName().setName(inner);
				found = true;
			}
		}
		if (!found) {
			return;
		}
		for (FunctionDeclaration f : new ArrayList<>(root.nodeIndex.get(FunctionDeclaration.class))) {
			if (f.getFunctionPrototype().getName().getName().equals("main")) {
				f.getFunctionPrototype().getName().setName(inner);
			}
		}
		StringBuilder main = new StringBuilder("void main() {\n");
		pre.forEach(s -> main.append('\t').append(s).append('\n'));
		main.append('\t').append(inner).append("();\n");
		post.forEach(s -> main.append('\t').append(s).append('\n'));
		tree.parseAndInjectNode(t, ASTInjectionPoint.END, main.append('}').toString());
	}

	/** Compute programs: set 0 holds the uniform block (binding 0) and every sampled texture (bindings 1..n). */
	private void computeBindings(TranslationUnit tree, Root root) {
		for (InterfaceBlockDeclaration block : blocks(tree)) {
			if (block.getBlockName().getName().equals(BLOCK_NAME)) {
				setLayout(block.getTypeQualifier(), "set", 0);
				setLayout(block.getTypeQualifier(), "binding", 0);
			}
		}
		int binding = 1;
		for (Global g : globals(tree)) {
			if (has(g.qualifier(), StorageType.UNIFORM) && isSampler(g.specifier()) && layoutValue(g.qualifier(), "set") != 1 || targetImage(g)) {
				removeLayout(g.qualifier(), "binding");
				setLayout(g.qualifier(), "set", 0);
				setLayout(g.qualifier(), "binding", binding++);
			}
		}
	}

	/**
	 * A colour or shadow colour target bound as an image in a compute program ({@code colorimgN}, {@code shadowcolorimgN}):
	 * set 0 after the samplers, listed with them (its GLSL image type tells the engine to bind a storage image).
	 */
	private boolean targetImage(Global g) {
		return p.kind() == LoweringParameters.Kind.COMPUTE && has(g.qualifier(), StorageType.UNIFORM) && isImage(g.specifier())
				&& TargetImages.matcher(g.name()).matches() && layoutValue(g.qualifier(), "set") != 1;
	}

	/**
	 * GLSL {@link #VERSION_FLOOR} core at least: everything the lowering emits (explicit locations and bindings, images,
	 * storage buffers, block locations) is core there, and the pack gets the newest language rules (implicit
	 * conversions, local consts initialised from variables), which OpenGL drivers commonly accept at older versions. The
	 * pack's own {@code #extension} directives move right after {@code #version} (the language only takes them before
	 * any declaration), with {@code require} relaxed to {@code enable}: an extension the compiler does not know is then a
	 * warning, not a failed program (the pack's code compiled on the OpenGL drivers it was written for).
	 */
	private void versionAndExtensions(PatchShaderType type, TranslationUnit tree, Root root) {
		VersionStatement version = tree.getVersionStatement();
		if (version.version.number < VERSION_FLOOR) {
			version.version = Version.fromNumber(VERSION_FLOOR);
		}
		version.profile = Profile.CORE;
		List<ExternalDeclaration> add = new ArrayList<>();
		for (ExternalDeclaration e : new ArrayList<>(tree.getChildren())) {
			if (e instanceof ExtensionDirective extension) {
				// glsl-transformer names the require behaviour DEBUG (its token is the lexer's require).
				ExtensionDirective.ExtensionBehavior behavior = extension.behavior == ExtensionDirective.ExtensionBehavior.DEBUG
						? ExtensionDirective.ExtensionBehavior.ENABLE : extension.behavior;
				add.add(new ExtensionDirective(extension.getName(), behavior));
				e.detachAndDelete();
			}
		}
		tree.injectNodes(ASTInjectionPoint.BEFORE_ALL, add);
	}

	/** Uniform blocks and samplers each stage declares (set-1 ones excluded), in stage and declaration order. */
	private void collectResources(Map<String, UniformType> resources, Map<String, String> samplerTypes) {
		for (TranslationUnit tree : trees.values()) {
			for (ExternalDeclaration e : tree.getChildren()) {
				if (e instanceof DeclarationExternalDeclaration d && d.getDeclaration() instanceof InterfaceBlockDeclaration block
						&& has(block.getTypeQualifier(), StorageType.UNIFORM) && !hasLayout(block.getTypeQualifier(), "push_constant")) {
					resources.put(block.getBlockName().getName(), UniformType.UNIFORM_BUFFER);
				}
			}
			for (Global g : globals(tree)) {
				if (has(g.qualifier(), StorageType.UNIFORM) && isSampler(g.specifier()) && layoutValue(g.qualifier(), "set") != 1) {
					String type = typeName(g.specifier());
					resources.put(g.name(), type.endsWith("Buffer") ? UniformType.TEXEL_BUFFER : UniformType.COMBINED_IMAGE_SAMPLER);
					samplerTypes.putIfAbsent(g.name(), type);
				} else if (targetImage(g)) {
					samplerTypes.putIfAbsent(g.name(), typeName(g.specifier())); // compute only: bound by the engine, not vanilla
				}
			}
		}
	}
}
