package dev.spacebod.aetherium.client.mixin.shaders.ids;

import dev.spacebod.aetherium.client.mixin.access.BlockEntityRenderStateAccessor;
import dev.spacebod.aetherium.shaders.helpers.DrawIds;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders: the block entity being submitted, as {@code blockEntityId}; its geometry is drawn with the pack's
 * block-entity programs ({@code gbuffers_block}).
 */
@Mixin(BlockEntityRenderDispatcher.class)
abstract class BlockEntitySubmitIdsMixin {
	@Inject(method = "submit", at = @At("HEAD"))
	private void aetherium$beginBlockEntity(BlockEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera,
			CallbackInfo ci) {
		if (DrawIds.tracking()) {
			DrawIds.beginBlockEntity(((BlockEntityRenderStateAccessor) state).aetherium$blockState());
		}
	}

	@Inject(method = "submit", at = @At("RETURN"))
	private void aetherium$endBlockEntity(CallbackInfo ci) {
		if (DrawIds.tracking()) {
			DrawIds.endBlockEntity();
		}
	}
}
