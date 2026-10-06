package dev.spacebod.aetherium.client.mixin.shaders.ids;

import dev.spacebod.aetherium.shaders.helpers.ItemIdsHolder;
import dev.spacebod.aetherium.shaders.helpers.DrawIds;
import dev.spacebod.aetherium.shaders.uniforms.CapturedRenderingState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders: an item render state remembers which item it shows (set by the model resolver), and submitting it
 * makes that item {@code currentRenderedItemId} for its layers.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemSubmitIdsMixin implements ItemIdsHolder {
	@Unique
	private @Nullable Item aetherium$item;
	@Unique
	private @Nullable Identifier aetherium$model;
	@Unique
	private int aetherium$savedItem;
	@Unique
	private int aetherium$savedBlockEntity;

	@Override
	public void aetherium$setItem(Item item, @Nullable Identifier model) {
		if (aetherium$item == null) {
			aetherium$item = item;
			aetherium$model = model;
		}
	}

	@Inject(method = "clear", at = @At("HEAD"))
	private void aetherium$clearItem(CallbackInfo ci) {
		aetherium$item = null;
		aetherium$model = null;
	}

	@Inject(method = "submit", at = @At("HEAD"))
	private void aetherium$beginItem(CallbackInfo ci) {
		if (DrawIds.tracking()) {
			aetherium$savedItem = CapturedRenderingState.INSTANCE.getCurrentRenderedItem();
			aetherium$savedBlockEntity = CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity();
			DrawIds.beginItem(aetherium$item, aetherium$model);
		}
	}

	@Inject(method = "submit", at = @At("RETURN"))
	private void aetherium$endItem(CallbackInfo ci) {
		if (DrawIds.tracking()) {
			CapturedRenderingState.INSTANCE.setCurrentRenderedItem(aetherium$savedItem);
			CapturedRenderingState.INSTANCE.setCurrentBlockEntity(aetherium$savedBlockEntity);
		}
	}
}
