package dev.spacebod.aetherium.mixin.opt.mem.state_cache_dedup;

import dev.spacebod.aetherium.memory.StateCacheDedup;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code mem.state_cache_dedup}: shares equal per-face occlusion arrays (vanilla already shares the empty and full ones). */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateBaseMixin {
	@Shadow
	private VoxelShape[] occlusionShapesByFace;

	@Inject(method = "initCache", at = @At("TAIL"))
	private void aetherium$dedupFaces(CallbackInfo ci) {
		this.occlusionShapesByFace = StateCacheDedup.occlusionFaces(this.occlusionShapesByFace);
	}
}
