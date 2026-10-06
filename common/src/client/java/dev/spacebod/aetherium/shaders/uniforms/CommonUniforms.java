package dev.spacebod.aetherium.shaders.uniforms;

import dev.spacebod.aetherium.shaders.compat.lod.LodCompat;
import dev.spacebod.aetherium.shaders.engine.RenderPhase;
import dev.spacebod.aetherium.shaders.gl.state.FogMode;
import dev.spacebod.aetherium.shaders.gl.state.StateUpdateNotifiers;
import dev.spacebod.aetherium.shaders.gl.uniform.DynamicUniformHolder;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;
import dev.spacebod.aetherium.shaders.shaderpack.IdMap;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import dev.spacebod.aetherium.shaders.uniforms.transforms.SmoothedFloat;
import dev.spacebod.aetherium.shaders.uniforms.transforms.SmoothedVec2f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Math;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector4f;

import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.ONCE;
import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.PER_FRAME;
import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.PER_TICK;

public final class CommonUniforms {
	private static final Minecraft client = Minecraft.getInstance();
	private static final Vector2i ZERO_VECTOR_2i = new Vector2i();
	private static final Vector3d ZERO_VECTOR_3d = new Vector3d();
	/** Reused by {@link #getEyeBrightness} (render thread only; consumers copy the value). */
	private static final Vector2i EYE_BRIGHTNESS = new Vector2i();

	private CommonUniforms() {
	}

	// Uniforms that can change between draws within a frame, driven by state-change notifiers.
	public static void addDynamicUniforms(DynamicUniformHolder uniforms, FogMode fogMode) {
		ExternallyManagedUniforms.addExternallyManagedUniforms(uniforms);
		FogUniforms.addFogUniforms(uniforms, fogMode);
		InternalUniforms.addFogUniforms(uniforms, fogMode);

		// Fallback for draws that can't supply entityId as a vertex attribute (e.g. lightning).
		uniforms.uniform1i("entityId", CapturedRenderingState.INSTANCE::getCurrentRenderedEntity, StateUpdateNotifiers.fallbackEntityNotifier);

		// Dynamic uniforms are re-evaluated on every program bind; the draw's albedo and the program's blend are known
		// by then (DrawState: set as a feature draw starts, and as the program binds).
		uniforms.uniform2i("atlasSize", DrawState::atlasSize, listener -> {
		});
		uniforms.uniform1i("gtextureId", DrawState::albedoId, StateUpdateNotifiers.bindTextureNotifier);
		uniforms.uniform1i("textureReloadCount", CapturedRenderingState.INSTANCE::getTextureReloadCount, StateUpdateNotifiers.bindTextureNotifier);
		uniforms.uniform2i("gtextureSize", DrawState::albedoSize, StateUpdateNotifiers.bindTextureNotifier);
		uniforms.uniform4i("blendFunc", DrawState::blendFunc, StateUpdateNotifiers.blendFuncNotifier);

		uniforms.uniform1i("renderStage", () -> RenderPhase.current().ordinal(), StateUpdateNotifiers.phaseChangeNotifier);
	}

	public static void addNonDynamicUniforms(UniformHolder uniforms, IdMap idMap, PackDirectives directives, FrameUpdateNotifier updateNotifier) {
		CameraUniforms.addCameraUniforms(uniforms, updateNotifier);
		ViewportUniforms.addViewportUniforms(uniforms);
		WorldTimeUniforms.addWorldTimeUniforms(uniforms);
		SystemTimeUniforms.addSystemTimeUniforms(uniforms);
		BiomeUniforms.addBiomeUniforms(uniforms);
		new CelestialUniforms(directives.getSunPathRotation()).addCelestialUniforms(uniforms);
		ExtendedUniforms.addExtendedUniforms(uniforms, updateNotifier);
		DateTimeUniforms.addTimeUniforms(uniforms);
		FrameMatrices matrices = new FrameMatrices(directives, updateNotifier);
		MatrixUniforms.addMatrixUniforms(uniforms, matrices);
		IdMapUniforms.addIdMapUniforms(updateNotifier, uniforms, idMap, directives.isOldHandLight());
		CommonUniforms.generalCommonUniforms(uniforms, updateNotifier, directives);
		InternalUniforms.addOtherUniforms(uniforms, matrices);
	}

