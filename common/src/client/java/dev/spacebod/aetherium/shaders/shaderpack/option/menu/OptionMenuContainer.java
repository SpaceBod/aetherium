package dev.spacebod.aetherium.shaders.shaderpack.option.menu;

import com.google.common.collect.Lists;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.shaderpack.option.ProfileSet;
import dev.spacebod.aetherium.shaders.shaderpack.option.ShaderPackOptions;
import dev.spacebod.aetherium.shaders.shaderpack.properties.ShaderProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class OptionMenuContainer {
	public final OptionMenuElementScreen mainScreen;
	public final Map<String, OptionMenuElementScreen> subScreens = new LinkedHashMap<>();

	private final List<OptionMenuOptionElement> usedOptionElements = new ArrayList<>();
	private final List<String> usedOptions = new ArrayList<>();
	/**
	 * Options for the {@code *} elements: every option no sub-screen lists. The main screen is built before this is
	 * filled, so the options it lists stay here too and appear again under a {@code *}.
	 */
	private final List<String> unusedOptions = new ArrayList<>();
	/** Screens with a {@code *} element, in the order they were built (main screen first, then sub-screens as declared). */
	private final List<DumpSlot> unusedOptionDumpQueue = new ArrayList<>();
	private final ProfileSet profiles;

	public OptionMenuContainer(ShaderProperties shaderProperties, ShaderPackOptions shaderPackOptions, ProfileSet profiles) {
		this.profiles = profiles;

		// Without a main screen option list, every option is dumped on to the main screen.
		this.mainScreen = new OptionMenuMainElementScreen(
			this, shaderProperties, shaderPackOptions,
			shaderProperties.getMainScreenOptions().orElseGet(() -> Collections.singletonList("*")),
			shaderProperties.getMainScreenColumnCount());

		this.unusedOptions.addAll(shaderPackOptions.getOptionSet().getBooleanOptions().keySet());
		this.unusedOptions.addAll(shaderPackOptions.getOptionSet().getStringOptions().keySet());

		Map<String, Integer> subScreenColumnCounts = shaderProperties.getSubScreenColumnCount();
		shaderProperties.getSubScreenOptions().forEach((screenKey, options) -> subScreens.put(screenKey, new OptionMenuSubElementScreen(
			screenKey, this, shaderProperties, shaderPackOptions, options, Optional.ofNullable(subScreenColumnCounts.get(screenKey)))));

		// The first screen with "*" gets every unused option; later ones get what is still unused then (nothing, unless
		// creating elements failed).
		for (DumpSlot entry : unusedOptionDumpQueue) {
			List<OptionMenuElement> elementsToInsert = new ArrayList<>();
			List<String> unusedOptionsCopy = Lists.newArrayList(this.unusedOptions);

			for (String optionId : unusedOptionsCopy) {
				try {
					OptionMenuElement element = OptionMenuElement.create(optionId, this, shaderProperties, shaderPackOptions);
					if (element != null) {
						elementsToInsert.add(element);

						if (element instanceof OptionMenuOptionElement) {
							this.notifyOptionAdded(optionId, (OptionMenuOptionElement) element);
						}
					}
				} catch (IllegalArgumentException error) {
					AetheriumShaders.logger.warn(error);

					elementsToInsert.add(OptionMenuElement.EMPTY);
				}
			}

			entry.elements().addAll(entry.index(), elementsToInsert);
		}
	}

	public ProfileSet getProfiles() {
		return profiles;
	}

	/** Called by screens containing a "*" element; the unused options are inserted once all screens have resolved. */
	public void queueForUnusedOptionDump(int index, List<OptionMenuElement> elementList) {
		// One slot per screen (by identity: two screens may hold equal lists); a later "*" in the same screen moves it.
		this.unusedOptionDumpQueue.removeIf(slot -> slot.elements() == elementList);
		this.unusedOptionDumpQueue.add(new DumpSlot(elementList, index));
	}

	private record DumpSlot(List<OptionMenuElement> elements, int index) {
	}

	public void notifyOptionAdded(String optionId, OptionMenuOptionElement option) {
		if (!usedOptions.contains(optionId)) {
			usedOptionElements.add(option);
			usedOptions.add(optionId);
		}

		unusedOptions.remove(optionId);
	}
}
