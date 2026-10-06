package dev.spacebod.aetherium.client.mixin.shaders;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.spacebod.aetherium.shaders.engine.EntityPhases;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.pathways.HorizonRenderer;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders: the pack's deferred passes run between the opaque world and the translucents. Vanilla's
 * classic-transparency path draws both in one render pass, so while a pack is active the translucent half is deferred
 * until the opaque pass has closed and the engine has taken depthtex1 and run the deferred passes, then replayed in a
 * second render pass on the same targets (the split vanilla's improved-transparency path makes too). The pack's horizon
 * box ({@link HorizonRenderer}) is drawn in a pass of its own right after the frame's clear.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Shadow
	@Final
	private LevelTargetBundle targets;

	@Shadow
	protected abstract void executeClassicTransparency(ChunkSectionsToRender chunkSectionsToRender,
			FeatureRenderDispatcher.PreparedFrame featureFrame, RenderPass renderPass);

	@Unique
	private @Nullable ChunkSectionsToRender aetherium$shaderSections;
	@Unique
	private FeatureRenderDispatcher.@Nullable PreparedFrame aetherium$shaderFeatures;

	/** The shadow map (then the prepare passes) renders before the world, once this frame's sections are known. */
	@Inject(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;addMainPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Z)V"))
	private void aetherium$shadows(CallbackInfo ci, @Local FeatureRenderDispatcher.PreparedFrame featureFrame) {
		ShaderPackEngine.get().renderShadows((LevelRenderer) (Object) this, featureFrame);
	}

	/** After the clear pass (and before the sky pass): the horizon box, when the pack keeps {@code sky=true}. */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FramePass;executes(Ljava/lang/Runnable;)V",
			ordinal = 0, shift = At.Shift.AFTER))
	private void aetherium$horizon(CallbackInfo ci, @Local FrameGraphBuilder frame, @Local(argsOnly = true) GpuBufferSlice terrainFog) {
		if (!HorizonRenderer.shouldDraw()) {
			return;
		}
		FramePass pass = frame.addPass("aetherium_horizon");
		targets.main = pass.readsAndWrites(targets.main);
		pass.executes(() -> HorizonRenderer.draw(Minecraft.getInstance().gameRenderer.mainRenderTarget(), terrainFog));
	}

	@WrapOperation(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void aetherium$deferTranslucents(LevelRenderer self, ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame features,
			RenderPass renderPass, Operation<Void> original) {
		if (!ShaderPackEngine.get().worldActive()) {
			original.call(self, sections, features, renderPass);
			return;
		}
		aetherium$shaderSections = sections;
		aetherium$shaderFeatures = features;
	}

	/** The translucent entity phases go with the solid entities, before the deferred passes ({@link EntityPhases}). */
	@WrapOperation(method = "executeSolid", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void aetherium$entitiesTogether(FeatureRenderDispatcher.PreparedFrame features, RenderPass renderPass, Operation<Void> original) {
		original.call(features, renderPass);
		EntityPhases.afterSolid(features, renderPass);
	}

	/** Vanilla's translucent features, without the entity phases already drawn with the solid entities. */
	@WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void aetherium$translucentRest(FeatureRenderDispatcher.PreparedFrame features, RenderPass renderPass, Operation<Void> original) {
		if (!EntityPhases.executeTranslucentRest(features, renderPass)) {
			original.call(features, renderPass);
		}
	}

	@Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/RenderPass;close()V",
			ordinal = 0, shift = At.Shift.AFTER))
	private void aetherium$afterOpaque(CallbackInfo ci, @Local RenderTarget mainTarget) {
		ChunkSectionsToRender sections = aetherium$shaderSections;
		FeatureRenderDispatcher.PreparedFrame features = aetherium$shaderFeatures;
		if (sections == null || features == null) {
			return;
		}
		aetherium$shaderSections = null;
		aetherium$shaderFeatures = null;
		ShaderPackEngine.get().afterOpaque(mainTarget);
		dev.spacebod.aetherium.client.DevHooks.stageBegin("shaders:translucent");
		try (RenderPass translucent = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Main (translucent, shaders)",
				mainTarget.getColorTextureView(), Optional.empty(), mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(translucent);
			executeClassicTransparency(sections, features, translucent);
		} finally {
			dev.spacebod.aetherium.client.DevHooks.stageEnd();
		}
	}
}
