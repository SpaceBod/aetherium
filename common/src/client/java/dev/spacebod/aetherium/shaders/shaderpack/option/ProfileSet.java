package dev.spacebod.aetherium.shaders.shaderpack.option;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.shaderpack.option.values.OptionValues;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

public class ProfileSet {
	/** The order profiles cycle through. */
	private final LinkedHashMap<String, Profile> orderedProfiles;
	/** The order profiles are matched in. */
	private final List<Profile> sortedProfiles;

	public ProfileSet(LinkedHashMap<String, Profile> orderedProfiles) {
		List<Profile> sorted = new ArrayList<>(orderedProfiles.values());

		Comparator<Profile> lowToHigh = Comparator.comparing(p -> p.precedence);
		Comparator<Profile> highToLow = lowToHigh.reversed();

		// Check profiles with more constraints (higher precedence) first, so a profile that is another
		// profile plus one extra constraint matches ahead of its less specific sibling.
		sorted.sort(highToLow);

		this.sortedProfiles = sorted;
		this.orderedProfiles = orderedProfiles;
	}

	public static ProfileSet fromTree(Map<String, List<String>> tree, OptionSet optionSet) {
		LinkedHashMap<String, Profile> profiles = new LinkedHashMap<>();

		for (String name : tree.keySet()) {
			List<String> path = new ArrayList<>();
			path.add(name);
			profiles.put(name, parse(name, path, tree, optionSet));
		}

		return new ProfileSet(profiles);
	}

	/**
	 * Builds a profile, expanding its {@code profile.X} references depth first. {@code path} is the chain of profiles
	 * being expanded (ending with {@code name}): a reference back into it is a cycle and is skipped, while two profiles
	 * may share a sub-profile. References to profiles that do not exist are skipped too.
	 */
	private static Profile parse(String name, List<String> path, Map<String, List<String>> tree, OptionSet optionSet) {
		Profile.Builder builder = new Profile.Builder(name);
		List<String> options = tree.getOrDefault(name, List.of());

		for (String option : options) {
			if (option.startsWith("!program.")) {
				builder.disableProgram(option.substring("!program.".length()));
			} else if (option.startsWith("profile.")) {
				String dependency = option.substring("profile.".length());

				if (path.contains(dependency)) {
					AetheriumShaders.logger.warn("profile \"{}\" includes itself through {}; ignoring that reference",
						dependency, String.join(" -> ", path));
				} else if (!tree.containsKey(dependency)) {
					AetheriumShaders.logger.warn("profile \"{}\" refers to the unknown profile \"{}\"; ignoring it", name, dependency);
				} else {
					path.add(dependency);
					builder.addAll(parse(dependency, path, tree, optionSet));
					path.removeLast();
				}
			} else if (option.startsWith("!")) {
				builder.option(option.substring(1), "false");
			} else if (option.contains("=")) {
				int splitPoint = option.indexOf("=");
				builder.option(option.substring(0, splitPoint), option.substring(splitPoint + 1));
			} else if (option.contains(":")) {
				int splitPoint = option.indexOf(":");
				builder.option(option.substring(0, splitPoint), option.substring(splitPoint + 1));
			} else if (optionSet.isBooleanOption(option)) {
				builder.option(option, "true");
			} else {
				AetheriumShaders.logger.warn("Invalid pack option: " + option);
			}
		}

		return builder.build();
	}

	public void forEach(BiConsumer<String, Profile> action) {
		orderedProfiles.forEach(action);
	}

	public ProfileResult scan(OptionSet options, OptionValues values) {
		if (sortedProfiles.isEmpty()) {
			return new ProfileResult(null, null, null);
		}

		for (int i = 0; i < sortedProfiles.size(); i++) {
			Profile current = sortedProfiles.get(i);

			if (current.matches(options, values)) {
				Profile next = sortedProfiles.get(Math.floorMod(i + 1, sortedProfiles.size()));
				Profile prev = sortedProfiles.get(Math.floorMod(i - 1, sortedProfiles.size()));

				return new ProfileResult(current, next, prev);
			}
		}

		// No profile matched
		Profile next = sortedProfiles.getFirst();
		Profile prev = sortedProfiles.getLast();

		return new ProfileResult(null, next, prev);
	}

	public int size() {
		return sortedProfiles.size();
	}

	public static class ProfileResult {
		public final Optional<Profile> current;
		public final Profile next;
		public final Profile previous;

		private ProfileResult(@Nullable Profile current, Profile next, Profile previous) {
			this.current = Optional.ofNullable(current);
			this.next = next;
			this.previous = previous;
		}
	}
}
