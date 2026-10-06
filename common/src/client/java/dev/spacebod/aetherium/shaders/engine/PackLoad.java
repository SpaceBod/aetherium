package dev.spacebod.aetherium.shaders.engine;

import com.google.common.collect.ImmutableList;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.spacebod.aetherium.client.gpu.StorageFeatures;
import dev.spacebod.aetherium.client.gpu.VulkanAccess;
import dev.spacebod.aetherium.shaders.config.ShaderPackSettings;
import dev.spacebod.aetherium.shaders.gl.ShaderLimits;
import dev.spacebod.aetherium.shaders.gl.shader.StandardMacros;
import dev.spacebod.aetherium.shaders.helpers.StringPair;
import dev.spacebod.aetherium.shaders.shaderpack.ShaderPack;
import dev.spacebod.aetherium.shaders.shaderpack.ShadowOverrides;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramArrayId;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.NamespacedId;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ComputeSource;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;

/**
 * The part of loading a pack that needs no render thread, run on a worker so the game keeps drawing (vanilla visuals)
 * meanwhile: reading and preprocessing the pack, the settings the shader conversion reads (shadow sampling, texture
 * renames, storage bindings), and every full-screen and compute program transformed and compiled. The engine then
 * finishes the load between two frames ({@link ShaderPackEngine}), taking the prepared programs from here.
 * <p>
 * Progress ({@link #phase}, {@link #done}, {@link #total}) is read by the settings screen and the loading line above
 * the hotbar.
 */
final class PackLoad {
	/** One worker: a second load only starts after the first was cancelled and finished. */
	private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Aetherium Shaders loader");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});

	final String packName;
	final Map<String, String> options;
	final ImmutableList<StringPair> defines;
	final ShadowOverrides shadows;
	final NamespacedId dimension;
	private final GpuFormat mainFormat;
	private final boolean storageFeatures;
	final long started = System.nanoTime();

	volatile String phase = "Reading the pack";
	/** 0 reading, 1 converting, 2 starting ({@link ShaderPackEngine.LoadProgress#step}). */
	volatile int step;
	final AtomicInteger done = new AtomicInteger();
	volatile int total;
	/** Set by {@link #cancel}: the worker stops at the next program and frees what it prepared. */
	private volatile boolean cancelled;
	private final CompletableFuture<PackLoad> future = new CompletableFuture<>();

	// Results, read by the render thread once the future completed.
	ShaderPackSettings.@Nullable OpenPack opened;
	@Nullable ShaderPack pack;
	@Nullable ProgramSet programs;
	List<RawTextures.Texture> raws = List.of();
	/** The pack needs its storage set (custom images, storage buffers, raw textures or targets bound as images). */
	boolean storageNeeded;
	/** That set's layout, made here for the compute pipelines; {@link PackStorage} takes it over (0 = none). */
	long storageSetLayout;
	@Nullable PackTargets targets;
	/** Prepared full-screen passes; a source mapped to null was skipped (already logged). */
	final Map<ProgramSource, CompositePass.@Nullable Prepared> passes = new IdentityHashMap<>();
	final Map<ComputeSource, ComputePass.@Nullable Prepared> computes = new IdentityHashMap<>();

	private PackLoad(String packName, Map<String, String> options, ImmutableList<StringPair> defines, ShadowOverrides shadows, NamespacedId dimension,
			GpuFormat mainFormat) {
		this.packName = packName;
		this.options = options;
		this.defines = defines;
		this.shadows = shadows;
		this.dimension = dimension;
		this.mainFormat = mainFormat;
		this.storageFeatures = StorageFeatures.enabled();
	}

	/** Render thread: reads what the load needs from the game, then starts the worker. */
	static PackLoad start(String packName, NamespacedId dimension, GpuFormat mainFormat) {
		PackLoad load = new PackLoad(packName, ShaderPackSettings.readOptions(packName),
				StandardMacros.createStandardEnvironmentDefines(), ShaderPackSettings.shadowOverrides(), dimension, mainFormat);
		LOADER.execute(load::run);
		return load;
	}

