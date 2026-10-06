package dev.spacebod.aetherium.shaders.uniforms;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;
import dev.spacebod.aetherium.shaders.shaderpack.DimensionId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;

import java.util.Objects;

import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.PER_TICK;

public final class WorldTimeUniforms {
	private WorldTimeUniforms() {
	}

	public static void addWorldTimeUniforms(UniformHolder uniforms) {
		uniforms
			.uniform1i(PER_TICK, "worldTime", WorldTimeUniforms::getWorldDayTime)
			.uniform1i(PER_TICK, "worldDay", WorldTimeUniforms::getWorldDay)
			.uniform1i(PER_TICK, "moonPhase", () -> Minecraft.getInstance().gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.MOON_PHASE, CapturedRenderingState.INSTANCE.getTickDelta()).index());
	}

	static int getWorldDayTime() {
		long timeOfDay = getWorld().getDefaultClockTime();

		if (AetheriumShaders.getCurrentDimension() == DimensionId.END || AetheriumShaders.getCurrentDimension() == DimensionId.NETHER) {
			// Don't zero the fixed time in the Nether/End: packs animate from it (e.g. End beams), which would freeze.
			return (int) (timeOfDay % 24000L);
		}

		long dayTime = getWorld().dimensionType().hasFixedTime() ? 0 :
			(timeOfDay % 24000L);

		return (int) dayTime;
	}

	private static int getWorldDay() {
		long timeOfDay = getWorld().getDefaultClockTime();
		long day = timeOfDay / 24000L;

		return (int) day;
	}

	private static ClientLevel getWorld() {
		return Objects.requireNonNull(Minecraft.getInstance().level);
	}
}
