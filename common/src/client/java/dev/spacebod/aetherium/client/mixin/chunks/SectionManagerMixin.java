package dev.spacebod.aetherium.client.mixin.chunks;

import dev.spacebod.aetherium.client.render.SectionBuilds;
import java.util.ArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sections whose finished builds the renderer took this frame count as rebuilt ({@link SectionBuilds}: the shadow cache). */
@Mixin(value = RenderSectionManager.class, remap = false)
abstract class SectionManagerMixin {
	@Inject(method = "processChunkBuildResults", at = @At("RETURN"))
	private void aetherium$built(ArrayList<?> results, Viewport viewport, UniformBufferManager uniforms, CallbackInfoReturnable<Integer> cir) {
		int changed = cir.getReturnValueI();
		if (changed > 0) {
			SectionBuilds.COMPLETED.add(changed);
		}
	}
}
