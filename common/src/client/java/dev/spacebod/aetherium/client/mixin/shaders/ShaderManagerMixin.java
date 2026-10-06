package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.shaders.engine.VanillaShaderSources;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Aetherium Shaders: keeps vanilla's loaded core shader sources, to rebuild vanilla pipelines for the pack's targets. */
@Mixin(ShaderManager.class)
abstract class ShaderManagerMixin {
	@Inject(method = "loadConfigs", at = @At("RETURN"))
	private static void aetherium$capture(CallbackInfoReturnable<ShaderManager.Configs> cir) {
		VanillaShaderSources.set(cir.getReturnValue());
	}
}
