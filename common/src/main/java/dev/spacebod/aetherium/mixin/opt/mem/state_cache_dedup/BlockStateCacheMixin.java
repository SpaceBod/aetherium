package dev.spacebod.aetherium.mixin.opt.mem.state_cache_dedup;

import dev.spacebod.aetherium.memory.StateCacheDedup;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code mem.state_cache_dedup}: shares equal collision shapes and faceSturdy arrays; see {@link StateCacheDedup}. */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache")
abstract class BlockStateCacheMixin {
	@Shadow
	@Final
	@Mutable
	public VoxelShape collisionShape;

	@Shadow
	@Final
	@Mutable
	private boolean[] faceSturdy;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void aetherium$dedup(CallbackInfo ci) {
		this.collisionShape = StateCacheDedup.shape(this.collisionShape);
		this.faceSturdy = StateCacheDedup.faceSturdy(this.faceSturdy);
	}
}