	/**
	 * Render thread: a load of a pack already read (another dimension of the pack in use): only {@code dimension}'s
	 * program set is built and converted, nothing is read from disk. The load owns {@code opened} from here on.
	 */
	static PackLoad start(ShaderPackSettings.OpenPack opened, ShaderPack pack, String packName, Map<String, String> options,
			ImmutableList<StringPair> defines, ShadowOverrides shadows, NamespacedId dimension, GpuFormat mainFormat) {
		PackLoad load = new PackLoad(packName, options, defines, shadows, dimension, mainFormat);
		load.opened = opened;
		load.pack = pack;
		LOADER.execute(load::run);
		return load;
	}

	boolean isDone() {
		return future.isDone();
	}

	/** The finished load; throws what the worker threw (the pack cannot be used). */
	PackLoad result() {
		try {
			return future.join();
		} catch (CompletionException e) {
			throw e.getCause() instanceof RuntimeException r ? r : e;
		}
	}

	/** Stops the worker and waits for it (at most the program it is on), then frees everything it prepared. */
	void cancel() {
		cancelled = true;
		try {
			future.join();
		} catch (RuntimeException e) {
			// A cancelled load's own failure has nobody to report to; a lost device does.
			DeviceLoss.rethrow(e);
		}
		release();
	}

	/** Frees prepared programs nobody took, and the storage set layout if the pack's storage did not take it. */
	void releaseUnused() {
		passes.values().forEach(p -> {
			if (p != null) {
				p.discard();
			}
		});
		passes.clear();
		computes.values().forEach(p -> {
			if (p != null) {
				p.discard();
			}
		});
		computes.clear();
		if (storageSetLayout != 0) {
			// Not taken by the pack's storage (the load failed or was cancelled before it was made).
			StorageSet.loadAbandoned(storageSetLayout);
			VK10.vkDestroyDescriptorSetLayout(device().vkDevice(), storageSetLayout, null);
			storageSetLayout = 0;
		}
	}

	private static VulkanDevice device() {
		return Objects.requireNonNull(VulkanAccess.device());
	}

	private void release() {
		releaseUnused();
		if (opened != null) {
			opened.close();
			opened = null;
		}
	}

	private void run() {
		try {
			ShaderPack pack = this.pack;
			if (pack == null) {
				opened = ShaderPackSettings.open(packName);
				pack = new ShaderPack(opened.root(), options, defines, opened.isZip(), false, shadows);
				this.pack = pack;
			}
			ProgramSet programs = pack.getProgramSet(dimension);
			this.programs = programs;
			PackDirectives directives = programs.getPackDirectives();
			// Before any program is converted: the conversion reads how shadow depth is sampled,
			ShadowSampling.configure(directives.getShadowDirectives(), ShadowSampling.hasShadowPass(programs, directives));
			// the raw custom textures (each stage's sampler is renamed to the texture's pack-wide name),
			TexturePatching.configure(directives.getTextureMap());
			// and where the pack's custom images, storage buffers and raw textures vanilla cannot hold are bound (set 1).
			raws = storageFeatures ? RawTextures.collect(pack) : List.of();
			// Targets bound as images (colorimgN, shadowcolorimgN) need storage usage; set before any target is created.
			TargetStorage.configure(storageFeatures && TargetStorage.usedBy(programs));
			storageNeeded = storageFeatures
					&& (!pack.getCustomImages().isEmpty() || !pack.getBufferObjects().isEmpty() || !raws.isEmpty() || TargetStorage.active());
			if (storageNeeded) {
				StorageBindings.configure(PackStorage.bindings(pack.getCustomImages()), !pack.getBufferObjects().isEmpty(), RawTextures.bindings(raws),
						TargetStorage.active());
				// Custom images the programs access in another format get views in it (bindings of the set layout below).
				StorageBindings.configureViews(ImageViews.scan(PackStorage.bindings(pack.getCustomImages()), TargetStorage.sourceTexts(programs)));
				storageSetLayout = PackStorage.createSetLayout(device().vkDevice(), pack.getCustomImages(), pack.getBufferObjects(), raws,
						TargetStorage.active());
				// The full-screen passes below compile before the pack's storage exists: they take set 1 from here.
				StorageSet.loading(storageSetLayout);
			}
			// colortex0..31: only those the pack names or writes are created (the textures themselves on first use).
			int targetCount = ShaderLimits.MAX_COLOR_BUFFERS;
			PackTargets targets = new PackTargets(directives.getRenderTargetDirectives().getRenderTargetSettings(), targetCount,
					ShaderPackEngine.referencedTargets(programs, targetCount));
			this.targets = targets;
			prepareAll(programs, targets);
			phase = "Starting";
			step = 2;
			if (cancelled) {
				release();
			}
			future.complete(this);
		} catch (Throwable t) {
			release();
			future.completeExceptionally(t instanceof CompletionException && t.getCause() != null ? t.getCause() : t);
		}
	}

