package dev.spacebod.aetherium.client.mixin.shaders.ids;

import dev.spacebod.aetherium.shaders.helpers.CapturedIds;
import dev.spacebod.aetherium.shaders.helpers.DrawIds;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.NamespacedId;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.LeashFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders: each submit carries the ids current when it was created (submission runs inside the entity / block
 * entity / item it belongs to); its vertices are written later, when the frame's features are prepared. The fire on a
 * burning entity is not the entity: its entity id is {@code minecraft:entity_flame}'s from entity.properties, with no
 * block entity or item id (its draw flags stay the entity's).
 */
@Mixin({ModelFeatureRenderer.Submit.class, ItemFeatureRenderer.Submit.class, TextFeatureRenderer.Submit.class, CustomFeatureRenderer.Submit.class,
		FlameFeatureRenderer.Submit.class, BlockModelFeatureRenderer.Submit.class, LeashFeatureRenderer.Submit.class, MovingBlockFeatureRenderer.Submit.class})
abstract class SubmitIdsMixin implements CapturedIds {
	@Unique
	private static final NamespacedId aetherium$FLAME = new NamespacedId("minecraft", "entity_flame");
	/** The entity, block entity and item ids of a packed capture (the draw flags are above them). */
	@Unique
	private static final long aetherium$ID_BITS = 0xFFFF_FFFF_FFFFL;

	@Unique
	private long aetherium$ids;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void aetherium$capture(CallbackInfo ci) {
		if (!DrawIds.tracking()) {
			aetherium$ids = 0L;
			return;
		}
		long ids = DrawIds.capture();
		if ((Object) this instanceof FlameFeatureRenderer.Submit) {
			Object2IntFunction<NamespacedId> entities = WorldRenderingSettings.INSTANCE.getEntityIds();
			int flame = entities == null ? 0 : entities.applyAsInt(aetherium$FLAME);
			ids = ids & ~aetherium$ID_BITS | flame & 0xFFFFL;
		}
		aetherium$ids = ids;
	}

	@Override
	public long aetherium$ids() {
		return aetherium$ids;
	}
}
