package dev.spacebod.aetherium.shaders.gui;

import com.google.common.collect.ImmutableList;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.config.ShaderPackSettings;
import dev.spacebod.aetherium.shaders.gl.shader.StandardMacros;
import dev.spacebod.aetherium.shaders.helpers.StringPair;
import dev.spacebod.aetherium.shaders.shaderpack.ShaderPack;
import dev.spacebod.aetherium.shaders.shaderpack.option.BaseOption;
import dev.spacebod.aetherium.shaders.shaderpack.option.BooleanOption;
import dev.spacebod.aetherium.shaders.shaderpack.option.MergedBooleanOption;
import dev.spacebod.aetherium.shaders.shaderpack.option.MergedStringOption;
import dev.spacebod.aetherium.shaders.shaderpack.option.Profile;
import dev.spacebod.aetherium.shaders.shaderpack.option.ProfileSet;
import dev.spacebod.aetherium.shaders.shaderpack.option.StringOption;
import dev.spacebod.aetherium.shaders.shaderpack.option.menu.OptionMenuContainer;
import dev.spacebod.aetherium.shaders.shaderpack.option.menu.OptionMenuElement;
import dev.spacebod.aetherium.shaders.shaderpack.option.menu.OptionMenuElementScreen;
import dev.spacebod.aetherium.shaders.shaderpack.option.menu.OptionMenuLinkElement;
import dev.spacebod.aetherium.shaders.shaderpack.option.menu.OptionMenuOptionElement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * One pack's options as the settings screen presents them: labels and descriptions from the pack's own language files
 * (the standard pack-format keys: {@code option.X}, {@code option.X.comment}, {@code value.X.v}, {@code prefix.X},
 * {@code suffix.X}, {@code screen.X}, {@code profile.X}), the values the pack runs with, the changes not applied yet
 * (the shared option queue, which the menu model reads too), profiles and a search over every screen.
 * <p>
 * Packs are read in the background ({@link #request}) with only what the screen needs (no program is preprocessed), and
 * kept for the next time the screen opens while the pack and its saved options are unchanged.
 */
public final class PackOptions {
	/** A search result or list entry: an element and the screen path it lives under. */
	public record Located(OptionMenuElement element, List<String> path) {
	}

	private final String packName;
	private final ShaderPack pack;
	private final Map<String, String> lang;

	private PackOptions(String packName, ShaderPack pack, Map<String, String> lang) {
		this.packName = packName;
		this.pack = pack;
		this.lang = lang;
	}

	/**
	 * Reads a pack for the screen (no GPU work, any thread): {@code defines} are the standard macros
	 * ({@link StandardMacros}, read on the render thread), {@code language} the game's selected language code.
	 */
	public static PackOptions load(String packName, Map<String, String> saved, ImmutableList<StringPair> defines, String language)
			throws IOException {
		try (ShaderPackSettings.OpenPack opened = ShaderPackSettings.open(packName)) {
			ShaderPack pack = new ShaderPack(opened.root(), saved, defines, opened.isZip(), true);
			// Per key: the player's language where the pack translates it, English otherwise.
			Map<String, String> lang = new HashMap<>(pack.getLanguageMap().getTranslations("en_us"));
			lang.putAll(pack.getLanguageMap().getTranslations(language));
			return new PackOptions(packName, pack, lang);
		}
	}

	/** Two workers: the selected pack is read while another (hovered in the list) may already be on its way. */
	private static final ExecutorService READER = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "Aetherium Shaders menu reader");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});

	/** What a read depends on: the pack file's time, its saved options, the standard macros and the language. */
	private record Stamp(long modified, Map<String, String> saved, List<StringPair> defines, String language) {
	}

	private record Read(Stamp stamp, CompletableFuture<PackOptions> future) {
	}

	/** Render thread only. Recently read packs, newest last. */
	private static final LinkedHashMap<String, Read> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Read> eldest) {
			return size() > 8;
		}
	};

	/**
	 * The pack's options, read in the background: done at once when it was read before and nothing changed since (the
	 * pack file, its saved options, the language). Render thread.
	 */
	public static CompletableFuture<PackOptions> request(String packName) {
		Map<String, String> saved = ShaderPackSettings.readOptions(packName);
		String language = Minecraft.getInstance().getLanguageManager().getSelected().toLowerCase(Locale.ROOT);
		long modified;
		try {
			modified = Files.getLastModifiedTime(ShaderPackSettings.packsDirectory().resolve(packName)).toMillis();
		} catch (IOException e) {
			modified = -1;
		}
		var defines = StandardMacros.createStandardEnvironmentDefines();
		Stamp stamp = new Stamp(modified, saved, defines, language);
		Read entry = CACHE.get(packName);
		if (entry != null && entry.stamp().equals(stamp) && !entry.future().isCompletedExceptionally()) {
			return entry.future();
		}
		CompletableFuture<PackOptions> future = CompletableFuture.supplyAsync(() -> {
			try {
				return load(packName, saved, defines, language);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}, READER);
		CACHE.put(packName, new Read(stamp, future));
		return future;
	}

	/** Forgets every pack read so far (the packs folder was refreshed, or a pack reloaded). */
	public static void forgetAll() {
		CACHE.clear();
	}

	public String packName() {
		return packName;
	}

	public OptionMenuContainer menu() {
		return pack.getMenuContainer();
	}

	public OptionMenuElementScreen screen(@Nullable String id) {
		if (id == null) {
			return menu().mainScreen;
		}
		OptionMenuElementScreen screen = menu().subScreens.get(id);
		return screen == null ? menu().mainScreen : screen;
	}

	// ---------------------------------------------------------------- text

	private @Nullable String tr(String key) {
		String v = lang.get(key);
		return v == null || v.isBlank() ? null : v;
	}

	public String optionLabel(String id) {
		String v = tr("option." + id);
		return v == null ? humanize(id) : v;
	}

	public String screenLabel(String id) {
		String v = tr("screen." + id);
		return v == null ? humanize(id) : v;
	}

	public String profileLabel(String name) {
		String v = tr("profile." + name);
		return v == null ? humanize(name) : v;
	}

	/** An option's description: the pack's translation, else the comment on its #define in the shader source. */
	public Optional<String> optionComment(String id) {
		String v = tr("option." + id + ".comment");
		if (v != null) {
			return Optional.of(v);
		}
		BaseOption option = option(id);
		return option == null ? Optional.empty() : option.getComment().filter(c -> !c.isBlank());
	}

	public Optional<String> screenComment(String id) {
		return Optional.ofNullable(tr("screen." + id + ".comment"));
	}

	public Optional<String> profileComment() {
		return Optional.ofNullable(tr("profile.comment"));
	}

	/** How a value of a string option reads: prefix + translated value + suffix. */
	public String valueLabel(String id, String value) {
		String v = tr("value." + id + "." + value);
		String prefix = lang.getOrDefault("prefix." + id, "");
		String suffix = lang.getOrDefault("suffix." + id, "");
		return prefix + (v == null ? value : v) + suffix;
	}

	/** {@code SHADOW_QUALITY} -> {@code Shadow Quality}, for options a pack does not translate. */
	static String humanize(String id) {
		String[] parts = id.replace('-', '_').split("_");
		StringBuilder b = new StringBuilder();
		for (String p : parts) {
			if (p.isEmpty()) {
				continue;
			}
			if (b.length() > 0) {
				b.append(' ');
			}
			b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase(Locale.ROOT));
		}
		return b.length() == 0 ? id : b.toString();
	}

	// ---------------------------------------------------------------- values

	public @Nullable BaseOption option(String id) {
		MergedBooleanOption b = pack.getShaderPackOptions().getOptionSet().getBooleanOptions().get(id);
		if (b != null) {
			return b.getOption();
		}
		MergedStringOption s = pack.getShaderPackOptions().getOptionSet().getStringOptions().get(id);
		return s == null ? null : s.getOption();
	}

	private static Map<String, String> queue() {
		return AetheriumShaders.getShaderPackOptionQueue();
	}

	/** The value the pack runs with (saved), as text. */
	public String appliedValue(String id) {
		BaseOption option = option(id);
		if (option instanceof BooleanOption b) {
			return Boolean.toString(pack.getShaderPackOptions().getOptionValues().getBooleanValueOrDefault(id));
		}
		if (option instanceof StringOption s) {
			return pack.getShaderPackOptions().getOptionValues().getStringValueOrDefault(id);
		}
		return "";
	}

	/** The value shown: pending if changed in the screen, else applied. */
	public String value(String id) {
		String pending = queue().get(id);
		return pending != null ? pending : appliedValue(id);
	}

	public String defaultValue(String id) {
		BaseOption option = option(id);
		if (option instanceof BooleanOption b) {
			return Boolean.toString(b.getDefaultValue());
		}
		return option instanceof StringOption s ? s.getDefaultValue() : "";
	}

	public void set(String id, String value) {
		if (value.equals(appliedValue(id))) {
			queue().remove(id);
		} else {
			queue().put(id, value);
		}
	}

	public boolean isDefault(String id) {
		return value(id).equals(defaultValue(id));
	}

	public boolean isPending(String id) {
		return queue().containsKey(id);
	}

	public int pendingCount() {
		return queue().size();
	}

	public void discardPending() {
		queue().clear();
	}

	/** Every option back to its default (pending until applied). */
	public void resetAll() {
		for (String id : allOptionIds()) {
			set(id, defaultValue(id));
		}
	}

	/**
	 * Pending values as a settings file sets them: the values it names, every other option at its default (as if the
	 * file were the pack's saved settings). Returns how many of its keys are options of this pack; others are ignored.
	 */
	public int importValues(Map<String, String> values) {
		queue().clear();
		int known = 0;
		for (String id : allOptionIds()) {
			String value = values.get(id);
			if (value != null) {
				known++;
			}
			set(id, value != null ? value : defaultValue(id));
		}
		return known;
	}

	public Set<String> allOptionIds() {
		Set<String> ids = new HashSet<>(pack.getShaderPackOptions().getOptionSet().getBooleanOptions().keySet());
		ids.addAll(pack.getShaderPackOptions().getOptionSet().getStringOptions().keySet());
		return ids;
	}

	/** The next (or previous) allowed value of a string option, wrapping around. */
	public String step(StringOption option, String current, int delta) {
		List<String> values = option.getAllowedValues();
		if (values.isEmpty()) {
			return current;
		}
		int i = values.indexOf(current);
		if (i < 0) {
			i = 0;
		}
		return values.get(Math.floorMod(i + delta, values.size()));
	}

	/**
	 * The options file as it would be with the pending changes applied: the file on disk now (so edits made to it since
	 * the pack was read are kept), the pending changes on top, and every option of the pack left at its default dropped.
	 * Keys this pack does not declare (options of an older version of it, for example) are kept as they are.
	 */
	public Map<String, String> changesToSave() {
		Map<String, String> changed = new TreeMap<>(ShaderPackSettings.readOptions(packName));
		changed.putAll(queue());
		changed.entrySet().removeIf(e -> option(e.getKey()) != null && e.getValue().equals(defaultValue(e.getKey())));
		return changed;
	}

	// ---------------------------------------------------------------- profiles

	public ProfileSet profiles() {
		return menu().getProfiles();
	}

	/** Profile names in the pack's order. */
	public List<String> profileNames() {
		List<String> names = new ArrayList<>();
		profiles().forEach((name, p) -> names.add(name));
		return names;
	}

	/** The profile matching the shown values, if any ("Custom" otherwise). Re-scanned only when the pending values change. */
	public Optional<String> currentProfile() {
		if (profileFor == null || !profileFor.equals(queue())) {
			var values = pack.getShaderPackOptions().getOptionValues().mutableCopy();
			values.addAll(queue());
			profile = profiles().scan(pack.getShaderPackOptions().getOptionSet(), values).current.map(p -> p.name);
			profileFor = new HashMap<>(queue());
		}
		return profile;
	}

	private @Nullable Map<String, String> profileFor;
	private Optional<String> profile = Optional.empty();

	public void applyProfile(String name) {
		profiles().forEach((n, profile) -> {
			if (n.equals(name)) {
				applyProfile(profile);
			}
		});
	}

	private void applyProfile(Profile profile) {
		profile.optionValues.forEach(this::set);
	}

	// ---------------------------------------------------------------- search

	/** Options whose name, label, value or description contains every word of the query, across all screens (a new list). */
	public List<Located> search(String query) {
		if (!query.equals(lastQuery)) {
			lastHits = List.copyOf(searchUncached(query));
			lastQuery = query;
		}
		return new ArrayList<>(lastHits);
	}

	private @Nullable String lastQuery;
	private List<Located> lastHits = List.of();

	private List<Located> searchUncached(String query) {
		String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
		List<Located> hits = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		collect(menu().mainScreen, new ArrayList<>(), new HashSet<>(), (element, path) -> {
			if (element instanceof OptionMenuOptionElement o && seen.add(o.optionId)) {
				String hay = (o.optionId + " " + optionLabel(o.optionId) + " " + optionComment(o.optionId).orElse("") + " "
						+ String.join(" ", path)).toLowerCase(Locale.ROOT).replaceAll("§.", "");
				for (String w : words) {
					if (!hay.contains(w)) {
						return;
					}
				}
				hits.add(new Located(element, List.copyOf(path)));
			}
		});
		return hits;
	}

	private interface Visitor {
		void visit(OptionMenuElement element, List<String> path);
	}

	private void collect(OptionMenuElementScreen screen, List<String> path, Set<OptionMenuElementScreen> visited, Visitor visitor) {
		if (!visited.add(screen)) {
			return;
		}
		for (OptionMenuElement element : screen.elements) {
			visitor.visit(element, path);
			if (element instanceof OptionMenuLinkElement link) {
				OptionMenuElementScreen sub = menu().subScreens.get(link.targetScreenId);
				if (sub != null) {
					path.add(screenLabel(link.targetScreenId));
					collect(sub, path, visited, visitor);
					path.removeLast();
				}
			}
		}
	}

}
