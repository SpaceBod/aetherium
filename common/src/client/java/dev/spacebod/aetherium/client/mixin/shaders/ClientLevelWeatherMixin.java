package dev.spacebod.aetherium.client.mixin.shaders;

import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * {@code weatherParticles=false}: rain splashes spawn as with the "minimal" particle setting while the pack runs (the
 * rain sound keeps its position).
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelWeatherMixin {
	@ModifyVariable(method = "tickWeatherEffects", at = @At("STORE"), ordinal = 0)
	private ParticleStatus aetherium$packRainParticles(ParticleStatus status) {
		PackDirectives d = ShaderPackEngine.get().activeDirectives();
		return d != null && !d.shouldRenderWeatherParticles() ? ParticleStatus.MINIMAL : status;
	}
}
