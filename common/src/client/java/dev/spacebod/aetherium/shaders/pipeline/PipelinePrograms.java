package dev.spacebod.aetherium.shaders.pipeline;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.spacebod.aetherium.shaders.compat.lod.LodCompat;
import dev.spacebod.aetherium.shaders.pipeline.programs.ShaderKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

/**
 * Which pack program replaces each vanilla 26.3 pipeline in the main and shadow passes (26.3 names: the
 * {@code *_TERRAIN_MULTIDRAW} variants, {@code TEXT_GRAYSCALE_*} for the intensity pipelines, a single
 * {@code WEATHER}). A pipeline missing here is drawn with its own vanilla shader, widened to the pack's gbuffer targets
 * (it writes the first one only).
 */
public final class PipelinePrograms {
	/** Context a key choice may depend on (hand pass, solid/translucent hand, block-entity geometry). */
	public record Context(boolean hand, boolean handSolid, boolean blockEntity) {
		public static final Context WORLD = new Context(false, false, false);
		/**
		 * The first-person hand's solid part: the early hand drawn before the deferred passes, or vanilla's whole hand pass
		 * when the hand is not split.
		 */
		public static final Context HAND = new Context(true, true, false);
		/** The translucent rest of the hand (translucent held items), drawn after the early solid hand: the hand_water programs. */
		public static final Context HAND_TRANSLUCENT = new Context(true, false, false);
		/** Block-entity geometry (chests, signs, banners ...): the pack's block programs. */
		public static final Context BLOCK_ENTITY = new Context(false, false, true);
	}

	private interface Choice {
		@Nullable ShaderKey choose(Context context);
	}

	private static final Map<RenderPipeline, Choice> MAIN = new IdentityHashMap<>();
	private static final Map<RenderPipeline, ShaderKey> SHADOW = new IdentityHashMap<>();
	/** Registration order (terrain first): the order programs are prebuilt in. */
	private static final List<RenderPipeline> MAIN_ORDER = new ArrayList<>();
	private static final List<RenderPipeline> SHADOW_ORDER = new ArrayList<>();

