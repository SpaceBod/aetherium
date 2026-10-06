package dev.spacebod.aetherium.client.mixin.shaders;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Aetherium Shaders: vanilla's round blob shadows under entities are dropped while the pack draws a shadow map; the
 * entities cast into the map instead.
 */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
	@WrapWithCondition(method = "submit", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitShadow(Lcom/mojang/blaze3d/vertex/PoseStack;FLjava/util/List;)V"))
	private boolean aetherium$blobShadow(SubmitNodeCollector collector, PoseStack poseStack, float radius,
			List<EntityRenderState.ShadowPiece> pieces) {
		return !ShaderPackEngine.get().hasShadowMap();
	}
}
