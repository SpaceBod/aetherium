package dev.spacebod.aetherium.shaders.shaderpack;

import dev.spacebod.aetherium.shaders.features.FeatureFlags;
import java.util.List;
import java.util.stream.Collectors;

/** A pack requires features ({@code features.required} in shaders.properties) this engine or this GPU does not have. */
public final class MissingFeaturesException extends IllegalStateException {
	public MissingFeaturesException(List<String> required) {
		super(message(required));
	}

	private static String message(List<String> required) {
		List<FeatureFlags> flags = required.stream().map(FeatureFlags::getValue).toList();
		String names = required.stream().map(name -> {
			FeatureFlags flag = FeatureFlags.getValue(name);
			return flag == FeatureFlags.UNKNOWN ? name : flag.getHumanReadableName();
		}).collect(Collectors.joining(", "));
		boolean engine = flags.stream().anyMatch(f -> f == FeatureFlags.UNKNOWN || !f.engineSupported());
		return (engine ? "This pack needs features Aetherium Shaders does not support yet: "
				: "This pack needs features your graphics card does not provide: ") + names;
	}
}
