package dev.spacebod.aetherium.shaders.gui;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Watches the shader packs folder while the settings screen is open, on a daemon thread: the names of entries added,
 * removed or changed are collected for the screen, which lists the packs again on its next frame.
 */
final class PackFolderWatch implements AutoCloseable {
	private final WatchService service;
	private final Set<String> changed = ConcurrentHashMap.newKeySet();

	private PackFolderWatch(Path folder) throws IOException {
		service = folder.getFileSystem().newWatchService();
		folder.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
		Thread thread = new Thread(this::run, "Aetherium Shaders pack folder watch");
		thread.setDaemon(true);
		thread.start();
	}

	/** Watches {@code folder}, or returns null when it does not exist or cannot be watched (the Reload button still works). */
	static @Nullable PackFolderWatch start(Path folder) {
		if (!Files.isDirectory(folder)) {
			return null;
		}
		try {
			return new PackFolderWatch(folder);
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.warn("could not watch {} for new shader packs", folder, e);
			return null;
		}
	}

	private void run() {
		try {
			while (true) {
				WatchKey key = service.take();
				for (WatchEvent<?> event : key.pollEvents()) {
					// OVERFLOW has no file name: an empty name still makes the screen list the folder again.
					changed.add(event.context() instanceof Path name ? name.toString() : "");
				}
				if (!key.reset()) {
					return;
				}
			}
		} catch (InterruptedException | ClosedWatchServiceException e) {
			// Closed with the screen.
		}
	}

	/** The entry names that changed since the last call (empty when none), and forgets them. Render thread. */
	Set<String> drain() {
		if (changed.isEmpty()) {
			return Set.of();
		}
		Set<String> names = Set.copyOf(changed);
		changed.removeAll(names);
		return names;
	}

	@Override
	public void close() {
		try {
			service.close();
		} catch (IOException e) {
			AetheriumShaders.logger.debug("could not stop watching the shader packs folder", e);
		}
	}
}
