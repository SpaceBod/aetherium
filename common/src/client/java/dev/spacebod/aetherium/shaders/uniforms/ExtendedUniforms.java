package dev.spacebod.aetherium.shaders.uniforms;

import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import dev.spacebod.aetherium.client.mixin.access.GameRendererHandAccessor;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency;
import dev.spacebod.aetherium.shaders.helpers.JomlConversions;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.NamespacedId;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Math;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Objects;

import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.PER_FRAME;

public class ExtendedUniforms {
	private static final Vector3d ZERO = new Vector3d(0);
	/**
	 * {@code lightningBoltPosition}: the first bolt among the rendered entities, looked for once per tick (the scan walks
	 * every entity); its position is read every frame. Weak: the cache must not keep a world alive.
	 */
	private static WeakReference<Entity> bolt = new WeakReference<>(null);
	private static WeakReference<ClientLevel> boltLevel = new WeakReference<>(null);
	private static long boltTick = Long.MIN_VALUE;

	public static void addExtendedUniforms(UniformHolder uniforms, FrameUpdateNotifier updateNotifier) {
		WorldInfoUniforms.addWorldInfoUniforms(uniforms);

		EndFlashStorage endFlashStorage = new EndFlashStorage();
		updateNotifier.addListener(endFlashStorage::tick);

		// sRGB: no colour-space conversion is offered.
		uniforms.uniform1i(UniformUpdateFrequency.PER_TICK, "currentColorSpace", () -> 0);

		uniforms.uniform1f(PER_FRAME, "chunkFadeTimeInv", () -> (float) (1.0 / (Minecraft.getInstance().options.chunkSectionFadeInTime().get() * 1000.0)));
		uniforms.uniform1i(PER_FRAME, "textureFilteringMode", () -> switch (Minecraft.getInstance().options.textureFiltering().get()) {
			case NONE -> 0;
			case RGSS -> 1;
			case ANISOTROPIC -> 2;
		});

		// Extensions to the pack format's uniforms.
		uniforms.uniform1b(UniformUpdateFrequency.PER_TICK, "feetInWater", ExtendedUniforms::getIsInShallowWater);
		uniforms.uniform1b(UniformUpdateFrequency.PER_TICK, "inSwimmingAnimation", ExtendedUniforms::getIsSwimming);
		uniforms.uniform1b(UniformUpdateFrequency.PER_TICK, "isRiding", ExtendedUniforms::getIsPassenger);
		uniforms.uniform1b(UniformUpdateFrequency.PER_TICK, "isElytraFlying", ExtendedUniforms::isElytraFlying);
		uniforms.uniform1b(UniformUpdateFrequency.PER_TICK, "vehicleInWater", ExtendedUniforms::getVehicleInShallowWater);
		uniforms.uniform1i(UniformUpdateFrequency.PER_TICK, "vehicleId", ExtendedUniforms::getVehicleId);
		uniforms.uniform3d(PER_FRAME, "vehicleLookVector", ExtendedUniforms::getVehicleLookVector);
		uniforms.uniform3d(PER_FRAME, "relativeVehiclePosition", ExtendedUniforms::getRelativeVehiclePosition);
		uniforms.uniform1f(PER_FRAME, "thunderStrength", ExtendedUniforms::getThunderStrength);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "currentPlayerHealth", ExtendedUniforms::getCurrentHealth);
		uniforms.uniform1b(UniformUpdateFrequency.PER_TICK, "heavyFog", ExtendedUniforms::isHeavyFog);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "maxPlayerHealth", ExtendedUniforms::getMaxHealth);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "currentPlayerHunger", ExtendedUniforms::getCurrentHunger);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "maxPlayerHunger", () -> 20);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "endFlashIntensity", endFlashStorage::getCurrentEndFlash);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "previousEndFlashIntensity", endFlashStorage::getLastEndFlash);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "currentPlayerArmor", ExtendedUniforms::getCurrentArmor);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "maxPlayerArmor", () -> 50);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "currentPlayerAir", ExtendedUniforms::getCurrentAir);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "maxPlayerAir", ExtendedUniforms::getMaxAir);
		uniforms.uniform1b(PER_FRAME, "firstPersonCamera", ExtendedUniforms::isFirstPersonCamera);
		uniforms.uniform1b(UniformUpdateFrequency.PER_TICK, "isSpectator", ExtendedUniforms::isSpectator);
		uniforms.uniform1i(PER_FRAME, "currentSelectedBlockId", ExtendedUniforms::getCurrentSelectedBlockId);
		uniforms.uniform1i(PER_FRAME, "seaLevel", () -> Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getSeaLevel());
		uniforms.uniform3f(PER_FRAME, "currentSelectedBlockPos", ExtendedUniforms::getCurrentSelectedBlockPos);
		uniforms.uniform3d(PER_FRAME, "eyePosition", ExtendedUniforms::getEyePosition);
		uniforms.uniform1f(UniformUpdateFrequency.PER_TICK, "cloudTime", CapturedRenderingState.INSTANCE::getCloudTime);
		uniforms.uniform3d(PER_FRAME, "relativeEyePosition", () -> CameraUniforms.getUnshiftedCameraPosition().sub(getEyePosition()));
		uniforms.uniform3d(PER_FRAME, "playerLookVector", () -> {
			if (Minecraft.getInstance().getCameraEntity() instanceof LivingEntity livingEntity) {
				return JomlConversions.fromVec3(livingEntity.getViewVector(CapturedRenderingState.INSTANCE.getTickDelta()));
			} else {
				return ZERO;
			}
		});
		uniforms.uniform3d(PER_FRAME, "playerBodyVector", () -> JomlConversions.fromVec3(Minecraft.getInstance().getCameraEntity().getForward()));
		Vector4f zero = new Vector4f(0, 0, 0, 0);
		Vector4f position = new Vector4f();
		uniforms.uniform4f(UniformUpdateFrequency.PER_TICK, "lightningBoltPosition", () -> {
			Entity found = lightningBolt();
			if (found == null) {
				return zero;
			}
			Vector3d unshiftedCameraPosition = CameraUniforms.getUnshiftedCameraPosition();
			Vec3 vec3 = found.getPosition(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true));
			return position.set((float) (vec3.x - unshiftedCameraPosition.x), (float) (vec3.y - unshiftedCameraPosition.y), (float) (vec3.z - unshiftedCameraPosition.z), 1);
		});
	}

	private static @Nullable Entity lightningBolt() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return null;
		}
		long tick = level.getGameTime();
		if (tick != boltTick || level != boltLevel.get()) {
			boltTick = tick;
			if (level != boltLevel.get()) {
				boltLevel = new WeakReference<>(level);
			}
			Entity first = null;
			for (Entity entity : level.entitiesForRendering()) {
				if (entity instanceof LightningBolt) {
					first = entity;
					break;
				}
			}
			if (first != bolt.get()) {
				bolt = new WeakReference<>(first);
			}
		}
		Entity found = bolt.get();
		return found == null || found.isRemoved() ? null : found;
	}

	private static int getVehicleId() {
		if (Minecraft.getInstance().player == null) return 0;
		if (Minecraft.getInstance().player.getVehicle() == null) return 0;

		Object2IntFunction<NamespacedId> entityId = WorldRenderingSettings.INSTANCE.getEntityIds();
		if (entityId == null) return 0;

		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(Minecraft.getInstance().player.getVehicle().getType());
		if (id == null) return 0;

		return entityId.applyAsInt(new NamespacedId(id.getNamespace(), id.getPath()));
	}

	private static Vector3d getVehicleLookVector() {
		if (Minecraft.getInstance().player == null) return ZERO;
		if (Minecraft.getInstance().player.getVehicle() == null) return ZERO;

		return JomlConversions.fromVec3(Minecraft.getInstance().player.getVehicle().getForward());
	}

	private static Vector3d getRelativeVehiclePosition() {
		if (Minecraft.getInstance().player == null) return ZERO;
		if (Minecraft.getInstance().player.getVehicle() == null) return ZERO;

		Vec3 vehiclePos = Minecraft.getInstance().player.getVehicle().getPosition(CapturedRenderingState.INSTANCE.getTickDelta());

		Vector3d pos = new Vector3d(vehiclePos.x, vehiclePos.y, vehiclePos.z);

		return CameraUniforms.getUnshiftedCameraPosition().sub(pos);
	}

	private static boolean getVehicleInShallowWater() {
		if (Minecraft.getInstance().player == null) return false;
		if (Minecraft.getInstance().player.getVehicle() == null) return false;

		return Minecraft.getInstance().player.getVehicle().isInShallowWater();
	}

	private static boolean getIsInShallowWater() {
		if (Minecraft.getInstance().player == null) {
			return false;
		}

		return Minecraft.getInstance().player.isInShallowWater();
	}

	private static boolean getIsSwimming() {
		if (Minecraft.getInstance().player == null) {
			return false;
		}

		return Minecraft.getInstance().player.isSwimming();
	}

	private static boolean getIsPassenger() {
		if (Minecraft.getInstance().player == null) {
			return false;
		}

		return Minecraft.getInstance().player.isPassenger();
	}

	private static boolean isElytraFlying() {
		if (Minecraft.getInstance().player == null) {
			return false;
		}

		return Minecraft.getInstance().player.isFallFlying();
	}

	private static boolean isHeavyFog() {
		if (Minecraft.getInstance().level != null) {
			return Minecraft.getInstance().gui.hud.getBossOverlay().shouldCreateWorldFog();
		}

		return false;
	}

	/** Whether the game draws a block outline this frame (its own rule: HUD shown, spectator and adventure mode limits). */
	private static boolean showsBlockOutline() {
		return ((GameRendererHandAccessor) Minecraft.getInstance().gameRenderer).aetherium$shouldRenderBlockOutline();
	}

	private static int getCurrentSelectedBlockId() {
		HitResult hitResult = Minecraft.getInstance().hitResult;
		if (Minecraft.getInstance().level != null && showsBlockOutline() && hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
			BlockPos blockPos4 = ((BlockHitResult) hitResult).getBlockPos();
			BlockState blockState = Minecraft.getInstance().level.getBlockState(blockPos4);
			if (!blockState.isAir() && Minecraft.getInstance().level.getWorldBorder().isWithinBounds(blockPos4)) {
				return WorldRenderingSettings.INSTANCE.getBlockStateIds().getInt(blockState);
			}
		}

		return 0;
	}

	private static Vector3f getCurrentSelectedBlockPos() {
		HitResult hitResult = Minecraft.getInstance().hitResult;
		if (Minecraft.getInstance().level != null && showsBlockOutline() && hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
			BlockPos blockPos4 = ((BlockHitResult) hitResult).getBlockPos();
			return Vec3.atCenterOf(blockPos4).subtract(Minecraft.getInstance().gameRenderer.mainCamera().position()).toVector3f();
		}

		return new Vector3f(-256.0f);
	}

	private static float getThunderStrength() {
		// Clamp: some servers send out-of-range thunder levels.
		return Math.clamp(0.0F, 1.0F,
			Minecraft.getInstance().level.getThunderLevel(CapturedRenderingState.INSTANCE.getTickDelta()));
	}

	private static float getCurrentHealth() {
		if (Minecraft.getInstance().player == null || !Minecraft.getInstance().gameMode.getPlayerMode().isSurvival()) {
			return -1;
		}

		return Minecraft.getInstance().player.getHealth() / Minecraft.getInstance().player.getMaxHealth();
	}

	private static float getCurrentHunger() {
		if (Minecraft.getInstance().player == null || !Minecraft.getInstance().gameMode.getPlayerMode().isSurvival()) {
			return -1;
		}

		return Minecraft.getInstance().player.getFoodData().getFoodLevel() / 20f;
	}

	private static float getCurrentAir() {
		if (Minecraft.getInstance().player == null || !Minecraft.getInstance().gameMode.getPlayerMode().isSurvival()) {
			return -1;
		}

		return (float) Minecraft.getInstance().player.getAirSupply() / (float) Minecraft.getInstance().player.getMaxAirSupply();
	}

	private static float getCurrentArmor() {
		if (Minecraft.getInstance().player == null || !Minecraft.getInstance().gameMode.getPlayerMode().isSurvival()) {
			return -1;
		}

		return Minecraft.getInstance().player.getArmorValue() / 50.0f;
	}

	private static float getMaxAir() {
		if (Minecraft.getInstance().player == null || !Minecraft.getInstance().gameMode.getPlayerMode().isSurvival()) {
			return -1;
		}

		return Minecraft.getInstance().player.getMaxAirSupply();
	}

	private static float getMaxHealth() {
		if (Minecraft.getInstance().player == null || !Minecraft.getInstance().gameMode.getPlayerMode().isSurvival()) {
			return -1;
		}

		return Minecraft.getInstance().player.getMaxHealth();
	}

	private static boolean isFirstPersonCamera() {
		// If camera type is not explicitly third-person, assume it's first-person.
		return switch (Minecraft.getInstance().options.getCameraType()) {
			case THIRD_PERSON_BACK, THIRD_PERSON_FRONT -> false;
			default -> true;
		};
	}

	private static boolean isSpectator() {
		return Minecraft.getInstance().gameMode.getPlayerMode() == GameType.SPECTATOR;
	}

	private static Vector3d getEyePosition() {
		Objects.requireNonNull(Minecraft.getInstance().getCameraEntity());
		Vec3 pos = Minecraft.getInstance().getCameraEntity().getEyePosition(CapturedRenderingState.INSTANCE.getTickDelta());
		return new Vector3d(pos.x, pos.y, pos.z);
	}

	public static class WorldInfoUniforms {
		public static void addWorldInfoUniforms(UniformHolder uniforms) {
			// The level is looked up every frame, so dimension changes and rejoins are picked up.
			uniforms.uniform1i(PER_FRAME, "bedrockLevel", () -> {
				ClientLevel level = Minecraft.getInstance().level;
				if (level != null) {
					return level.dimensionType().minY();
				} else {
					return 0;
				}
			});
			uniforms.uniform1f(PER_FRAME, "cloudHeight", () -> {
				ClientLevel level = Minecraft.getInstance().level;
				if (level != null) {
					return Minecraft.getInstance().gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.CLOUD_HEIGHT, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
				} else {
					return 192.0;
				}
			});

			uniforms.uniform1i(PER_FRAME, "heightLimit", () -> {
				ClientLevel level = Minecraft.getInstance().level;
				if (level != null) {
					return level.dimensionType().height();
				} else {
					return 256;
				}
			});
			uniforms.uniform1i(PER_FRAME, "logicalHeightLimit", () -> {
				ClientLevel level = Minecraft.getInstance().level;
				if (level != null) {
					return level.dimensionType().logicalHeight();
				} else {
					return 256;
				}
			});
			uniforms.uniform1b(PER_FRAME, "hasCeiling", () -> {
				ClientLevel level = Minecraft.getInstance().level;
				if (level != null) {
					return level.dimensionType().hasCeiling();
				} else {
					return false;
				}
			});
			uniforms.uniform1b(PER_FRAME, "hasSkylight", () -> {
				ClientLevel level = Minecraft.getInstance().level;
				if (level != null) {
					return level.dimensionType().hasSkyLight();
				} else {
					return true;
				}
			});
			uniforms.uniform1f(PER_FRAME, "ambientLight", () -> {
				ClientLevel level = Minecraft.getInstance().level;
				if (level != null) {
					return level.dimensionType().ambientLight();
				} else {
					return 0f;
				}
			});

		}
	}
}
