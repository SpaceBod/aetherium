package dev.spacebod.aetherium.client.mixin.chunks;

import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which terrain passes the chunk renderer's last {@code prepare} found geometry for. */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public interface ChunkRendererAccessor {
	@Accessor("shouldDraw")
	boolean[] aetherium$shouldDraw();
}
