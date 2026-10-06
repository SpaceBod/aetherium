package dev.spacebod.aetherium.shaders.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import dev.spacebod.aetherium.shaders.gl.shader.ShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.PatchShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.VanillaParameters;

public class VanillaTransformer {
	public static void transform(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		VanillaParameters parameters) {
		// Before CommonTransformer so its attribute renames also apply to the attributes injected here.
		if (parameters.inputs.hasOverlay()) {
			EntityPatcher.patchOverlayColor(t, tree, root, parameters);
			EntityPatcher.patchEntityId(t, tree, root, parameters);
		} else if (parameters.inputs.isText()) {
			EntityPatcher.patchEntityId(t, tree, root, parameters);
		}

		// The pack's own alpha test reads this program's alpha reference, compiled in as a constant by the lowering.
		root.rename("alphaTestRef", "aeth_currentAlphaTest");
		CommonTransformer.transform(t, tree, root, parameters, false);

		// 26.3: vanilla's own block names and layouts, so the render pass binds them (see VanillaInterface).
		VanillaInterface.inject(t, tree, parameters);
		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			CommonTransformer.patchIntegerAttribute(t, tree, root, "mc_Entity", "aeth_Entity", parameters.inputs.getEntityComponents());

			// Packs written against 1.15+ read the lightmap coordinates from either name.
			root.rename("gl_MultiTexCoord2", "gl_MultiTexCoord1");
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"in float aeth_LineWidth;");

