package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.shaders.engine.CrackRouting;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders: a block crack submitted to a collection's solid phase or breaking overlay goes to the one of the two
 * the pack wants its cracks in ({@link CrackRouting}).
 */
@Mixin(SimpleFeatureRenderPhase.class)
abstract class CrackRouteMixin implements CrackRouting.CrackPhase {
	@Unique
	private @Nullable SimpleFeatureRenderPhase aetherium$solid;
	@Unique
	private @Nullable SimpleFeatureRenderPhase aetherium$breakingOverlay;

	@Override
	public void aetherium$linkCrackPhases(SimpleFeatureRenderPhase solid, SimpleFeatureRenderPhase breakingOverlay) {
		aetherium$solid = solid;
		aetherium$breakingOverlay = breakingOverlay;
	}

	@Inject(method = "submit", at = @At("HEAD"), cancellable = true)
	private void aetherium$routeCrack(SubmitNode submit, CallbackInfo ci) {
		SimpleFeatureRenderPhase solid = aetherium$solid;
		SimpleFeatureRenderPhase overlay = aetherium$breakingOverlay;
		SimpleFeatureRenderPhase target = solid == null || overlay == null ? null : CrackRouting.target(solid, overlay);
		if (target != null && target != (Object) this) {
			target.submit(submit);
			ci.cancel();
		}
	}
}
