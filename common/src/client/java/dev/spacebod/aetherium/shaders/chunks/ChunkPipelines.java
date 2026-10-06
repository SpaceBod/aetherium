package dev.spacebod.aetherium.shaders.chunks;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.pipeline.PipelinePrograms;
import dev.spacebod.aetherium.shaders.pipeline.programs.ShaderKey;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

/**
 * The chunk renderer's terrain pipelines, one per terrain pass, as it creates them: each joins the pipelines pack
 * programs replace, with the program of the vanilla terrain layer it draws (solid, cutout, translucent), and the main
 * and shadow passes substitute it like any vanilla pipeline. Passes other than the three are left alone.
 */
public final class ChunkPipelines {
	private ChunkPipelines() {
	}

	/** Render thread, when the renderer has built {@code pipeline} for {@code pass}. */
	public static void created(TerrainRenderPass pass, RenderPipeline pipeline) {
		ShaderKey main;
		ShaderKey shadow;
		if (pass == DefaultTerrainRenderPasses.SOLID) {
			main = ShaderKey.TERRAIN_SOLID;
			shadow = ShaderKey.SHADOW_TERRAIN_CUTOUT;
		} else if (pass == DefaultTerrainRenderPasses.CUTOUT) {
			main = ShaderKey.TERRAIN_CUTOUT;
			shadow = ShaderKey.SHADOW_TERRAIN_CUTOUT;
		} else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
			main = ShaderKey.TERRAIN_TRANSLUCENT;
			shadow = ShaderKey.SHADOW_TRANSLUCENT;
		} else {
			return;
		}
		if (PipelinePrograms.addTerrain(pipeline, main, shadow)) {
			ShaderPackEngine.get().onTerrainPipeline(pipeline, pass.isTranslucent());
		}
	}
}