			if (parameters.inputs.hasTex() && !parameters.isClouds()) {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord0",
					"vec4(aeth_UV0, 0.0, 1.0)");
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"in vec2 aeth_UV0;");
			} else {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord0",
					"vec4(0.5, 0.5, 0.0, 1.0)");
			}

			if (parameters.inputs.isIE()) {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord1",
					"vec4(aeth_LightUV, 0.0, 1.0)");

				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"uniform ivec2 aeth_LightUV;");
			} else if (parameters.inputs.hasLight()) {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord1",
					"vec4(aeth_UV2, 0.0, 1.0)");

				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"in ivec2 aeth_UV2;");
			} else {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord1",
					"vec4(240.0, 240.0, 0.0, 1.0)");
			}

			CommonTransformer.patchMultiTexCoord3(t, tree, root, parameters);

			// gl_MultiTexCoord0 and gl_MultiTexCoord1 are the only valid inputs (with
			// gl_MultiTexCoord2 and gl_MultiTexCoord3 as aliases), other texture
			// coordinates are not valid inputs.
			CommonTransformer.replaceGlMultiTexCoordBounded(t, root, 4, 7);
		}

		if (parameters.inputs.hasColor() && parameters.type == PatchShaderType.VERTEX) {
			// The vertex attribute; a fragment stage reads gl_Color as the interpolated aeth_vColor (CommonTransformer).
			if (parameters.alpha.reference() == Float.MAX_VALUE) {
				root.replaceReferenceExpressions(t, "gl_Color",
					"vec4((aeth_Color * aeth_transforms.ColorModulator).rgb, aeth_transforms.ColorModulator.a)");
			} else if (parameters.isClouds()) {
				root.replaceReferenceExpressions(t, "gl_Color", "aeth_cloudCol");
			} else {
				root.replaceReferenceExpressions(t, "gl_Color",
					"(aeth_Color * aeth_transforms.ColorModulator)");
			}

			if (parameters.type.glShaderType == ShaderType.VERTEX) {
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"in vec4 aeth_Color;");
			}
		} else if (parameters.inputs.isGlint()) {
			// The colour modulator applies regardless of the alpha test state.
			root.replaceReferenceExpressions(t, "gl_Color", "vec4(aeth_transforms.ColorModulator.rgb, aeth_transforms.ColorModulator.a * aeth_globalInfo.GlintAlpha)");
		} else {
			// The colour modulator applies regardless of the alpha test state.
			root.replaceReferenceExpressions(t, "gl_Color", "aeth_transforms.ColorModulator");
		}

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			if (!parameters.isClouds()) {
				if (parameters.inputs.hasNormal()) {
					if (!parameters.inputs.isNewLines()) {
						root.rename("gl_Normal", "aeth_Normal");
					} else {
						root.replaceReferenceExpressions(t, "gl_Normal",
							"vec3(0.0, 0.0, 1.0)");
					}

					tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
						"in vec3 aeth_Normal;");
				} else {
					root.replaceReferenceExpressions(t, "gl_Normal",
						"vec3(0.0, 0.0, 1.0)");
				}
			}
		}

		// 26.3: the lightmap texture matrix is a constant (UV2 is 0..240 texels -> texel centres), not a uniform.
		tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
			"const mat4 aeth_LightmapTextureMatrix = mat4(vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0), vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0));");

		// gl_TextureMatrix[0] is the draw's texture matrix, [1] the lightmap's.
		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix0, "aeth_transforms.TextureMat");
		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix1, "aeth_LightmapTextureMatrix");

		root.replaceReferenceExpressions(t, "gl_NormalMatrix",
			"aeth_NormalMat");

		root.replaceReferenceExpressions(t, "gl_ModelViewMatrixInverse",
			"aeth_ModelViewMatInverse");

		root.replaceReferenceExpressions(t, "gl_ProjectionMatrixInverse",
			"aeth_ProjMatInverse");
		// 26.3: derived in the shader from the bound matrices (the model-view matrix changes per draw for entities).
		String modelView = VanillaInterface.modelView(parameters);
		CommonTransformer.injectDerivedMatrices(t, tree, root, modelView, !parameters.source.dynamic());

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"in vec3 aeth_Position;");
			if (root.identifierIndex.has("ftransform")) {
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
					// Two matrix-vector products, not a matrix-matrix product per vertex.
					"vec4 ftransform() { return gl_ProjectionMatrix * (gl_ModelViewMatrix * gl_Vertex); }");
			}

			if (parameters.inputs.isNewLines()) {
				root.replaceReferenceExpressions(t, "gl_Vertex",
					"vec4(aeth_Position + aeth_vertex_offset, 1.0)");

				// Wrap the pack's main: run it for both line end points (offset by aeth_Normal), then widen in
				// screen space.
				root.rename("main", "aethMain");
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"vec3 aeth_vertex_offset = vec3(0.0);");
				tree.parseAndInjectNodes(t, ASTInjectionPoint.END,
					"void aeth_widen_lines(vec4 linePosStart, vec4 linePosEnd) {" +
						"vec3 ndc1 = linePosStart.xyz / linePosStart.w;" +
						"vec3 ndc2 = linePosEnd.xyz / linePosEnd.w;" +
						"vec2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * aeth_globalInfo.ScreenSize);" +
						"vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * aeth_LineWidth / aeth_globalInfo.ScreenSize;"
						+
						"if (lineOffset.x < 0.0) {" +
						"    lineOffset *= -1.0;" +
						"}" +
						"if (gl_VertexID % 2 == 0) {" +
						"    gl_Position = vec4((ndc1 + vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);" +
						"} else {" +
						"    gl_Position = vec4((ndc1 - vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);" +
						"}}",
					"void main() {" +
						"aeth_vertex_offset = aeth_Normal;" +
						"aethMain();" +
						"vec4 linePosEnd = gl_Position;" +
						"gl_Position = vec4(0.0);" +
						"aeth_vertex_offset = vec3(0.0);" +
						"aethMain();" +
						"vec4 linePosStart = gl_Position;" +
						"aeth_widen_lines(linePosStart, linePosEnd);}");
			} else if (parameters.isClouds()) {
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS, """
					layout(std140) uniform CloudInfo {
					    vec4 CloudColor;
					    vec3 CloudOffset;
					    vec3 CellSize;
					} aeth_Clouds;
					""",
					"""
						const vec3[] aeth_cloudVertices = vec3[](
						    // Bottom face
						    vec3(1, 0, 0),
						    vec3(1, 0, 1),
						    vec3(0, 0, 1),
						    vec3(0, 0, 0),
						    // Top face
						    vec3(0, 1, 0),
						    vec3(0, 1, 1),
						    vec3(1, 1, 1),
						    vec3(1, 1, 0),
						    // North face
						    vec3(0, 0, 0),
						    vec3(0, 1, 0),
						    vec3(1, 1, 0),
						    vec3(1, 0, 0),
						    // South face
						    vec3(1, 0, 1),
						    vec3(1, 1, 1),
						    vec3(0, 1, 1),
						    vec3(0, 0, 1),
						    // West face
						    vec3(0, 0, 1),
						    vec3(0, 1, 1),
						    vec3(0, 1, 0),
						    vec3(0, 0, 0),
						    // East face
						    vec3(1, 0, 0),
						    vec3(1, 1, 0),
						    vec3(1, 1, 1),
						    vec3(1, 0, 1)
						);
						""",
					"""
						const vec3[] aeth_cloudNormals = vec3[](
						    // Bottom face
						    vec3(0, -1, 0),
						    // Top face
						    vec3(0, 1, 0),
						    // North face
						    vec3(0, 0, -1),
						    // South face
						    vec3(0, 0, 1),
						    // West face
						    vec3(-1, 0, 0),
						    // East face
						    vec3(1, 0, 0)
						);
						""",
					"""
						const vec4[] aeth_faceColors = vec4[](
						    // Bottom face
						    vec4(0.7, 0.7, 0.7, 0.8),
						    // Top face
						    vec4(1.0, 1.0, 1.0, 0.8),
						    // North face
						    vec4(0.8, 0.8, 0.8, 0.8),
						    // South face
						    vec4(0.8, 0.8, 0.8, 0.8),
						    // West face
						    vec4(0.9, 0.9, 0.9, 0.8),
						    // East face
						    vec4(0.9, 0.9, 0.9, 0.8)
						);
						""",
					"""
						vec3 aeth_cloudPos;""",
					"""
						vec3 aeth_cloudNormal;""",
						"""
						vec4 aeth_cloudCol;
						""",

					"const int FLAG_MASK_DIR = 7;",
					"const int FLAG_INSIDE_FACE = 1 << 4;",
					"const int FLAG_USE_TOP_COLOR = 1 << 5;",
					"const int FLAG_EXTRA_Z = 1 << 6;",
					"const int FLAG_EXTRA_X = 1 << 7;",
					"uniform isamplerBuffer CloudFaces;",
					"""
					void aeth_cloudsMain() {
					    int quadVertex = gl_VertexID % 4;
					    int index = (gl_VertexID / 4) * 3;

					    int cellX = texelFetch(CloudFaces, index).r;
					    int cellZ = texelFetch(CloudFaces, index + 1).r;
					    int dirAndFlags = texelFetch(CloudFaces, index + 2).r;
					    int direction = dirAndFlags & FLAG_MASK_DIR;
					    bool isInsideFace = (dirAndFlags & FLAG_INSIDE_FACE) == FLAG_INSIDE_FACE;
					    bool useTopColor = (dirAndFlags & FLAG_USE_TOP_COLOR) == FLAG_USE_TOP_COLOR;
					    cellX = (cellX << 1) | ((dirAndFlags & FLAG_EXTRA_X) >> 7);
					    cellZ = (cellZ << 1) | ((dirAndFlags & FLAG_EXTRA_Z) >> 6);
					    vec3 faceVertex = aeth_cloudVertices[(direction * 4) + (isInsideFace ? 3 - quadVertex : quadVertex)];
					    aeth_cloudPos = (faceVertex * aeth_Clouds.CellSize) + (vec3(cellX, 0, cellZ) * aeth_Clouds.CellSize) + aeth_Clouds.CloudOffset;
					    aeth_cloudNormal = aeth_cloudNormals[direction];
					    aeth_cloudCol = (useTopColor ? aeth_faceColors[1] : aeth_faceColors[direction]) * aeth_Clouds.CloudColor;
					    }
					""");
				tree.prependMainFunctionBody(t, "aeth_cloudsMain();");
				root.replaceReferenceExpressions(t, "gl_Vertex", "vec4(aeth_cloudPos, 1.0)");
				root.replaceReferenceExpressions(t, "gl_Normal", "aeth_cloudNormal");
			} else {
				root.replaceReferenceExpressions(t, "gl_Vertex", "vec4(aeth_Position, 1.0)");
			}
		}

		root.replaceReferenceExpressions(t, "gl_ModelViewProjectionMatrix",
			"(gl_ProjectionMatrix * gl_ModelViewMatrix)");

		StringBuilder transform = new StringBuilder("(");
		if (parameters.inputs.isNewLines()) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"const float aeth_VIEW_SHRINK = 1.0 - (1.0 / 256.0);",
				"const mat4 aeth_VIEW_SCALE = mat4(" +
					"aeth_VIEW_SHRINK, 0.0, 0.0, 0.0," +
					"0.0, aeth_VIEW_SHRINK, 0.0, 0.0," +
					"0.0, 0.0, aeth_VIEW_SHRINK, 0.0," +
					"0.0, 0.0, 0.0, 1.0);");
			transform.append("aeth_VIEW_SCALE * ");
		}
		// m * translate(offset) without the matrix product: only the last column changes.
		tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
			"mat4 _aeth_internal_offset(mat4 m, vec3 offset) {" +
				"return mat4(m[0], m[1], m[2], m * vec4(offset, 1.0)); }");
		transform.append("_aeth_internal_offset(aeth_transforms.ModelViewMat, aeth_transforms.ModelOffset))");
		root.replaceReferenceExpressions(t, "gl_ModelViewMatrix", transform.toString());

		root.rename("gl_ProjectionMatrix", "aeth_ProjMatGL");
	}
}
