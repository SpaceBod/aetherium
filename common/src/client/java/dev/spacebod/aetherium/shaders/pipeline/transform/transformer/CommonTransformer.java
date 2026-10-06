package dev.spacebod.aetherium.shaders.pipeline.transform.transformer;

import dev.spacebod.aetherium.core.Optimisations;
import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.ArrayAccessExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier.BuiltinType.TypeKind;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.TypeSpecifier;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.match.AutoHintedMatcher;
import io.github.douira.glsl_transformer.ast.query.match.Matcher;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.ast.transform.Template;
import io.github.douira.glsl_transformer.parser.ParseShape;
import io.github.douira.glsl_transformer.util.Type;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTest;
import dev.spacebod.aetherium.shaders.gl.shader.ShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.Parameters;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.VanillaParameters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class CommonTransformer {
	public static final AutoHintedMatcher<Expression> glTextureMatrix0 = new AutoHintedMatcher<>(
		"gl_TextureMatrix[0]", ParseShape.EXPRESSION);
	public static final AutoHintedMatcher<Expression> glTextureMatrix1 = new AutoHintedMatcher<>(
		"gl_TextureMatrix[1]", ParseShape.EXPRESSION);
	public static final AutoHintedMatcher<Expression> glTextureMatrix2 = new AutoHintedMatcher<>(
		"gl_TextureMatrix[2]", ParseShape.EXPRESSION);
	public static final Matcher<ExternalDeclaration> sampler = new Matcher<>(
		"uniform Type name;", ParseShape.EXTERNAL_DECLARATION) {
		{
			markClassedPredicateWildcard("type",
				pattern.getRoot().identifierIndex.getUnique("Type").getAncestor(TypeSpecifier.class),
				BuiltinFixedTypeSpecifier.class,
				specifier -> specifier.type.kind == TypeKind.SAMPLER);
			markClassWildcard("name*",
				pattern.getRoot().identifierIndex.getUnique("name").getAncestor(DeclarationMember.class));
		}
	};

	private static final AutoHintedMatcher<Expression> glFragDataI = new AutoHintedMatcher<>(
		"gl_FragData[index]", ParseShape.EXPRESSION) {
		{
			markClassedPredicateWildcard("index",
				pattern.getRoot().identifierIndex.getUnique("index").getAncestor(ReferenceExpression.class),
				LiteralExpression.class,
				literalExpression -> literalExpression.isInteger() && literalExpression.getInteger() >= 0);
		}
	};

	private static final Template<ExternalDeclaration> fragDataDeclaration = Template
		.withExternalDeclaration("layout (location = __index) out vec4 __name;");
	private static final Template<ExternalDeclaration> inputDeclarationTemplate = Template.withExternalDeclaration(
		"uniform int __name;");
	private static final Template<ExternalDeclaration> inputDeclarationTemplateLayout = Template.withExternalDeclaration(
		"layout (location = __index) uniform int __name;");

	static {
		fragDataDeclaration.markLocalReplacement("__index", ReferenceExpression.class);
		fragDataDeclaration.markIdentifierReplacement("__name");
	}

	static {
		inputDeclarationTemplate.markLocalReplacement(
			inputDeclarationTemplate.getSourceRoot().nodeIndex.getOne(StorageQualifier.class));
		inputDeclarationTemplate.markLocalReplacement(
			inputDeclarationTemplate.getSourceRoot().nodeIndex.getOne(BuiltinNumericTypeSpecifier.class));
		inputDeclarationTemplate.markIdentifierReplacement("__name");

		inputDeclarationTemplateLayout.markLocalReplacement("__index", ReferenceExpression.class);
		inputDeclarationTemplateLayout.markLocalReplacement(
			inputDeclarationTemplateLayout.getSourceRoot().nodeIndex.getOne(StorageQualifier.class));
		inputDeclarationTemplateLayout.markLocalReplacement(
			inputDeclarationTemplateLayout.getSourceRoot().nodeIndex.getOne(BuiltinNumericTypeSpecifier.class));
		inputDeclarationTemplateLayout.markIdentifierReplacement("__name");
	}

	/**
	 * The matrices packs read that are derived from the bound model-view and projection, declared only when the shader
	 * uses them (a global initialiser runs in every invocation, fragment shaders included). The normal matrix is the
	 * inverse transpose of the model-view's 3x3 part as its cofactor matrix over the determinant: the same value as
	 * {@code mat3(transpose(inverse(mv)))} for an affine model-view, at a fraction of a 4x4 inverse.
	 */
	static void injectDerivedMatrices(ASTParser t, TranslationUnit tree, Root root, String modelView) {
		injectDerivedMatrices(t, tree, root, modelView, false);
	}

	/**
	 * {@code terrain}: the model-view is terrain's, the same for every draw of a frame (the view, or the shadow map's
	 * view in the shadow pass); with {@code shaders.cpu_matrices} the normal matrix and the inverse model-view are then
	 * uniforms the engine computes once per frame ({@code aeth_TerrainNormalMat}, {@code aeth_TerrainModelViewInverse}).
	 */
	static void injectDerivedMatrices(ASTParser t, TranslationUnit tree, Root root, String modelView, boolean terrain) {
		if (terrain && Optimisations.isEnabled("shaders.cpu_matrices")) {
			if (root.identifierIndex.has("aeth_NormalMat")) {
				root.rename("aeth_NormalMat", "aeth_TerrainNormalMat");
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform mat3 aeth_TerrainNormalMat;");
			}
			if (root.identifierIndex.has("aeth_ModelViewMatInverse")) {
				root.rename("aeth_ModelViewMatInverse", "aeth_TerrainModelViewInverse");
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform mat4 aeth_TerrainModelViewInverse;");
			}
		}
		if (root.identifierIndex.has("aeth_NormalMat")) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
				"mat3 aeth_normalMatrix(mat4 m) { vec3 c0 = m[0].xyz; vec3 c1 = m[1].xyz; vec3 c2 = m[2].xyz;" +
					" vec3 r0 = cross(c1, c2); return mat3(r0, cross(c2, c0), cross(c0, c1)) / dot(c0, r0); }",
				"mat3 aeth_NormalMat = aeth_normalMatrix(" + modelView + ");");
		}
		if (root.identifierIndex.has("aeth_ProjMatInverse")) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
				"mat4 aeth_ProjMatInverse = inverse(aeth_ProjMatGL);");
		}
		if (root.identifierIndex.has("aeth_ModelViewMatInverse")) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
				"mat4 aeth_ModelViewMatInverse = inverse(" + modelView + ");");
		}
	}

	static void renameFunctionCall(Root root, String oldName, String newName) {
		root.process(root.identifierIndex.getStream(oldName)
				.filter(id -> id.getParent() instanceof FunctionCallExpression),
			id -> id.setName(newName));
	}

	static void renameAndWrapShadow(ASTParser t, Root root, String oldName, String innerName) {
		root.process(root.identifierIndex.getStream(oldName)
				.filter(id -> id.getParent() instanceof FunctionCallExpression),
			id -> {
				FunctionCallExpression functionCall = (FunctionCallExpression) id.getParent();
				functionCall.getFunctionName().setName(innerName);
				FunctionCallExpression wrapper = (FunctionCallExpression) t.parseExpression(root, "vec4()");
				functionCall.replaceBy(wrapper);
				wrapper.getParameters().add(functionCall);
			});
	}

	public static void patchMultiTexCoord3(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		Parameters parameters) {
		if (parameters.type.glShaderType == ShaderType.VERTEX
			&& root.identifierIndex.has("gl_MultiTexCoord3")
			&& !root.identifierIndex.has("mc_midTexCoord")) {
			// gl_MultiTexCoord3 is a legacy alias of mc_midTexCoord. Skipped when mc_midTexCoord is already
			// referenced, since an existing declaration can't be handled robustly.
			root.rename("gl_MultiTexCoord3", "mc_midTexCoord");
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"attribute vec4 mc_midTexCoord;");
		}
	}

	// Packs declare mc_Entity as a float, but TERRAIN binds it as an integer attribute, so it reads
	// back as zero. Swap the pack's declaration for the integer type and hand it a converted alias.
	public static void patchIntegerAttribute(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		String name,
		String alias,
		int components) {
		if (components == 0) {
			return;
		}

		Type dimension = Type.BOOL;
		for (Identifier id : root.identifierIndex.get(name)) {
			TypeAndInitDeclaration initDeclaration = (TypeAndInitDeclaration) id.getAncestor(
				2, 0, TypeAndInitDeclaration.class::isInstance);
			if (initDeclaration == null) {
				continue;
			}
			DeclarationExternalDeclaration declaration = (DeclarationExternalDeclaration) initDeclaration.getAncestor(
				1, 0, DeclarationExternalDeclaration.class::isInstance);
			if (declaration == null) {
				continue;
			}
			if (initDeclaration.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier numeric) {
				dimension = numeric.type;

				declaration.detachAndDelete();
				initDeclaration.detachAndDelete();
				id.detachAndDelete();
				break;
			}
		}

		// The pack never declared it, so there is nothing to redeclare.
		if (dimension == Type.BOOL) {
			return;
		}

		root.replaceReferenceExpressions(t, name, alias);

		String declaration = switch (dimension) {
			case FLOAT32 -> "float " + alias + " = float(" + name + ".x);";
			case INT32 -> "int " + alias + " = " + name + ".x;";
			case F32VEC2 -> "vec2 " + alias + " = vec2(" + swizzle(name, components, 2, true) + ");";
			case F32VEC3 -> "vec3 " + alias + " = vec3(" + swizzle(name, components, 3, true) + ");";
			case F32VEC4 -> "vec4 " + alias + " = vec4(" + swizzle(name, components, 4, true) + ");";
			case I32VEC2 -> "ivec2 " + alias + " = ivec2(" + swizzle(name, components, 2, false) + ");";
			case I32VEC3 -> "ivec3 " + alias + " = ivec3(" + swizzle(name, components, 3, false) + ");";
			case I32VEC4 -> "ivec4 " + alias + " = ivec4(" + swizzle(name, components, 4, false) + ");";
			default -> throw new IllegalStateException(
				"Got an invalid format for " + name + " (" + dimension.getCompactName() + ").");
		};

		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, declaration);
		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
			"in ivec" + components + " " + name + ";");
	}

	// Pads the attribute out to the width the pack declared: zero, with one in the fourth slot (OpenGL's default).
	private static String swizzle(String name, int available, int declared, boolean floating) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < declared; i++) {
			if (i > 0) {
				builder.append(", ");
			}
			if (i < available) {
				builder.append(name).append('.').append("xyzw".charAt(i));
			} else if (i == 3) {
				builder.append(floating ? "1.0" : "1");
			} else {
				builder.append(floating ? "0.0" : "0");
			}
		}
		return builder.toString();
	}

	public static void upgradeStorageQualifiers(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		Parameters parameters) {
		for (StorageQualifier qualifier : root.nodeIndex.get(StorageQualifier.class)) {
			if (qualifier.storageType == StorageType.ATTRIBUTE) {
				qualifier.storageType = StorageType.IN;
			} else if (qualifier.storageType == StorageType.VARYING) {
				qualifier.storageType = parameters.type.glShaderType == ShaderType.VERTEX
					? StorageType.OUT
					: StorageType.IN;
			}
		}
	}

	public static void transform(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		Parameters parameters,
		boolean core) {
		// The gl_PerVertex member form of gl_FogFragCoord is not renamed.
		root.rename("gl_FogFragCoord", "aeth_FogFragCoord");

		// Vertex to fragment. A geometry stage in between does not forward it: the fragment stage then reads the zero
		// the lowering gives inputs the stage before lacks.
		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"out float aeth_FogFragCoord;");
			tree.prependMainFunctionBody(t, "aeth_FogFragCoord = 0.0f;");
		} else if (parameters.type.glShaderType == ShaderType.FRAGMENT) {
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"in float aeth_FogFragCoord;");
		}

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			// gl_FrontColor becomes a local nothing reads: packs that write it (SEUS v11, Renewed) never read it back.
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"vec4 aeth_FrontColor;");
			root.rename("gl_FrontColor", "aeth_FrontColor");
		}

		if (parameters.type.glShaderType == ShaderType.FRAGMENT) {
			// gl_FragColor is an alias of gl_FragData[0].
			if (root.identifierIndex.has("gl_FragColor")) {
				root.replaceReferenceExpressions(t, "gl_FragColor", "gl_FragData[0]");
			}

			if (root.identifierIndex.has("gl_TexCoord")) {
				root.rename("gl_TexCoord", "aeth_vTexCoords");
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "in vec4 aeth_vTexCoords[3];");
			}

			if (root.identifierIndex.has("gl_Color")) {
				root.rename("gl_Color", "aeth_vColor");
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "in vec4 aeth_vColor;");
			}

			// gl_FragData[i] with a literal index -> aeth_FragDatai
			List<Expression> replaceExpressions = new ArrayList<>();
			List<Long> replaceIndexes = new ArrayList<>();
			Set<Long> replaceIndexesSet = new HashSet<>();
			for (Identifier id : root.identifierIndex.get("gl_FragData")) {
				ArrayAccessExpression accessExpression = id.getAncestor(ArrayAccessExpression.class);
				if (accessExpression == null || !glFragDataI.matchesExtract(accessExpression)) {
					continue;
				}
				replaceExpressions.add(accessExpression);
				long index = glFragDataI.getNodeMatch("index", LiteralExpression.class).getInteger();
				replaceIndexes.add(index);
				replaceIndexesSet.add(index);
			}
			for (int i = 0; i < replaceExpressions.size(); i++) {
				replaceExpressions.get(i).replaceByAndDelete(
					new ReferenceExpression(new Identifier("aeth_FragData" + replaceIndexes.get(i))));
			}
			for (long index : replaceIndexesSet) {
				tree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS,
					fragDataDeclaration.getInstanceFor(root,
						new LiteralExpression(Type.INT32, index),
						new Identifier("aeth_FragData" + index)));
			}

			// insert alpha test for aeth_FragData0 in the fragment shader
			if ((parameters.getAlphaTest() != AlphaTest.ALWAYS && !core) && replaceIndexesSet.contains(0L)) {
				if (!root.identifierIndex.has("aeth_currentAlphaTest")) {
					tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform float aeth_currentAlphaTest;");
				}
				tree.appendMainFunctionBody(t,
					parameters.getAlphaTest().toExpression("aeth_FragData0.a", "aeth_currentAlphaTest", "	"));
			}
		}

		if (parameters.type.glShaderType == ShaderType.VERTEX || parameters.type.glShaderType == ShaderType.FRAGMENT) {
			upgradeStorageQualifiers(t, tree, root, parameters);
		}

		// Rename non-call uses of `texture` and `gcolor` to `gtexture`, but only where they are declared as
		// samplers; if both are, their declarations are merged into one.
		RenameTargetResult gcolorResult = getGtextureRenameTargets("gcolor", root);
		RenameTargetResult textureResult = getGtextureRenameTargets("texture", root);
		DeclarationMember samplerDeclarationMember = null;
		Stream<Identifier> targets = Stream.empty();
		if (gcolorResult != null) {
			samplerDeclarationMember = gcolorResult.samplerDeclarationMember;
			targets = Stream.concat(targets, gcolorResult.targets);
		}
		if (textureResult != null) {
			// if two exist, remove the member from the second one
			if (samplerDeclarationMember == null) {
				samplerDeclarationMember = textureResult.samplerDeclarationMember;
			} else {
				DeclarationMember secondDeclarationMember = textureResult.samplerDeclarationMember;
				if (((TypeAndInitDeclaration) secondDeclarationMember.getParent()).getMembers().size() == 1) {
					textureResult.samplerDeclaration.detachAndDelete();
				} else {
					secondDeclarationMember.detachAndDelete();
				}
			}
			targets = Stream.concat(targets, textureResult.targets);
		}
		if (samplerDeclarationMember != null) {
			samplerDeclarationMember.getName().setName("gtexture");
		}
		root.process(targets.filter(id -> !(id.getParent() instanceof FunctionCallExpression)),
			id -> id.setName("gtexture"));

		// gl_Fog must be valid in every pass, composites included: SEUS v11 reads gl_Fog.color.
		root.rename("gl_Fog", "aethInt_Fog");
		if (!(parameters instanceof VanillaParameters)) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"uniform float aeth_FogDensity;",
				"uniform float aeth_FogStart;",
				"uniform float aeth_FogEnd;",
				"uniform vec4 aeth_FogColor;",
				"struct aeth_FogParameters {" +
					"vec4 color;" +
					"float density;" +
					"float start;" +
					"float end;" +
					"float scale;" +
					"};",
				"aeth_FogParameters aethInt_Fog = aeth_FogParameters(aeth_FogColor, aeth_FogDensity, aeth_FogStart, aeth_FogEnd, 1.0 / (aeth_FogEnd - aeth_FogStart));");
		}

		// Legacy sampling functions under their core names.
		renameFunctionCall(root, "texture2D", "texture");
		renameFunctionCall(root, "texture3D", "texture");
		renameFunctionCall(root, "texture2DLod", "textureLod");
		renameFunctionCall(root, "texture3DLod", "textureLod");
		renameFunctionCall(root, "texture2DProj", "textureProj");
		renameFunctionCall(root, "texture3DProj", "textureProj");
		renameFunctionCall(root, "texture2DGrad", "textureGrad");
		renameFunctionCall(root, "texture2DGradARB", "textureGrad");
		renameFunctionCall(root, "texture3DGrad", "textureGrad");
		renameFunctionCall(root, "texelFetch2D", "texelFetch");
		renameFunctionCall(root, "texelFetch3D", "texelFetch");
		renameFunctionCall(root, "textureSize2D", "textureSize");
		renameFunctionCall(root, "texture1D", "texture");
		renameFunctionCall(root, "texture1DLod", "textureLod");
		renameFunctionCall(root, "textureCube", "texture");
		renameFunctionCall(root, "textureCubeLod", "textureLod");
		renameFunctionCall(root, "texture2DProjLod", "textureProjLod");
		renameFunctionCall(root, "texture2DOffset", "textureOffset");
		renameFunctionCall(root, "texture2DLodOffset", "textureLodOffset");

		// The legacy comparisons return a vec4, the core ones a float.
		renameAndWrapShadow(t, root, "shadow2D", "texture");
		renameAndWrapShadow(t, root, "shadow2DLod", "textureLod");
		renameAndWrapShadow(t, root, "shadow2DProj", "textureProj");
		renameAndWrapShadow(t, root, "shadow2DProjLod", "textureProjLod");
	}

	private static RenameTargetResult getGtextureRenameTargets(String name, Root root) {
		List<Identifier> gtextureTargets = new ArrayList<>();
		DeclarationExternalDeclaration samplerDeclaration = null;
		DeclarationMember samplerDeclarationMember = null;

		// collect targets until we find out if the name is a sampler or not
		for (Identifier id : root.identifierIndex.get(name)) {
			gtextureTargets.add(id);
			if (samplerDeclaration != null) {
				continue;
			}
			DeclarationExternalDeclaration externalDeclaration = (DeclarationExternalDeclaration) id.getAncestor(
				3, 0, DeclarationExternalDeclaration.class::isInstance);
			if (externalDeclaration == null) {
				continue;
			}
			if (sampler.matchesExtract(externalDeclaration)) {
				// one of the declaration's members must be this name
				boolean foundNameMatch = false;
				for (DeclarationMember member : sampler
					.getNodeMatch("name*", DeclarationMember.class)
					.getAncestor(TypeAndInitDeclaration.class).getMembers()) {
					if (member.getName().getName().equals(name)) {
						foundNameMatch = true;
					}
				}
				if (!foundNameMatch) {
					return null;
				}

				samplerDeclaration = externalDeclaration;
				samplerDeclarationMember = id.getAncestor(DeclarationMember.class);

				// the declaration itself is renamed separately
				gtextureTargets.removeLast();
				continue;
			}
			// declared, but not as a sampler: leave the name alone
			return null;
		}
		if (samplerDeclaration == null) {
			// never declared as a sampler: leave the name alone
			return null;
		}
		return new RenameTargetResult(samplerDeclaration, samplerDeclarationMember, gtextureTargets.stream());
	}

	public static void applyIntelHd4000Workaround(Root root) {
		// Intel HD 4000/2500 driver bug: a call to ftransform() makes the driver assume the compatibility
		// built-in (even in a core shader that defines its own ftransform) and reserve attribute location 0
		// for gl_Vertex, so binding our vertex format then fails to link. Renaming sidesteps it.
		// Runs after our ftransform is injected, so the definition and all calls are renamed together.
		root.rename("ftransform", "aeth_ftransform");
	}

	public static void replaceGlMultiTexCoordBounded(
		ASTParser t,
		Root root,
		int minimum,
		int maximum) {
		root.replaceReferenceExpressions(t,
			root.getPrefixIdentifierIndex().prefixQueryFlat("gl_MultiTexCoord")
				.filter(id -> {
					int index = Integer.parseInt(id.getName().substring("gl_MultiTexCoord".length()));
					return index >= minimum && index <= maximum;
				}),
			"vec4(0.0, 0.0, 0.0, 1.0)");
	}

	public static void addIfNotExists(Root root, ASTParser t, TranslationUnit tree, String name, Type type,
									  StorageType storageType) {
		if (root.externalDeclarationIndex.getStream(name)
			.noneMatch((entry) -> entry.declaration() instanceof DeclarationExternalDeclaration)) {
			tree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS, inputDeclarationTemplate.getInstanceFor(root,
				new StorageQualifier(storageType),
				new BuiltinNumericTypeSpecifier(type),
				new Identifier(name)));
		}
	}

	public static void addIfNotExists(Root root, ASTParser t, TranslationUnit tree, String name, Type type,
									  StorageType storageType, int location) {
		if (root.externalDeclarationIndex.getStream(name)
			.noneMatch((entry) -> entry.declaration() instanceof DeclarationExternalDeclaration)) {
			tree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS, inputDeclarationTemplateLayout.getInstanceFor(root,
				new LiteralExpression(Type.INT32, location),
				new StorageQualifier(storageType),
				new BuiltinNumericTypeSpecifier(type),
				new Identifier(name)));
		}
	}

	private record RenameTargetResult(DeclarationExternalDeclaration samplerDeclaration,
									  DeclarationMember samplerDeclarationMember, Stream<Identifier> targets) {
	}
}
