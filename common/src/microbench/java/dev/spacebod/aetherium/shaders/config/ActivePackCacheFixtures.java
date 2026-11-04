package dev.spacebod.aetherium.shaders.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Game-free regression checks: cached reads must not touch disk, and saves/reloads must replace even empty choices. */
public final class ActivePackCacheFixtures {
	private ActivePackCacheFixtures() {
	}

	public static void main(String[] args) throws IOException {
		Path directory = Files.createTempDirectory("aetherium-active-pack-");
		Path file = directory.resolve("aetherium.json");
		try {
			selectionDefaults(file);
			cachedReads(file);
			savedChoices(file);
			reloadAndRetry(file);
		} finally {
			Files.deleteIfExists(file);
			Files.delete(directory);
		}
		System.out.println("active pack cache: defaults, cached off/on reads, saves, external edits and malformed-file recovery pass");
	}

	private static void selectionDefaults(Path file) throws IOException {
		ActivePackCache cache = new ActivePackCache();
		expect(cache.get(file), null);
		String[] configs = {"{}", "{\"shaders\":{}}", "{\"shaders\":{\"pack\":\"pack.zip\"}}",
				"{\"shaders\":{\"enabled\":false,\"pack\":\"pack.zip\"}}", "{\"shaders\":{\"enabled\":true}}"};
		String[] expected = {null, null, "pack.zip", null, null};
		for (int i = 0; i < configs.length; i++) {
			Files.writeString(file, configs[i]);
			cache.invalidate();
			expect(cache.get(file), expected[i]);
		}
	}

	private static void cachedReads(Path file) throws IOException {
		for (boolean enabled : new boolean[] {false, true}) {
			ActivePackCache cache = new ActivePackCache();
			Files.writeString(file, "{\"shaders\":{\"enabled\":" + enabled + ",\"pack\":\"pack.zip\"}}");
			expect(cache.get(file), enabled ? "pack.zip" : null);
			// Any fresh read would fail to parse. Both on and off must remain cached until explicitly invalidated.
			Files.writeString(file, "{");
			for (int i = 0; i < 10_000; i++) {
				expect(cache.get(file), enabled ? "pack.zip" : null);
			}
		}
		Files.delete(file);
	}

	private static void savedChoices(Path file) throws IOException {
		ActivePackCache cache = new ActivePackCache();
		expect(cache.get(file), null);
		cache.saved("first.zip");
		expect(cache.get(file), "first.zip");
		cache.saved(null);
		expect(cache.get(file), null);
		cache.saved("second.zip");
		expect(cache.get(file), "second.zip");
	}

	private static void reloadAndRetry(Path file) throws IOException {
		ActivePackCache cache = new ActivePackCache();
		expect(cache.get(file), null);
		Files.writeString(file, "{\"shaders\":{\"pack\":\"edited.zip\"}}");
		expect(cache.get(file), null);
		cache.invalidate();
		expect(cache.get(file), "edited.zip");
		Files.writeString(file, "{");
		cache.invalidate();
		try {
			cache.get(file);
			throw new AssertionError("malformed config was accepted");
		} catch (com.google.gson.JsonParseException expected) {
			// A failed load must not poison the cache: fixing the file can be retried.
		}
		Files.writeString(file, "{\"shaders\":{\"pack\":\"fixed.zip\"}}");
		expect(cache.get(file), "fixed.zip");
	}

	private static void expect(Optional<String> actual, String expected) {
		if (!actual.equals(Optional.ofNullable(expected))) {
			throw new AssertionError("expected " + expected + ", got " + actual);
		}
	}
}
