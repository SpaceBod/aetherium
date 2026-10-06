package dev.spacebod.aetherium.client.mixin.shaders.ids;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.spacebod.aetherium.shaders.helpers.ShadowCasters;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Aetherium Shaders: entities only the shadow map draws ({@link ShadowCasters}), extracted after vanilla's. */
@Mixin(LevelExtractor.class)
abstract class ShadowCasterExtractMixin {
	@Shadow
	@Final
	private LevelRenderer levelRenderer;

	@Shadow
	private @Nullable ClientLevel level;

	@Inject(method = "extractVisibleEntities", at = @At("HEAD"))
	private void aetherium$beginCasters(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState output, CallbackInfo ci) {
		ShadowCasters.beginFrame();
	}

	@WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
	private EntityRenderState aetherium$recordExtracted(LevelExtractor self, Entity entity, float partialTicks, Operation<EntityRenderState> original) {
		ShadowCasters.extracted(entity);
		return original.call(self, entity, partialTicks);
	}

	@Inject(method = "extractVisibleEntities", at = @At("RETURN"))
	private void aetherium$extractCasters(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState output, CallbackInfo ci) {
		if (level != null) {
			ShadowCasters.extractCasters(level, camera, deltaTracker, levelRenderer.entityRenderDispatcher(), output);
		}
	}
}
