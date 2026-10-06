package dev.spacebod.aetherium.shaders.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

/**
 * The system's own "open file" window (through SDL, which the game already runs on), for importing a pack's settings.
 * <p>
 * SDL is asked on the render thread and returns at once; the answer comes later through a callback, on the render
 * thread or one of SDL's own, so it is handed back as a future and the screen picks it up on its next frame. The
 * dialog belongs to the game's window, so the game keeps drawing under it.
 */
final class FileDialog {
	/** Questions SDL has not answered yet, by the number handed to it with each one (passed back to the callback). */
	private static final Map<Long, CompletableFuture<Optional<Path>>> WAITING = new ConcurrentHashMap<>();
	private static final AtomicLong NEXT = new AtomicLong(1);

	private FileDialog() {
	}

	/**
	 * Asks for a settings file ({@code .txt}) to open, starting at {@code origin}. Empty when the window was dismissed;
	 * completes exceptionally when the system has no dialog to offer.
	 */
	static CompletableFuture<Optional<Path>> open(String title, Path origin) {
		CompletableFuture<Optional<Path>> answer = new CompletableFuture<>();
		long question = NEXT.getAndIncrement();
		// Before asking: SDL answers at once, before it returns, when it cannot show a dialog at all.
		WAITING.put(question, answer);
		try {
			ask(title, origin, question);
		} catch (RuntimeException | LinkageError e) {
			WAITING.remove(question);
			answer.completeExceptionally(e);
		}
		return answer;
	}

	private static void ask(String title, Path origin, long question) {
		RenderSystem.assertOnRenderThread();
		SDL_DialogFileFilter.Buffer filters = Kept.FILTERS;
		long window = Minecraft.getInstance().getWindow().handle();
		int properties = SDLProperties.SDL_CreateProperties();
		if (properties == 0) {
			throw new IllegalStateException("SDL could not make a file dialog: " + SDLError.SDL_GetError());
		}
		// SDL is done with the property set once the call returns; the filters are read until the callback has run,
		// which is why they are kept for the life of the game.
		try {
			SDLProperties.SDL_SetPointerProperty(properties, SDLDialog.SDL_PROP_FILE_DIALOG_WINDOW_POINTER, window);
			SDLProperties.SDL_SetPointerProperty(properties, SDLDialog.SDL_PROP_FILE_DIALOG_FILTERS_POINTER, filters.address());
			SDLProperties.SDL_SetNumberProperty(properties, SDLDialog.SDL_PROP_FILE_DIALOG_NFILTERS_NUMBER, filters.remaining());
			SDLProperties.SDL_SetStringProperty(properties, SDLDialog.SDL_PROP_FILE_DIALOG_TITLE_STRING, title);
			SDLProperties.SDL_SetStringProperty(properties, SDLDialog.SDL_PROP_FILE_DIALOG_LOCATION_STRING, origin.toAbsolutePath().toString());
			SDLDialog.SDL_ShowFileDialogWithProperties(SDLDialog.SDL_FILEDIALOG_OPENFILE, Kept.CALLBACK, question, properties);
		} finally {
			SDLProperties.SDL_DestroyProperties(properties);
		}
	}

	/**
	 * SDL's answer: {@code files} is null when SDL could not show the dialog, an empty list when it was dismissed, else
	 * the chosen file first. Called from native code: nothing may be thrown from here.
	 */
	private static void answered(long question, long files, int filter) {
		CompletableFuture<Optional<Path>> answer = WAITING.remove(question);
		if (answer == null) {
			return;
		}
		try {
			if (files == MemoryUtil.NULL) {
				answer.completeExceptionally(new IllegalStateException("SDL could not show a file dialog: " + SDLError.SDL_GetError()));
				return;
			}
			long first = MemoryUtil.memGetAddress(files);
			answer.complete(first == MemoryUtil.NULL ? Optional.empty() : Optional.of(Paths.get(MemoryUtil.memUTF8(first))));
		} catch (RuntimeException e) {
			answer.completeExceptionally(e);
		}
	}

	/**
	 * What SDL holds on to: the callback every dialog answers through, and the filter list. Made on first use (inside
	 * {@link #open}'s try, so a failure goes back through the future) and kept, since nothing says when SDL is done.
	 */
	private static final class Kept {
		static final SDL_DialogFileCallback CALLBACK = SDL_DialogFileCallback.create(FileDialog::answered);
		private static final ByteBuffer LABEL = MemoryUtil.memUTF8(Component.translatable("aetherium.shaders.screen.import_filter").getString());
		private static final ByteBuffer PATTERN = MemoryUtil.memUTF8("txt");
		static final SDL_DialogFileFilter.Buffer FILTERS = SDL_DialogFileFilter.calloc(1);

		static {
			FILTERS.get(0).set(LABEL, PATTERN);
		}

		private Kept() {
		}
	}
}
