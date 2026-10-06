package dev.spacebod.aetherium.client.mixin.access;

import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** An atlas's sprites, size and mip levels (material atlases are laid out exactly like it). */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {
	@Accessor("texturesByName")
	Map<Identifier, TextureAtlasSprite> aetherium$sprites();

	@Accessor("maxMipLevel")
	int aetherium$maxMipLevel();

	@Invoker("getWidth")
	int aetherium$width();

	@Invoker("getHeight")
	int aetherium$height();
}
