package dev.spacebod.aetherium.client.mixin.shaders;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.spacebod.aetherium.shaders.engine.RenderScale;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.GameRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders frame hooks in {@code GameRenderer}: level start (clears, begin/prepare passes, camera state), the
 * world projection packs read as gbufferProjection, the hand (no depth clear, its own hand projection, depthtex2
 * captured before it), and the composite/final passes right after the hand, before vanilla's screen effects and GUI.
 * Vanilla's improved transparency is off while a pack is active (packs do their own translucency). View bobbing, hurt tilt
 * and the nausea/portal spin go into the camera's view matrix instead of the projection while a pack draws the world
 * (as the pack format expects): packs rebuild view positions from a plain perspective projection, and a bob baked into
 * it makes their shadows, fog and sky swim with every step. The render scale brackets the world phase: the frame's clear
 * hands the main target a scaled set ({@link RenderScale#beginWorld}) and the end of {@code renderLevel} puts the window's
 * back and upscales onto it. Inert without a pack.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Unique
	private static final String RENDER_ITEM_IN_HAND = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand("
			+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;"
			+ "Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V";

	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	/** This frame's bob/hurt/spin transform, folded into the view matrix instead of the projection (null: vanilla's way). */
	@Unique
	private Matrix4f aetherium$viewEffects;
	/** The camera's view rotation before the effects were folded in, restored after the level. */
	@Unique
	private final Matrix4f aetherium$plainView = new Matrix4f();

	@Shadow
	private void bobHurt(CameraRenderState cameraState, PoseStack poseStack) {
	}

	@Shadow
	private void bobView(CameraRenderState cameraState, PoseStack poseStack) {
	}

	/** A frame that ended inside the scaled world phase hands the window-sized textures back before the size check. */
	@Inject(method = "render", at = @At("HEAD"))
	private void aetherium$recoverScale(CallbackInfo ci) {
		RenderScale.recover(mainRenderTarget);
	}

	/** The frame's clear: with a render scale in use, the world phase gets the scaled textures and the clear lands on them. */
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"))
	private void aetherium$scaleWorld(CommandEncoder encoder, GpuTexture color, Vector4fc clearColor, GpuTexture depth, double clearDepth,
			Operation<Void> original) {
		if (RenderScale.beginWorld(mainRenderTarget, gameRenderState)) {
			original.call(encoder, mainRenderTarget.getColorTexture(), clearColor, mainRenderTarget.getDepthTexture(), clearDepth);
		} else {
			original.call(encoder, color, clearColor, depth, clearDepth);
		}
	}

	/** The level, hand, pack passes and screen effects are done: back to the window's size, upscaled before the interface. */
	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void aetherium$upscaleWorld(CallbackInfo ci) {
		RenderScale.endWorld(mainRenderTarget);
	}

	/**
	 * Before anything of the frame reads the camera: with a pack, the view effects go into the view rotation first, so
	 * the frame start (gbufferModelView, per-frame and custom uniforms, celestial positions, begin passes) and every
	 * world pass see the same view. Folding them in later left the pack's sky and lighting a bob behind the terrain.
	 */
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void aetherium$beforeLevel(CallbackInfo ci) {
		ShaderPackEngine engine = ShaderPackEngine.get();
		CameraRenderState camera = gameRenderState.levelRenderState.cameraRenderState;
		Matrix4f effects = engine.packActive() ? aetherium$viewEffects(camera) : null;
		if (effects != null) {
			aetherium$plainView.set(camera.viewRotationMatrix);
			// effects * view: the effects act after the camera rotation, as they did on the projection's input.
			camera.viewRotationMatrix.mulLocal(effects);
		}
		engine.beginLevel(camera, gameRenderState.levelRenderState.worldPartialTicks, mainRenderTarget);
		if (effects != null && !engine.worldActive()) {
			camera.viewRotationMatrix.set(aetherium$plainView);
			effects = null;
		}
		aetherium$viewEffects = effects;
	}

	/** Vanilla's bob, hurt tilt and nausea/portal spin for this frame (GameRenderer.renderLevel), as one matrix. */
	@Unique
	private Matrix4f aetherium$viewEffects(CameraRenderState camera) {
		PoseStack bob = new PoseStack();
		bobHurt(camera, bob);
		if (gameRenderState.optionsRenderState.bobView) {
			bobView(camera, bob);
		}
		Matrix4f effects = new Matrix4f(bob.last().pose());
		var player = gameRenderState.levelRenderState.playerRenderState;
		float scale = gameRenderState.optionsRenderState.screenEffectScale;
		float spin = Math.max(player.portalEffectIntensity, player.nauseaEffectIntensity) * (scale * scale);
		if (spin > 0.0F) {
			float skew = 5.0F / (spin * spin + 5.0F) - spin * 0.04F;
			skew *= skew;
			org.joml.Vector3f axis = new org.joml.Vector3f(0.0F, net.minecraft.util.Mth.SQRT_OF_TWO / 2.0F, net.minecraft.util.Mth.SQRT_OF_TWO / 2.0F);
			float angle = player.spinningEffectAngle * (float) (Math.PI / 180.0);
			effects.rotate(angle, axis).scale(1.0F / skew, 1.0F, 1.0F).rotate(-angle, axis);
		}
		return effects;
	}

	/** Vanilla's own application of the effects to the projection: skipped while they are in the view. */
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;"))
	private Matrix4f aetherium$bob(Matrix4f projection, Matrix4fc bob, Operation<Matrix4f> original) {
		return aetherium$viewEffects == null ? original.call(projection, bob) : projection;
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;rotate(FLorg/joml/Vector3fc;)Lorg/joml/Matrix4f;"))
	private Matrix4f aetherium$spinRotate(Matrix4f projection, float angle, Vector3fc axis, Operation<Matrix4f> original) {
		return aetherium$viewEffects == null ? original.call(projection, angle, axis) : projection;
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;scale(FFF)Lorg/joml/Matrix4f;"))
	private Matrix4f aetherium$spinScale(Matrix4f projection, float x, float y, float z, Operation<Matrix4f> original) {
		return aetherium$viewEffects == null ? original.call(projection, x, y, z) : projection;
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private GpuBufferSlice aetherium$worldProjection(ProjectionMatrixBuffer buffer, Matrix4f projection, Operation<GpuBufferSlice> original) {
		ShaderPackEngine.get().onWorldProjection(projection);
		return original.call(buffer, projection);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud("
			+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;"
			+ "Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void aetherium$restoreView(CallbackInfo ci) {
		if (aetherium$viewEffects != null) {
			// The hand applies its own bob to the plain view.
			gameRenderState.levelRenderState.cameraRenderState.viewRotationMatrix.set(aetherium$plainView);
			aetherium$viewEffects = null;
		}
	}

	@WrapOperation(method = "render3dHud", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private GpuBufferSlice aetherium$handProjection(ProjectionMatrixBuffer buffer, Projection projection, Operation<GpuBufferSlice> original) {
		ShaderPackEngine engine = ShaderPackEngine.get();
		if (!engine.worldActive()) {
			return original.call(buffer, projection);
		}
		return buffer.getBuffer(engine.handProjection(projection.getMatrix(new Matrix4f())));
	}

	@WrapOperation(method = "render3dHud", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"))
	private void aetherium$keepWorldDepth(CommandEncoder encoder, GpuTexture depth, double value, Operation<Void> original) {
		if (!ShaderPackEngine.get().worldActive()) {
			original.call(encoder, depth, value);
		}
	}

	@Inject(method = "render3dHud", at = @At(value = "INVOKE", target = RENDER_ITEM_IN_HAND))
	private void aetherium$beforeHand(CallbackInfo ci) {
		ShaderPackEngine.get().beforeHand(mainRenderTarget.getDepthTextureView());
		dev.spacebod.aetherium.client.DevHooks.stageBegin("hand");
	}

	@Inject(method = "render3dHud", at = @At(value = "INVOKE", target = RENDER_ITEM_IN_HAND, shift = At.Shift.AFTER))
	private void aetherium$afterHand(CallbackInfo ci) {
		dev.spacebod.aetherium.client.DevHooks.stageEnd();
		ShaderPackEngine.get().afterHand(mainRenderTarget);
	}

	/**
	 * Packs light the solid hand in their deferred passes, so its solid part is drawn inside the level before them
	 * ({@link ShaderPackEngine#afterOpaque}); vanilla's own hand pass then draws only what is left.
	 */
	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"))
	private void aetherium$handPhases(com.mojang.renderpearl.api.commands.RenderPass pass,
			net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame frame, Operation<Void> original) {
		ShaderPackEngine engine = ShaderPackEngine.get();
		if (!engine.drawingSolidHand() && !engine.solidHandDrawn()) {
			original.call(pass, frame);
			return;
		}
		// The bare arm draws with entity_translucent (skins may have translucent layers), which puts it in the
		// translucent-model phase; packs light it as part of the solid hand, so that phase goes with the solid one.
		// Translucent held items (glass, ice) are in the translucent blocks-and-items phase and stay late.
		var access = (dev.spacebod.aetherium.client.mixin.access.PreparedFrameAccessor) (Object) frame;
		var context = access.aetherium$context();
		var collections = access.aetherium$submitNodeStorage().getSubmitsPerOrder().values();
		if (engine.drawingSolidHand()) {
			frame.executeSolid(pass);
			for (var c : collections) {
				access.aetherium$executePhase(c.translucentModels, context, pass);
			}
			return;
		}
		// Vanilla's executeTranslucent without the translucent-model phase, then the rest of renderAllFeatures.
		for (var c : collections) {
			access.aetherium$executePhase(c.shadows, context, pass);
			access.aetherium$executePhase(c.nameTags, context, pass);
			access.aetherium$executePhase(c.texts, context, pass);
			access.aetherium$executePhase(c.translucentCustomGeometry, context, pass);
		}
		for (var c : collections) {
			access.aetherium$executePhase(c.shapeOutlines, context, pass);
			access.aetherium$executePhase(c.translucentGizmos, context, pass);
		}
		for (var c : collections) {
			access.aetherium$executePhase(c.translucentBlocksAndItems, context, pass);
			access.aetherium$executePhase(c.breakingOverlay, context, pass);
			access.aetherium$executePhase(c.waterMask, context, pass);
		}
		frame.executeTranslucentAfterTerrain(pass);
		frame.executeSeeThrough(pass);
		frame.executeAlwaysOnTop(pass);
	}

	/** The early solid hand is prepared on the engine's own dispatcher: vanilla's has one frame, still open during the level. */
	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
	private net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame aetherium$handDispatcher(
			net.minecraft.client.renderer.feature.FeatureRenderDispatcher dispatcher, net.minecraft.client.renderer.SubmitNodeStorage storage,
			Operation<net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame> original) {
		ShaderPackEngine engine = ShaderPackEngine.get();
		return original.call(engine.drawingSolidHand() ? engine.handDispatcher() : dispatcher, storage);
	}

	@ModifyReturnValue(method = "useImprovedTransparency", at = @At("RETURN"))
	private boolean aetherium$noImprovedTransparency(boolean original) {
		return original && !ShaderPackEngine.get().packActive();
	}
}
