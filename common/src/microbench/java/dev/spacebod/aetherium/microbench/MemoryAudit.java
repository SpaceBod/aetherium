package dev.spacebod.aetherium.microbench;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * How much block-state memory is still reclaimable on vanilla 26.3, measured on the real block
 * state table (bootstrapped in code, every state's cache initialised as in game). Sizes are shallow
 * object sizes under compressed oops (12-byte header, 4-byte references, 8-byte alignment), summed
 * over identity-distinct objects: an estimate of retained heap, good to a few percent.
 *
 * <p>Run with {@code gradlew :common:microbench -Ponly=memory}.
 */
final class MemoryAudit {
	private MemoryAudit() {
	}

	private static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	static void run() {
		bootstrap();
		List<BlockState> states = new ArrayList<>();
		for (BlockState s : Block.BLOCK_STATE_REGISTRY) {
			states.add(s);
		}
		System.out.printf(Locale.ROOT, "%nmemory audit: %,d block states, %,d blocks%n", states.size(),
				states.stream().map(BlockState::getBlock).distinct().count());

		neighbours(states);
		stateCache(states);
		occlusionFaces(states);
		paletteDetector();
	}

	// ------------------------------------------------------------------ palette threading detector

	private static void paletteDetector() {
		long detector = deepSize(List.of(new net.minecraft.util.ThreadingDetector("PalettedContainer"))) - deepSize(List.of("PalettedContainer"));
		// RD16: 33 x 33 columns x 24 sections, two containers each (blocks, biomes), on the client and again on the integrated server.
		long containers = 33L * 33 * 24 * 2 * 2;
		System.out.printf(Locale.ROOT, "  palette ThreadingDetector: %d bytes each (5 objects); RD16 singleplayer ~%,d containers -> %s, re-allocated on every chunk load%n",
				detector, containers, mb(detector * containers));
	}

	// ------------------------------------------------------------------ mem.state_cache_dedup parity + heap

	/**
	 * Applies {@link dev.spacebod.aetherium.memory.StateCacheDedup} to every state as its mixins would (by
	 * reflection here: no mixins in this JVM), checks that every shape answers exactly as before, and
	 * measures the heap it frees after full GCs.
	 */
	static void dedupParity() {
		bootstrap();
		List<BlockState> states = new ArrayList<>();
		for (BlockState s : Block.BLOCK_STATE_REGISTRY) {
			states.add(s);
		}
		Field cacheField = field(BlockBehaviour.BlockStateBase.class, "cache");
		Field collision = field(cacheField.getType(), "collisionShape");
		Field sturdy = field(cacheField.getType(), "faceSturdy");
		Field faces = field(BlockBehaviour.BlockStateBase.class, "occlusionShapesByFace");
		Field shapeField = field(VoxelShape.class, "shape");
		Field[] coords = {field(net.minecraft.world.phys.shapes.ArrayVoxelShape.class, "xs"),
				field(net.minecraft.world.phys.shapes.ArrayVoxelShape.class, "ys"),
				field(net.minecraft.world.phys.shapes.ArrayVoxelShape.class, "zs")};
		dev.spacebod.aetherium.memory.StateCacheDedup.adopter = (duplicate, canonical) -> {
			if (duplicate instanceof net.minecraft.world.phys.shapes.ArrayVoxelShape) {
				for (Field f : coords) {
					set(f, duplicate, get(f, canonical));
				}
			}
			set(shapeField, duplicate, get(shapeField, canonical));
		};

		// Fingerprint every shape (collision and occlusion faces) before: boxes, coordinates, and collisions of probe boxes.
		Map<VoxelShape, String> before = new IdentityHashMap<>();
		for (BlockState s : states) {
			Object cache = get(cacheField, s);
			if (cache != null) {
				before.computeIfAbsent((VoxelShape) get(collision, cache), MemoryAudit::fingerprint);
			}
			for (VoxelShape f : (VoxelShape[]) get(faces, s)) {
				before.computeIfAbsent(f, MemoryAudit::fingerprint);
			}
		}
		Map<BlockState, String> collisionBefore = new IdentityHashMap<>();
		Map<BlockState, boolean[]> sturdyBefore = new IdentityHashMap<>();
		Map<BlockState, List<VoxelShape>> facesBefore = new IdentityHashMap<>();
		for (BlockState s : states) {
			Object cache = get(cacheField, s);
			if (cache != null) {
				sturdyBefore.put(s, ((boolean[]) get(sturdy, cache)).clone());
				collisionBefore.put(s, before.get((VoxelShape) get(collision, cache)));
			}
			facesBefore.put(s, List.of((VoxelShape[]) get(faces, s)));
		}

		long heapBefore = usedHeapAfterGc();
		for (BlockState s : states) {
			Object cache = get(cacheField, s);
			if (cache != null) {
				set(collision, cache, dev.spacebod.aetherium.memory.StateCacheDedup.shape((VoxelShape) get(collision, cache)));
				set(sturdy, cache, dev.spacebod.aetherium.memory.StateCacheDedup.faceSturdy((boolean[]) get(sturdy, cache)));
			}
			set(faces, s, dev.spacebod.aetherium.memory.StateCacheDedup.occlusionFaces((VoxelShape[]) get(faces, s)));
		}
		long heapAfter = usedHeapAfterGc();

		int checked = 0;
		for (Map.Entry<VoxelShape, String> e : before.entrySet()) {
			if (!fingerprint(e.getKey()).equals(e.getValue())) {
				throw new AssertionError("shape answers differently after dedup: " + e.getValue());
			}
			checked++;
		}
		for (BlockState s : states) {
			Object cache = get(cacheField, s);
			if (cache != null) {
				if (!Arrays.equals(sturdyBefore.get(s), (boolean[]) get(sturdy, cache))) {
					throw new AssertionError(s + ": faceSturdy changed");
				}
				if (!fingerprint((VoxelShape) get(collision, cache)).equals(collisionBefore.get(s))) {
					throw new AssertionError(s + ": collision shape answers differently");
				}
			}
			if (!facesBefore.get(s).equals(List.of((VoxelShape[]) get(faces, s)))) {
				throw new AssertionError(s + ": occlusion faces changed");
			}
		}
		System.out.printf(Locale.ROOT, "mem.state_cache_dedup:          OK, %,d shapes answer identically; heap freed %s (full-GC measurement)%n",
				checked, mb(heapBefore - heapAfter));
	}

	/** Everything a shape answers: its boxes, per-axis coordinates, bounds, and sweep distances of probe boxes along each axis. */
	private static String fingerprint(VoxelShape shape) {
		StringBuilder b = new StringBuilder(shape.getClass().getSimpleName()).append(shape.toAabbs());
		for (Direction.Axis axis : Direction.Axis.values()) {
			b.append(shape.getCoords(axis)).append(shape.isEmpty() ? "" : shape.min(axis) + "/" + shape.max(axis));
			for (double o : new double[]{-0.75, -0.3, 0.1, 0.45, 0.9}) {
				net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(o, o, o, o + 0.4, o + 0.6, o + 0.5);
				b.append(',').append(shape.collide(axis, box.move(axis == Direction.Axis.X ? -2 : 0, axis == Direction.Axis.Y ? -2 : 0, axis == Direction.Axis.Z ? -2 : 0), 3.0));
			}
		}
		return b.toString();
	}

	private static long usedHeapAfterGc() {
		java.lang.management.MemoryMXBean memory = java.lang.management.ManagementFactory.getMemoryMXBean();
		for (int i = 0; i < 4; i++) {
			System.gc();
		}
		return memory.getHeapMemoryUsage().getUsed();
	}

	static void set(Field f, Object o, Object v) {
		try {
			f.set(o, v);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	// ------------------------------------------------------------------ neighbour tables / property values

	private static void neighbours(List<BlockState> states) {
		Field neighbors = field(StateHolder.class, "neighbors");
		Field values = field(StateHolder.class, "propertyValues");
		Set<Object> outer = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Object> rows = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Object> valueArrays = Collections.newSetFromMap(new IdentityHashMap<>());
		long outerBytes = 0, rowBytes = 0, valueBytes = 0;
		for (BlockState s : states) {
			Object[] n = (Object[]) get(neighbors, s);
			if (outer.add(n)) {
				outerBytes += arrayBytes(n.length, 4);
				for (Object row : n) {
					if (rows.add(row)) {
						rowBytes += arrayBytes(Array.getLength(row), 4);
					}
				}
			}
			Object[] v = (Object[]) get(values, s);
			if (valueArrays.add(v)) {
				valueBytes += arrayBytes(v.length, 4);
			}
		}
		// A packed-index state map costs one int per state (padding usually absorbs it) plus per block a key
		// table and a value list of states x properties references; bounded above by one reference per state.
		long fastMapBytes = states.size() * 4L;
		System.out.printf(Locale.ROOT, "  neighbour tables: %,d outer arrays (%s), %,d shared rows (%s)%n",
				outer.size(), mb(outerBytes), rows.size(), mb(rowBytes));
		System.out.printf(Locale.ROOT, "  property value arrays: %,d (%s)%n", valueArrays.size(), mb(valueBytes));
		System.out.printf(Locale.ROOT, "  -> packed state map + shared property values could save at most ~%s"
				+ " (vanilla 26.3 already shares rows by pivot)%n",mb(outerBytes + rowBytes + valueBytes - fastMapBytes));
	}

	// ------------------------------------------------------------------ state cache dedup

	private static void stateCache(List<BlockState> states) {
		Field cacheField = field(BlockBehaviour.BlockStateBase.class, "cache");
		Class<?> cacheClass = cacheField.getType();
		Field collision = field(cacheClass, "collisionShape");
		Field sturdy = field(cacheClass, "faceSturdy");
		Set<Object> caches = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Object> shapes = Collections.newSetFromMap(new IdentityHashMap<>());
		Map<String, Object> canonicalShapes = new HashMap<>();
		Set<Object> sturdyArrays = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<String> sturdyDistinct = new java.util.HashSet<>();
		long sturdyBytes = 0;
		for (BlockState s : states) {
			Object cache = get(cacheField, s);
			if (cache == null) {
				continue;
			}
			caches.add(cache);
			VoxelShape shape = (VoxelShape) get(collision, cache);
			shapes.add(shape);
			canonicalShapes.putIfAbsent(shapeKey(shape), shape);
			boolean[] b = (boolean[]) get(sturdy, cache);
			if (sturdyArrays.add(b)) {
				sturdyBytes += arrayBytes(b.length, 1);
			}
			sturdyDistinct.add(Arrays.toString(b));
		}
		long allShapes = deepSize(new ArrayList<>(shapes));
		long canonical = deepSize(new ArrayList<>(canonicalShapes.values()));
		long sturdyAfter = (long) sturdyDistinct.size() * arrayBytes(Direction.values().length * 3, 1);
		System.out.printf(Locale.ROOT, "  state caches: %,d; collision shapes: %,d identity-distinct (%s deep), %,d structurally distinct (%s)%n",
				caches.size(), shapes.size(), mb(allShapes), canonicalShapes.size(), mb(canonical));
		System.out.printf(Locale.ROOT, "  faceSturdy arrays: %,d (%s), %d distinct contents%n", sturdyArrays.size(), mb(sturdyBytes), sturdyDistinct.size());
		System.out.printf(Locale.ROOT, "  -> state cache dedup saves ~%s (shapes %s + faceSturdy %s)%n",
				mb(allShapes - canonical + sturdyBytes - sturdyAfter), mb(allShapes - canonical), mb(sturdyBytes - sturdyAfter));
	}

	private static void occlusionFaces(List<BlockState> states) {
		Field faces = field(BlockBehaviour.BlockStateBase.class, "occlusionShapesByFace");
		Set<Object> arrays = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<List<Object>> distinct = new java.util.HashSet<>();
		for (BlockState s : states) {
			Object[] a = (Object[]) get(faces, s);
			if (a != null && arrays.add(a)) {
				distinct.add(Arrays.asList(a));
			}
		}
		System.out.printf(Locale.ROOT, "  occlusion face arrays:%,d arrays (%s), %,d distinct by element identity%n",
				arrays.size(), mb(arrays.size() * arrayBytes(6, 4)), distinct.size());
	}

	/** Structural identity of a shape: its class plus its exact box decomposition. */
	private static String shapeKey(VoxelShape shape) {
		return shape.getClass().getSimpleName() + shape.toAabbs();
	}

	// ------------------------------------------------------------------ size estimation

	static long arrayBytes(int length, int elementBytes) {
		return align(16L + (long) length * elementBytes);
	}

	private static long align(long bytes) {
		return (bytes + 7) & ~7L;
	}

	/** Shallow sizes of every object reachable from {@code roots}, each counted once; stops at enums and classes. */
	static long deepSize(List<?> roots) {
		Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Object> queue = new ArrayDeque<>();
		for (Object r : roots) {
			if (r != null) {
				queue.add(r);
			}
		}
		long total = 0;
		while (!queue.isEmpty()) {
			Object o = queue.pop();
			if (o == null || o instanceof Class<?> || o instanceof Enum<?> || !seen.add(o)) {
				continue;
			}
			Class<?> c = o.getClass();
			if (c.isArray()) {
				Class<?> e = c.getComponentType();
				int n = Array.getLength(o);
				if (e.isPrimitive()) {
					total += arrayBytes(n, primitiveBytes(e));
				} else {
					total += arrayBytes(n, 4);
					for (int i = 0; i < n; i++) {
						Object x = Array.get(o, i);
						if (x != null) {
							queue.add(x);
						}
					}
				}
				continue;
			}
			long shallow = 12;
			for (Class<?> k = c; k != null; k = k.getSuperclass()) {
				for (Field f : k.getDeclaredFields()) {
					if (Modifier.isStatic(f.getModifiers())) {
						continue;
					}
					Class<?> t = f.getType();
					shallow += t.isPrimitive() ? primitiveBytes(t) : 4;
					if (!t.isPrimitive() && f.trySetAccessible()) {
						Object x = get(f, o);
						if (x != null) {
							queue.add(x);
						}
					}
				}
			}
			total += align(shallow);
		}
		return total;
	}

	private static int primitiveBytes(Class<?> t) {
		if (t == long.class || t == double.class) {
			return 8;
		}
		if (t == int.class || t == float.class) {
			return 4;
		}
		if (t == short.class || t == char.class) {
			return 2;
		}
		return 1;
	}

	static Field field(Class<?> owner, String name) {
		try {
			Field f = owner.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException(owner + "." + name, e);
		}
	}

	static Object get(Field f, Object o) {
		try {
			return f.get(o);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	static String mb(long bytes) {
		return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
	}
}
