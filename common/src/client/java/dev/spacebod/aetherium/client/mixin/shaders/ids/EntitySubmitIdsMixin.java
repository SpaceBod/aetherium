package dev.spacebod.aetherium.client.mixin.shaders.ids;

import dev.spacebod.aetherium.shaders.helpers.DrawIds;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Aetherium Shaders: the entity being submitted, as {@code entityId} for everything it submits. */
@Mixin(EntityRenderDispatcher.class)
abstract class EntitySubmitIdsMixin {
	@Inject(method = "submit", at = @At("HEAD"))
	private void aetherium$beginEntity(EntityRenderState renderState, CameraRenderState camera, double x, double y, double z, PoseStack poseStack,
			SubmitNodeCollector collector, CallbackInfo ci) {
		if (DrawIds.tracking()) {
			DrawIds.beginEntity(renderState);
		}
	}

	@Inject(method = "submit", at = @At("RETURN"))
	private void aetherium$endEntity(CallbackInfo ci) {
		if (DrawIds.tracking()) {
			DrawIds.endEntity();
		}
	}
}
