package dev.spacebod.aetherium.client.gpu;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.util.ShaderCompileException;
import dev.spacebod.aetherium.client.mixin.access.VulkanDeviceFeaturesAccessor;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Geometry stages for pack pipelines. Vanilla's pipeline API has vertex and fragment shaders only, so a pack program's
 * geometry stage travels beside it: registered under the pipeline's id while the pipeline compiles ({@link #compiling}),
 * compiled by vanilla's pipeline builder with its own compiler options and put with the other stages (descriptors are
 * renumbered by name across all of them; the fragment inputs are checked against its outputs), then created by the
 * Vulkan backend as a geometry stage ({@link Module} marks it). Needs the device's {@code geometryShader} feature,
 * requested as an optional feature set when vanilla creates its device.
 */
public final class GeometryStages {
	public static final FeatureSet FEATURE = new FeatureSet("Aetherium geometry shaders", Set.of(), Set.of(
			new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "geometryShader")));
	/** {@code VK_SHADER_STAGE_GEOMETRY_BIT}. */
	public static final int STAGE_BIT = 0x8;

	/** Pipeline id -> geometry stage GLSL, while that pipeline compiles. */
	private static final Map<Identifier, String> SOURCES = new ConcurrentHashMap<>();
	/** Set while vanilla's GLSL compiler compiles a geometry stage (it only knows vertex and fragment kinds). */
	private static final ThreadLocal<Boolean> COMPILING = ThreadLocal.withInitial(() -> false);

	private GeometryStages() {
	}

	/** Compile harness only: whether the device it stands in for runs geometry stages. */
	private static volatile @Nullable Boolean assumed;

	public static void assumeForCompileCheck(boolean geometryStages) {
		assumed = geometryStages;
	}

	/** Geometry stages can be used: Vulkan, with the device's geometry shader feature enabled. */
	public static boolean enabled() {
		Boolean assumedHere = assumed;
		if (assumedHere != null) {
			return assumedHere;
		}
		VulkanDevice device = VulkanAccess.device();
		return device != null && ((VulkanDeviceFeaturesAccessor) device).aetherium$enabledFeatures().contains(FEATURE);
	}

	/** Runs {@code compile} (vanilla's pipeline compile of {@code id}) with {@code geometry} as that pipeline's geometry stage. */
	public static <T> T compiling(Identifier id, String geometry, Supplier<T> compile) {
		SOURCES.put(id, geometry);
		try {
			return compile.get();
		} finally {
			SOURCES.remove(id);
		}
	}

	/** The geometry stage registered for pipeline {@code id}, or null. */
	public static @Nullable String source(Identifier id) {
		return SOURCES.get(id);
	}

	/** Whether vanilla's compiler is compiling a geometry stage on this thread (it then uses the geometry kind). */
	public static boolean compilingStage() {
		return COMPILING.get();
	}

	/** Runs vanilla's compile of a geometry stage (see {@link #compilingStage}). */
	public static SpvModule compileStage(StageCompiler compiler) throws ShaderCompileException {
		COMPILING.set(true);
		try {
			return new Module(compiler.compile());
		} finally {
			COMPILING.set(false);
		}
	}

	public interface StageCompiler {
		SpvModule compile() throws ShaderCompileException;
	}

	/**
	 * A compiled geometry stage: vanilla's module (its {@link #type} says VERTEX, the API having no other kind), marked
	 * so the Vulkan backend creates it as a geometry stage.
	 */
	public static final class Module implements SpvModule {
		private final SpvModule module;

		Module(SpvModule module) {
			this.module = module;
		}

		@Override
		public void close() {
			module.close();
		}

		@Override
		public ByteBuffer spv() {
			return module.spv();
		}

		@Override
		public ShaderType type() {
			return module.type();
		}

		@Override
		public Reflection reflect() throws ShaderCompileException {
			return module.reflect();
		}

		@Override
		public @Nullable Reflection getReflectionInfoIfAvailable() {
			return module.getReflectionInfoIfAvailable();
		}
	}
}
