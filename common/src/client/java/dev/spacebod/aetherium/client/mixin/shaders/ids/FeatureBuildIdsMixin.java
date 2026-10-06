package dev.spacebod.aetherium.client.mixin.shaders.ids;

import dev.spacebod.aetherium.shaders.helpers.CapturedIds;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.spacebod.aetherium.shaders.helpers.DrawIds;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.LeashFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders: while a feature renderer writes each submit's vertices, that submit's ids are current, so every
 * vertex gets them ({@code EntityVertexFormats}). Hooked on the renderers' loop over their submits.
 */
@Mixin({ModelFeatureRenderer.class, ItemFeatureRenderer.class, TextFeatureRenderer.class, CustomFeatureRenderer.class, FlameFeatureRenderer.class,
		BlockModelFeatureRenderer.class, LeashFeatureRenderer.class, MovingBlockFeatureRenderer.class})
abstract class FeatureBuildIdsMixin {
	@ModifyExpressionValue(method = "buildGroup", at = @At(value = "INVOKE", target = "Ljava/util/Iterator;next()Ljava/lang/Object;"))
	private Object aetherium$applyIds(Object next) {
		if (next instanceof CapturedIds ids) {
			DrawIds.apply(ids.aetherium$ids());
		}
		return next;
	}

	@Inject(method = "buildGroup", at = @At("RETURN"))
	private void aetherium$clearIds(CallbackInfo ci) {
		if (DrawIds.tracking()) {
			DrawIds.clear();
		}
	}
}