	public static void generalCommonUniforms(UniformHolder uniforms, FrameUpdateNotifier updateNotifier, PackDirectives directives) {
		ExternallyManagedUniforms.addExternallyManagedUniforms(uniforms);

		SmoothedVec2f eyeBrightnessSmooth = new SmoothedVec2f(directives.getEyeBrightnessHalfLife(), directives.getEyeBrightnessHalfLife(), CommonUniforms::getEyeBrightness, updateNotifier);
		Vector2i eyeBrightnessSmoothed = new Vector2i();

		uniforms
			.uniform1b(PER_FRAME, "hideGUI", () -> client.gui.hud.isHidden())
			.uniform1b(PER_FRAME, "isRightHanded", () -> client.options.mainHand().get() == HumanoidArm.RIGHT)
			.uniform1i(PER_FRAME, "isEyeInWater", CommonUniforms::isEyeInWater)
			.uniform1f(PER_FRAME, "blindness", CommonUniforms::getBlindness)
			.uniform1f(PER_FRAME, "darknessFactor", CommonUniforms::getDarknessFactor)
			.uniform1f(PER_FRAME, "darknessLightFactor", CapturedRenderingState.INSTANCE::getDarknessLightFactor)
			.uniform1f(PER_FRAME, "nightVision", CommonUniforms::getNightVision)
			.uniform1b(PER_FRAME, "is_sneaking", CommonUniforms::isSneaking)
			.uniform1b(PER_FRAME, "is_sprinting", CommonUniforms::isSprinting)
			.uniform1b(PER_FRAME, "is_hurt", CommonUniforms::isHurt)
			.uniform1b(PER_FRAME, "is_invisible", CommonUniforms::isInvisible)
			.uniform1b(PER_FRAME, "is_burning", CommonUniforms::isBurning)
			.uniform1b(PER_FRAME, "is_on_ground", CommonUniforms::isOnGround)
			// Unclamped: a gamma set beyond the slider's range (options.txt) reaches the pack as is.
			.uniform1f(PER_FRAME, "screenBrightness", () -> client.options.gamma().get())
			// Dummy for programs where entityColor isn't supplied as a vertex attribute; suppresses missing-uniform
			// warnings.
			.uniform4f(ONCE, "entityColor", () -> new Vector4f(0, 0, 0, 0))
			.uniform1i(ONCE, "blockEntityId", () -> -1)
			.uniform1i(ONCE, "currentRenderedItemId", () -> -1)
			.uniform1i(PER_FRAME, "anisotropicFiltering", () -> {
				if (Minecraft.getInstance().options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC) {
					return Minecraft.getInstance().options.maxAnisotropyValue();
				} else {
					return 0;
				}
			})
			.uniform1f(ONCE, "pi", () -> Math.PI)
			.uniform1f(PER_TICK, "playerMood", CommonUniforms::getPlayerMood)
			.uniform1f(PER_TICK, "constantMood", CommonUniforms::getConstantMood)
			.uniform2i(PER_FRAME, "eyeBrightness", CommonUniforms::getEyeBrightness)
			.uniform2i(PER_FRAME, "eyeBrightnessSmooth", () -> {
				Vector2f smoothed = eyeBrightnessSmooth.get();
				return eyeBrightnessSmoothed.set((int) smoothed.x(), (int) smoothed.y());
			})
			.uniform1f(PER_TICK, "rainStrength", CommonUniforms::getRainStrength)
			.uniform1f(PER_TICK, "wetness", new SmoothedFloat(directives.getWetnessHalfLife(), directives.getDrynessHalfLife(), CommonUniforms::getRainStrength, updateNotifier))
			.uniform3d(PER_FRAME, "skyColor", CommonUniforms::getSkyColor)
			// Level-of-detail terrain: the planes of the LOD projection (dhProjection), the LOD distance in blocks.
			.uniform1f(PER_FRAME, "dhFarPlane", LodCompat::getFarPlane)
			.uniform1f(PER_FRAME, "dhNearPlane", LodCompat::getNearPlane)
			.uniform1i(PER_FRAME, "dhRenderDistance", () -> LodCompat.getRenderDistance() * 16);
	}

	private static boolean isOnGround() {
		return client.player != null && client.player.onGround();
	}

	private static boolean isHurt() {
		if (client.player != null) {
			return client.player.hurtTime > 0; // recent damage; isHurt() means below max health
		} else {
			return false;
		}
	}

	private static boolean isInvisible() {
		if (client.player != null) {
			return client.player.isInvisible();
		} else {
			return false;
		}
	}

	private static boolean isBurning() {
		if (client.player != null) {
			return client.player.isOnFire();
		} else {
			return false;
		}
	}

	private static boolean isSneaking() {
		if (client.player != null) {
			return client.player.isCrouching();
		} else {
			return false;
		}
	}

	private static boolean isSprinting() {
		if (client.player != null) {
			return client.player.isSprinting();
		} else {
			return false;
		}
	}

