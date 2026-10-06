package dev.spacebod.aetherium.dev.mixin;

import dev.spacebod.aetherium.dev.bench.DirtySources;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tags dirty marks from light-update packets, chunk light setup and biome packets. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerDirtyMixin {
	@Inject(method = "readSectionList", at = @At("HEAD"))
	private void aetherium$enterPacket(CallbackInfo ci) {
		DirtySources.enter(DirtySources.LIGHT_PACKET);
	}

	@Inject(method = "readSectionList", at = @At("RETURN"))
	private void aetherium$exitPacket(CallbackInfo ci) {
		DirtySources.exit(DirtySources.LIGHT_PACKET);
	}

	@Inject(method = "enableChunkLight", at = @At("HEAD"))
	private void aetherium$enterChunk(CallbackInfo ci) {
		DirtySources.enter(DirtySources.CHUNK_LIGHT);
	}

	@Inject(method = "enableChunkLight", at = @At("RETURN"))
	private void aetherium$exitChunk(CallbackInfo ci) {
		DirtySources.exit(DirtySources.CHUNK_LIGHT);
	}

	@Inject(method = "handleChunksBiomes", at = @At("HEAD"))
	private void aetherium$enterBiomes(CallbackInfo ci) {
		DirtySources.enter(DirtySources.BIOMES);
	}

	@Inject(method = "handleChunksBiomes", at = @At("RETURN"))
	private void aetherium$exitBiomes(CallbackInfo ci) {
		DirtySources.exit(DirtySources.BIOMES);
	}
}
