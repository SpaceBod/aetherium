package dev.spacebod.aetherium.shaders.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.util.Type;
import dev.spacebod.aetherium.shaders.gl.shader.ShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.Parameters;

import static dev.spacebod.aetherium.shaders.pipeline.transform.transformer.CommonTransformer.addIfNotExists;

public class LodGenericTransformer {
	public static void transform(
		ASTParser t,
		TranslationUnit tree,
		Root root, Parameters parameters) {
		CommonTransformer.transform(t, tree, root, parameters, false);


		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix0, "mat4(1.0)");
		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix1, "mat4(1.0)");
		root.rename("gl_ProjectionMatrix", "aeth_ProjectionMatrix");

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			// Since 1.15 the pack format treats gl_MultiTexCoord2 as an alias of gl_MultiTexCoord1 (lightmap).
			root.rename("gl_MultiTexCoord2", "gl_MultiTexCoord1");

			root.replaceReferenceExpressions(t, "gl_MultiTexCoord0",
				"vec4(0.0, 0.0, 0.0, 1.0)");

			root.replaceReferenceExpressions(t, "gl_MultiTexCoord1",
				"vec4(_vert_tex_light_coord, 0.0, 1.0)");


			// Only 0 and 1 are real inputs (2 and 3 are aliases); 4..7 become constants.
			CommonTransformer.replaceGlMultiTexCoordBounded(t, root, 4, 7);
		}

		if (parameters.type.glShaderType == ShaderType.FRAGMENT) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,

									 """
										bool dh_hasTexture() { return false; }""", """
										vec4 dh_sampleTexture() {
											return vec4(1.0);
										 }
										 """);
		}

		root.rename("gl_Color", "_vert_color");

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			root.replaceReferenceExpressions(t, "gl_Normal", "_vert_normal");

		}

		// The normal matrix and the inverses are uniforms computed on the CPU.
		root.replaceReferenceExpressions(t, "gl_NormalMatrix",
			"aeth_NormalMatrix");
		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
			"uniform mat3 aeth_NormalMatrix;");

		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
			"uniform mat4 aeth_ModelViewMatrixInverse;");

		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
			"uniform mat4 aeth_ProjectionMatrixInverse;");

		root.rename("gl_ModelViewMatrix", "aeth_ModelViewMatrix");
		root.rename("gl_ModelViewMatrixInverse", "aeth_ModelViewMatrixInverse");
		root.rename("gl_ProjectionMatrixInverse", "aeth_ProjectionMatrixInverse");

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			// Positions are relative to the LOD buffer, not aligned to chunks (Vaporwave-Shaderpack assumes chunk alignment).
			if (root.identifierIndex.has("ftransform")) {
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
					"vec4 ftransform() { return gl_ModelViewProjectionMatrix * gl_Vertex; }");
			}
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"uniform mat4 aeth_ProjectionMatrix;",
				"uniform mat4 aeth_ModelViewMatrix;",
				"vec4 getVertexPosition() { return vec4(_vert_position, 1.0); }");
			root.replaceReferenceExpressions(t, "gl_Vertex", "getVertexPosition()");

			// Injected last so _vert_position is declared before the code above: piece-wise injections land
			// in reverse order, array injections in order.
			injectVertInit(t, tree, root, parameters);
		} else {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"uniform mat4 aeth_ModelViewMatrix;",
				"uniform mat4 aeth_ProjectionMatrix;");
		}

		root.replaceReferenceExpressions(t, "gl_ModelViewProjectionMatrix",
			"(aeth_ProjectionMatrix * aeth_ModelViewMatrix)");

		CommonTransformer.applyIntelHd4000Workaround(root);
	}

	public static void injectVertInit(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		Parameters parameters) {
		tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
			"vec3 _vert_position;",
			"vec2 _vert_tex_light_coord;",
			"int dhMaterialId;",
			"vec4 _vert_color;",
			"vec3 _vert_normal;",
			"uniform ivec3 uOffsetChunk;",
			"uniform vec3 uOffsetSubChunk;",
			"uniform ivec3 uCameraPosChunk;",
			"uniform vec3 uCameraPosSubChunk;",
			"uniform int uSkyLight;",
			"uniform int uBlockLight;",
			"const vec3 aethNormals[6] = vec3[](vec3(0,0,-1),vec3(0,0,1),vec3(-1,0,0),vec3(1,0,0),vec3(0,-1,0),vec3(0,1,0));",
			"""
				void _vert_init() {
					vec3 trans = (aTranslateChunk + uOffsetChunk - uCameraPosChunk) * 16.0f;
					trans += (aTranslateSubChunk + uOffsetSubChunk - uCameraPosSubChunk);
					mat4 transform = mat4(
				         aScale.x, 0.0,      0.0,      0.0,
				         0.0,      aScale.y, 0.0,      0.0,
				         0.0,      0.0,      aScale.z, 0.0,
				         trans.x,  trans.y,  trans.z,  1.0
				     );
				     _vert_position = (transform * vec4(vPosition, 1.0)).xyz;
					_vert_normal = aethNormals[(gl_VertexID % 24) / 4]; // boxes pre-expanded in one buffer, 24 vertices each, as the mod draws them
								float blockLight = (float(uBlockLight)+0.5) / 16.0;
								float skyLight = (float(uSkyLight)+0.5) / 16.0;
				     _vert_tex_light_coord = vec2(blockLight, skyLight);
				     dhMaterialId = aMaterial;
				     _vert_color = aeth_color;
				     }
				""");
		addIfNotExists(root, t, tree, "aeth_color", Type.F32VEC4, StorageQualifier.StorageType.IN, 1);
		addIfNotExists(root, t, tree, "aScale", Type.F32VEC3, StorageQualifier.StorageType.IN, 2);
		addIfNotExists(root, t, tree, "aTranslateChunk", Type.I32VEC3, StorageQualifier.StorageType.IN, 3);
		addIfNotExists(root, t, tree, "aTranslateSubChunk", Type.F32VEC3, StorageQualifier.StorageType.IN, 4);
		addIfNotExists(root, t, tree, "aMaterial", Type.INT32, StorageQualifier.StorageType.IN, 5);
		addIfNotExists(root, t, tree, "vPosition", Type.F32VEC3, StorageQualifier.StorageType.IN, 0);
		tree.prependMainFunctionBody(t, "_vert_init();");
	}
}
