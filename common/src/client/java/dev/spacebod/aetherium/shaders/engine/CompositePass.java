package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.framebuffer.ViewportData;
import dev.spacebod.aetherium.shaders.gl.state.FogMode;
import dev.spacebod.aetherium.shaders.pipeline.transform.PatchShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.TransformPatcher;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.TargetImages;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import dev.spacebod.aetherium.shaders.uniforms.CommonUniforms;
import dev.spacebod.aetherium.shaders.uniforms.custom.CustomUniforms;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * One full-screen pack program (composite, deferred, final): the pack source through the transformer and the Vulkan
 * steps, built as an ordinary vanilla {@link RenderPipeline} whose bind-group layout is the {@code AetheriumUniforms}
 * block plus every sampler the program declares, compiled by vanilla (SPIR-V for Vulkan). Fragment output {@code i}
 * goes to colour target {@code drawBuffers[i]}.
 */
public final class CompositePass implements AutoCloseable {
	private final String name;
	private final int[] drawBuffers;
	/** The final pass: writes vanilla's main colour target instead of the colortex draw buffers. */
	private final boolean writesMain;
	private final Set<String> samplers;
	private final BlockProgramUniforms uniforms;
	private final RenderPipeline pipeline;
	private final CompiledRenderPipeline compiled;
	private final List<String> unsupported;
	private final TextureStage stage;
	/** Targets whose mip chain is generated before this pass runs ({@code colortexNMipmapEnabled}). */
	private Set<Integer> mipmapped = Set.of();
	/** {@code scale.<pass>}: the viewport the pass draws into, as a fraction of its targets (full size by default). */
	private ViewportData viewport = ViewportData.defaultValue();
	/** Draw buffers the pass blends into (it keeps part of what they held). */
	private final Set<Integer> blended = new TreeSet<>();
	/** The fragment stage can {@code discard}: texels it discards keep what the target held. */
	private boolean discards;

	private CompositePass(String name, TextureStage stage, int[] drawBuffers, boolean writesMain, Set<String> samplers, BlockProgramUniforms uniforms,
			RenderPipeline pipeline, CompiledRenderPipeline compiled, List<String> unsupported) {
		this.name = name;
		this.stage = stage;
		this.drawBuffers = drawBuffers;
		this.writesMain = writesMain;
		this.samplers = samplers;
		this.uniforms = uniforms;
		this.pipeline = pipeline;
		this.compiled = compiled;
		this.unsupported = unsupported;
	}

	/**
	 * Transforms, compiles and builds one program; null (logged) when it cannot be used. Fragment output i goes to target
	 * {@code drawBuffers[i]} (colortex, shadowcolor for shadowcomp passes, or vanilla's main target for the final pass,
	 * {@code writesMain}); {@code format} gives each target's format.
	 */
	public static @Nullable CompositePass create(ProgramSource source, TextureStage stage, int[] drawBuffers, boolean writesMain,
			IntFunction<GpuFormat> format, CustomUniforms customUniforms) {
		Prepared prepared = prepare(source, stage, drawBuffers, writesMain, format);
		return prepared == null ? null : finish(prepared, customUniforms);
	}

	/**
	 * A program transformed and compiled ({@link #prepare}), its pipeline not yet finished: what a pack load does off the
	 * render thread. {@link #finish} makes the pass; {@link #discard} frees one that is not used.
	 */
	public record Prepared(ProgramSource source, TextureStage stage, int[] drawBuffers, boolean writesMain, Lowered lowered, RenderPipeline pipeline,
			CompiledRenderPipeline.Pending pending, Set<Integer> blended) {
		public void discard() {
			CompiledRenderPipeline compiled = pending.finishCompile();
			if (compiled != null) {
				compiled.close();
			}
		}
	}

