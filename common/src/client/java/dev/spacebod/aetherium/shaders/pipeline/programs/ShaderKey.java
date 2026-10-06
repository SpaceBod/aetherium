package dev.spacebod.aetherium.shaders.pipeline.programs;

import dev.spacebod.aetherium.shaders.gl.blending.AlphaTest;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTests;
import dev.spacebod.aetherium.shaders.gl.state.FogMode;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import java.util.Locale;

/**
 * Which pack program draws a vanilla pipeline, with which alpha test, fog mode and lighting model. There is no vertex
 * format column: the pack program always reads the vertex format of the vanilla pipeline it replaces, which the
 * transformer is told about.
 */
public enum ShaderKey {
	BASIC(ProgramId.Basic, AlphaTests.OFF, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	TEXTURED(ProgramId.Textured, AlphaTests.NON_ZERO_ALPHA, FogMode.OFF, LightingModel.LIGHTMAP),
	SKY_BASIC(ProgramId.SkyBasic, AlphaTests.OFF, FogMode.OFF, LightingModel.FULLBRIGHT),
	SKY_BASIC_COLOR(ProgramId.SkyBasic, AlphaTests.NON_ZERO_ALPHA, FogMode.OFF, LightingModel.LIGHTMAP),
	SKY_TEXTURED(ProgramId.SkyTextured, AlphaTests.OFF, FogMode.OFF, LightingModel.LIGHTMAP),
	CLOUDS(ProgramId.Clouds, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	TERRAIN_SOLID(ProgramId.TerrainSolid, AlphaTests.OFF, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	TERRAIN_CUTOUT(ProgramId.TerrainCutout, AlphaTests.HALF_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	TERRAIN_TRANSLUCENT(ProgramId.Water, AlphaTests.NON_ZERO_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	MOVING_BLOCK(ProgramId.Block, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	ENTITIES_SOLID(ProgramId.Entities, AlphaTests.OFF, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	ENTITIES_CUTOUT(ProgramId.Entities, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	ENTITIES_CUTOUT_DIFFUSE(ProgramId.Entities, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.DIFFUSE_LM),
	ENTITIES_TRANSLUCENT(ProgramId.EntitiesTrans, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.DIFFUSE_LM),
	ENTITIES_EYES(ProgramId.SpiderEyes, AlphaTests.NON_ZERO_ALPHA, FogMode.PER_VERTEX, LightingModel.FULLBRIGHT),
	ENTITIES_EYES_TRANS(ProgramId.SpiderEyes, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.FULLBRIGHT),
	HAND_CUTOUT(ProgramId.Hand, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	HAND_CUTOUT_DIFFUSE(ProgramId.Hand, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.DIFFUSE_LM),
	HAND_TEXT(ProgramId.Hand, AlphaTests.NON_ZERO_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	HAND_TEXT_TRANSLUCENT(ProgramId.HandWater, AlphaTests.NON_ZERO_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	HAND_TRANSLUCENT(ProgramId.HandWater, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	HAND_WATER_DIFFUSE(ProgramId.HandWater, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.DIFFUSE_LM),
	LIGHTNING(ProgramId.Lightning, AlphaTests.OFF, FogMode.PER_VERTEX, LightingModel.FULLBRIGHT),
	LEASH(ProgramId.Basic, AlphaTests.OFF, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	PARTICLES(ProgramId.Particles, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	PARTICLES_TRANS(ProgramId.ParticlesTrans, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	WEATHER(ProgramId.Weather, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	CRUMBLING(ProgramId.DamagedBlock, AlphaTests.ONE_TENTH_ALPHA, FogMode.OFF, LightingModel.FULLBRIGHT),
	TEXT(ProgramId.EntitiesTrans, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	TEXT_BE(ProgramId.BlockTrans, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	BLOCK_ENTITY(ProgramId.Block, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	BLOCK_ENTITY_DIFFUSE(ProgramId.Block, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.DIFFUSE_LM),
	BE_TRANSLUCENT(ProgramId.BlockTrans, AlphaTests.ONE_TENTH_ALPHA, FogMode.PER_VERTEX, LightingModel.DIFFUSE_LM),
	BEACON(ProgramId.BeaconBeam, AlphaTests.OFF, FogMode.PER_FRAGMENT, LightingModel.FULLBRIGHT),
	GLINT(ProgramId.ArmorGlint, AlphaTests.NON_ZERO_ALPHA, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	LINES(ProgramId.Line, AlphaTests.OFF, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	/** Level-of-detail terrain (dh_terrain / dh_water): drawn from the LOD mod's own vertex format, see LodCompat. */
	LOD_TERRAIN(ProgramId.DhTerrain, AlphaTests.OFF, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	LOD_WATER(ProgramId.DhWater, AlphaTests.OFF, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),
	/** The LOD mod's generic objects (LOD clouds, beacon beams): dh_generic, else dh_terrain. */
	LOD_GENERIC(ProgramId.DhGeneric, AlphaTests.OFF, FogMode.PER_VERTEX, LightingModel.LIGHTMAP),

	SHADOW_TERRAIN_CUTOUT(ProgramId.ShadowCutout, AlphaTests.ONE_TENTH_ALPHA, FogMode.OFF, LightingModel.LIGHTMAP),
	SHADOW_TRANSLUCENT(ProgramId.ShadowWater, AlphaTests.ONE_TENTH_ALPHA, FogMode.OFF, LightingModel.LIGHTMAP),
	SHADOW_ENTITIES_CUTOUT(ProgramId.ShadowEntities, AlphaTests.ONE_TENTH_ALPHA, FogMode.OFF, LightingModel.LIGHTMAP),
	SHADOW_BLOCK(ProgramId.ShadowBlock, AlphaTests.ONE_TENTH_ALPHA, FogMode.OFF, LightingModel.LIGHTMAP),
	SHADOW_BASIC(ProgramId.Shadow, AlphaTests.OFF, FogMode.OFF, LightingModel.LIGHTMAP),
	SHADOW_TEX(ProgramId.Shadow, AlphaTests.NON_ZERO_ALPHA, FogMode.OFF, LightingModel.LIGHTMAP),
	SHADOW_LINES(ProgramId.Shadow, AlphaTests.OFF, FogMode.OFF, LightingModel.LIGHTMAP),
	SHADOW_LEASH(ProgramId.Shadow, AlphaTests.OFF, FogMode.OFF, LightingModel.LIGHTMAP),
	SHADOW_LIGHTNING(ProgramId.ShadowLightning, AlphaTests.OFF, FogMode.OFF, LightingModel.FULLBRIGHT),
	SHADOW_PARTICLES(ProgramId.Shadow, AlphaTests.ONE_TENTH_ALPHA, FogMode.OFF, LightingModel.LIGHTMAP),
	SHADOW_TEXT(ProgramId.ShadowEntities, AlphaTests.NON_ZERO_ALPHA, FogMode.OFF, LightingModel.LIGHTMAP),
	SHADOW_BEACON_BEAM(ProgramId.ShadowEntities, AlphaTests.OFF, FogMode.OFF, LightingModel.FULLBRIGHT),
	SHADOW_LOD(ProgramId.DhShadow, AlphaTests.OFF, FogMode.OFF, LightingModel.LIGHTMAP);

	private final ProgramId program;
	private final AlphaTest alphaTest;
	private final FogMode fogMode;
	private final LightingModel lightingModel;

	ShaderKey(ProgramId program, AlphaTest alphaTest, FogMode fogMode, LightingModel lightingModel) {
		this.program = program;
		this.alphaTest = alphaTest;
		this.fogMode = fogMode;
		this.lightingModel = lightingModel;
	}

	public ProgramId getProgram() {
		return program;
	}

	public AlphaTest getAlphaTest() {
		return alphaTest;
	}

	public FogMode getFogMode() {
		return fogMode;
	}

	public String getName() {
		return toString().toLowerCase(Locale.ROOT);
	}

	public boolean isShadow() {
		return name().startsWith("SHADOW_");
	}

	public boolean shouldIgnoreLightmap() {
		return lightingModel == LightingModel.FULLBRIGHT;
	}

	/** Level-of-detail terrain: the pack's dh_* programs, transformed for the LOD vertex format. */
	public boolean isLod() {
		return this == LOD_TERRAIN || this == LOD_WATER || this == LOD_GENERIC || this == SHADOW_LOD;
	}

	public boolean isGlint() {
		return this == GLINT;
	}

	public boolean isText() {
		return name().contains("TEXT");
	}

	enum LightingModel {
		FULLBRIGHT,
		LIGHTMAP,
		DIFFUSE_LM
	}
}
