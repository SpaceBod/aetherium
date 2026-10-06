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

public class LodTerrainTransformer {
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
									 "in vec3 aeth_vBlockPos;",
									 "flat in uvec2 aeth_TexId;",
									 "uniform sampler2D dhBlockAtlas;",
									 """
										bool dh_hasTexture() { return aeth_TexId.x != 0u; }""", """
										vec2 dh_blockFaceUv() {
											vec3 pos = fract(aeth_vBlockPos);
										      switch (aeth_TexId.y)
										      {
										          case 0u: return vec2(pos.x, 1.0 - pos.z); // down
										          case 1u: return vec2(pos.x, pos.z); // up
										          case 2u: return vec2(1.0 - pos.x, 1.0 - pos.y); // north
										          case 3u: return vec2(pos.x, 1.0 - pos.y); // south
										          case 4u: return vec2(pos.z, 1.0 - pos.y); // west
										          default: return vec2(1.0 - pos.z, 1.0 - pos.y); // east
										      }
										}""", """
										vec4 dh_sampleTexture() {
											ivec2 atlasSize = textureSize(dhBlockAtlas, 0);
										          vec2 tileOrigin = vec2(float(aeth_TexId.x % 256u), float(aeth_TexId.x / 256u)) * 16.0;
										          vec2 uv = (tileOrigin + dh_blockFaceUv() * 16.0) / vec2(atlasSize);
										          return texture(dhBlockAtlas, uv);
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
				"vec4 getVertexPosition() { return vec4(modelOffset + _vert_position, 1.0); }");
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
			"uniform float mircoOffset;",
			"uniform vec3 modelOffset;",
			"out vec3 aeth_vBlockPos;",
			"flat out uvec2 aeth_TexId;",
			"const vec3 aethNormals[6] = vec3[](vec3(0,-1,0),vec3(0,1,0),vec3(0,0,-1),vec3(0,0,1),vec3(-1,0,0),vec3(1,0,0));",
			"void _vert_init() {" +
				"    uint meta = vPosition.a;\n" +
				"uint mirco = (meta & 0xFF00u) >> 8u; // mirco offset which is a xyz 2bit value\n" +
				"    // 0b00 = no offset\n" +
				"    // 0b01 = positive offset\n" +
				"    // 0b11 = negative offset\n" +
				"    // format is: 0b00zzyyxx\n" +
				"    float mx = (mirco & 1u)!=0u ? mircoOffset : 0.0;\n" +
				"    mx = (mirco & 2u)!=0u ? -mx : mx;\n" +
				"    float my = (mirco & 4u)!=0u ? mircoOffset : 0.0;\n" +
				"    my = (mirco & 8u)!=0u ? -my : my;\n" +
				"    float mz = (mirco & 16u)!=0u ? mircoOffset : 0.0;\n" +
				"    mz = (mirco & 32u)!=0u ? -mz : mz;\n" +
				"        uint lights = meta & 0xFFu;\n" +
				"_vert_position = (vPosition.xyz + vec3(mx, 0, mz));" +
				"_vert_normal = aethNormals[aethExtra.y];" +
				"dhMaterialId = int(aethExtra.x);" +
				"_vert_tex_light_coord = vec2((float(lights/16u)+0.5) / 16.0, (mod(float(lights), 16.0)+0.5) / 16.0);" +
				"aeth_vBlockPos = vec3(vPosition.xyz);" +
				"aeth_TexId =  uvec2(aethExtra.z | (aethExtra.w << 8u), aethExtra.y);" +
				"_vert_color = aeth_color; }");
		addIfNotExists(root, t, tree, "aeth_color", Type.F32VEC4, StorageQualifier.StorageType.IN);
		addIfNotExists(root, t, tree, "vPosition", Type.U32VEC4, StorageQualifier.StorageType.IN);
		addIfNotExists(root, t, tree, "aethExtra", Type.U32VEC4, StorageQualifier.StorageType.IN);
		tree.prependMainFunctionBody(t, "_vert_init();");
	}
}