	/** The thread-safe half of {@link #create}: transform and pipeline compile (any thread); null (logged) when skipped. */
	public static @Nullable Prepared prepare(ProgramSource source, TextureStage stage, int[] drawBuffers, boolean writesMain, IntFunction<GpuFormat> format) {
		String name = source.getName();
		if (source.getVertexSource().isEmpty() || source.getFragmentSource().isEmpty()) {
			return null;
		}
		if (source.getGeometrySource().isPresent()) {
			AetheriumShaders.logger.warn("[{}] geometry stage in a full-screen pass is not supported yet; pass skipped", name);
			return null;
		}
		Lowered lowered = lower(name, source.getVertexSource().get(), source.getFragmentSource().get(), stage);
		String vertex = lowered.vertex();
		String fragment = lowered.fragment();
		UniformBlock.Layout blockLayout = lowered.layout();
		Set<String> samplers = lowered.samplers();
		List<String> unsupported = lowered.unsupported();
		if (!unsupported.isEmpty()) {
			AetheriumShaders.logger.warn("[{}] uses {} which Aetherium Shaders does not provide yet; pass skipped", name, unsupported);
			return null;
		}

		Identifier id = Identifier.fromNamespaceAndPath("aetherium_shaders", name.toLowerCase(Locale.ROOT));
		BindGroupLayout.Builder layout = BindGroupLayout.builder();
		if (!blockLayout.members().isEmpty()) {
			layout.withUniform(UniformBlock.BLOCK_NAME, UniformType.UNIFORM_BUFFER);
		}
		for (String sampler : samplers) {
			layout.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
		}
		RenderPipeline.Builder builder = RenderPipeline.builder()
				.withLocation(id)
				.withVertexShader(id)
				.withFragmentShader(id)
				.withBindGroupLayout(layout.build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withDepthStencilState(Optional.empty())
				.withCull(false);
		// blend.<pass> / blend.<pass>.<buffer>: no blending unless the pack asks for it.
		ColorTargetState[] colorTargets = PipelineState.colorTargets(drawBuffers, format, drawBuffers, source.getDirectives(), Optional.empty(),
				ColorTargetState.WRITE_ALL);
		Set<Integer> blended = new TreeSet<>();
		for (int i = 0; i < colorTargets.length; i++) {
			builder.withColorTargetState(i, colorTargets[i]);
			if (colorTargets[i].blendFunction().isPresent()) {
				blended.add(drawBuffers[i]);
			}
		}
		RenderPipeline pipeline = builder.build();

		ShaderSource shaderSource = new ShaderSource() {
			@Override
			public @Nullable String getShader(Identifier shaderId, ShaderType type) {
				return type == ShaderType.VERTEX ? vertex : type == ShaderType.FRAGMENT ? fragment : null;
			}

			@Override
			public ShaderSource.@Nullable CachedIncludeSource getInclude(Identifier includeId) {
				return null;
			}

			@Override
			public void close() {
			}
		};
		CompiledRenderPipeline.Pending pending = RenderSystem.getDevice().compilePipeline(pipeline, shaderSource, Runnable::run).join();
		return new Prepared(source, stage, drawBuffers, writesMain, lowered, pipeline, pending, Set.copyOf(blended));
	}

	/** The render-thread half of {@link #create}: the pipeline and the uniforms; null (logged) when vanilla could not build it. */
	public static @Nullable CompositePass finish(Prepared prepared, CustomUniforms customUniforms) {
		ProgramSource source = prepared.source();
		String name = source.getName();
		String vertex = prepared.lowered().vertex();
		String fragment = prepared.lowered().fragment();
		CompiledRenderPipeline compiled = prepared.pending().finishCompile();
		if (compiled == null) {
			AetheriumShaders.logger.error("[{}] vanilla could not build the pipeline (see the error above); pass skipped", name);
			FailedProgramDump.write(name, Map.of(PatchShaderType.VERTEX, vertex, PatchShaderType.FRAGMENT, fragment));
			return null;
		}

		BlockProgramUniforms uniforms = new BlockProgramUniforms(name, prepared.lowered().layout());
		CommonUniforms.addDynamicUniforms(uniforms, FogMode.OFF);
		customUniforms.assignTo(uniforms);
		boolean writesMain = prepared.writesMain();
		CompositePass pass = new CompositePass(name, prepared.stage(), prepared.drawBuffers(), writesMain, prepared.lowered().samplers(), uniforms,
				prepared.pipeline(), compiled, prepared.lowered().unsupported());
		pass.mipmapped = Set.copyOf(source.getDirectives().getMipmappedBuffers());
		Matcher image = TargetImages.matcher(vertex + "\n" + fragment);
		while (image.find()) {
			(TargetImages.shadow(image) ? pass.shadowColourImages : pass.colourImages).add(TargetImages.index(image));
		}
		pass.viewport = writesMain ? ViewportData.defaultValue() : source.getDirectives().getViewportScale();
		pass.blended.addAll(prepared.blended());
		pass.discards = DISCARD.matcher(fragment).find();
		customUniforms.mapholderToPass(uniforms, pass);
		List<String> missing = uniforms.unprovided();
		if (!missing.isEmpty()) {
			AetheriumShaders.logger.debug("[{}] uniforms without a provider (read as 0): {}", name, missing);
		}
		return pass;
	}

	/** A full-screen program after the transform and the Vulkan lowering: what vanilla compiles, and its interface. */
	public record Lowered(String vertex, String fragment, UniformBlock.Layout layout, Set<String> samplers, List<String> unsupported) {
	}

	/** Transforms and lowers one full-screen program (no game needed: the compile harness uses it too). */
	public static Lowered lower(String name, String vertexSource, String fragmentSource, TextureStage stage) {
		Set<String> samplers = new LinkedHashSet<>();
		List<String> unsupported = new ArrayList<>();
		var program = TransformPatcher.patchCompositeLowered(name, vertexSource, null, fragmentSource, stage,
				TexturePatching.current(), Lowering.fullscreen());
		program.samplerTypes().forEach((sampler, type) -> {
			// 2D float and integer samplers (integer targets are bound nearest, SamplerTable) and comparison samplers on the
			// shadow depth maps; other kinds need engine support.
			if (!type.matches("[iu]?sampler2D|sampler2DShadow")) {
				unsupported.add(type + " " + sampler);
			}
			samplers.add(sampler);
		});
		return new Lowered(program.stages().get(PatchShaderType.VERTEX), program.stages().get(PatchShaderType.FRAGMENT), program.layout(), samplers,
				unsupported);
	}

	public String name() {
		return name;
	}

	/** The custom-texture stage the pass belongs to. */
	public TextureStage stage() {
		return stage;
	}

	/** {@code scale.<pass>}: scale and origin of the viewport, as fractions of the targets' size. */
	public ViewportData viewport() {
		return viewport;
	}

	/** Whether {@code scale.<pass>} draws into part of the targets rather than all of them. */
	public boolean scaled() {
		return viewport.scale() != 1.0f || viewport.viewportX() != 0.0f || viewport.viewportY() != 0.0f;
	}

	public Set<Integer> mipmappedBuffers() {
		return mipmapped;
	}

	public int[] drawBuffers() {
		return drawBuffers;
	}

	public boolean writesMain() {
		return writesMain;
	}

	/**
	 * Whether the pass writes every texel of {@code target}: it is a draw buffer, the viewport is the whole target, the
	 * pass does not blend into it and its fragment stage cannot {@code discard}.
	 */
	public boolean writesWhole(int target) {
		return PipelineState.indexOf(drawBuffers, target) >= 0 && !scaled() && !blended.contains(target) && !discards;
	}

	public Set<String> samplers() {
		return samplers;
	}

	private static final Pattern DISCARD = Pattern.compile("\\bdiscard\\b");
	/** The colour / shadow colour targets the pass binds as images (set 1, {@link PackStorage#targetImageSet}). */
	private final Set<Integer> colourImages = new TreeSet<>();
	private final Set<Integer> shadowColourImages = new TreeSet<>();

	public Set<Integer> colourImages() {
		return colourImages;
	}

	public Set<Integer> shadowColourImages() {
		return shadowColourImages;
	}

	public BlockProgramUniforms uniforms() {
		return uniforms;
	}

	public CompiledRenderPipeline compiled() {
		return compiled;
	}

	/** Updates the program's uniform block for this frame, then writes the custom uniforms into it. */
	public void updateUniforms(CustomUniforms customUniforms) {
		uniforms.update();
		UniformBlockBuffer.begin(uniforms.buffer());
		try {
			customUniforms.push(this);
		} finally {
			UniformBlockBuffer.end();
		}
	}

	/**
	 * What the engine keeps for this pass across frames ({@code shaders.frozen_fullscreen}): how each sampler and image
	 * target resolves, the label, the render area of the last size, and the uniform block slice uploaded with its stage.
	 */
	static final class Frozen {
		final Object table;
		final String[] names;
		final SamplerTable.Plan[] plans;
		final GpuTextureView[] views;
		final GpuSampler[] samplers;
		final int[] colourImageTargets;
		final SamplerTable.Plan[] colourImagePlans;
		final int[] shadowImageTargets;
		final SamplerTable.Plan[] shadowImagePlans;
		final Map<Integer, Long> colourImageViews = new HashMap<>();
		final Map<Integer, Long> shadowImageViews = new HashMap<>();
		final Supplier<String> label;
		int areaWidth = -1;
		int areaHeight = -1;
		RenderPass.@Nullable RenderArea area;
		/** The block uploaded with its stage ({@link #blockStage}: which upload). */
		@Nullable GpuBufferSlice block;
		int blockStage = -1;

		Frozen(Object table, String[] names, SamplerTable.Plan[] plans, int[] colourImageTargets, SamplerTable.Plan[] colourImagePlans,
				int[] shadowImageTargets, SamplerTable.Plan[] shadowImagePlans, String label) {
			this.table = table;
			this.names = names;
			this.plans = plans;
			this.views = new GpuTextureView[plans.length];
			this.samplers = new GpuSampler[plans.length];
			this.colourImageTargets = colourImageTargets;
			this.colourImagePlans = colourImagePlans;
			this.shadowImageTargets = shadowImageTargets;
			this.shadowImagePlans = shadowImagePlans;
			this.label = () -> label;
		}
	}

	@Nullable Frozen frozen;

	@Override
	public void close() {
		compiled.close();
	}
}
