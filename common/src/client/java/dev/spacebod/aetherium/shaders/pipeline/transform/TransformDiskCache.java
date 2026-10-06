package dev.spacebod.aetherium.shaders.pipeline.transform;

import com.mojang.renderpearl.api.pipeline.UniformType;
import dev.spacebod.aetherium.core.Optimisations;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.engine.UniformBlock;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.LoweringParameters;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.VulkanLowering;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.Parameters;
import dev.spacebod.aetherium.shaders.platform.ShaderPlatform;
import io.github.douira.glsl_transformer.ast.transform.EnumASTTransformer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;
import org.jspecify.annotations.Nullable;

/**
 * {@link TransformPatcher}'s results on disk ({@code <gameDir>/aetherium/shader-cache/transform/}), so a later load of the
 * same programs skips the transform. Switch: {@code shaders.transform_cache}.
 * <p>
 * An entry is found by a SHA-256 key over everything the result depends on: the preprocessed source of each stage, the
 * transform parameters and the lowering parameters (encoded field by field, see {@link #encode}), the debug-options
 * switch, the session's optimisation switches, and a build identity: a hash of the code the transform runs (this mod's
 * classes and the parser library's), so any code change invalidates every entry. The entry holds the transform's output
 * before NaN preservation (applied per request, as for the in-memory cache).
 * <p>
 * File layout: magic, format, the key, payload length, the payload's SHA-256, then the deflated payload. An entry that
 * does not read back exactly (wrong key, hash or structure) is deleted and treated as a miss. Writes go to a temporary
 * file moved into place atomically. The folder is kept under {@link #CAP_BYTES}; the least recently used entries go
 * first (a hit refreshes the entry's modification time).
 */
public final class TransformDiskCache {
	public static final String SWITCH = "shaders.transform_cache";
	private static final long CAP_BYTES = 256L << 20;
	/** Trimming stops below this share of the cap, so a full cache is not trimmed on every write. */
	private static final long TRIM_TO_BYTES = CAP_BYTES * 3 / 4;
	private static final int MAGIC = 0x41455443;
	/** Bumped when the file layout or the key encoding changes. */
	private static final int FORMAT = 2;
	private static final String SUFFIX = ".bin";

	/** Set when a caller chose the folder (or turned the cache off) instead of the switch and the game folder. */
	private static volatile boolean overridden;
	private static volatile @Nullable Path override;
	private static volatile @Nullable Path resolved;
	private static final Object INIT_LOCK = new Object();
	private static final AtomicLong size = new AtomicLong(-1);
	private static final AtomicBoolean trimming = new AtomicBoolean();
	private static volatile byte @Nullable [] identity;
	private static volatile boolean identityFailed;
	private static final AtomicBoolean warned = new AtomicBoolean();

	private TransformDiskCache() {
	}

	/** Uses {@code dir} for the cache regardless of the switch ({@code null}: no disk cache), for tools without the game. */
	public static void useDirectory(@Nullable Path dir) {
		synchronized (INIT_LOCK) {
			overridden = true;
			override = dir;
			resolved = null;
			size.set(-1);
		}
	}

	/** The cache folder, or null when the cache is off (switch, no game folder, or no build identity). */
	static @Nullable Path directory() {
		if (!overridden && !Optimisations.isEnabled(SWITCH)) {
			return null;
		}
		Path dir = resolved;
		if (dir != null) {
			return dir;
		}
		dir = overridden ? override : defaultDirectory();
		if (dir == null || buildIdentity() == null) {
			return null;
		}
		synchronized (INIT_LOCK) {
			if (resolved == null) {
				try {
					Files.createDirectories(dir);
				} catch (IOException e) {
					warn("cannot create " + dir + ": " + e);
					return null;
				}
				resolved = dir;
				size.set(scan(dir));
				trimIfNeeded(dir);
			}
			return resolved;
		}
	}

