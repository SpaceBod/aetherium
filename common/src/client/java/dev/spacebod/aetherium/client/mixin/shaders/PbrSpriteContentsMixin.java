package dev.spacebod.aetherium.client.mixin.shaders;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import dev.spacebod.aetherium.shaders.pbr.PbrLoader;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Aetherium Shaders: a material sprite's mip levels follow its format's rules, not vanilla's colour mipmaps. */
@Mixin(SpriteContents.class)
abstract class PbrSpriteContentsMixin {
	@WrapOperation(method = "increaseMipLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/texture/MipmapGenerator;generateMipLevels(Lnet/minecraft/resources/Identifier;[Lcom/mojang/blaze3d/platform/NativeImage;ILnet/minecraft/client/renderer/texture/MipmapStrategy;FLcom/mojang/blaze3d/platform/Transparency;)[Lcom/mojang/blaze3d/platform/NativeImage;"))
	private NativeImage[] aetherium$mapMipLevels(Identifier name, NativeImage[] levels, int maxLevel, MipmapStrategy strategy, float alphaCutoffBias,
			Transparency transparency, Operation<NativeImage[]> original) {
		if ((Object) this instanceof PbrLoader.PbrSpriteContents map) {
			return map.mipLevels(levels, maxLevel);
		}
		return original.call(name, levels, maxLevel, strategy, alphaCutoffBias, transparency);
	}
}
