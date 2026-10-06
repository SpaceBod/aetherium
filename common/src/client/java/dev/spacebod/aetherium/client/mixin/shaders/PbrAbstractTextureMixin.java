package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.spacebod.aetherium.shaders.pbr.PbrTextures;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Aetherium Shaders: a texture's GPU texture goes away, and with it the material maps loaded for it ({@link PbrTextures}). */
@Mixin(AbstractTexture.class)
abstract class PbrAbstractTextureMixin {
	@Shadow
	protected @Nullable GpuTexture texture;

	@Inject(method = "releaseTextures", at = @At("HEAD"))
	private void aetherium$forgetMaps(CallbackInfo ci) {
		PbrTextures.forget(texture);
	}
}