	static {
		main(RenderPipelines.SOLID_BLOCK, ShaderKey.TERRAIN_SOLID);
		main(RenderPipelines.CUTOUT_BLOCK, ShaderKey.TERRAIN_CUTOUT);
		main(RenderPipelines.SOLID_TERRAIN, ShaderKey.TERRAIN_SOLID);
		main(RenderPipelines.SOLID_TERRAIN_MULTIDRAW, ShaderKey.TERRAIN_SOLID);
		main(RenderPipelines.CUTOUT_TERRAIN, ShaderKey.TERRAIN_CUTOUT);
		main(RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW, ShaderKey.TERRAIN_CUTOUT);
		main(RenderPipelines.TRANSLUCENT_TERRAIN, ShaderKey.TERRAIN_TRANSLUCENT);
		main(RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW, ShaderKey.TERRAIN_TRANSLUCENT);
		main(RenderPipelines.TRANSLUCENT_BLOCK, ShaderKey.MOVING_BLOCK);
		main(RenderPipelines.WORLD_BORDER, ShaderKey.TEXTURED);
		mainChoice(RenderPipelines.ENTITY_CUTOUT, PipelinePrograms::cutout);
		mainChoice(RenderPipelines.ENTITY_CUTOUT_CULL, PipelinePrograms::cutout);
		mainChoice(RenderPipelines.ENTITY_CUTOUT_DISSOLVE, PipelinePrograms::cutout);
		mainChoice(RenderPipelines.ENTITY_TRANSLUCENT_CULL, PipelinePrograms::translucent);
		mainChoice(RenderPipelines.ITEM_TRANSLUCENT, PipelinePrograms::itemTranslucent);
		mainChoice(RenderPipelines.ITEM_CUTOUT, PipelinePrograms::cutout);
		mainChoice(RenderPipelines.ENTITY_TRANSLUCENT, PipelinePrograms::translucent);
		mainChoice(RenderPipelines.ENTITY_SHADOW, PipelinePrograms::translucent);
		main(RenderPipelines.LINES, ShaderKey.LINES);
		main(RenderPipelines.LINES_TRANSLUCENT, ShaderKey.LINES);
		main(RenderPipelines.SECONDARY_BLOCK_OUTLINE, ShaderKey.LINES);
		main(RenderPipelines.LINES_DEPTH_BIAS, ShaderKey.LINES);
		main(RenderPipelines.LINES_TRANSLUCENT_NO_DEPTH_WRITE, ShaderKey.LINES);
		main(RenderPipelines.STARS, ShaderKey.SKY_BASIC);
		main(RenderPipelines.SUNRISE_SUNSET, ShaderKey.SKY_BASIC_COLOR);
		main(RenderPipelines.SKY, ShaderKey.SKY_BASIC);
		main(RenderPipelines.CELESTIAL, ShaderKey.SKY_TEXTURED);
		main(RenderPipelines.OPAQUE_PARTICLE, ShaderKey.PARTICLES);
		main(RenderPipelines.TRANSLUCENT_PARTICLE, ShaderKey.PARTICLES_TRANS);
		main(RenderPipelines.WATER_MASK, ShaderKey.BASIC);
		main(RenderPipelines.GLINT, ShaderKey.GLINT);
		mainChoice(RenderPipelines.ARMOR_CUTOUT_NO_CULL, PipelinePrograms::cutout);
		main(RenderPipelines.EYES, ShaderKey.ENTITIES_EYES);
		main(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE, ShaderKey.ENTITIES_EYES_TRANS);
		mainChoice(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, PipelinePrograms::cutout);
		mainChoice(RenderPipelines.WOLF_ARMOR_CRACKS, PipelinePrograms::translucent);
		mainChoice(RenderPipelines.BREEZE_WIND, PipelinePrograms::translucent);
		mainChoice(RenderPipelines.ENTITY_SOLID, PipelinePrograms::solid);
		mainChoice(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, PipelinePrograms::solid);
		main(RenderPipelines.END_GATEWAY, ShaderKey.BLOCK_ENTITY);
		main(RenderPipelines.ENERGY_SWIRL, ShaderKey.ENTITIES_CUTOUT);
		main(RenderPipelines.END_CRYSTAL_BEAM, ShaderKey.ENTITIES_CUTOUT);
		main(RenderPipelines.ENTITY_CUTOUT_Z_OFFSET, ShaderKey.ENTITIES_CUTOUT);
		main(RenderPipelines.LIGHTNING, ShaderKey.LIGHTNING);
		main(RenderPipelines.DRAGON_RAYS, ShaderKey.LIGHTNING);
		main(RenderPipelines.BEACON_BEAM_OPAQUE, ShaderKey.BEACON);
		main(RenderPipelines.BEACON_BEAM_TRANSLUCENT, ShaderKey.BEACON);
		main(RenderPipelines.END_PORTAL, ShaderKey.BLOCK_ENTITY);
		main(RenderPipelines.END_SKY, ShaderKey.SKY_TEXTURED);
		main(RenderPipelines.WEATHER, ShaderKey.WEATHER);
		mainChoice(RenderPipelines.TEXT, PipelinePrograms::text);
		mainChoice(RenderPipelines.TEXT_POLYGON_OFFSET, PipelinePrograms::text);
		mainChoice(RenderPipelines.TEXT_SEE_THROUGH, PipelinePrograms::text);
		mainChoice(RenderPipelines.TEXT_GRAYSCALE, PipelinePrograms::text);
		mainChoice(RenderPipelines.TEXT_GRAYSCALE_POLYGON_OFFSET, PipelinePrograms::text);
		mainChoice(RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH, PipelinePrograms::text);
		main(RenderPipelines.CRUMBLING, ShaderKey.CRUMBLING);
		main(RenderPipelines.LEASH, ShaderKey.LEASH);
		main(RenderPipelines.CLOUDS, ShaderKey.CLOUDS);
		main(RenderPipelines.FLAT_CLOUDS, ShaderKey.CLOUDS);
		mainChoice(RenderPipelines.BANNER_PATTERN, PipelinePrograms::translucent);
		// 26.3 draws the enchantment glint in the same draw (GLINT define): the pack's base program lights the item and
		// adds vanilla's glint term itself (WorldProgram.addGlint).
		mainChoice(RenderPipelines.ITEM_CUTOUT_GLINT, PipelinePrograms::cutout);
		mainChoice(RenderPipelines.ITEM_CUTOUT_GLINT_SPECIAL, PipelinePrograms::cutout);
		mainChoice(RenderPipelines.ITEM_TRANSLUCENT_GLINT, PipelinePrograms::itemTranslucent);
		mainChoice(RenderPipelines.ITEM_TRANSLUCENT_GLINT_SPECIAL, PipelinePrograms::itemTranslucent);
		mainChoice(RenderPipelines.ENTITY_SOLID_GLINT, PipelinePrograms::solid);
		mainChoice(RenderPipelines.ARMOR_CUTOUT_NO_CULL_GLINT, PipelinePrograms::cutout);

		shadow(RenderPipelines.SOLID_BLOCK, ShaderKey.SHADOW_TERRAIN_CUTOUT);
		shadow(RenderPipelines.SOLID_TERRAIN, ShaderKey.SHADOW_TERRAIN_CUTOUT);
		shadow(RenderPipelines.SOLID_TERRAIN_MULTIDRAW, ShaderKey.SHADOW_TERRAIN_CUTOUT);
		shadow(RenderPipelines.CUTOUT_TERRAIN, ShaderKey.SHADOW_TERRAIN_CUTOUT);
		shadow(RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW, ShaderKey.SHADOW_TERRAIN_CUTOUT);
		shadow(RenderPipelines.TRANSLUCENT_TERRAIN, ShaderKey.SHADOW_TRANSLUCENT);
		shadow(RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW, ShaderKey.SHADOW_TRANSLUCENT);
		shadow(RenderPipelines.CUTOUT_BLOCK, ShaderKey.SHADOW_TERRAIN_CUTOUT);
		shadow(RenderPipelines.TRANSLUCENT_BLOCK, ShaderKey.SHADOW_TRANSLUCENT);
		for (RenderPipeline entity : new RenderPipeline[]{RenderPipelines.ENTITY_CUTOUT, RenderPipelines.ARMOR_CUTOUT_NO_CULL,
				RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, RenderPipelines.ENTITY_SOLID, RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD,
				RenderPipelines.ENTITY_CUTOUT_CULL, RenderPipelines.ITEM_CUTOUT, RenderPipelines.ITEM_TRANSLUCENT, RenderPipelines.ENTITY_TRANSLUCENT,
				RenderPipelines.ENTITY_CUTOUT_DISSOLVE, RenderPipelines.ENTITY_TRANSLUCENT_CULL, RenderPipelines.END_CRYSTAL_BEAM,
				RenderPipelines.ENTITY_CUTOUT_Z_OFFSET, RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE, RenderPipelines.BREEZE_WIND, RenderPipelines.EYES,
				RenderPipelines.BANNER_PATTERN, RenderPipelines.ENERGY_SWIRL, RenderPipelines.GLINT,
				RenderPipelines.WOLF_ARMOR_CRACKS, RenderPipelines.ITEM_CUTOUT_GLINT, RenderPipelines.ITEM_CUTOUT_GLINT_SPECIAL,
				RenderPipelines.ITEM_TRANSLUCENT_GLINT, RenderPipelines.ITEM_TRANSLUCENT_GLINT_SPECIAL, RenderPipelines.ENTITY_SOLID_GLINT,
				RenderPipelines.ARMOR_CUTOUT_NO_CULL_GLINT}) {
			if (entity != null) {
				shadow(entity, ShaderKey.SHADOW_ENTITIES_CUTOUT);
			}
		}
		shadow(RenderPipelines.CRUMBLING, ShaderKey.SHADOW_TEX);
		shadow(RenderPipelines.WEATHER, ShaderKey.SHADOW_PARTICLES);
		shadow(RenderPipelines.OPAQUE_PARTICLE, ShaderKey.SHADOW_PARTICLES);
		shadow(RenderPipelines.TRANSLUCENT_PARTICLE, ShaderKey.SHADOW_PARTICLES);
		shadow(RenderPipelines.LEASH, ShaderKey.SHADOW_LEASH);
		shadow(RenderPipelines.WATER_MASK, ShaderKey.SHADOW_BASIC);
		shadow(RenderPipelines.LINES, ShaderKey.SHADOW_LINES);
		shadow(RenderPipelines.SECONDARY_BLOCK_OUTLINE, ShaderKey.SHADOW_LINES);
		// Sign text, name tags and map contents; intensity glyphs read their single channel through the pipeline's define.
		for (RenderPipeline text : new RenderPipeline[]{RenderPipelines.TEXT, RenderPipelines.TEXT_POLYGON_OFFSET, RenderPipelines.TEXT_SEE_THROUGH,
				RenderPipelines.TEXT_GRAYSCALE, RenderPipelines.TEXT_GRAYSCALE_POLYGON_OFFSET, RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH}) {
			shadow(text, ShaderKey.SHADOW_TEXT);
		}
		shadow(RenderPipelines.BEACON_BEAM_OPAQUE, ShaderKey.SHADOW_BEACON_BEAM);
		shadow(RenderPipelines.BEACON_BEAM_TRANSLUCENT, ShaderKey.SHADOW_BEACON_BEAM);
		// shadow_block is the End portal and gateway's program; other block entities cast through shadow_entities.
		shadow(RenderPipelines.END_PORTAL, ShaderKey.SHADOW_BLOCK);
		shadow(RenderPipelines.END_GATEWAY, ShaderKey.SHADOW_BLOCK);
		shadow(RenderPipelines.LIGHTNING, ShaderKey.SHADOW_LIGHTNING);
		shadow(RenderPipelines.DRAGON_RAYS, ShaderKey.SHADOW_LIGHTNING);
	}

