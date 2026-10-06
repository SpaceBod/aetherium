package dev.spacebod.aetherium.shaders.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.util.Type;
import dev.spacebod.aetherium.shaders.pipeline.transform.PatchShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.VanillaParameters;

import static dev.spacebod.aetherium.shaders.pipeline.transform.transformer.CommonTransformer.addIfNotExists;

public class VanillaCoreTransformer {
	public static void transform(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		VanillaParameters parameters) {

		if (parameters.inputs.hasOverlay()) {
			if (!parameters.inputs.isText()) {
				EntityPatcher.patchOverlayColor(t, tree, root, parameters);
			}
			EntityPatcher.patchEntityId(t, tree, root, parameters);
		}

		// 26.3: vanilla's own block names and layouts, so the render pass binds them (see VanillaInterface).
		VanillaInterface.inject(t, tree, parameters);

		CommonTransformer.transform(t, tree, root, parameters, true);
		root.rename("alphaTestRef", "aeth_currentAlphaTest");
		root.replaceReferenceExpressions(t, "modelViewMatrix", "aeth_transforms.ModelViewMat");
		root.replaceReferenceExpressions(t, "gl_ModelViewMatrix", "aeth_transforms.ModelViewMat");
		root.rename("modelViewMatrixInverse", "aeth_ModelViewMatInverse");
		root.rename("gl_ModelViewMatrixInverse", "aeth_ModelViewMatInverse");
		root.replaceReferenceExpressions(t, "projectionMatrix", "aeth_ProjMatGL");
		root.replaceReferenceExpressions(t, "gl_ProjectionMatrix", "aeth_ProjMatGL");
		root.rename("projectionMatrixInverse", "aeth_ProjMatInverse");
		root.rename("gl_ProjectionMatrixInverse", "aeth_ProjMatInverse");
		root.replaceReferenceExpressions(t, "textureMatrix", "aeth_transforms.TextureMat");

		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix0, "aeth_transforms.TextureMat");
		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix1,
			"mat4(vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0), vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0))");
		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix2,
			"mat4(vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0), vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0))");
		root.rename("normalMatrix", "aeth_NormalMat");
		root.rename("gl_NormalMatrix", "aeth_NormalMat");
		// 26.3: derived in the shader from the bound matrices (the model-view matrix changes per draw for entities).
		String modelView = VanillaInterface.modelView(parameters);
		CommonTransformer.injectDerivedMatrices(t, tree, root, modelView, !parameters.source.dynamic());
		root.replaceReferenceExpressions(t, "chunkOffset", "aeth_transforms.ModelOffset");

		CommonTransformer.upgradeStorageQualifiers(t, tree, root, parameters);

		if (parameters.type == PatchShaderType.VERTEX) {
			// Line programs take this path even when written for the compatibility profile, which calls ftransform() and
			// reads gl_ModelViewProjectionMatrix.
			String offset = parameters.source.dynamic() ? "aeth_transforms.ModelOffset" : "vec3(0.0)";
			root.replaceReferenceExpressions(t, "gl_ModelViewProjectionMatrix", "(aeth_ProjMatGL * " + modelView + ")");
			for (String name : new String[]{"ftransform", "aeth_ftransform"}) {
				if (root.identifierIndex.has(name)) {
					tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
						"vec4 " + name + "() { return aeth_ProjMatGL * (" + modelView + " * vec4(aeth_Position + " + offset + ", 1.0)); }");
				}
			}
			CommonTransformer.patchIntegerAttribute(t, tree, root, "mc_Entity", "aeth_Entity", parameters.inputs.getEntityComponents());

			root.replaceReferenceExpressions(t, "gl_Vertex", "vec4(aeth_Position, 1.0)");
			root.rename("vaPosition", "aeth_Position");
			if (parameters.inputs.hasColor()) {
				root.replaceReferenceExpressions(t, "vaColor", "aeth_Color * aeth_transforms.ColorModulator");
				root.replaceReferenceExpressions(t, "gl_Color", "aeth_Color * aeth_transforms.ColorModulator");
			} else {
				root.replaceReferenceExpressions(t, "vaColor", "aeth_transforms.ColorModulator");
				root.replaceReferenceExpressions(t, "gl_Color", "aeth_transforms.ColorModulator");
			}
			root.rename("vaNormal", "aeth_Normal");
			root.rename("gl_Normal", "aeth_Normal");
			root.rename("vaUV0", "aeth_UV0");
			root.replaceReferenceExpressions(t, "gl_MultiTexCoord0", "vec4(aeth_UV0, 0.0, 1.0)");
			if (parameters.inputs.hasLight()) {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord1", "vec4(aeth_UV2, 0.0, 1.0)");
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord2", "vec4(aeth_UV2, 0.0, 1.0)");
				root.rename("vaUV2", "aeth_UV2");
			} else {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord1", "vec4(240.0, 240.0, 0.0, 1.0)");
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord2", "vec4(240.0, 240.0, 0.0, 1.0)");
				root.rename("vaUV2", "aeth_UV2");
			}
			root.rename("vaUV1", "aeth_UV1");

			addIfNotExists(root, t, tree, "aeth_Color", Type.F32VEC4, StorageType.IN);
			addIfNotExists(root, t, tree, "aeth_Position", Type.F32VEC3, StorageType.IN);
			addIfNotExists(root, t, tree, "aeth_Normal", Type.F32VEC3, StorageType.IN);
			addIfNotExists(root, t, tree, "aeth_UV0", Type.F32VEC2, StorageType.IN);
			addIfNotExists(root, t, tree, "aeth_UV1", Type.I32VEC2, StorageType.IN);
			addIfNotExists(root, t, tree, "aeth_UV2", Type.I32VEC2, StorageType.IN);
		}
	}
}
