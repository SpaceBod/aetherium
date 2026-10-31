package dev.spacebod.aetherium.microbench;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.spacebod.aetherium.shaders.compat.lod.LodCompat;
import dev.spacebod.aetherium.shaders.pipeline.PipelinePrograms;
import dev.spacebod.aetherium.shaders.pipeline.programs.ShaderKey;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.server.Bootstrap;

public final class ModCompatibilityFixtures {
	private ModCompatibilityFixtures() {
	}

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		if (args.length != 0 && LodCompat.installed() != Boolean.parseBoolean(args[0])) {
			throw new AssertionError("unexpected LOD API availability");
		}
		copiedPipelines();
		cachedMisses();
		lateTerrainRegistration();
		cachedCopyTerrainRegistration();
		lodFallback();
	}

	/** Cosmetic mods copy vanilla pipelines and change fixed render state; their shader program must follow the copy. */
	private static void copiedPipelines() {
		var original = RenderPipelines.ENTITY_TRANSLUCENT;
		var copy = new PipelineCopy(original, original.getShaders(), original.getShaderDefines(), original.getVertexFormatBindings());
		for (var context : List.of(PipelinePrograms.Context.WORLD, PipelinePrograms.Context.HAND,
				PipelinePrograms.Context.HAND_TRANSLUCENT, PipelinePrograms.Context.BLOCK_ENTITY)) {
			if (PipelinePrograms.main(copy, context) != PipelinePrograms.main(original, context)
					|| PipelinePrograms.shadow(copy, context) != PipelinePrograms.shadow(original, context)) {
				throw new AssertionError("copied entity pipeline lost its world or shadow program: " + context);
			}
		}
		var shaders = new EnumMap<>(original.getShaders());
		shaders.put(ShaderType.FRAGMENT, Identifier.fromNamespaceAndPath("fixture", "custom"));
		var defines = original.getShaderDefines().withOverrides(new ShaderDefines(Map.of(), Set.of("CUSTOM")));
		for (var custom : List.of(
				new PipelineCopy(original, shaders, original.getShaderDefines(), original.getVertexFormatBindings()),
				new PipelineCopy(original, original.getShaders(), defines, original.getVertexFormatBindings()),
				new PipelineCopy(original, original.getShaders(), original.getShaderDefines(), List.of(DefaultVertexFormat.POSITION)))) {
			if (PipelinePrograms.main(custom, PipelinePrograms.Context.WORLD) != null
					|| PipelinePrograms.shadow(custom, PipelinePrograms.Context.WORLD) != null) {
				throw new AssertionError("a changed shader interface was mistaken for a vanilla pipeline copy");
			}
		}
		System.out.println("pipeline copies: cosmetic render-state variants inherit world/shadow programs; custom shader interfaces stay separate");
	}

	private static void cachedMisses() {
		CountingPipeline unknown = new CountingPipeline(RenderPipelines.ENTITY_TRANSLUCENT,
				Identifier.fromNamespaceAndPath("fixture", "unmapped"));
		int mainCount = PipelinePrograms.mainPipelines().size();
		int shadowCount = PipelinePrograms.shadowPipelines().size();
		int before = unknown.locationReads;
		if (PipelinePrograms.main(unknown, PipelinePrograms.Context.WORLD) != null || unknown.locationReads - before <= 1) {
			throw new AssertionError("main miss did not exercise the copy search");
		}
		before = unknown.locationReads;
		if (PipelinePrograms.shadow(unknown, PipelinePrograms.Context.WORLD) != null || unknown.locationReads - before <= 1) {
			throw new AssertionError("shadow miss did not exercise the copy search");
		}
		before = unknown.locationReads;
		for (int draw = 0; draw < 1_000; draw++) {
			if (PipelinePrograms.main(unknown, PipelinePrograms.Context.WORLD) != null
					|| PipelinePrograms.shadow(unknown, PipelinePrograms.Context.WORLD) != null) {
				throw new AssertionError("a cached miss gained a shader program");
			}
		}
		// The fallback may inspect the namespace once per lookup, but a list scan reads it repeatedly.
		if (unknown.locationReads - before > 2_000) {
			throw new AssertionError("cached misses repeated the pipeline scan: " + (unknown.locationReads - before) + " metadata reads");
		}
		if (PipelinePrograms.mainPipelines().size() != mainCount || PipelinePrograms.shadowPipelines().size() != shadowCount) {
			throw new AssertionError("misses entered the prebuild registration lists");
		}
		System.out.println("pipeline misses: repeated main/shadow lookups avoid list scans");
	}

	private static void lateTerrainRegistration() {
		Identifier location = Identifier.fromNamespaceAndPath("fixture", "late_terrain");
		CountingPipeline original = new CountingPipeline(RenderPipelines.SOLID_TERRAIN, location);
		CountingPipeline copy = new CountingPipeline(original, location);
		for (RenderPipeline pipeline : List.of(original, copy)) {
			if (PipelinePrograms.main(pipeline, PipelinePrograms.Context.WORLD) != null
					|| PipelinePrograms.shadow(pipeline, PipelinePrograms.Context.WORLD) != null) {
				throw new AssertionError("unregistered terrain unexpectedly had a program");
			}
		}
		int mainCount = PipelinePrograms.mainPipelines().size();
		int shadowCount = PipelinePrograms.shadowPipelines().size();
		if (!PipelinePrograms.addTerrain(original, ShaderKey.TERRAIN_SOLID, ShaderKey.SHADOW_TERRAIN_CUTOUT)) {
			throw new AssertionError("a cached miss prevented terrain registration");
		}
		for (RenderPipeline pipeline : List.of(original, copy)) {
			if (PipelinePrograms.main(pipeline, PipelinePrograms.Context.WORLD) != ShaderKey.TERRAIN_SOLID
					|| PipelinePrograms.shadow(pipeline, PipelinePrograms.Context.WORLD) != ShaderKey.SHADOW_TERRAIN_CUTOUT) {
				throw new AssertionError("new terrain registration did not invalidate cached misses");
			}
		}
		if (PipelinePrograms.addTerrain(original, ShaderKey.TERRAIN_SOLID, ShaderKey.SHADOW_TERRAIN_CUTOUT)
				|| PipelinePrograms.mainPipelines().size() != mainCount + 1
				|| PipelinePrograms.shadowPipelines().size() != shadowCount + 1
				|| PipelinePrograms.mainPipelines().contains(copy) || PipelinePrograms.shadowPipelines().contains(copy)) {
			throw new AssertionError("terrain or copied pipelines were registered twice");
		}
		int before = copy.locationReads;
		for (int draw = 0; draw < 1_000; draw++) {
			PipelinePrograms.main(copy, PipelinePrograms.Context.WORLD);
			PipelinePrograms.shadow(copy, PipelinePrograms.Context.WORLD);
		}
		if (copy.locationReads != before) {
			throw new AssertionError("a positive copy lookup stopped using its cache");
		}
		System.out.println("terrain registration: direct and copied misses recover; positive caches and registration order retained");
	}

	/** A successful copy lookup is not an explicit terrain registration in either pass. */
	private static void cachedCopyTerrainRegistration() {
		RenderPipeline original = RenderPipelines.SOLID_TERRAIN;
		for (int lookup = 0; lookup < 3; lookup++) {
			CountingPipeline copy = new CountingPipeline(original, original.getLocation());
			if (lookup != 1 && PipelinePrograms.main(copy, PipelinePrograms.Context.WORLD) != ShaderKey.TERRAIN_SOLID) {
				throw new AssertionError("the early main lookup did not cache a matching pipeline copy");
			}
			if (lookup != 0 && PipelinePrograms.shadow(copy, PipelinePrograms.Context.WORLD) != ShaderKey.SHADOW_TERRAIN_CUTOUT) {
				throw new AssertionError("the early shadow lookup did not cache a matching pipeline copy");
			}
			int mainCount = PipelinePrograms.mainPipelines().size();
			int shadowCount = PipelinePrograms.shadowPipelines().size();
			if (!PipelinePrograms.addTerrain(copy, ShaderKey.TERRAIN_CUTOUT, ShaderKey.SHADOW_TRANSLUCENT)) {
				throw new AssertionError("a cached copy match prevented explicit terrain registration");
			}
			if (PipelinePrograms.main(copy, PipelinePrograms.Context.WORLD) != ShaderKey.TERRAIN_CUTOUT
					|| PipelinePrograms.shadow(copy, PipelinePrograms.Context.WORLD) != ShaderKey.SHADOW_TRANSLUCENT) {
				throw new AssertionError("explicit terrain registration retained a cached copy's shader choice");
			}
			if (PipelinePrograms.mainPipelines().size() != mainCount + 1
					|| PipelinePrograms.shadowPipelines().size() != shadowCount + 1
					|| PipelinePrograms.mainPipelines().get(mainCount) != copy
					|| PipelinePrograms.shadowPipelines().get(shadowCount) != copy) {
				throw new AssertionError("a cached copy was not appended to both prebuild registration lists");
			}
			if (PipelinePrograms.addTerrain(copy, ShaderKey.TERRAIN_CUTOUT, ShaderKey.SHADOW_TRANSLUCENT)
					|| PipelinePrograms.mainPipelines().size() != mainCount + 1
					|| PipelinePrograms.shadowPipelines().size() != shadowCount + 1) {
				throw new AssertionError("a promoted pipeline copy was registered more than once");
			}
			int before = copy.locationReads;
			for (int draw = 0; draw < 1_000; draw++) {
				PipelinePrograms.main(copy, PipelinePrograms.Context.WORLD);
				PipelinePrograms.shadow(copy, PipelinePrograms.Context.WORLD);
			}
			if (copy.locationReads != before) {
				throw new AssertionError("registered terrain resumed scanning for pipeline copies");
			}
		}
		if (PipelinePrograms.main(original, PipelinePrograms.Context.WORLD) != ShaderKey.TERRAIN_SOLID
				|| PipelinePrograms.shadow(original, PipelinePrograms.Context.WORLD) != ShaderKey.SHADOW_TERRAIN_CUTOUT) {
			throw new AssertionError("promoting a copy changed the original pipeline's registration");
		}
		System.out.println("terrain registration: early main/shadow copy matches promote to originals; shader choices, prebuild order and duplicate guards pass");
	}

	private static void lodFallback() {
		String[] paths = {"opaque_terrain", "transparent_terrain", "generic_objects"};
		ShaderKey[] main = {ShaderKey.LOD_TERRAIN, ShaderKey.LOD_WATER, ShaderKey.LOD_GENERIC};
		ShaderKey[] shadow = {ShaderKey.SHADOW_LOD, ShaderKey.SHADOW_LOD, null};
		for (int index = 0; index < paths.length; index++) {
			CountingPipeline pipeline = new CountingPipeline(RenderPipelines.SOLID_TERRAIN,
					Identifier.fromNamespaceAndPath("distanthorizons", paths[index]));
			ShaderKey expectedMain = LodCompat.installed() ? main[index] : null;
			ShaderKey expectedShadow = LodCompat.installed() ? shadow[index] : null;
			for (int draw = 0; draw < 1_000; draw++) {
				if (PipelinePrograms.main(pipeline, PipelinePrograms.Context.WORLD) != expectedMain
						|| PipelinePrograms.shadow(pipeline, PipelinePrograms.Context.WORLD) != expectedShadow) {
					throw new AssertionError("a cached copy miss bypassed the LOD fallback");
				}
			}
		}
		System.out.println("pipeline fallback: LOD API " + (LodCompat.installed() ? "present" : "absent") + " retains its main/shadow routing");
	}

	private static final class CountingPipeline extends RenderPipeline {
		private int locationReads;

		CountingPipeline(RenderPipeline original, Identifier location) {
			super(location, original.getShaders(), original.getShaderDefines(), original.getBindGroupLayouts(),
					original.getColorTargetStates().toArray(ColorTargetState[]::new), original.getDepthStencilState(),
					original.getPolygonMode(), !original.isCull(), original.getVertexFormatBindings().toArray(VertexFormat[]::new),
					original.getPrimitiveTopology(), original.pushConstantSize(), original.getSortKey());
		}

		@Override
		public Identifier getLocation() {
			locationReads++;
			return super.getLocation();
		}
	}

	private static final class PipelineCopy extends RenderPipeline {
		PipelineCopy(RenderPipeline original, Map<ShaderType, Identifier> shaders, ShaderDefines defines, List<VertexFormat> formats) {
			super(original.getLocation(), shaders, defines, original.getBindGroupLayouts(),
					original.getColorTargetStates().toArray(ColorTargetState[]::new), original.getDepthStencilState(),
					original.getPolygonMode(), !original.isCull(), formats.toArray(VertexFormat[]::new),
					original.getPrimitiveTopology(), original.pushConstantSize(), original.getSortKey());
		}
	}
}
