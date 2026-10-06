package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.spacebod.aetherium.shaders.engine.RenderPhase;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.pipeline.WorldRenderingPhase;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The vanilla sky while a pack runs. Each element draws with its own {@code renderStage} (sky disc, sunset, sun, moon,
 * stars and the void share a few pipelines, so the program alone cannot tell them apart); anything else the sky draws
 * (the End sky, the End flash) reads {@code CUSTOM_SKY}. The pack's {@code sun=false} and {@code moon=false} hide those,
 * and the sun and moon follow the pack's {@code sunPathRotation} like its shadows do. With the camera in a fluid the
 * sunset fan and the void plane are not drawn, and with blindness or darkness the sunset fan is not, so neither shows
 * through the fog.
 * <p>
 * {@code stars=} and {@code sky=} remove nothing vanilla draws: packs that set {@code stars=false} still get the star
 * geometry in {@code gbuffers_skybasic} (where they draw their own stars), and {@code sky=} gates only the fog-coloured
 * horizon box drawn before the sky ({@code HorizonRenderer}), not vanilla's sky disc.
 */
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
	private static void aetherium$phase(WorldRenderingPhase phase) {
		if (ShaderPackEngine.get().activeDirectives() != null) {
			RenderPhase.override(phase);
		}
	}

	private static boolean aetherium$cameraInFluid() {
		return Minecraft.getInstance().gameRenderer.mainCamera().getFluidInCamera() != FogType.NONE;
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void aetherium$skyBegin(CallbackInfo ci) {
		aetherium$phase(WorldRenderingPhase.CUSTOM_SKY);
	}

	@Inject(method = "renderSkyDisc", at = @At("HEAD"))
	private void aetherium$skyDisc(CallbackInfo ci) {
		aetherium$phase(WorldRenderingPhase.SKY);
	}

	@Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
	private void aetherium$sunset(CallbackInfo ci) {
		if (ShaderPackEngine.get().activeDirectives() == null) {
			return;
		}
		if (aetherium$cameraInFluid() || Minecraft.getInstance().getCameraEntity() instanceof LivingEntity living
				&& (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) {
			ci.cancel();
			return;
		}
		RenderPhase.override(WorldRenderingPhase.SUNSET);
	}

	@Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
	private void aetherium$sun(CallbackInfo ci) {
		PackDirectives d = ShaderPackEngine.get().activeDirectives();
		if (d != null && !d.shouldRenderSun()) {
			ci.cancel();
			return;
		}
		aetherium$phase(WorldRenderingPhase.SUN);
	}

	@Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
	private void aetherium$moon(CallbackInfo ci) {
		PackDirectives d = ShaderPackEngine.get().activeDirectives();
		if (d != null && !d.shouldRenderMoon()) {
			ci.cancel();
			return;
		}
		aetherium$phase(WorldRenderingPhase.MOON);
	}

	@Inject(method = "renderStars", at = @At("HEAD"))
	private void aetherium$stars(CallbackInfo ci) {
		aetherium$phase(WorldRenderingPhase.STARS);
	}

	@Inject(method = "renderDarkDisc", at = @At("HEAD"), cancellable = true)
	private void aetherium$void(CallbackInfo ci) {
		if (ShaderPackEngine.get().activeDirectives() == null) {
			return;
		}
		if (aetherium$cameraInFluid()) {
			ci.cancel();
			return;
		}
		RenderPhase.override(WorldRenderingPhase.VOID);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void aetherium$skyDone(CallbackInfo ci) {
		RenderPhase.override(null);
	}

	/** After vanilla's first rotation (-90 degrees about Y): tilt by sunPathRotation about Z. */
	@Inject(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V", ordinal = 0, shift = At.Shift.AFTER))
	private void aetherium$sunPath(com.mojang.renderpearl.api.commands.RenderPass renderPass, PoseStack poseStack, float sunAngle,
								   float moonAngle, float starAngle, net.minecraft.world.level.MoonPhase moonPhase, float rainBrightness,
								   float starBrightness, CallbackInfo ci) {
		PackDirectives d = ShaderPackEngine.get().activeDirectives();
		if (d != null && d.getSunPathRotation() != 0) {
			poseStack.rotateDegrees(Axis.ZP, d.getSunPathRotation());
		}
	}
}
