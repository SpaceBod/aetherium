package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Aetherium Shaders on the F3 screen, under the system specs: the pack, its programs and its shadow map. */
@Mixin(DebugEntrySystemSpecs.class)
abstract class DebugSystemSpecsMixin {
	@Unique
	private static final Identifier GROUP = Identifier.fromNamespaceAndPath("aetherium", "shaders");

	@Inject(method = "display", at = @At("RETURN"))
	private void aetherium$shaderLines(DebugScreenDisplayer displayer, @Nullable Level serverOrClientLevel, @Nullable LevelChunk clientChunk,
			@Nullable LevelChunk serverChunk, CallbackInfo ci) {
		displayer.addToGroup(GROUP, ShaderPackEngine.get().debugLines());
	}
}