	private PipelinePrograms() {
	}

	private static void main(RenderPipeline pipeline, ShaderKey key) {
		mainChoice(pipeline, context -> key);
	}

	private static void mainChoice(RenderPipeline pipeline, Choice choice) {
		if (MAIN.put(pipeline, choice) == null) {
			MAIN_ORDER.add(pipeline);
		}
	}

	private static void shadow(RenderPipeline pipeline, ShaderKey key) {
		if (SHADOW.put(pipeline, key) == null) {
			SHADOW_ORDER.add(pipeline);
		}
	}

	/**
	 * A terrain pipeline created after start-up (the chunk renderer's, one per terrain pass): drawn with {@code main} in
	 * the main pass and {@code shadow} in the shadow pass. False when it was already known.
	 */
	public static boolean addTerrain(RenderPipeline pipeline, ShaderKey main, ShaderKey shadow) {
		if (MAIN.containsKey(pipeline)) {
			return false;
		}
		main(pipeline, main);
		shadow(pipeline, shadow);
		return true;
	}

	/** Every vanilla pipeline with a main-pass program, terrain first. */
	public static List<RenderPipeline> mainPipelines() {
		return Collections.unmodifiableList(MAIN_ORDER);
	}

	/** Every vanilla pipeline with a shadow-pass program, terrain first. */
	public static List<RenderPipeline> shadowPipelines() {
		return Collections.unmodifiableList(SHADOW_ORDER);
	}

