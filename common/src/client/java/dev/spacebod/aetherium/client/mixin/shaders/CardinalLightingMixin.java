package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Aetherium Shaders: vanilla's per-face shading (block quads and fluids, baked into vertex colours at mesh time) is off
 * while a pack runs, unless the pack keeps it ({@code oldLighting=true}): packs light faces themselves, and vanilla's
 * darker sides would be applied twice. Every face reads the top face's value. Chunks are rebuilt when this changes.
 */
@Mixin(CardinalLighting.class)
abstract class CardinalLightingMixin {
	@ModifyVariable(method = "byFace", at = @At("HEAD"), argsOnly = true)
	private Direction aetherium$packFaceShading(Direction face) {
		return WorldRenderingSettings.INSTANCE.shouldDisableDirectionalShading() ? Direction.UP : face;
	}
}
