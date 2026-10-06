package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.shaders.uniforms.BiomeUniforms;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Numbers vanilla's biomes in registration order, as packs expect: the {@code BIOME_*} defines (also usable in
 * custom uniforms) and the {@code biome} uniform both come from this table.
 */
@Mixin(Biomes.class)
abstract class BiomesMixin {
	@Unique
	private static int aetherium$nextBiomeId;

	@Inject(method = "register", at = @At("TAIL"))
	private static void aetherium$numberBiome(String name, CallbackInfoReturnable<ResourceKey<Biome>> cir) {
		BiomeUniforms.getBiomeMap().put(cir.getReturnValue(), aetherium$nextBiomeId++);
	}
}
