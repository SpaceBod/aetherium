package dev.spacebod.aetherium.client.mixin.shaders.ids;

import dev.spacebod.aetherium.shaders.helpers.ItemIdsHolder;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.ItemOwner;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Aetherium Shaders: the item (and its model id) an item render state is filled for, kept for its id. */
@Mixin(ItemModelResolver.class)
abstract class ItemModelResolverIdsMixin {
	@Inject(method = "appendItemLayers", at = @At("HEAD"))
	private void aetherium$rememberItem(ItemStackRenderState output, ItemStack item, ItemDisplayContext displayContext, @Nullable Level level,
			@Nullable ItemOwner owner, int seed, CallbackInfo ci) {
		if (!item.isEmpty()) {
			((ItemIdsHolder) output).aetherium$setItem(item.getItem(), item.get(DataComponents.ITEM_MODEL));
		}
	}
}
