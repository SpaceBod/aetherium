package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.pipeline.PipelinePrograms;
import dev.spacebod.aetherium.shaders.pipeline.programs.ShaderKey;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramFallbackResolver;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import dev.spacebod.aetherium.shaders.uniforms.custom.CustomUniforms;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Which pipeline a vanilla draw gets inside a world (gbuffers) render pass: the pack program {@link PipelinePrograms}
 * assigns (built once per vanilla pipeline and context), or, when the pack has no program for it or the program cannot
 * be built, the vanilla pipeline itself widened to the gbuffer attachments (its output goes to the first attachment,
 * the others are masked off). Programs are prebuilt on a worker thread from pack load ({@link #prebuild}) and finished
 * on the render thread; a draw that needs one first waits for its build, anything not queued is built on the spot.
 */
public final class WorldPrograms implements AutoCloseable {
	private record Key(RenderPipeline vanilla, @Nullable ShaderKey shaderKey) {
	}

	private final ProgramSet programs;
	private final ProgramFallbackResolver resolver;
	private final TargetLayout layout;
	/** Shadow pass: the shadow program table, no culling; pipelines with no shadow program are skipped. */
	private final boolean shadow;
	private final CustomUniforms customUniforms;
	/**
	 * A quarter of the cores (2 to 12), below normal priority. The GLSL transform is serialised (TransformPatcher) but
	 * short (about 20 ms a program); most of a build is the SPIR-V compile and the driver's pipeline build (about 0.7 s for
	 * a large pack's program), which run in parallel. Packs bring 100+ programs: entering a world waits for them.
	 */
	static final ExecutorService COMPILER = Executors.newFixedThreadPool(
			Math.max(2, Math.min(12, Runtime.getRuntime().availableProcessors() / 4)), new ThreadFactory() {
				private final AtomicInteger count = new AtomicInteger();

				@Override
				public Thread newThread(Runnable r) {
					Thread thread = new Thread(r, "Aetherium Shaders compiler " + count.incrementAndGet());
					thread.setDaemon(true);
					thread.setPriority(Thread.NORM_PRIORITY - 1);
					return thread;
				}
			});
	private final Map<Key, CompiledRenderPipeline> resolved = new HashMap<>();
	private final Map<Key, Job> inFlight = new LinkedHashMap<>();
	private final Map<CompiledRenderPipeline, WorldProgram> byCompiled = new IdentityHashMap<>();
	private final List<CompiledRenderPipeline> widened = new ArrayList<>();
	private final Map<RenderPipeline, CompiledRenderPipeline> skipped = new IdentityHashMap<>();
	private final Map<ShaderKey, Optional<ProgramSource>> sources = new EnumMap<>(ShaderKey.class);
	/** Per vanilla compiled pipeline: what it draws with in each context ({@link #route}). */
	private final Map<CompiledRenderPipeline, Route> routes = new IdentityHashMap<>();
	/** Per program source: the targets its declared samplers name, for programs not built yet ({@link #reach}). */
	private final Map<ProgramSource, Long> declaredReach = new IdentityHashMap<>();
	/** Changes whenever a program is finished or built ({@link #reach} answers may change). */
	private int generation;
	/** Set by {@link #close}: queued background builds skip their work. */
	private volatile boolean closed;

	public WorldPrograms(ProgramSet programs, TargetLayout layout, CustomUniforms customUniforms, boolean shadow) {
		this.shadow = shadow;
		this.programs = programs;
		this.resolver = new ProgramFallbackResolver(programs);
		this.layout = layout;
		this.customUniforms = customUniforms;
	}

	/** The pipeline to draw with in place of {@code vanilla} (never null: falls back to the vanilla pipeline widened). */
	public CompiledRenderPipeline resolve(RenderPipeline vanilla, PipelinePrograms.Context context) {
		ShaderKey key = shadow ? PipelinePrograms.shadow(vanilla, context) : PipelinePrograms.main(vanilla, context);
		Key k = new Key(vanilla, key);
		CompiledRenderPipeline pipeline = resolved.get(k);
		if (pipeline != null) {
			return pipeline;
		}
		Job job = inFlight.get(k);
		pipeline = job != null ? finish(k, job) : build(vanilla, key);
		resolved.put(k, pipeline);
		generation++;
		return pipeline;
	}

	/**
	 * One vanilla compiled pipeline's substitutes: per context, the pipeline bound instead and the pack program behind
	 * it (null for a widened vanilla pipeline), filled the first time the pipeline is bound in that context.
	 */
	public static final class Route {
		private final RenderPipeline vanilla;
		private final @Nullable CompiledRenderPipeline[] pipelines = new CompiledRenderPipeline[CONTEXTS];
		private final @Nullable WorldProgram[] programs = new WorldProgram[CONTEXTS];

		private Route(RenderPipeline vanilla) {
			this.vanilla = vanilla;
		}

		public RenderPipeline vanilla() {
			return vanilla;
		}
	}

	/** Contexts are three flags: hand, solid hand, block entity. */
	private static final int CONTEXTS = 8;

	private static int index(PipelinePrograms.Context context) {
		return (context.hand() ? 1 : 0) | (context.handSolid() ? 2 : 0) | (context.blockEntity() ? 4 : 0);
	}

	/**
	 * The route of a vanilla compiled pipeline (one identity lookup once known); null when {@code compiled} is not a
	 * vanilla pipeline {@code vanillaOf} knows.
	 */
	public @Nullable Route route(CompiledRenderPipeline compiled, Map<CompiledRenderPipeline, RenderPipeline> vanillaOf) {
		Route route = routes.get(compiled);
		if (route == null) {
			RenderPipeline vanilla = vanillaOf.get(compiled);
			if (vanilla == null) {
				return null;
			}
			route = new Route(vanilla);
			routes.put(compiled, route);
		}
		return route;
	}

	/** {@link #resolve} through a route: after the first time per context, two array reads. */
	public CompiledRenderPipeline resolve(Route route, PipelinePrograms.Context context) {
		int i = index(context);
		CompiledRenderPipeline pipeline = route.pipelines[i];
		if (pipeline == null) {
			pipeline = resolve(route.vanilla, context);
			route.programs[i] = byCompiled.get(pipeline);
			route.pipelines[i] = pipeline;
		}
		return pipeline;
	}

	/** The pack program behind what {@link #resolve(Route, PipelinePrograms.Context)} last returned for that context. */
	public static @Nullable WorldProgram program(Route route, PipelinePrograms.Context context) {
		return route.programs[index(context)];
	}

	/** Changes whenever a program build finishes ({@link #reach} may answer differently). */
	public int generation() {
		return generation;
	}

	/**
	 * What the program {@code vanilla} gets in {@code context} can read among the colour targets and depthtex0
	 * ({@link WorldProgram#reachMask}): the samplers the built program reaches, or, while it is not built yet, every
	 * target its source declares a sampler for. 0 when the pipeline has no program (it draws widened or not at all).
	 */
	public long reach(RenderPipeline vanilla, PipelinePrograms.Context context) {
		ShaderKey key = shadow ? PipelinePrograms.shadow(vanilla, context) : PipelinePrograms.main(vanilla, context);
		if (key == null) {
			return 0;
		}
		CompiledRenderPipeline pipeline = resolved.get(new Key(vanilla, key));
		if (pipeline != null) {
			WorldProgram program = byCompiled.get(pipeline);
			return program == null ? 0 : program.reach();
		}
		Optional<ProgramSource> source = sources.computeIfAbsent(key, s -> resolver.resolve(s.getProgram()));
		if (source.isEmpty()) {
			return 0;
		}
		return declaredReach.computeIfAbsent(source.get(), s -> {
			StringBuilder text = new StringBuilder();
			s.getVertexSource().ifPresent(t -> text.append(t).append('\n'));
			s.getGeometrySource().ifPresent(t -> text.append(t).append('\n'));
			s.getFragmentSource().ifPresent(text::append);
			return WorldProgram.reachMask(SamplerUsage.declaredSamplers(text.toString()));
		});
	}

	/** Queues a background build of the program {@code vanilla} gets in {@code context} (no-op when there is none). */
	public void prebuild(RenderPipeline vanilla, PipelinePrograms.Context context) {
		ShaderKey key = shadow ? PipelinePrograms.shadow(vanilla, context) : PipelinePrograms.main(vanilla, context);
		if (key == null) {
			return;
		}
		Key k = new Key(vanilla, key);
		if (resolved.containsKey(k) || inFlight.containsKey(k)) {
			return;
		}
		Optional<ProgramSource> source = sources.computeIfAbsent(key, s -> resolver.resolve(s.getProgram()));
		if (source.isEmpty()) {
			return;
		}
		Job job = new Job(() -> closed ? null : WorldProgram.compile(source.get(), key, vanilla, layout, shadow));
		inFlight.put(k, job);
		COMPILER.execute(job::run);
	}

	/** Render thread, outside render passes: finishes every background build that is done. */
	public void drainFinished() {
		if (inFlight.isEmpty()) {
			return;
		}
		for (var it = new ArrayList<>(inFlight.entrySet()).iterator(); it.hasNext(); ) {
			var e = it.next();
			if (e.getValue().result.isDone()) {
				resolved.put(e.getKey(), finish(e.getKey(), e.getValue()));
				generation++;
			}
		}
	}

	/**
	 * Whether the program {@code vanilla} gets in {@code context} needs no more building: built (or its build done), no
	 * program, or never queued.
	 */
	public boolean ready(RenderPipeline vanilla, PipelinePrograms.Context context) {
		ShaderKey key = shadow ? PipelinePrograms.shadow(vanilla, context) : PipelinePrograms.main(vanilla, context);
		Job job = key == null ? null : inFlight.get(new Key(vanilla, key));
		return job == null || job.result.isDone();
	}

	/** Render thread: finishes every queued build now, building the ones no worker started yet on this thread. */
	public void finishAll() {
		for (var e : new ArrayList<>(inFlight.entrySet())) {
			resolved.put(e.getKey(), finish(e.getKey(), e.getValue()));
			generation++;
		}
	}

	/** Background builds still running. */
	public int pending() {
		return inFlight.size();
	}

	private CompiledRenderPipeline finish(Key k, Job job) {
		inFlight.remove(k);
		// Not started yet: build it here rather than wait for the queue ahead of it (large packs queue 100+ programs).
		job.run();
		WorldProgram.Built built;
		try {
			built = job.result.join();
		} catch (RuntimeException e) {
			DeviceLoss.rethrow(e);
			AetheriumShaders.logger.error("[{}] failed to build; vanilla shader used", k.vanilla().getLocation(), e);
			built = null;
		}
		WorldProgram program = built == null ? null : WorldProgram.finish(built, customUniforms);
		if (program != null) {
			byCompiled.put(program.compiled(), program);
			AetheriumShaders.logger.info("{}", program.name());
			return program.compiled();
		}
		return fallback(k.vanilla(), k.shaderKey());
	}

	/**
	 * A pipeline whose program could not be built: vanilla's shader, widened to the gbuffer attachments. LOD terrain
	 * draws nothing instead: the LOD mod's own colour would land in the pack's albedo, unlit and unfogged.
	 */
	private CompiledRenderPipeline fallback(RenderPipeline vanilla, @Nullable ShaderKey key) {
		if (key != null && key.isLod()) {
			AetheriumShaders.logger.warn("[{}] no LOD program: those LODs are not drawn", vanilla.getLocation());
			return skip(vanilla);
		}
		CompiledRenderPipeline w = widen(vanilla);
		widened.add(w);
		return w;
	}

	/**
	 * {@code vanilla} drawing nothing: a shadow caster the pack's directives leave out of the shadow map, or any draw
	 * under {@code skipAllRendering}.
	 */
	public CompiledRenderPipeline skip(RenderPipeline vanilla) {
		CompiledRenderPipeline pipeline = skipped.get(vanilla);
		if (pipeline == null) {
			pipeline = widen(vanilla, true);
			widened.add(pipeline);
			skipped.put(vanilla, pipeline);
		}
		return pipeline;
	}

	/** The pack program behind a pipeline {@link #resolve} returned, or null for a widened vanilla pipeline. */
	public @Nullable WorldProgram program(CompiledRenderPipeline pipeline) {
		return byCompiled.get(pipeline);
	}

	private CompiledRenderPipeline build(RenderPipeline vanilla, @Nullable ShaderKey key) {
		if (key != null) {
			Optional<ProgramSource> source = sources.computeIfAbsent(key, k -> resolver.resolve(k.getProgram()));
			if (source.isPresent()) {
				WorldProgram program;
				try {
					program = WorldProgram.create(source.get(), key, vanilla, layout, customUniforms, shadow);
				} catch (RuntimeException e) {
					DeviceLoss.rethrow(e);
					// One program failing must not take the pack (or the open render pass) down: this draw keeps vanilla's shader.
					AetheriumShaders.logger.error("[{} for {}] failed to build; vanilla shader used", source.get().getName(), vanilla.getLocation(), e);
					program = null;
				}
				if (program != null) {
					byCompiled.put(program.compiled(), program);
					AetheriumShaders.logger.info("{} draws {}", source.get().getName(), vanilla.getLocation());
					return program.compiled();
				}
			}
		}
		return fallback(vanilla, key);
	}

	/**
	 * {@code vanilla} with its own shaders, one colour target per gbuffer attachment, output to the first. In the shadow
	 * pass a pipeline without a shadow program draws nothing instead: never passes the depth test, writes no colour.
	 */
	private CompiledRenderPipeline widen(RenderPipeline vanilla) {
		return widen(vanilla, shadow);
	}

	/** {@link #widen(RenderPipeline)}; {@code nothing}: never passes the depth test and writes no colour. */
	private CompiledRenderPipeline widen(RenderPipeline vanilla, boolean nothing) {
		ShaderSource shaders = VanillaShaderSources.get();
		if (shaders == null) {
			throw new IllegalStateException("vanilla shader sources not captured; cannot draw " + vanilla.getLocation() + " into the pack's targets");
		}
		Identifier id = Identifier.fromNamespaceAndPath("aetherium_shaders", (nothing ? "skip/" : "widened/") + vanilla.getLocation().getNamespace() + "/"
				+ vanilla.getLocation().getPath());
		RenderPipeline.Builder builder = RenderPipeline.builder()
				.withLocation(id)
				.withPrimitiveTopology(vanilla.getPrimitiveTopology())
				.withPolygonMode(vanilla.getPolygonMode())
				.withCull(vanilla.isCull())
				.withDepthStencilState(nothing
						? Optional.of(new DepthStencilState(CompareOp.NEVER_PASS, false))
						: Optional.ofNullable(vanilla.getDepthStencilState()))
				.withPushConstantSize(vanilla.pushConstantSize());
		for (Map.Entry<ShaderType, Identifier> shader : vanilla.getShaders().entrySet()) {
			if (shader.getKey() == ShaderType.VERTEX) {
				builder.withVertexShader(shader.getValue());
			} else {
				builder.withFragmentShader(shader.getValue());
			}
		}
		ShaderDefines defines = vanilla.getShaderDefines();
		defines.values().forEach((name, value) -> defineRaw(builder, name, value));
		defines.flags().forEach(builder::withShaderDefine);
		for (BindGroupLayout bindGroup : vanilla.getBindGroupLayouts()) {
			builder.withBindGroupLayout(bindGroup);
		}
		List<VertexFormat> bindings = WorldProgram.extendedBindings(vanilla.getVertexFormatBindings());
		for (int i = 0; i < bindings.size(); i++) {
			if (bindings.get(i) != null) {
				builder.withVertexBinding(i, bindings.get(i));
			}
		}
		ColorTargetState first = vanilla.getColorTargetStates().isEmpty() ? null : vanilla.getColorTargetStates().getFirst();
		int[] attachments = layout.attachments();
		for (int a = 0; a < attachments.length; a++) {
			GpuFormat format = layout.format(attachments[a]);
			if (a == 0 && first != null && !nothing && !WorldProgram.isIntegerFormat(format)) {
				builder.withColorTargetState(a, new ColorTargetState(first.blendFunction(), format, first.writeMask()));
			} else {
				builder.withColorTargetState(a, new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_NONE));
			}
		}
		CompiledRenderPipeline compiled = RenderSystem.getDevice().compilePipeline(builder.build(), shaders, Runnable::run).join().finishCompile();
		if (compiled == null) {
			throw new IllegalStateException("could not widen " + vanilla.getLocation() + " to the pack's gbuffer targets");
		}
		return compiled;
	}

	/** ShaderDefines keeps values as source text; the builder takes ints or floats. */
	private static void defineRaw(RenderPipeline.Builder builder, String name, String value) {
		try {
			builder.withShaderDefine(name, Integer.parseInt(value));
		} catch (NumberFormatException e) {
			builder.withShaderDefine(name, Float.parseFloat(value.replace("f", "").replace("F", "")));
		}
	}

	/**
	 * One queued program build, run once by whichever thread claims it first: the compiler worker in queue order, or
	 * the render thread as soon as a draw needs it.
	 */
	private static final class Job {
		private final AtomicBoolean claimed = new AtomicBoolean();
		private final Supplier<WorldProgram.@Nullable Built> work;
		final CompletableFuture<WorldProgram.@Nullable Built> result = new CompletableFuture<>();

		Job(Supplier<WorldProgram.@Nullable Built> work) {
			this.work = work;
		}

		void run() {
			if (claimed.compareAndSet(false, true)) {
				try {
					result.complete(work.get());
				} catch (Throwable t) {
					result.completeExceptionally(t);
				}
			}
		}

		/** Never runs if not started yet. */
		void cancel() {
			if (claimed.compareAndSet(false, true)) {
				result.complete(null);
			}
		}
	}

	@Override
	public void close() {
		// Queued builds skip their work; the one running (at most) is waited for and its GPU pipeline released, without
		// building the program or a widened fallback for it.
		closed = true;
		for (Job job : inFlight.values()) {
			job.cancel();
			WorldProgram.Built built;
			try {
				built = job.result.join();
			} catch (RuntimeException e) {
				DeviceLoss.rethrow(e);
				continue;
			}
			if (built != null) {
				CompiledRenderPipeline compiled = built.pending().finishCompile();
				if (compiled != null) {
					compiled.close();
				}
			}
		}
		inFlight.clear();
		byCompiled.values().forEach(WorldProgram::close);
		byCompiled.clear();
		widened.forEach(CompiledRenderPipeline::close);
		widened.clear();
		skipped.clear();
		resolved.clear();
		routes.clear();
	}
}