	/** Converts and compiles every full-screen and compute program the frame plan will ask for, a few at a time. */
	private void prepareAll(ProgramSet programs, PackTargets targets) {
		List<Runnable> jobs = new ArrayList<>();
		for (ProgramArrayId id : new ProgramArrayId[]{ProgramArrayId.Begin, ProgramArrayId.Prepare, ProgramArrayId.Deferred, ProgramArrayId.Composite,
				ProgramArrayId.ShadowComposite}) {
			TextureStage textures = FramePlan.textureStage(id);
			for (ProgramSource source : programs.getComposite(id)) {
				if (source == null || !source.isValid()) {
					continue;
				}
				if (id == ProgramArrayId.ShadowComposite) {
					// Shadow colour formats are known once the shadow map exists (render thread): only the conversion here.
					jobs.add(() -> source.getVertexSource().ifPresent(v -> source.getFragmentSource().ifPresent(f -> {
						if (source.getGeometrySource().isEmpty()) {
							CompositePass.lower(source.getName(), v, f, TextureStage.SHADOWCOMP);
						}
					})));
				} else {
					jobs.add(() -> preparePass(source, textures, source.getDirectives().getDrawBuffers(), false, targets::format));
				}
			}
			ComputeSource[][] stageComputes = programs.getCompute(id);
			if (stageComputes != null) {
				for (ComputeSource[] list : stageComputes) {
					addComputes(jobs, list, textures);
				}
			}
		}
		programs.get(ProgramId.Final).ifPresent(source -> jobs.add(() -> preparePass(source, TextureStage.COMPOSITE_AND_FINAL, new int[]{0}, true,
				target -> mainFormat)));
		addComputes(jobs, programs.getSetup(), TextureStage.SETUP);
		addComputes(jobs, programs.getShadowCompute(), TextureStage.SHADOWCOMP);
		addComputes(jobs, programs.getFinalCompute(), TextureStage.COMPOSITE_AND_FINAL);

		total = jobs.size();
		phase = "Converting shaders";
		step = 1;
		// The conversion itself is serialised (one transformer); SPIR-V compilation overlaps it on the other workers.
		List<CompletableFuture<Void>> running = new ArrayList<>();
		for (Runnable job : jobs) {
			running.add(CompletableFuture.runAsync(() -> {
				if (!cancelled) {
					job.run();
				}
				done.incrementAndGet();
			}, WorldPrograms.COMPILER));
		}
		CompletableFuture.allOf(running.toArray(CompletableFuture[]::new)).join();
	}

	private void addComputes(List<Runnable> jobs, ComputeSource @Nullable [] sources, TextureStage stage) {
		if (sources == null || !storageFeatures) {
			return;
		}
		for (ComputeSource source : sources) {
			if (source != null && source.isValid()) {
				jobs.add(() -> {
					ComputePass.Prepared prepared = ComputePass.prepare(source, stage, device().vkDevice(), storageSetLayout);
					synchronized (computes) {
						computes.put(source, prepared);
					}
				});
			}
		}
	}

	private void preparePass(ProgramSource source, TextureStage stage, int[] drawBuffers, boolean writesMain, IntFunction<GpuFormat> format) {
		CompositePass.Prepared prepared = CompositePass.prepare(source, stage, drawBuffers, writesMain, format);
		synchronized (passes) {
			passes.put(source, prepared);
		}
	}

	/** Whether {@code source} went through {@link #prepareAll} (its pass, or null when skipped, is in {@link #takePass}). */
	boolean hasPass(ProgramSource source) {
		synchronized (passes) {
			return passes.containsKey(source);
		}
	}

	CompositePass.@Nullable Prepared takePass(ProgramSource source) {
		synchronized (passes) {
			return passes.remove(source);
		}
	}

	boolean hasCompute(ComputeSource source) {
		synchronized (computes) {
			return computes.containsKey(source);
		}
	}

	ComputePass.@Nullable Prepared takeCompute(ComputeSource source) {
		synchronized (computes) {
			return computes.remove(source);
		}
	}

	/** Seconds since the load started. */
	double seconds() {
		return (System.nanoTime() - started) / 1e9;
	}
}
