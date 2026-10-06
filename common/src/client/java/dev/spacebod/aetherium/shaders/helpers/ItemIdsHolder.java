package dev.spacebod.aetherium.shaders.helpers;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/** An item render state's item, kept for {@code currentRenderedItemId} (first item set since the state was cleared). */
public interface ItemIdsHolder {
	void aetherium$setItem(Item item, @Nullable Identifier model);
}