	/** The pack program for a vanilla pipeline in the main (gbuffers) pass, or null (draw it with its vanilla shader). */
	public static @Nullable ShaderKey main(RenderPipeline pipeline, Context context) {
		Choice choice = MAIN.get(pipeline);
		return choice == null ? LodCompat.key(pipeline, false) : choice.choose(context);
	}

	/**
	 * The pack program for a vanilla pipeline in the shadow pass, or null: not drawn into the shadow map (anything
	 * unlisted). The shadow programs do not depend on the context: block entities draw with their pipeline's program.
	 */
	public static @Nullable ShaderKey shadow(RenderPipeline pipeline, Context context) {
		ShaderKey key = SHADOW.get(pipeline);
		return key != null ? key : LodCompat.key(pipeline, true);
	}

	private static ShaderKey cutout(Context c) {
		if (c.hand()) {
			return c.handSolid() ? ShaderKey.HAND_CUTOUT_DIFFUSE : ShaderKey.HAND_WATER_DIFFUSE;
		}
		return c.blockEntity() ? ShaderKey.BLOCK_ENTITY_DIFFUSE : ShaderKey.ENTITIES_CUTOUT_DIFFUSE;
	}

	private static ShaderKey solid(Context c) {
		if (c.hand()) {
			return c.handSolid() ? ShaderKey.HAND_CUTOUT : ShaderKey.HAND_TRANSLUCENT;
		}
		return c.blockEntity() ? ShaderKey.BLOCK_ENTITY : ShaderKey.ENTITIES_SOLID;
	}

	private static ShaderKey translucent(Context c) {
		if (c.hand()) {
			// The bare arm draws with entity_translucent, in the solid hand: gbuffers_hand.
			return c.handSolid() ? ShaderKey.HAND_CUTOUT_DIFFUSE : ShaderKey.HAND_WATER_DIFFUSE;
		}
		return c.blockEntity() ? ShaderKey.BE_TRANSLUCENT : ShaderKey.ENTITIES_TRANSLUCENT;
	}

	/** Translucent items (stained glass, ice ...): in the hand, gbuffers_hand_water (also when the hand is drawn in one pass). */
	private static ShaderKey itemTranslucent(Context c) {
		return c.hand() ? ShaderKey.HAND_WATER_DIFFUSE : ShaderKey.ENTITIES_TRANSLUCENT;
	}

	private static ShaderKey text(Context c) {
		if (c.hand()) {
			return c.handSolid() ? ShaderKey.HAND_TEXT : ShaderKey.HAND_TEXT_TRANSLUCENT;
		}
		return c.blockEntity() ? ShaderKey.TEXT_BE : ShaderKey.TEXT;
	}
}
