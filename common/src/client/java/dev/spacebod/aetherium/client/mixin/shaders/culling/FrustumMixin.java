package dev.spacebod.aetherium.client.mixin.shaders.culling;

import dev.spacebod.aetherium.shaders.helpers.AcceptAllFrustum;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Aetherium Shaders: a frustum marked {@link AcceptAllFrustum#aetherium$acceptAll} contains everything. */
@Mixin(Frustum.class)
abstract class FrustumMixin implements AcceptAllFrustum {
	@Unique
	private boolean aetherium$acceptAll;

	@Override
	public void aetherium$acceptAll() {
		aetherium$acceptAll = true;
	}

	@Inject(method = "cubeInFrustum(DDDDDD)I", at = @At("HEAD"), cancellable = true)
	private void aetherium$inside(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, CallbackInfoReturnable<Integer> cir) {
		if (aetherium$acceptAll) {
			cir.setReturnValue(-2); // fully inside
		}
	}
}
