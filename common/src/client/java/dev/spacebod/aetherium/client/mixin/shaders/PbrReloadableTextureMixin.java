package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.blaze3d.platform.NativeImage;
import dev.spacebod.aetherium.shaders.pbr.PbrTextures;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Aetherium Shaders: textures loaded from resources (entities, armour) may have material maps ({@link PbrTextures}). */
@Mixin(ReloadableTexture.class)
abstract class PbrReloadableTextureMixin {
	@Inject(method = "doLoad", at = @At("RETURN"))
	private void aetherium$track(NativeImage image, CallbackInfo ci) {
		PbrTextures.track((ReloadableTexture) (Object) this);
	}
}