	private static Vector3d getSkyColor() {
		if (client.level == null || client.getCameraEntity() == null) {
			return ZERO_VECTOR_3d;
		}

		org.joml.Vector3fc skyColor = client.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR,
			CapturedRenderingState.INSTANCE.getTickDelta());

		return new Vector3d(skyColor.x(), skyColor.y(), skyColor.z());
	}

	static float getBlindness() {
		Entity cameraEntity = client.getCameraEntity();

		if (cameraEntity instanceof LivingEntity) {
			MobEffectInstance blindness = ((LivingEntity) cameraEntity).getEffect(MobEffects.BLINDNESS);

			if (blindness != null) {
				// Mirrors vanilla's blindness fog ramp (fades over the last second); unspecified by the pack format.
				if (blindness.isInfiniteDuration()) {
					return 1.0f;
				} else {
					return Math.clamp(0.0F, 1.0F, blindness.getDuration() / 20.0F);
				}
			}
		}

		return 0.0F;
	}

	static float getDarknessFactor() {
		Entity cameraEntity = client.getCameraEntity();

		if (cameraEntity instanceof LivingEntity) {
			MobEffectInstance darkness = ((LivingEntity) cameraEntity).getEffect(MobEffects.DARKNESS);

			if (darkness != null) {
				return darkness.getBlendFactor((LivingEntity) cameraEntity, CapturedRenderingState.INSTANCE.getTickDelta());
			}
		}

		return 0.0F;
	}

	private static float getPlayerMood() {
		if (!(client.getCameraEntity() instanceof LocalPlayer)) {
			return 0.0F;
		}

		// Should already be in [0, 1]; clamp defensively.
		return Math.clamp(0.0F, 1.0F, ((LocalPlayer) client.getCameraEntity()).getCurrentMood());
	}

	private static float getConstantMood() {
		if (!(client.getCameraEntity() instanceof LocalPlayer)) {
			return 0.0F;
		}

		// Approximated with vanilla's mood value; no separate unsmoothed mood is tracked.
		return Math.clamp(0.0F, 1.0F, getPlayerMood());
	}

	static float getRainStrength() {
		if (client.level == null) {
			return 0f;
		}

		// Clamp: some servers send out-of-range rain levels.
		return Math.clamp(0.0F, 1.0F,
			client.level.getRainLevel(CapturedRenderingState.INSTANCE.getTickDelta()));
	}

	private static Vector2i getEyeBrightness() {
		if (client.getCameraEntity() == null || client.level == null) {
			return ZERO_VECTOR_2i;
		}

		Vec3 feet = client.getCameraEntity().position();
		BlockPos eyeBlockPos = BlockPos.containing(feet.x, client.getCameraEntity().getEyeY(), feet.z);

		int blockLight = client.level.getBrightness(LightLayer.BLOCK, eyeBlockPos);
		int skyLight = client.level.getBrightness(LightLayer.SKY, eyeBlockPos);

		return EYE_BRIGHTNESS.set(blockLight * 16, skyLight * 16);
	}

	private static float getNightVision() {
		Entity cameraEntity = client.getCameraEntity();

		// nightVisionScale reads the effect without a null check: only asked when the effect is there.
		if (cameraEntity instanceof LivingEntity livingEntity && livingEntity.hasEffect(MobEffects.NIGHT_VISION)) {
			float nightVisionStrength = GameRenderer.nightVisionScale(livingEntity, CapturedRenderingState.INSTANCE.getTickDelta());

			if (nightVisionStrength > 0) {
				return Math.clamp(0.0F, 1.0F, nightVisionStrength);
			}
		}

		// Report conduit power's underwater vision as night vision so packs handle it automatically. Uses the
		// player, not the camera entity, to match vanilla's lightmap.
		if (client.player != null && client.player.hasEffect(MobEffects.CONDUIT_POWER)) {
			float underwaterVisibility = client.player.getWaterVision();

			if (underwaterVisibility > 0.0f) {
				return Math.clamp(0.0F, 1.0F, underwaterVisibility);
			}
		}

		return 0.0F;
	}

	static int isEyeInWater() {
		// Client tweaks that hide the fluid overlay can make this report air while submerged. Accepted: hiding the
		// overlay is meant to remove the underwater look anyway.
		FogType submersionType = client.gameRenderer.mainCamera().getFluidInCamera();
		boolean isSpectator = client.player != null && client.player.isSpectator();
		if (submersionType == FogType.WATER) {
			return 1;
		} else if (!isSpectator && submersionType == FogType.LAVA) {
			return 2;
		} else if (submersionType == FogType.POWDER_SNOW) {
			return 3;
		} else {
			return 0;
		}
	}
}
