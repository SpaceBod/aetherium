package dev.spacebod.aetherium.client.mixin.access;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A sprite's padding in its atlas (a material sprite takes the same place). */
@Mixin(TextureAtlasSprite.class)
public interface TextureAtlasSpriteAccessor {
	@Accessor("padding")
	int aetherium$padding();
}
