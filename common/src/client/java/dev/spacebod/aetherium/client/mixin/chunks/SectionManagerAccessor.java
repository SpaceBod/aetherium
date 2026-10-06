package dev.spacebod.aetherium.client.mixin.chunks;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.RemovableMultiForest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Every section with geometry, as a tree any viewport can walk. */
@Mixin(value = RenderSectionManager.class, remap = false)
public interface SectionManagerAccessor {
	@Accessor("renderableSectionTree")
	RemovableMultiForest aetherium$renderable();
}
