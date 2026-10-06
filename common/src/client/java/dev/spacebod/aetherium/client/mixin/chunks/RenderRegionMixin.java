package dev.spacebod.aetherium.client.mixin.chunks;

import dev.spacebod.aetherium.shaders.chunks.ChunkShadows;
import java.util.IdentityHashMap;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A region's draw batches for the shadow map, beside the camera's: while {@link ChunkShadows} draws, the region hands
 * out (and clears) these, so the camera's batches, filled for its own view and face culling, are never touched.
 */
@Mixin(value = RenderRegion.class, remap = false)
abstract class RenderRegionMixin {
	@Unique
	private final Map<TerrainRenderPass, MultiDrawBatch> aetherium$shadowBatches = new IdentityHashMap<>();

	@Inject(method = "getCachedBatch", at = @At("HEAD"), cancellable = true)
	private void aetherium$shadowBatch(TerrainRenderPass pass, CallbackInfoReturnable<MultiDrawBatch> cir) {
		if (ChunkShadows.drawing()) {
			cir.setReturnValue(aetherium$shadowBatches.computeIfAbsent(pass, p -> MultiDrawBatch.newBatch(ModelQuadFacing.COUNT * 256 + 1)));
		}
	}

	@Inject(method = "clearAllCachedBatches", at = @At("HEAD"), cancellable = true)
	private void aetherium$clearAll(CallbackInfo ci) {
		aetherium$shadowBatches.values().forEach(MultiDrawBatch::clear);
		if (ChunkShadows.drawing()) {
			ci.cancel();
		}
	}

	@Inject(method = "clearCachedBatchFor", at = @At("HEAD"), cancellable = true)
	private void aetherium$clearFor(TerrainRenderPass pass, CallbackInfo ci) {
		MultiDrawBatch batch = aetherium$shadowBatches.get(pass);
		if (batch != null) {
			batch.clear();
		}
		if (ChunkShadows.drawing()) {
			ci.cancel();
		}
	}

	@Inject(method = "delete", at = @At("HEAD"))
	private void aetherium$delete(CallbackInfo ci) {
		aetherium$shadowBatches.values().forEach(MultiDrawBatch::delete);
		aetherium$shadowBatches.clear();
	}
}
