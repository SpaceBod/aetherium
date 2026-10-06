package dev.spacebod.aetherium.shaders.shaderpack;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.platform.ShaderPlatform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reading files out of a pack: paths a pack names are confined to the pack, sizes are capped, and text decodes
 * leniently (a stray non-UTF-8 byte or a byte-order mark never refuses a pack).
 */
public final class PackFiles {
	/** Largest source or properties file read from a pack. */
	public static final long MAX_TEXT_BYTES = 8L << 20;
	/** Largest texture or storage-buffer file read from a pack. */
	public static final long MAX_DATA_BYTES = 256L << 20;

	private PackFiles() {
	}

	/**
	 * {@code relative} (a path written in the pack, an optional leading slash allowed) resolved against {@code root},
	 * the pack's {@code shaders} directory. Empty, with an error logged, when the result would leave the pack (the
	 * folder or zip that holds {@code root}).
	 */
	public static Optional<Path> resolve(Path root, String relative) {
		String path = relative;
		while (path.startsWith("/") || path.startsWith("\\")) {
			path = path.substring(1);
		}
		Path base = root.toAbsolutePath().normalize();
		Path pack = base.getParent() != null ? base.getParent() : base;
		Path resolved;
		try {
			resolved = base.resolve(path).toAbsolutePath().normalize();
		} catch (RuntimeException e) {
			AetheriumShaders.logger.error("ignoring the pack path \"{}\": {}", relative, e.getMessage());
			return Optional.empty();
		}
		if (!resolved.startsWith(pack)) {
			AetheriumShaders.logger.error("ignoring the pack path \"{}\": it points outside the pack", relative);
			return Optional.empty();
		}
		return Optional.of(resolved);
	}

	/** The whole file, refused with an {@link IOException} when it is larger than {@code limit} bytes. */
	public static byte[] readBytes(Path file, long limit) throws IOException {
		long size = Files.size(file);
		if (size > limit) {
			throw new IOException(file + " is " + size + " bytes, more than the " + limit + " a pack file may have");
		}
		try (InputStream in = Files.newInputStream(file)) {
			byte[] data = in.readNBytes((int) Math.min(limit, Integer.MAX_VALUE - 8) + 1);
			if (data.length > limit) {
				throw new IOException(file + " is larger than the " + limit + " bytes a pack file may have");
			}
			return data;
		}
	}

	/**
	 * A text file as UTF-8, malformed bytes replaced, a leading byte-order mark removed. Line endings are left as they
	 * are (callers split on any of CR, LF and CRLF).
	 */
	public static String readText(Path file) throws IOException {
		return decode(readBytes(file, MAX_TEXT_BYTES));
	}

	/** {@link #readText}, decoded as ISO-8859-1 (properties files); a UTF-8 byte-order mark is still removed. */
	public static String readLatin1(Path file) throws IOException {
		byte[] data = readBytes(file, MAX_TEXT_BYTES);
		int start = hasUtf8Bom(data) ? 3 : 0;
		return new String(data, start, data.length - start, StandardCharsets.ISO_8859_1);
	}

	private static String decode(byte[] data) {
		int start = hasUtf8Bom(data) ? 3 : 0;
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE)
				.decode(ByteBuffer.wrap(data, start, data.length - start))
				.toString();
		} catch (CharacterCodingException e) {
			// Unreachable with REPLACE; decode the plain way rather than fail.
			return new String(data, start, data.length - start, StandardCharsets.UTF_8);
		}
	}

	private static boolean hasUtf8Bom(byte[] data) {
		return data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF;
	}

	/**
	 * Debug output: writes {@code content} to {@code <game dir>/shader-dump/<name>}. Failures are logged, never thrown.
	 */
	public static void dump(String name, String content) {
		Path file = ShaderPlatform.getInstance().getGameDir().resolve("shader-dump").resolve(name);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.warn("could not write the debug dump {}: {}", file, e.toString());
		}
	}
}
