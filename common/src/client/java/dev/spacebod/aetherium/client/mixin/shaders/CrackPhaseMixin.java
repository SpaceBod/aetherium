package dev.spacebod.aetherium.client.mixin.shaders;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.spacebod.aetherium.shaders.engine.CrackRouting;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders: the crack over a block entity goes to the breaking overlay while a pack that wants its crack after the
 * deferred passes draws, and each collection's solid phase and breaking overlay are linked to each other for the block
 * cracks ({@link CrackRouting}).
 */
@Mixin(SubmitNodeCollection.class)
abstract class CrackPhaseMixin {
	@Shadow
	@Final
	public SimpleFeatureRenderPhase solid;
	@Shadow
	@Final
	public SimpleFeatureRenderPhase breakingOverlay;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void aetherium$linkCrackPhase(CallbackInfo ci) {
		((CrackRouting.CrackPhase) solid).aetherium$linkCrackPhases(solid, breakingOverlay);
		((CrackRouting.CrackPhase) breakingOverlay).aetherium$linkCrackPhases(solid, breakingOverlay);
	}

	@ModifyExpressionValue(method = "submitCrumblingOverlay", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;solid:Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;"))
	private SimpleFeatureRenderPhase aetherium$crackAfterDeferred(SimpleFeatureRenderPhase solid) {
		return ShaderPackEngine.get().cracksAfterDeferred() ? breakingOverlay : solid;
	}
}
