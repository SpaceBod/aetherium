package dev.spacebod.aetherium.client.mixin.chunks;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The chunk renderer's world renderer: its section manager, terrain uniforms and this frame's fog. */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface WorldRendererAccessor {
	@Accessor("renderSectionManager")
	RenderSectionManager aetherium$sections();

	@Accessor("uniformBufferManager")
	UniformBufferManager aetherium$uniforms();

	@Accessor("lastFogParameters")
	FogParameters aetherium$fog();

	@Accessor("useTranslucencySorting")
	boolean aetherium$translucencySorting();
}