	private static @Nullable Path defaultDirectory() {
		try {
			return ShaderPlatform.getInstance().getGameDir().resolve("aetherium").resolve("shader-cache").resolve("transform");
		} catch (RuntimeException e) {
			warn("no game folder: " + e);
			return null;
		}
	}

	/** Deletes every entry (the settings screen's tool); returns the bytes freed. */
	public static long clear() {
		Path dir = overridden ? override : defaultDirectory();
		if (dir == null || !Files.isDirectory(dir)) {
			return 0;
		}
		long freed = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
			for (Path file : files) {
				try {
					long bytes = Files.size(file);
					Files.deleteIfExists(file);
					freed += bytes;
				} catch (IOException e) {
					// in use or gone: left for the next trim
				}
			}
		} catch (IOException e) {
			warn("clearing " + dir + " failed: " + e);
		}
		size.set(dir.equals(resolved) ? scan(dir) : -1);
		AetheriumShaders.logger.info("Shader transform cache cleared ({} KiB)", freed >> 10);
		return freed;
	}

	// ------------------------------------------------------------------ keys

	/**
	 * The key of one transform, or null when the cache is off or a parameter cannot be encoded (that transform is then
	 * not cached).
	 */
	static byte @Nullable [] key(Parameters parameters, LoweringParameters lowering, Map<PatchShaderType, String> sources, boolean debugOptions) {
		if (directory() == null) {
			return null;
		}
		byte[] id = buildIdentity();
		if (id == null) {
			return null;
		}
		try {
			MessageDigest md = sha256();
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeInt(FORMAT);
			out.write(id);
			out.writeBoolean(debugOptions);
			Map<String, Boolean> switches = new LinkedHashMap<>(Optimisations.snapshot());
			switches.remove(SWITCH);
			encode(out, switches, 0);
			encode(out, parameters, 0);
			encode(out, lowering, 0);
			out.flush();
			md.update(bytes.toByteArray());
			for (PatchShaderType type : PatchShaderType.values()) {
				String source = sources.get(type);
				md.update((byte) (source == null ? 0 : 1));
				if (source != null) {
					byte[] utf8 = utf8(source);
					md.update(ByteBuffer.allocate(4).putInt(utf8.length).array());
					md.update(utf8);
				}
			}
			return md.digest();
		} catch (IOException | ReflectiveOperationException | RuntimeException e) {
			AetheriumShaders.logger.debug("[{}] transform not cached on disk: {}", parameters.name, e.toString());
			return null;
		}
	}

	/** Parameter fields that are per-transform scratch state, not inputs. */
	private static final Set<String> PARAMETER_SCRATCH = Set.of("type", "name", "lowering", "lowered");

	/**
	 * Writes {@code value} so that two values encode to the same bytes exactly when they are equal as transform inputs:
	 * maps and sets in a sorted order (their iteration order differs between runs), records by component, and this mod's
	 * other classes by every instance field (sorted by declaring class depth and name). Anything else throws.
	 */
	static void encode(DataOutputStream out, @Nullable Object value, int depth) throws IOException, ReflectiveOperationException {
		if (depth > 24) {
			throw new IllegalArgumentException("value nested too deeply");
		}
		switch (value) {
			case null -> out.writeByte(0);
			case String s -> {
				out.writeByte(1);
				writeString(out, s);
			}
			case Boolean b -> {
				out.writeByte(2);
				out.writeBoolean(b);
			}
			case Integer i -> {
				out.writeByte(3);
				out.writeInt(i);
			}
			case Long l -> {
				out.writeByte(4);
				out.writeLong(l);
			}
			case Float f -> {
				out.writeByte(5);
				out.writeInt(Float.floatToRawIntBits(f));
			}
			case Double d -> {
				out.writeByte(6);
				out.writeLong(Double.doubleToRawLongBits(d));
			}
			case Character c -> {
				out.writeByte(7);
				out.writeChar(c);
			}
			case Enum<?> e -> {
				out.writeByte(8);
				writeString(out, e.getDeclaringClass().getName());
				writeString(out, e.name());
			}
			case Map<?, ?> map -> {
				out.writeByte(9);
				List<byte[][]> entries = new ArrayList<>(map.size());
				for (Map.Entry<?, ?> e : map.entrySet()) {
					entries.add(new byte[][]{bytes(e.getKey(), depth + 1), bytes(e.getValue(), depth + 1)});
				}
				entries.sort((a, b) -> Arrays.compareUnsigned(a[0], b[0]));
				out.writeInt(entries.size());
				for (byte[][] e : entries) {
					out.write(e[0]);
					out.write(e[1]);
				}
			}
			case Set<?> set -> {
				out.writeByte(10);
				List<byte[]> members = new ArrayList<>(set.size());
				for (Object o : set) {
					members.add(bytes(o, depth + 1));
				}
				members.sort(Arrays::compareUnsigned);
				out.writeInt(members.size());
				for (byte[] m : members) {
					out.write(m);
				}
			}
			case List<?> list -> {
				out.writeByte(11);
				out.writeInt(list.size());
				for (Object o : list) {
					encode(out, o, depth + 1);
				}
			}
			case Record record -> {
				out.writeByte(12);
				writeString(out, record.getClass().getName());
				for (RecordComponent component : record.getClass().getRecordComponents()) {
					var accessor = component.getAccessor();
					accessor.setAccessible(true);
					encode(out, accessor.invoke(record), depth + 1);
				}
			}
			default -> {
				Class<?> type = value.getClass();
				if (!type.getName().startsWith("dev.spacebod.aetherium.")) {
					throw new IllegalArgumentException("cannot encode " + type.getName());
				}
				out.writeByte(13);
				writeString(out, type.getName());
				List<Class<?>> hierarchy = new ArrayList<>();
				for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
					hierarchy.addFirst(c);
				}
				for (Class<?> c : hierarchy) {
					Field[] fields = c.getDeclaredFields();
					Arrays.sort(fields, Comparator.comparing(Field::getName));
					for (Field field : fields) {
						int modifiers = field.getModifiers();
						if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()
								|| c == Parameters.class && PARAMETER_SCRATCH.contains(field.getName())) {
							continue;
						}
						field.setAccessible(true);
						writeString(out, field.getName());
						encode(out, field.get(value), depth + 1); // primitives arrive boxed
					}
				}
			}
		}
	}

	private static byte[] bytes(@Nullable Object value, int depth) throws IOException, ReflectiveOperationException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
		DataOutputStream out = new DataOutputStream(bytes);
		encode(out, value, depth);
		out.flush();
		return bytes.toByteArray();
	}

	// ------------------------------------------------------------------ build identity

	/** Classes whose code (and their whole code source: the mod's classes, the parser library) the transform's output depends on. */
	private static final List<Class<?>> CODE_ANCHORS = List.of(TransformPatcher.class, Optimisations.class, EnumASTTransformer.class,
			org.antlr.v4.runtime.Token.class);

	/** A hash of the code that produces transforms, computed once; null when it cannot be read (the disk cache is then off). */
	static byte @Nullable [] buildIdentity() {
		byte[] id = identity;
		if (id != null || identityFailed) {
			return id;
		}
		synchronized (TransformDiskCache.class) {
			if (identity == null && !identityFailed) {
				long started = System.nanoTime();
				try {
					identity = computeIdentity();
					AetheriumShaders.logger.debug("Shader transform cache build identity {} ({} ms)", HexFormat.of().formatHex(identity, 0, 8),
							(System.nanoTime() - started) / 1_000_000);
				} catch (IOException | RuntimeException e) {
					identityFailed = true;
					warn("cannot hash the transform's code: " + e);
				}
			}
			return identity;
		}
	}

	private static byte[] computeIdentity() throws IOException {
		MessageDigest md = sha256();
		md.update(("transform cache " + FORMAT).getBytes(StandardCharsets.UTF_8));
		List<Path> seen = new ArrayList<>();
		for (Class<?> anchor : CODE_ANCHORS) {
			Path source = codeSource(anchor);
			if (source == null) {
				if (anchor == TransformPatcher.class) {
					throw new IOException("no readable code source for " + anchor.getName());
				}
				// A library nested in the mod's own jar: covered by that jar's hash; its class bytes as well, to be sure.
				md.update(classBytes(anchor));
				continue;
			}
			if (seen.contains(source)) {
				continue;
			}
			seen.add(source);
			hashTree(md, source);
		}
		return md.digest();
	}

	private static @Nullable Path codeSource(Class<?> type) {
		try {
			var domain = type.getProtectionDomain();
			var code = domain == null ? null : domain.getCodeSource();
			URL location = code == null ? null : code.getLocation();
			if (location == null) {
				return null;
			}
			Path path = Path.of(location.toURI());
			return Files.exists(path) ? path : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static byte[] classBytes(Class<?> type) throws IOException {
		String resource = type.getName().replace('.', '/') + ".class";
		try (InputStream in = type.getClassLoader() == null ? ClassLoader.getSystemResourceAsStream(resource)
				: type.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("no class bytes for " + type.getName());
			}
			return in.readAllBytes();
		}
	}

	private static void hashTree(MessageDigest md, Path root) throws IOException {
		if (!Files.isDirectory(root)) {
			hashFile(md, root);
			return;
		}
		List<Path> files;
		try (Stream<Path> walk = Files.walk(root)) {
			files = new ArrayList<>(walk.filter(Files::isRegularFile).toList());
		}
		files.sort(Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/')));
		for (Path file : files) {
			md.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
			md.update((byte) 0);
			hashFile(md, file);
		}
	}

	private static void hashFile(MessageDigest md, Path file) throws IOException {
		byte[] buffer = new byte[1 << 16];
		long length = 0;
		try (InputStream in = Files.newInputStream(file)) {
			int n;
			while ((n = in.read(buffer)) > 0) {
				md.update(buffer, 0, n);
				length += n;
			}
		}
		md.update(ByteBuffer.allocate(8).putLong(length).array());
	}

	// ------------------------------------------------------------------ entries

	/** The entry under {@code key}, or null (absent, unreadable or not exactly what was written: then deleted). */
	static TransformPatcher.@Nullable Transformed read(byte[] key) {
		Path dir = directory();
		if (dir == null) {
			return null;
		}
		Path file = dir.resolve(HexFormat.of().formatHex(key) + SUFFIX);
		byte[] data;
		try {
			data = Files.readAllBytes(file);
		} catch (IOException e) {
			return null; // absent (or being replaced)
		}
		try {
			TransformPatcher.Transformed entry = decode(key, data);
			try {
				Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
			} catch (IOException e) {
				// recency only matters for trimming
			}
			return entry;
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.debug("Shader transform cache entry {} unreadable ({}): rewritten", file.getFileName(), e.toString());
			try {
				if (Files.deleteIfExists(file)) {
					size.addAndGet(-data.length);
				}
			} catch (IOException ignored) {
				// replaced by the write that follows the transform
			}
			return null;
		}
	}

	/** Stores {@code entry} under {@code key}; failures only cost the next load the transform. */
	static void write(byte[] key, TransformPatcher.Transformed entry) {
		Path dir = directory();
		if (dir == null) {
			return;
		}
		byte[] data;
		try {
			data = encodeEntry(key, entry);
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.debug("Shader transform cache: entry not written: {}", e.toString());
			return;
		}
		String name = HexFormat.of().formatHex(key);
		Path file = dir.resolve(name + SUFFIX);
		Path temp = dir.resolve(name + "." + Thread.currentThread().threadId() + ".tmp");
		try {
			Files.write(temp, data);
			try {
				Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			size.addAndGet(data.length);
			trimIfNeeded(dir);
		} catch (IOException e) {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException ignored) {
				// left for the next scan
			}
			AetheriumShaders.logger.debug("Shader transform cache: {} not written: {}", file.getFileName(), e.toString());
		}
	}

	private static byte[] encodeEntry(byte[] key, TransformPatcher.Transformed entry) throws IOException {
		ByteArrayOutputStream raw = new ByteArrayOutputStream(1 << 16);
		try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(raw, new Deflater(Deflater.BEST_SPEED), 1 << 16))) {
			Map<PatchShaderType, String> stages = entry.stages();
			out.writeInt(stages.size());
			for (Map.Entry<PatchShaderType, String> e : stages.entrySet()) {
				writeString(out, e.getKey().name());
				writeNullable(out, e.getValue());
			}
			VulkanLowering.Result r = entry.lowered();
			out.writeInt(r.layout().members().size());
			for (UniformBlock.Member m : r.layout().members()) {
				writeString(out, m.type());
				writeString(out, m.name());
				out.writeInt(m.arrayLength());
				out.writeInt(m.offset());
				writeNullable(out, m.initializer());
			}
			out.writeInt(r.layout().size());
			out.writeInt(r.resources().size());
			for (Map.Entry<String, UniformType> e : r.resources().entrySet()) {
				writeString(out, e.getKey());
				writeString(out, e.getValue().name());
			}
			out.writeInt(r.samplerTypes().size());
			for (Map.Entry<String, String> e : r.samplerTypes().entrySet()) {
				writeString(out, e.getKey());
				writeString(out, e.getValue());
			}
			out.writeInt(r.notes().size());
			for (String note : r.notes()) {
				writeString(out, note);
			}
			out.writeBoolean(r.testsNaN());
			out.writeBoolean(r.geometryFolded());
		}
		byte[] payload = raw.toByteArray();
		ByteArrayOutputStream file = new ByteArrayOutputStream(payload.length + 80);
		DataOutputStream out = new DataOutputStream(file);
		out.writeInt(MAGIC);
		out.writeInt(FORMAT);
		out.write(key);
		out.writeInt(payload.length);
		out.write(sha256().digest(payload));
		out.write(payload);
		out.flush();
		return file.toByteArray();
	}

	private static TransformPatcher.Transformed decode(byte[] key, byte[] data) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		if (in.readInt() != MAGIC || in.readInt() != FORMAT) {
			throw new IOException("not an entry of this format");
		}
		byte[] storedKey = in.readNBytes(key.length);
		if (!Arrays.equals(storedKey, key)) {
			throw new IOException("key mismatch");
		}
		int length = in.readInt();
		byte[] hash = in.readNBytes(32);
		int offset = 4 + 4 + key.length + 4 + 32;
		if (length < 0 || offset + length != data.length) {
			throw new IOException("truncated");
		}
		MessageDigest md = sha256();
		md.update(data, offset, length);
		if (!MessageDigest.isEqual(md.digest(), hash)) {
			throw new IOException("payload hash mismatch");
		}
		try (DataInputStream p = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data, offset, length), new java.util.zip.Inflater(), 1 << 16))) {
			Map<PatchShaderType, String> stages = new EnumMap<>(PatchShaderType.class);
			for (int i = count(p); i > 0; i--) {
				stages.put(PatchShaderType.valueOf(readString(p)), readNullable(p));
			}
			List<UniformBlock.Member> members = new ArrayList<>();
			for (int i = count(p); i > 0; i--) {
				members.add(new UniformBlock.Member(readString(p), readString(p), p.readInt(), p.readInt(), readNullable(p)));
			}
			UniformBlock.Layout layout = new UniformBlock.Layout(members, p.readInt());
			Map<String, UniformType> resources = new LinkedHashMap<>();
			for (int i = count(p); i > 0; i--) {
				resources.put(readString(p), UniformType.valueOf(readString(p)));
			}
			Map<String, String> samplerTypes = new LinkedHashMap<>();
			for (int i = count(p); i > 0; i--) {
				samplerTypes.put(readString(p), readString(p));
			}
			List<String> notes = new ArrayList<>();
			for (int i = count(p); i > 0; i--) {
				notes.add(readString(p));
			}
			boolean testsNaN = p.readBoolean();
			boolean geometryFolded = p.readBoolean();
			if (p.read() != -1) {
				throw new IOException("trailing data");
			}
			return new TransformPatcher.Transformed(stages, new VulkanLowering.Result(layout, resources, samplerTypes, notes, testsNaN, geometryFolded));
		}
	}

	private static int count(DataInputStream in) throws IOException {
		int n = in.readInt();
		if (n < 0 || n > 1 << 20) {
			throw new IOException("bad count " + n);
		}
		return n;
	}

	// ------------------------------------------------------------------ size

	private static long scan(Path dir) {
		long total = 0;
		long staleTemp = System.currentTimeMillis() - 3_600_000L;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
			for (Path file : files) {
				try {
					BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
					if (file.getFileName().toString().endsWith(".tmp") && attributes.lastModifiedTime().toMillis() < staleTemp) {
						Files.deleteIfExists(file);
					} else if (attributes.isRegularFile()) {
						total += attributes.size();
					}
				} catch (IOException e) {
					// gone meanwhile
				}
			}
		} catch (IOException e) {
			return 0;
		}
		return total;
	}

	private static void trimIfNeeded(Path dir) {
		if (size.get() <= CAP_BYTES || !trimming.compareAndSet(false, true)) {
			return;
		}
		try {
			record Item(Path file, long size, long modified) {
			}
			List<Item> items = new ArrayList<>();
			try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
				for (Path file : files) {
					try {
						BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
						items.add(new Item(file, a.size(), a.lastModifiedTime().toMillis()));
					} catch (IOException e) {
						// gone meanwhile
					}
				}
			}
			long total = items.stream().mapToLong(Item::size).sum();
			items.sort(Comparator.comparingLong(Item::modified));
			int removed = 0;
			for (Item item : items) {
				if (total <= TRIM_TO_BYTES) {
					break;
				}
				try {
					Files.deleteIfExists(item.file());
					total -= item.size();
					removed++;
				} catch (IOException e) {
					// in use: tried again on the next trim
				}
			}
			size.set(total);
			AetheriumShaders.logger.debug("Shader transform cache trimmed: {} entries removed, {} MiB kept", removed, total >> 20);
		} catch (IOException e) {
			AetheriumShaders.logger.debug("Shader transform cache trim failed: {}", e.toString());
		} finally {
			trimming.set(false);
		}
	}

	// ------------------------------------------------------------------ helpers

	private static void writeString(DataOutputStream out, String s) throws IOException {
		byte[] utf8 = utf8(s);
		out.writeInt(utf8.length);
		out.write(utf8);
	}

	private static void writeNullable(DataOutputStream out, @Nullable String s) throws IOException {
		out.writeBoolean(s != null);
		if (s != null) {
			writeString(out, s);
		}
	}

	private static String readString(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0 || length > 1 << 28) {
			throw new IOException("bad string length " + length);
		}
		byte[] utf8 = in.readNBytes(length);
		if (utf8.length != length) {
			throw new IOException("truncated string");
		}
		return new String(utf8, StandardCharsets.UTF_8);
	}

	private static @Nullable String readNullable(DataInputStream in) throws IOException {
		return in.readBoolean() ? readString(in) : null;
	}

	/** UTF-8 of {@code s}; throws when it would not read back as the same string (unpaired surrogates). */
	private static byte[] utf8(String s) {
		byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
		for (int i = 0, n = s.length(); i < n; i++) {
			if (Character.isSurrogate(s.charAt(i))) {
				if (!new String(utf8, StandardCharsets.UTF_8).equals(s)) {
					throw new IllegalArgumentException("text with unpaired surrogates is not cached");
				}
				break;
			}
		}
		return utf8;
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void warn(String message) {
		if (warned.compareAndSet(false, true)) {
			AetheriumShaders.logger.warn("Shader transform cache off: {}", message);
		}
	}
}
