package dev.spacebod.aetherium.client.mixin.shaders;

import com.llamalad7.mixinextras.sugar.Local;
import dev.spacebod.aetherium.shaders.pbr.PbrKind;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.texture.atlas.sources.DirectoryLister;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Aetherium Shaders: a material map ({@code stone_n.png} next to {@code stone.png}) is not a sprite of its own; atlases
 * built from whole directories leave it out (it is loaded into the material atlas instead).
 */
@Mixin(DirectoryLister.class)
abstract class PbrDirectoryListerMixin {
	@ModifyArg(method = "run", at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V", remap = false), index = 0)
	private BiConsumer<? super Identifier, ? super Resource> aetherium$skipMaps(BiConsumer<? super Identifier, ? super Resource> action,
			@Local(argsOnly = true) ResourceManager resources) {
		return (location, resource) -> {
			String base = PbrKind.baseOf(location.getPath());
			if (base != null && resources.getResource(location.withPath(base)).isPresent()) {
				return;
			}
			action.accept(location, resource);
		};
	}
}
