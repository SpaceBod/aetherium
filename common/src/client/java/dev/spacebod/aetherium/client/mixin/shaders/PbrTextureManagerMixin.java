package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.shaders.pbr.PbrTextures;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Aetherium Shaders: the resource packs' material format is read again with every resource reload ({@link PbrTextures}). */
@Mixin(TextureManager.class)
abstract class PbrTextureManagerMixin {
	@Inject(method = "reload", at = @At("HEAD"))
	private void aetherium$materialFormat(PreparableReloadListener.SharedState reload, Executor tasks, PreparableReloadListener.PreparationBarrier barrier,
			Executor main, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
		PbrTextures.onResourceReload(reload.resourceManager());
	}
}
