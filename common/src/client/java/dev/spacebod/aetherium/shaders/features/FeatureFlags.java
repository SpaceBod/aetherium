package dev.spacebod.aetherium.shaders.features;

import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.function.BooleanSupplier;

public enum FeatureFlags {
	SEPARATE_HARDWARE_SAMPLERS(() -> true, () -> true),
	HIGHER_SHADOWCOLOR(() -> true, () -> true),
	CUSTOM_IMAGES(() -> true, ShaderRenderSystem::supportsImageLoadStore),
	PER_BUFFER_BLENDING(() -> true, ShaderRenderSystem::supportsBufferBlending),
	COMPUTE_SHADERS(() -> true, ShaderRenderSystem::supportsCompute),
	TESSELLATION_SHADERS(() -> true, ShaderRenderSystem::supportsTesselation),
	ENTITY_TRANSLUCENT(() -> true, () -> true),
	REVERSED_CULLING(() -> true, () -> true),
	BLOCK_EMISSION_ATTRIBUTE(() -> true, () -> true),
	CAN_DISABLE_WEATHER(() -> true, () -> true),
	SSBO(() -> true, ShaderRenderSystem::supportsSSBO),
	FADE_VARIABLE(() -> true, () -> true),
	TEXTURE_FILTERING(() -> true, () -> true),
	UNKNOWN(() -> false, () -> false);

	private final BooleanSupplier engineRequirement;
	private final BooleanSupplier hardwareRequirement;

	FeatureFlags(BooleanSupplier engineRequirement, BooleanSupplier hardwareRequirement) {
		this.engineRequirement = engineRequirement;
		this.hardwareRequirement = hardwareRequirement;
	}

	public static boolean isInvalid(String name) {
		try {
			return !FeatureFlags.valueOf(name.toUpperCase(Locale.US)).isUsable();
		} catch (IllegalArgumentException e) {
			return true;
		}
	}

	public static FeatureFlags getValue(String value) {
		if (value.equalsIgnoreCase("TESSELATION_SHADERS")) {
			// Accept the historical misspelling.
			value = "TESSELLATION_SHADERS";
		}

		try {
			return FeatureFlags.valueOf(value.toUpperCase(Locale.US));
		} catch (IllegalArgumentException e) {
			return FeatureFlags.UNKNOWN;
		}
	}

	/** Whether the engine implements this feature (whatever the hardware). */
	public boolean engineSupported() {
		return engineRequirement.getAsBoolean();
	}

	public String getHumanReadableName() {
		return StringUtils.capitalize(name().replace("_", " ").toLowerCase());
	}

	public boolean isUsable() {
		return engineRequirement.getAsBoolean() && hardwareRequirement.getAsBoolean();
	}
}
