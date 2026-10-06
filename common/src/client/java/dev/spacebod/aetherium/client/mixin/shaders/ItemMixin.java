package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.shaders.helpers.ItemLightProvider;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Aetherium Shaders: every item is an {@link ItemLightProvider} (the interface's defaults give vanilla block
 * items their block's light), which the held-item light uniforms rely on.
 */
@Mixin(Item.class)
abstract class ItemMixin implements ItemLightProvider {
}
