package dev.spacebod.aetherium.shaders.pipeline.transform.transformer;

import dev.spacebod.aetherium.shaders.gl.shader.ShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.VanillaParameters;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import java.util.ArrayList;
import java.util.List;

/**
 * Vanilla 26.3's shader interface as pack programs see it. The uniform blocks carry vanilla's own block names and
 * std140 layouts ({@code assets/minecraft/shaders/include/*.glsl}), so the render pass binds them exactly as it does for
 * vanilla's shaders; the instance names ({@code aeth_fogP}, {@code aeth_transforms}, ...) are what the rest of the
 * transformer references.
 *
 * <p>Terrain has no {@code DynamicTransforms}: its model-view matrix is in {@code TerrainUniform} and its model offset is
 * the section position relative to the camera ({@code ChunkSection} block per draw, or the {@code ChunkPosition}
 * instance attribute with multi-draw-indirect). There {@code aeth_transforms} is a global struct with the same members,
 * so every other part of the transformer is shared.
 *
 * <p>Packs see OpenGL's depth convention. Vanilla 26.3 binds a reversed, zero-to-one projection; its OpenGL twin has
 * clip z' = w - 2z (row 2 := row 3 - 2 row 2), exposed as {@code aeth_ProjMatGL}. The engine maps {@code gl_Position}
 * back to vanilla's convention after the pack's last vertex stage (see {@code WorldInterface}).
 */
public final class VanillaInterface {
	/** Where the model-view matrix and model offset come from. */
	public enum TransformSource {
		/** {@code DynamicTransforms} block (entities, sky, particles, block entities, ...). */
		DYNAMIC,
		/**
		 * {@code DynamicTransforms} replayed into the shadow map: its model-view was written for the player's camera, so it is
		 * rebased with {@code aeth_ShadowFromView} (shadow model-view x inverse camera view).
		 */
		DYNAMIC_SHADOW,
		/** Terrain drawn section by section: {@code TerrainUniform} + {@code ChunkSection} block. */
		TERRAIN,
		/** Terrain drawn with multi-draw-indirect: {@code TerrainUniform} + {@code ChunkPosition} vertex attribute (binding 1). */
		TERRAIN_MULTIDRAW,
		/**
		 * Terrain from the chunk renderer's regions: the model-view is {@code aeth_TerrainModelView} (the frame's view, or the
		 * shadow map's in the shadow pass) and the model offset the region's camera-relative origin (push constant) plus the
		 * section's place in its region (the vertex's last data byte, see {@code ChunkMeshFormat}).
		 */
		TERRAIN_REGION;

		/** Whether draws read {@code DynamicTransforms} (as themselves or rebased for the shadow map). */
		public boolean dynamic() {
			return this == DYNAMIC || this == DYNAMIC_SHADOW;
		}
	}

	/** aeth_ProjMat with row 2 replaced by row 3 - 2 row 2 (one expression: no helper function in the declarations). */
	private static final String GL_PROJECTION;

