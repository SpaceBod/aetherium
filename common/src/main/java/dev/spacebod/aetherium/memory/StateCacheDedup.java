package dev.spacebod.aetherium.memory;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * {@code mem.state_cache_dedup}: every block state's cache holds its collision shape and an 18-entry
 * {@code faceSturdy} array, and every partially occluding state an array of six occlusion face shapes.
 * On 26.3 that is 35,524 caches with 6,093 distinct collision shape objects of which only 330 differ,
 * 35,524 {@code faceSturdy} arrays with 57 distinct contents, and 21,324 face arrays with 5,520 distinct
 * contents ({@code gradlew :common:microbench -Ponly=memory}). Equal values are replaced by one shared
 * instance as each cache is built.
 *
 * <p>Blocks keep their own references to the duplicate shapes (per-state shape tables), so replacing the
 * reference in the cache alone frees nothing: a duplicate shape's internals (point lists and voxel bits)
 * are pointed at the canonical shape's, leaving an empty shell. Shapes are immutable, and
 * two shapes are merged only when their class, point lists and voxel data are exactly equal, so every
 * query answers identically.
 */
public final class StateCacheDedup {
	/** Points a duplicate shape's internals at the canonical one; replaced by the parity harness, which runs without mixins. */
	public interface Adopter {
		void adopt(VoxelShape duplicate, VoxelShape canonical);
	}

	public static volatile Adopter adopter = (duplicate, canonical) -> {
		VoxelShapeAccess target = (VoxelShapeAccess) duplicate;
		if (duplicate instanceof ArrayVoxelShape) {
			ArrayVoxelShapeAccess from = (ArrayVoxelShapeAccess) canonical;
			ArrayVoxelShapeAccess to = (ArrayVoxelShapeAccess) duplicate;
			to.aetherium$setXs(from.aetherium$xs());
			to.aetherium$setYs(from.aetherium$ys());
			to.aetherium$setZs(from.aetherium$zs());
		}
		target.aetherium$setShape(((VoxelShapeAccess) canonical).aetherium$shape());
	};

	/** Implemented by the accessor mixins of the optimisation. */
	public interface VoxelShapeAccess {
		DiscreteVoxelShape aetherium$shape();

		void aetherium$setShape(DiscreteVoxelShape shape);
	}

	public interface ArrayVoxelShapeAccess {
		DoubleList aetherium$xs();

		DoubleList aetherium$ys();

		DoubleList aetherium$zs();

		void aetherium$setXs(DoubleList xs);

		void aetherium$setYs(DoubleList ys);

		void aetherium$setZs(DoubleList zs);
	}

	private static final Map<ShapeKey, VoxelShape> SHAPES = new HashMap<>();
	private static final Map<BooleansKey, boolean[]> STURDY = new HashMap<>();
	private static final Map<List<VoxelShape>, VoxelShape[]> FACES = new HashMap<>();

	private StateCacheDedup() {
	}

	public static synchronized VoxelShape shape(VoxelShape shape) {
		ShapeKey key = ShapeKey.of(shape);
		if (key == null) {
			return shape;
		}
		VoxelShape canonical = SHAPES.putIfAbsent(key, shape);
		if (canonical == null || canonical == shape) {
			return shape;
		}
		adopter.adopt(shape, canonical);
		return canonical;
	}

	public static synchronized boolean[] faceSturdy(boolean[] values) {
		boolean[] canonical = STURDY.putIfAbsent(new BooleansKey(values), values);
		return canonical == null ? values : canonical;
	}

	public static synchronized VoxelShape[] occlusionFaces(VoxelShape[] faces) {
		if (faces.length != Direction.values().length) {
			return faces;
		}
		VoxelShape[] canonical = FACES.putIfAbsent(List.of(faces), faces);
		return canonical == null ? faces : canonical;
	}

	/** Exact structural identity of the shape classes whose internals this class knows completely. */
	private record ShapeKey(Class<?> type, int[] discrete, long[] bits, double[] xs, double[] ys, double[] zs) {
		private static final VarHandle SHAPE;
		private static final VarHandle XS;
		private static final VarHandle YS;
		private static final VarHandle ZS;
		private static final VarHandle STORAGE;
		private static final VarHandle[] BOUNDS = new VarHandle[6];
		private static final double[] NONE = new double[0];

		static {
			try {
				SHAPE = MethodHandles.privateLookupIn(VoxelShape.class, MethodHandles.lookup())
						.findVarHandle(VoxelShape.class, "shape", DiscreteVoxelShape.class);
				MethodHandles.Lookup array = MethodHandles.privateLookupIn(ArrayVoxelShape.class, MethodHandles.lookup());
				XS = array.findVarHandle(ArrayVoxelShape.class, "xs", DoubleList.class);
				YS = array.findVarHandle(ArrayVoxelShape.class, "ys", DoubleList.class);
				ZS = array.findVarHandle(ArrayVoxelShape.class, "zs", DoubleList.class);
				MethodHandles.Lookup bits = MethodHandles.privateLookupIn(BitSetDiscreteVoxelShape.class, MethodHandles.lookup());
				STORAGE = bits.findVarHandle(BitSetDiscreteVoxelShape.class, "storage", BitSet.class);
				String[] names = {"xMin", "yMin", "zMin", "xMax", "yMax", "zMax"};
				for (int i = 0; i < names.length; i++) {
					BOUNDS[i] = bits.findVarHandle(BitSetDiscreteVoxelShape.class, names[i], int.class);
				}
			} catch (ReflectiveOperationException e) {
				throw new ExceptionInInitializerError(e);
			}
		}

		static @Nullable ShapeKey of(VoxelShape shape) {
			Class<?> type = shape.getClass();
			if (type != ArrayVoxelShape.class && type != CubeVoxelShape.class) {
				return null;
			}
			if (!(SHAPE.get(shape) instanceof BitSetDiscreteVoxelShape discrete)) {
				return null;
			}
			int[] d = new int[9];
			d[0] = discrete.getXSize();
			d[1] = discrete.getYSize();
			d[2] = discrete.getZSize();
			for (int i = 0; i < 6; i++) {
				d[3 + i] = (int) BOUNDS[i].get(discrete);
			}
			long[] words = ((BitSet) STORAGE.get(discrete)).toLongArray();
			if (type == ArrayVoxelShape.class) {
				return new ShapeKey(type, d, words, ((DoubleList) XS.get(shape)).toDoubleArray(),
						((DoubleList) YS.get(shape)).toDoubleArray(), ((DoubleList) ZS.get(shape)).toDoubleArray());
			}
			return new ShapeKey(type, d, words, NONE, NONE, NONE);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof ShapeKey k && type == k.type && Arrays.equals(discrete, k.discrete) && Arrays.equals(bits, k.bits)
					&& Arrays.equals(xs, k.xs) && Arrays.equals(ys, k.ys) && Arrays.equals(zs, k.zs);
		}

		@Override
		public int hashCode() {
			int h = type.hashCode();
			h = 31 * h + Arrays.hashCode(discrete);
			h = 31 * h + Arrays.hashCode(bits);
			h = 31 * h + Arrays.hashCode(xs);
			h = 31 * h + Arrays.hashCode(ys);
			return 31 * h + Arrays.hashCode(zs);
		}
	}

	private record BooleansKey(boolean[] values) {
		@Override
		public boolean equals(Object o) {
			return o instanceof BooleansKey k && Arrays.equals(values, k.values);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(values);
		}
	}
}
