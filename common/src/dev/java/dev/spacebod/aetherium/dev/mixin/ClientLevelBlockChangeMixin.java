package dev.spacebod.aetherium.dev.mixin;

import dev.spacebod.aetherium.dev.bench.DirtySources;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Histogram of block changes the client applies (what makes sections dirty from "block"). */
@Mixin(ClientLevel.class)
abstract class ClientLevelBlockChangeMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void aetherium$histogram(BlockPos pos, BlockState old, BlockState current, int updateFlags, CallbackInfo ci) {
		// Only read by benchmark results: skip the registry lookups and string keys otherwise.
		if (dev.spacebod.aetherium.dev.bench.BenchDriver.active() == null) {
			return;
		}
		DirtySources.blockChange(BuiltInRegistries.BLOCK.getKey(old.getBlock()).getPath(),
				BuiltInRegistries.BLOCK.getKey(current.getBlock()).getPath(), old.getBlock() == current.getBlock());
	}
}