	static {
		StringBuilder b = new StringBuilder("mat4(");
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) {
				if (c + r > 0) {
					b.append(", ");
				}
				String m = "aeth_ProjMat[" + c + "]";
				b.append(r == 2 ? m + "[3] - 2.0 * " + m + "[2]" : m + "[" + r + "]");
			}
		}
		GL_PROJECTION = b.append(')').toString();
	}

	/** Model-view matrix expression usable in global initializers. */
	public static String modelView(VanillaParameters parameters) {
		return parameters.source.dynamic() ? "aeth_transforms.ModelViewMat"
			: parameters.source == TransformSource.TERRAIN_REGION ? "aeth_TerrainModelView" : "aeth_terrainInfo.ModelViewMat";
	}

	private VanillaInterface() {
	}

	public static void inject(ASTParser t, TranslationUnit tree, VanillaParameters parameters) {
		// One injection, in dependency order (each separate BEFORE_DECLARATIONS injection lands ahead of the previous one).
		List<String> nodes = new ArrayList<>();
		// mc_chunkFade: vanilla's per-section fade-in (0 = just appeared, 1 = fully visible) for terrain; -1 elsewhere.
		String chunkFade = null;
		nodes.add("""
			layout(std140) uniform Fog {
			    vec4 FogColor;
			    float FogEnvironmentalStart;
			    float FogEnvironmentalEnd;
			    float FogRenderDistanceStart;
			    float FogRenderDistanceEnd;
			    float FogSkyEnd;
			    float FogCloudsEnd;
			} aeth_fogP;
			""");
		nodes.add("struct aeth_FogParameters { vec4 color; float density; float start; float end; float scale; };");
		nodes.add("aeth_FogParameters aethInt_Fog = aeth_FogParameters(aeth_fogP.FogColor, 0.0, aeth_fogP.FogEnvironmentalStart, aeth_fogP.FogEnvironmentalEnd, 1.0 / (aeth_fogP.FogEnvironmentalEnd - aeth_fogP.FogEnvironmentalStart));");
		nodes.add("""
			layout(std140) uniform Projection {
			    mat4 aeth_ProjMat;
			};
			""");
		nodes.add("mat4 aeth_ProjMatGL = " + GL_PROJECTION + ";");
		nodes.add("""
			layout(std140) uniform Globals {
			    ivec3 CameraBlockPos;
			    float GlintAlpha;
			    vec3 CameraOffset;
			    float GameTime;
			    vec2 ScreenSize;
			    int MenuBlurRadius;
			    int UseRgss;
			} aeth_globalInfo;
			""");

		if (parameters.source == TransformSource.DYNAMIC) {
			nodes.add("""
				layout(std140) uniform DynamicTransforms {
				    mat4 ModelViewMat;
				    mat4 TextureMat;
				    vec4 ColorModulator;
				    vec3 ModelOffset;
				} aeth_transforms;
				""");
		} else if (parameters.source == TransformSource.DYNAMIC_SHADOW) {
			nodes.add("""
				layout(std140) uniform DynamicTransforms {
				    mat4 ModelViewMat;
				    mat4 TextureMat;
				    vec4 ColorModulator;
				    vec3 ModelOffset;
				} aeth_dynamicTransforms;
				""");
			nodes.add("uniform mat4 aeth_ShadowFromView;");
			nodes.add("struct aeth_TransformsT { mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset; };");
			nodes.add("aeth_TransformsT aeth_transforms = aeth_TransformsT(aeth_ShadowFromView * aeth_dynamicTransforms.ModelViewMat, "
				+ "aeth_dynamicTransforms.TextureMat, aeth_dynamicTransforms.ColorModulator, aeth_dynamicTransforms.ModelOffset);");
		} else if (parameters.source == TransformSource.TERRAIN_REGION) {
			nodes.add("uniform mat4 aeth_TerrainModelView;");
			String offset = "vec3(0.0)";
			if (parameters.type.glShaderType == ShaderType.VERTEX) {
				// The region's draw constants, as the chunk renderer pushes them for every region. The vertex inputs are declared
				// by the lowering (VulkanLowering.chunkVertex), ahead of everything that reads them.
				nodes.add("layout(push_constant) uniform aeth_RegionConstants { vec3 aeth_RegionOffset; int aeth_RegionTime; uint aeth_RegionId; };");
				offset = "aeth_RegionOffset + vec3((uvec3(a_LightAndData.w) >> uvec3(5u, 0u, 2u)) & uvec3(7u, 3u, 7u)) * 16.0";
			}
			nodes.add("struct aeth_TransformsT { mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset; };");
			nodes.add("aeth_TransformsT aeth_transforms = aeth_TransformsT(aeth_TerrainModelView, mat4(1.0), vec4(1.0), " + offset + ");");
			chunkFade = "1.0";
		} else {
			nodes.add("""
				layout(std140) uniform TerrainUniform {
				    mat4 ModelViewMat;
				    ivec2 TextureSize;
				} aeth_terrainInfo;
				""");
			String chunkPosition = null;
			if (parameters.source == TransformSource.TERRAIN) {
				nodes.add("""
					layout(std140) uniform ChunkSection {
					    ivec3 ChunkPosition;
					    float ChunkVisibility;
					} aeth_chunkSection;
					""");
				chunkPosition = "aeth_chunkSection.ChunkPosition";
				chunkFade = "aeth_chunkSection.ChunkVisibility";
			} else if (parameters.type.glShaderType == ShaderType.VERTEX) {
				nodes.add("in ivec3 aeth_ChunkPosition;");
				nodes.add("in float aeth_ChunkVisibility;");
				chunkPosition = "aeth_ChunkPosition";
				chunkFade = "aeth_ChunkVisibility";
			}
			String offset = chunkPosition == null ? "vec3(0.0)"
				: "vec3(" + chunkPosition + " - aeth_globalInfo.CameraBlockPos) + aeth_globalInfo.CameraOffset";
			nodes.add("struct aeth_TransformsT { mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset; };");
			nodes.add("aeth_TransformsT aeth_transforms = aeth_TransformsT(aeth_terrainInfo.ModelViewMat, mat4(1.0), vec4(1.0), " + offset + ");");
		}
		nodes.add(chunkFade == null ? "const float mc_chunkFade = -1.0;" : "float mc_chunkFade = " + chunkFade + ";");
		tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS, nodes.toArray(new String[0]));
	}
}
