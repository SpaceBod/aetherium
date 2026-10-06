package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;
import dev.spacebod.aetherium.client.gpu.DeviceTraits;
import dev.spacebod.aetherium.client.gpu.GeometryStages;
import dev.spacebod.aetherium.shaders.chunks.ChunkMeshFormat;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTest;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTestFunction;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.LoweringParameters;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.VulkanLowering;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parameters for the Vulkan lowering pack programs go through, on the syntax tree inside the transform
 * ({@link VulkanLowering}), per program kind.
 */
public final class Lowering {
	private Lowering() {
	}

	/** World program parameters (gbuffers / shadow programs for a vanilla pipeline). */
	static LoweringParameters world(Map<String, GpuFormat> attributes, AlphaTest alpha, int[] drawBuffers, int[] attachments, boolean glDepth,
			LoweringParameters.Glint glint, boolean grayscaleAlbedo) {
		// Chunk mesh terrain is drawn by the chunk renderer, which binds the atlas and lightmap under its own names.
		boolean chunks = attributes.get(ChunkMeshFormat.POSITION) == GpuFormat.RG32_UINT;
		String albedo = chunks ? ChunkMeshFormat.ALBEDO : "Sampler0";
		String lightmap = chunks ? ChunkMeshFormat.LIGHTMAP : "Sampler2";
		return new LoweringParameters(LoweringParameters.Kind.WORLD, attributes, glDepth, alphaReference(alpha),
				List.of(new LoweringParameters.Rename("gtexture", albedo), new LoweringParameters.Rename("tex", albedo),
						new LoweringParameters.Rename("lightmap", lightmap), new LoweringParameters.Rename("aeth_overlay", "Sampler1")),
				outputs(drawBuffers, attachments), LoweringParameters.LodVariant.NONE, glint, grayscaleAlbedo, StorageBindings.snapshot(),
				ShadowSampling.snapshot(), platform());
	}

	/** LOD program parameters: the LOD mod's lightmap and atlas names, its own blocks. */
	static LoweringParameters lod(Map<String, GpuFormat> attributes, int[] drawBuffers, int[] attachments, boolean glDepth, boolean generic) {
		List<LoweringParameters.Rename> renames = new ArrayList<>();
		renames.add(new LoweringParameters.Rename("lightmap", "uLightMap"));
		// LOD terrain: the albedo names read the mod's block atlas too (it is the program's only texture there).
		String albedo = generic ? "Sampler0" : "uBlockAtlas";
		if (!generic) {
			renames.add(new LoweringParameters.Rename("dhBlockAtlas", "uBlockAtlas"));
		}
		renames.add(new LoweringParameters.Rename("gtexture", albedo));
		renames.add(new LoweringParameters.Rename("tex", albedo));
		renames.add(new LoweringParameters.Rename("aeth_overlay", "Sampler1"));
		return new LoweringParameters(LoweringParameters.Kind.WORLD, attributes, glDepth, 0.0f, renames, outputs(drawBuffers, attachments),
				generic ? LoweringParameters.LodVariant.GENERIC : LoweringParameters.LodVariant.TERRAIN, LoweringParameters.Glint.NONE, false,
				StorageBindings.snapshot(), ShadowSampling.snapshot(), platform());
	}

	static LoweringParameters fullscreen() {
		return LoweringParameters.fullscreen(StorageBindings.snapshot(), ShadowSampling.snapshot(), platform());
	}

	static LoweringParameters compute() {
		return LoweringParameters.compute(StorageBindings.snapshot(), ShadowSampling.snapshot(), platform());
	}

	/** The device programs are lowered for now (a value: part of the transform cache key). */
	static LoweringParameters.Platform platform() {
		return new LoweringParameters.Platform(DeviceTraits.moltenVk(), GeometryStages.enabled());
	}

	/** The constant {@code aeth_currentAlphaTest} becomes (0 = no test). */
	static float alphaReference(AlphaTest alpha) {
		return alpha.function() == AlphaTestFunction.ALWAYS || alpha.reference() == Float.MAX_VALUE ? 0.0f : alpha.reference();
	}

	/** Fragment output location i -> the attachment holding colortex drawBuffers[i], or -1. */
	private static List<Integer> outputs(int[] drawBuffers, int[] attachments) {
		List<Integer> out = new ArrayList<>();
		for (int buffer : drawBuffers) {
			int attachment = -1;
			for (int a = 0; a < attachments.length; a++) {
				if (attachments[a] == buffer) {
					attachment = a;
					break;
				}
			}
			out.add(attachment);
		}
		return out;
	}
}
