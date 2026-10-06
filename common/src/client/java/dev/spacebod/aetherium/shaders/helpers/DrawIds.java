package dev.spacebod.aetherium.shaders.helpers;

import dev.spacebod.aetherium.shaders.engine.EntityVertexFormats;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.NamespacedId;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import dev.spacebod.aetherium.shaders.uniforms.CapturedRenderingState;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ZombieVillagerRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The pack-facing ids of what is being drawn ({@code entityId}, {@code blockEntityId}, {@code currentRenderedItemId}),
 * from the pack's {@code entity.properties}, {@code block.properties} and {@code item.properties}. Set while vanilla
 * submits an entity, block entity or item, captured into each submit, and restored when the submit's vertices are
 * written (they carry the ids per vertex: {@code EntityVertexFormats}). Render thread only.
 */
public final class DrawIds {
	private static final NamespacedId CURRENT_PLAYER = new NamespacedId("minecraft", "current_player");
	private static final NamespacedId CONVERTING_VILLAGER = new NamespacedId("minecraft", "zombie_villager_converting");
	private static final Map<EntityType<?>, NamespacedId> ENTITY_NAMES = new IdentityHashMap<>();
	/** Draw flags: block-entity geometry (drawn with the block programs). */
	public static final int BLOCK_ENTITY = 1;
	/** An entity only the shadow map draws (outside the camera's view, or the first-person player). */
	public static final int SHADOW_ONLY = 2;
	/** The camera's own player ({@code shadowPlayer}). */
	public static final int PLAYER = 4;
	/** A block entity whose block emits light ({@code shadowLightBlockEntities}). */
	public static final int LIGHT = 8;
	/** Every flag combination: render-type copies are kept per combination. */
	public static final int COMBINATIONS = 16;
	/** Render-type name prefix of draws with flags: {@code aetherium_draw:<flags, one hex digit>:<vanilla name>}. */
	public static final String PREFIX = "aetherium_draw:";
	/** The flags of what is being submitted (submission) or built (feature preparation). */
	private static int flags;

	private DrawIds() {
	}

	/** Whether ids are tracked now: a pack draws the world (block-entity draws are split even without id maps). */
	public static boolean tracking() {
		return EntityVertexFormats.active();
	}

	/** Entity submission starts: its id from entity.properties (special: the camera's player, a converting zombie villager). */
	public static void beginEntity(EntityRenderState state) {
		boolean player = state instanceof AvatarRenderState avatar && Minecraft.getInstance().getCameraEntity() != null
				&& Minecraft.getInstance().getCameraEntity().getId() == avatar.id;
		flags = (ShadowCasters.isShadowOnly(state) ? SHADOW_ONLY : 0) | (player ? PLAYER : 0);
		Object2IntFunction<NamespacedId> ids = WorldRenderingSettings.INSTANCE.getEntityIds();
		if (ids == null) {
			return;
		}
		int id;
		if (state instanceof ZombieVillagerRenderState zombie && zombie.isConverting && WorldRenderingSettings.INSTANCE.hasVillagerConversionId()) {
			id = ids.applyAsInt(CONVERTING_VILLAGER);
		} else if (player && ids.containsKey(CURRENT_PLAYER)) {
			id = ids.getInt(CURRENT_PLAYER);
		} else {
			id = ids.applyAsInt(ENTITY_NAMES.computeIfAbsent(state.entityType, type -> {
				Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
				return new NamespacedId(key.getNamespace(), key.getPath());
			}));
		}
		CapturedRenderingState.INSTANCE.setCurrentEntity(id);
	}

	public static void endEntity() {
		flags = 0;
		CapturedRenderingState.INSTANCE.setCurrentEntity(0);
		CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
	}

	/** Block entity submission starts: its block state's id from block.properties. */
	public static void beginBlockEntity(BlockState state) {
		flags = BLOCK_ENTITY | (state.getLightEmission() > 0 ? LIGHT : 0);
		Object2IntMap<BlockState> ids = WorldRenderingSettings.INSTANCE.getBlockStateIds();
		if (ids != null) {
			CapturedRenderingState.INSTANCE.setCurrentBlockEntity(ids.getInt(state));
		}
	}

	public static void endBlockEntity() {
		flags = 0;
		CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
	}

	/** The flags of what is being submitted or built now (0 = a plain draw). */
	public static int flags() {
		return flags;
	}

	/**
	 * An item's layers are submitted: its id from item.properties (by its model id when it has one), or for block items
	 * the block's id from block.properties, as packs expect.
	 */
	public static void beginItem(@Nullable Item item, @Nullable Identifier model) {
		if (item == null || WorldRenderingSettings.INSTANCE.getItemIds() == null) {
			return;
		}
		if (item instanceof BlockItem blockItem && !(item instanceof SolidBucketItem)) {
			Object2IntMap<BlockState> blocks = WorldRenderingSettings.INSTANCE.getBlockStateIds();
			if (blocks != null) {
				CapturedRenderingState.INSTANCE.setCurrentBlockEntity(1);
				CapturedRenderingState.INSTANCE.setCurrentRenderedItem(blocks.getOrDefault(blockItem.getBlock().defaultBlockState(), 0));
			}
			return;
		}
		Identifier key = model != null ? model : BuiltInRegistries.ITEM.getKey(item);
		CapturedRenderingState.INSTANCE.setCurrentRenderedItem(WorldRenderingSettings.INSTANCE.getItemIds()
				.applyAsInt(new NamespacedId(key.getNamespace(), key.getPath())));
	}

	/** The ids as one packed value: entity, block entity, item (16 bits each), plus the draw flags. */
	public static long capture() {
		CapturedRenderingState s = CapturedRenderingState.INSTANCE;
		return (s.getCurrentRenderedEntity() & 0xFFFFL) | (s.getCurrentRenderedBlockEntity() & 0xFFFFL) << 16
				| (s.getCurrentRenderedItem() & 0xFFFFL) << 32 | (long) flags << 48;
	}

	/** Restores ids {@link #capture} packed. */
	public static void apply(long packed) {
		CapturedRenderingState s = CapturedRenderingState.INSTANCE;
		s.setCurrentEntity((short) packed);
		s.setCurrentBlockEntity((short) (packed >>> 16));
		s.setCurrentRenderedItem((short) (packed >>> 32));
		flags = (int) (packed >>> 48) & 0xFF;
	}

	public static void clear() {
		apply(0L);
	}
}
