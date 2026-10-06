package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.spacebod.aetherium.client.gpu.GeometryStages;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.chunks.ChunkMeshFormat;
import dev.spacebod.aetherium.shaders.compat.lod.LodUniforms;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTest;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency;
import dev.spacebod.aetherium.shaders.pbr.PbrKind;
import dev.spacebod.aetherium.shaders.pipeline.WorldRenderingPhase;
import dev.spacebod.aetherium.shaders.pipeline.programs.ShaderKey;
import dev.spacebod.aetherium.shaders.pipeline.transform.PatchShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.LoweredProgram;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.LoweringParameters;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.VanillaInterface;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import dev.spacebod.aetherium.shaders.uniforms.CapturedRenderingState;
import dev.spacebod.aetherium.shaders.uniforms.CommonUniforms;
import dev.spacebod.aetherium.shaders.uniforms.InternalUniforms;
import dev.spacebod.aetherium.shaders.uniforms.SystemTimeUniforms;
import dev.spacebod.aetherium.shaders.uniforms.custom.CustomUniforms;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * A pack world program (gbuffers_*) standing in for one vanilla pipeline: the pack source prepared by
 * {@link WorldInterface} for that pipeline's vertex format and uniform blocks, built as a vanilla {@link RenderPipeline}
 * with the vanilla pipeline's depth, cull, polygon mode and topology, and one colour target per gbuffer attachment
 * (targets the program does not write are masked off). Blending follows the vanilla pipeline unless the pack overrides
 * it ({@code blend.<program>}, {@code blend.<program>.<buffer>}).
 */
public final class WorldProgram implements AutoCloseable {
	private final String name;
	private final ShaderKey key;
	private final CompiledRenderPipeline compiled;
	private final BlockProgramUniforms uniforms;
	private final List<String> samplers;
	private final int[] drawBuffers;
	private final boolean writesDepth;
	/** Blend on the program's first output (for {@code blendFunc}). */
	private final Optional<BlendFunction> blend;

	private WorldProgram(String name, ShaderKey key, CompiledRenderPipeline compiled, BlockProgramUniforms uniforms, List<String> samplers,
			int[] drawBuffers, boolean writesDepth, Optional<BlendFunction> blend) {
		this.name = name;
		this.key = key;
		this.compiled = compiled;
		this.uniforms = uniforms;
		this.samplers = samplers;
		this.drawBuffers = drawBuffers;
		this.writesDepth = writesDepth;
		this.blend = blend;
		this.usesWatershadow = ShadowSampling.declaresWatershadow(samplers);
		this.reach = reachMask(samplers);
	}

	private final long reach;

	private final boolean usesWatershadow;

	/** Vanilla's depth state, except {@code rain.depth}: the pack's weather program writes depth. */
	private static @Nullable DepthStencilState depthState(ProgramSource source, ShaderKey key, RenderPipeline vanilla) {
		var state = vanilla.getDepthStencilState();
		if (state == null || key != ShaderKey.WEATHER || state.writeDepth() || !source.getParent().getPackDirectives().rainDepth()) {
			return state;
		}
		return new DepthStencilState(state.depthTest(), true, state.depthBiasScaleFactor(), state.depthBiasConstant());
	}

	/** Vanilla's reversed-depth state for the shadow pass's OpenGL depth: comparison mirrored, bias sign flipped. */
	private static @Nullable DepthStencilState glDepthState(
			@Nullable DepthStencilState reversed) {
		if (reversed == null) {
			return null;
		}
		var op = switch (reversed.depthTest()) {
			case GREATER_THAN -> CompareOp.LESS_THAN;
			case GREATER_THAN_OR_EQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
			case LESS_THAN -> CompareOp.GREATER_THAN;
			case LESS_THAN_OR_EQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
			default -> reversed.depthTest();
		};
		return new DepthStencilState(op, reversed.writeDepth(), -reversed.depthBiasScaleFactor(),
				-reversed.depthBiasConstant());
	}

	/** Whether the program samples {@code watershadow} (shadow samplers then split opaque/translucent), fixed at build. */
	public boolean usesWatershadow() {
		return usesWatershadow;
	}

	/** The colortex targets the program writes (its DRAWBUFFERS). */
	public Optional<BlendFunction> blend() {
		return blend;
	}

	public int[] drawBuffers() {
		return drawBuffers;
	}

	/** Whether its draws write depth. */
	public boolean writesDepth() {
		return writesDepth;
	}

	/** The part of a build that may run on any thread: transform, pipeline description, GPU compile. */
	public record Built(String label, ShaderKey key, CompiledRenderPipeline.Pending pending, UniformBlock.Layout layout, List<String> samplers,
			int[] drawBuffers, boolean writesDepth, Optional<BlendFunction> blend, List<String> notes, Map<PatchShaderType, String> stages) {
	}

	/** Builds the program for {@code vanilla} on the render thread; null (logged) when the program cannot be used there. */
	public static @Nullable WorldProgram create(ProgramSource source, ShaderKey key, RenderPipeline vanilla, TargetLayout layout,
			CustomUniforms customUniforms, boolean shadow) {
		Built built = compile(source, key, vanilla, layout, shadow);
		return built == null ? null : finish(built, customUniforms);
	}

	/**
	 * Vanilla's vertex bindings with entity and world-text formats extended: while a pack draws the world those draws are
	 * built in {@link EntityVertexFormats}' formats, so every pipeline drawing them reads that stride.
	 */
	static List<VertexFormat> extendedBindings(List<VertexFormat> bindings) {
		List<VertexFormat> out = new ArrayList<>(bindings.size());
		for (VertexFormat format : bindings) {
			out.add(EntityVertexFormats.forPipeline(format, true));
		}
		return out;
	}

	/** Transforms and compiles the program for {@code vanilla}; any thread. Null (logged) when it cannot be used there. */
	public static @Nullable Built compile(ProgramSource source, ShaderKey key, RenderPipeline vanilla, TargetLayout layout, boolean shadow) {
		String name = source.getName();
		String label = name + " for " + vanilla.getLocation();
		if (source.getVertexSource().isEmpty() || source.getFragmentSource().isEmpty()) {
			return null;
		}
		if (source.getTessControlSource().isPresent() || source.getTessEvalSource().isPresent()) {
			AetheriumShaders.logger.warn("[{}] tessellation stages are not available yet; vanilla shader used", label);
			return null;
		}
		int[] drawBuffers = source.getDirectives().getDrawBuffers();
		boolean lod = key.isLod();
		String geometry = source.getGeometrySource().orElse(null);
		if (geometry != null) {
			// A device without geometry stages still takes one that only hands each corner on: the lowering folds it away.
			String refused = lod ? "level-of-detail programs take no geometry stage"
					: !acceptsPrimitives(geometry, vanilla.getPrimitiveTopology()) ? "its input primitive does not match " + vanilla.getPrimitiveTopology() + " draws"
					: null;
			if (refused != null) {
				AetheriumShaders.logger.warn("[{}] has a geometry stage, but {}; vanilla shader used", label, refused);
				return null;
			}
		}
		// LOD programs read the LOD mod's vertex bytes under their own attribute names (WorldInterface.LOD_FORMAT).
		List<VertexFormat> formats = !lod ? extendedBindings(vanilla.getVertexFormatBindings())
				: List.of(key == ShaderKey.LOD_GENERIC ? WorldInterface.LOD_GENERIC_FORMAT : WorldInterface.LOD_FORMAT);
		String location = vanilla.getLocation().getPath();
		boolean clouds = location.contains("clouds");
		boolean vertexPulled = formats.isEmpty() || formats.getFirst() == null;
		if (vertexPulled && !clouds) {
			AetheriumShaders.logger.warn("[{}] pipeline has no vertex buffer at binding 0; vanilla shader used", label);
			return null;
		}
		VanillaInterface.TransformSource transformSource = transformSource(vanilla);
		if (shadow && transformSource == VanillaInterface.TransformSource.DYNAMIC) {
			transformSource = VanillaInterface.TransformSource.DYNAMIC_SHADOW;
		}
		AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(key.getAlphaTest());
		boolean lines = !vertexPulled && formats.getFirst().contains("LineWidth");
		long started = System.nanoTime();
		// Single-pass enchantment glint (26.3's GLINT define): the item's program adds vanilla's glint term itself.
		boolean singlePassGlint = !shadow && !key.isGlint() && vanilla.getShaderDefines().flags().contains("GLINT");
		BindGroupLayout.UniformDescription vanillaGlintSampler = BindGroupLayout.flattenUniforms(vanilla.getBindGroupLayouts()).stream()
				.filter(u -> u.name().equals("GlintSampler")).findFirst().orElse(null);
		boolean wantGlint = singlePassGlint && vanillaGlintSampler != null && !vanilla.getShaderDefines().flags().contains("GLINT_SPECIAL")
				&& drawBuffers.length > 0 && indexOf(layout.attachments(), drawBuffers[0]) >= 0;
		var glintParameters = !singlePassGlint ? LoweringParameters.Glint.NONE
				: new LoweringParameters.Glint(true,
						wantGlint ? indexOf(layout.attachments(), drawBuffers[0]) : -1);
		WorldInterface.Prepared prepared;
		try {
			prepared = key == ShaderKey.LOD_GENERIC
					? WorldInterface.prepareLodGeneric(name, source.getVertexSource().get(), source.getFragmentSource().get(), drawBuffers, layout.attachments())
					: lod
					? WorldInterface.prepareLod(name, source.getVertexSource().get(), source.getFragmentSource().get(), drawBuffers, layout.attachments(), shadow)
					: WorldInterface.prepare(name, source.getVertexSource().get(), geometry, source.getFragmentSource().get(), formats,
							transformSource, alpha, key.shouldIgnoreLightmap(), lines, location.contains("clouds"), key.isGlint(), key.isText(),
							drawBuffers, layout.attachments(), shadow, glintParameters, vanilla.getShaderDefines().flags().contains("IS_GRAYSCALE"));
		} catch (RuntimeException e) {
			AetheriumShaders.logger.error("[{}] could not be transformed; vanilla shader used", label, e);
			return null;
		}
		WorldInterface.dump(label + " " + key, prepared.stages());
		if (prepared.stages().containsKey(PatchShaderType.GEOMETRY) && !GeometryStages.enabled()) {
			AetheriumShaders.logger.warn("[{}] has a geometry stage that does more than hand each corner on, and the device has no geometry "
					+ "shader support; vanilla shader used", label);
			return null;
		}

		Map<String, BindGroupLayout.UniformDescription> vanillaUniforms = new HashMap<>();
		for (BindGroupLayout.UniformDescription u : BindGroupLayout.flattenUniforms(vanilla.getBindGroupLayouts())) {
			vanillaUniforms.put(u.name(), u);
		}
		BindGroupLayout.Builder bindings = BindGroupLayout.builder();
		List<String> samplers = new ArrayList<>();
		for (Map.Entry<String, UniformType> r : prepared.resources().entrySet()) {
			BindGroupLayout.UniformDescription known = vanillaUniforms.get(r.getKey());
			if (known != null) {
				if (known.gpuFormat() != null) {
					bindings.withUniform(known.name(), known.type(), known.gpuFormat());
				} else {
					bindings.withUniform(known.name(), known.type());
				}
			} else if (r.getValue() == UniformType.TEXEL_BUFFER) {
				AetheriumShaders.logger.warn("[{}] texel buffer {} is not provided; vanilla shader used", label, r.getKey());
				return null;
			} else {
				bindings.withUniform(r.getKey(), r.getValue());
				if (r.getValue() == UniformType.COMBINED_IMAGE_SAMPLER) {
					samplers.add(r.getKey());
				}
			}
		}

		Identifier id = Identifier.fromNamespaceAndPath("aetherium_shaders",
				((shadow ? "shadow/" : "world/") + name + "/" + location).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_"));
		RenderPipeline.Builder builder = RenderPipeline.builder()
				.withLocation(id)
				.withVertexShader(id)
				.withFragmentShader(id)
				.withBindGroupLayout(bindings.build())
				.withPrimitiveTopology(vanilla.getPrimitiveTopology())
				.withPolygonMode(vanilla.getPolygonMode())
				// The shadow pass draws without back-face culling (a hill's far side still casts its shadow).
				// Transparent LODs (water) draw both faces, as packs written for them expect.
				.withCull(!shadow && vanilla.isCull() && key != ShaderKey.LOD_WATER)
				.withDepthStencilState(Optional.ofNullable(shadow ? glDepthState(vanilla.getDepthStencilState()) : depthState(source, key, vanilla)));
		if (vanilla.pushConstantSize() > 0) {
			// The draw loop pushes its constants (the chunk renderer's region offset) whichever program is bound.
			builder.withPushConstantSize(vanilla.pushConstantSize());
		}
		for (int i = 0; i < formats.size(); i++) {
			if (formats.get(i) != null) {
				builder.withVertexBinding(i, WorldInterface.renamed(formats.get(i)));
			}
		}
		ColorTargetState vanillaTarget = vanilla.getColorTargetStates().isEmpty() ? null : vanilla.getColorTargetStates().getFirst();
		Optional<BlendFunction> vanillaBlend = vanillaTarget == null ? Optional.empty() : vanillaTarget.blendFunction();
		int writeMask = vanillaTarget == null ? ColorTargetState.WRITE_ALL : vanillaTarget.writeMask();
		int[] attachments = layout.attachments();
		ColorTargetState[] targets = PipelineState.colorTargets(attachments, layout::format, drawBuffers, source.getDirectives(), vanillaBlend, writeMask);
		for (int a = 0; a < targets.length; a++) {
			builder.withColorTargetState(a, targets[a]);
		}
		int first = drawBuffers.length == 0 ? -1 : indexOf(attachments, drawBuffers[0]);
		Optional<BlendFunction> blend = first < 0 || targets[first] == null ? Optional.empty() : targets[first].blendFunction();
		RenderPipeline pipeline = builder.build();

		Map<PatchShaderType, String> stages = new EnumMap<>(prepared.stages());
		// Never `invariant gl_Position` on one program alone: it changes how that program computes positions, and coplanar
		// quads drawn by different programs (a grass block's side and its cutout overlay) then z-fight.
		ShaderSource shaderSource = new ShaderSource() {
			@Override
			public @Nullable String getShader(Identifier shaderId, ShaderType type) {
				return type == ShaderType.VERTEX ? stages.get(PatchShaderType.VERTEX) : stages.get(PatchShaderType.FRAGMENT);
			}

			@Override
			public ShaderSource.@Nullable CachedIncludeSource getInclude(Identifier includeId) {
				return null;
			}

			@Override
			public void close() {
			}
		};
		long transformed = System.nanoTime();
		String geometryStage = stages.get(PatchShaderType.GEOMETRY);
		CompiledRenderPipeline.Pending pending = geometryStage == null
				? RenderSystem.getDevice().compilePipeline(pipeline, shaderSource, Runnable::run).join()
				: GeometryStages.compiling(id, geometryStage,
						() -> RenderSystem.getDevice().compilePipeline(pipeline, shaderSource, Runnable::run).join());
		AetheriumShaders.logger.debug("[{}] built on {}: transform {} ms, compile {} ms", label, Thread.currentThread().getName(),
				(transformed - started) / 1_000_000, (System.nanoTime() - transformed) / 1_000_000);
		var depthState = shadow ? glDepthState(vanilla.getDepthStencilState()) : depthState(source, key, vanilla);
		boolean writesDepth = depthState != null && depthState.writeDepth();
		return new Built(label, key, pending, prepared.layout(), samplers, drawBuffers, writesDepth, blend, prepared.notes(), stages);
	}

	private static final Pattern GEOMETRY_INPUT = Pattern.compile(
			"layout\\s*\\(\\s*(points|lines_adjacency|lines|triangles_adjacency|triangles)\\s*(?:,[^)]*)?\\)\\s*in\\s*;");

	/**
	 * Whether a geometry stage's input primitive ({@code layout(triangles) in;}) is what {@code topology} draws: a
	 * mismatch is an invalid pipeline. Vanilla's lines are drawn as triangles, quads as indexed triangles.
	 */
	static boolean acceptsPrimitives(String geometry, PrimitiveTopology topology) {
		Matcher m = GEOMETRY_INPUT.matcher(geometry);
		if (!m.find()) {
			return false;
		}
		return switch (m.group(1)) {
			case "triangles" -> switch (topology) {
				case TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN, QUADS, LINES -> true;
				default -> false;
			};
			case "lines" -> topology == PrimitiveTopology.DEBUG_LINES
					|| topology == PrimitiveTopology.DEBUG_LINE_STRIP;
			case "points" -> topology == PrimitiveTopology.POINTS;
			default -> false;
		};
	}

	/** Render thread: finishes the GPU pipeline and attaches the program's uniforms. Null (logged) when the pipeline failed. */
	public static @Nullable WorldProgram finish(Built built, CustomUniforms customUniforms) {
		String label = built.label();
		CompiledRenderPipeline compiled = built.pending().finishCompile();
		if (compiled == null) {
			AetheriumShaders.logger.error("[{}] vanilla could not build the pipeline (see the error above); vanilla shader used", label);
			FailedProgramDump.write(label, built.stages());
			return null;
		}
		BlockProgramUniforms uniforms = new BlockProgramUniforms(label, built.layout());
		CommonUniforms.addDynamicUniforms(uniforms, built.key().getFogMode());
		uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "aeth_ShadowFromView",
				() -> ShaderPackEngine.get().shadowFromView());
		InternalUniforms.addTerrainMatrices(uniforms, built.key().isShadow());
		boolean shadowView = built.key().isShadow();
		uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "aeth_TerrainModelView",
				() -> shadowView ? ShaderPackEngine.get().shadowModelView() : CapturedRenderingState.INSTANCE.getGbufferModelView());
		if (built.key().isLod()) {
			LodUniforms.add(uniforms, built.key().isShadow());
		}
		customUniforms.assignTo(uniforms);
		WorldProgram program = new WorldProgram(label, built.key(), compiled, uniforms, built.samplers(), built.drawBuffers(), built.writesDepth(), built.blend());
		customUniforms.mapholderToPass(uniforms, program);
		List<String> missing = uniforms.unprovided();
		if (!missing.isEmpty()) {
			AetheriumShaders.logger.debug("[{}] uniforms without a provider (read as 0): {}", label, missing);
		}
		if (!built.notes().isEmpty()) {
			AetheriumShaders.logger.debug("[{}] {}", label, built.notes());
		}
		return program;
	}

	/**
	 * Terrain pipelines read TerrainUniform + ChunkSection (or the ChunkPosition attribute), the chunk renderer's read its
	 * region meshes ({@link ChunkMeshFormat}); everything else DynamicTransforms.
	 */
	static VanillaInterface.TransformSource transformSource(RenderPipeline vanilla) {
		VertexFormat mesh = vanilla.getVertexFormatBindings().isEmpty() ? null : vanilla.getVertexFormatBindings().getFirst();
		if (mesh != null && mesh.contains(ChunkMeshFormat.POSITION)) {
			return VanillaInterface.TransformSource.TERRAIN_REGION;
		}
		boolean terrain = BindGroupLayout.flattenUniforms(vanilla.getBindGroupLayouts()).stream().anyMatch(u -> u.name().equals("TerrainUniform"));
		if (!terrain) {
			return VanillaInterface.TransformSource.DYNAMIC;
		}
		VertexFormat instance = vanilla.getVertexFormatBindings().size() > 1 ? vanilla.getVertexFormatBindings().get(1) : null;
		return instance != null && instance.contains("ChunkPosition") ? VanillaInterface.TransformSource.TERRAIN_MULTIDRAW
				: VanillaInterface.TransformSource.TERRAIN;
	}

	/** The phase packs read as {@code renderStage} while this program draws. */
	public WorldRenderingPhase phase() {
		return switch (key) {
			case SKY_BASIC, SKY_BASIC_COLOR -> WorldRenderingPhase.SKY;
			// The sun and moon get SUN/MOON from the sky renderer's override; drawn on its own (the End sky) it is CUSTOM_SKY.
			case SKY_TEXTURED -> WorldRenderingPhase.CUSTOM_SKY;
			case CLOUDS -> WorldRenderingPhase.CLOUDS;
			case TERRAIN_SOLID -> WorldRenderingPhase.TERRAIN_SOLID;
			case TERRAIN_CUTOUT -> WorldRenderingPhase.TERRAIN_CUTOUT;
			case TERRAIN_TRANSLUCENT, LOD_WATER -> WorldRenderingPhase.TERRAIN_TRANSLUCENT;
			case LOD_TERRAIN, SHADOW_LOD -> WorldRenderingPhase.TERRAIN_SOLID;
			case BLOCK_ENTITY, BLOCK_ENTITY_DIFFUSE, BE_TRANSLUCENT, TEXT_BE, MOVING_BLOCK, BEACON -> WorldRenderingPhase.BLOCK_ENTITIES;
			case PARTICLES, PARTICLES_TRANS -> WorldRenderingPhase.PARTICLES;
			case WEATHER -> WorldRenderingPhase.RAIN_SNOW;
			// The block-breaking overlay has no stage of its own in the pack format.
			case CRUMBLING -> WorldRenderingPhase.NONE;
			case LINES -> WorldRenderingPhase.OUTLINE;
			case HAND_CUTOUT, HAND_CUTOUT_DIFFUSE, HAND_TEXT -> WorldRenderingPhase.HAND_SOLID;
			case HAND_TRANSLUCENT, HAND_WATER_DIFFUSE, HAND_TEXT_TRANSLUCENT -> WorldRenderingPhase.HAND_TRANSLUCENT;
			case TEXTURED -> WorldRenderingPhase.WORLD_BORDER;
			default -> WorldRenderingPhase.ENTITIES;
		};
	}

	public String name() {
		return name;
	}

	/** A shadow program (drawn into the shadow map). */
	public boolean isShadow() {
		return key.isShadow();
	}

	public CompiledRenderPipeline compiled() {
		return compiled;
	}

	public BlockProgramUniforms uniforms() {
		return uniforms;
	}

	/** Samplers the engine binds (the pack's own: colortex, depthtex, noisetex, shadow maps ...); vanilla binds Sampler0/1/2. */
	public List<String> samplers() {
		return samplers;
	}

	/**
	 * The program's uniform block after updating it for this draw. Custom uniforms are evaluated once per frame, so with
	 * {@code once} set they are pushed only on the frame's first bind (their offsets are written by nothing else).
	 * Returns whether anything was written into the block (false: it holds what the last update left).
	 */
	public boolean updateUniforms(CustomUniforms customUniforms, boolean once) {
		boolean wrote = uniforms.update();
		long frame = SystemTimeUniforms.COUNTER.frameId();
		if (once && frame == pushedFrame) {
			return wrote;
		}
		pushedFrame = frame;
		UniformBlockBuffer.begin(uniforms.buffer());
		try {
			customUniforms.push(this);
		} finally {
			UniformBlockBuffer.end();
		}
		return true;
	}

	private long pushedFrame = Long.MIN_VALUE;
	private @Nullable ByteBuffer lastBytes;
	private @Nullable GpuBufferSlice lastSlice;
	private int lastGeneration = Integer.MIN_VALUE;

	/**
	 * The slice holding this program's current block: with {@code reuse}, the one uploaded earlier this frame when the
	 * bytes are unchanged (not compared when {@code written} is false: nothing was written since), else a new upload.
	 */
	GpuBufferSlice upload(UniformArena arena, boolean reuse, boolean written) {
		ByteBuffer bytes = uniforms.buffer().duplicate().clear();
		if (reuse && lastSlice != null && lastGeneration == arena.generation() && (!written || bytes.equals(lastBytes))) {
			return lastSlice;
		}
		lastSlice = arena.upload(bytes);
		lastGeneration = arena.generation();
		if (lastBytes == null) {
			lastBytes = ByteBuffer.allocateDirect(bytes.capacity()).order(bytes.order());
		}
		lastBytes.clear();
		lastBytes.put(bytes.duplicate());
		lastBytes.flip();
		return lastSlice;
	}

	/** Bit of {@link #reach} for {@code depthtex0} (bits 0 to 31 are colortex0 to colortex31). */
	public static final long REACH_DEPTH = 1L << 32;

	/**
	 * The colour targets (bit N: colortexN, by any of its names) and {@code depthtex0} ({@link #REACH_DEPTH}) among
	 * {@code samplers}, the names a program binds; {@code texture} (a world program's albedo) is not a target.
	 */
	public static long reachMask(Collection<String> samplers) {
		long mask = 0;
		for (String name : LoweredProgram.reachedTargets(samplers)) {
			if (name.equals("depthtex0") || name.equals("gdepthtex")) {
				mask |= REACH_DEPTH;
				continue;
			}
			int target = name.equals("texture") ? -1 : GbufferLayout.targetIndex(name);
			if (target >= 0 && target < 32) {
				mask |= 1L << target;
			}
		}
		return mask;
	}

	/** What the program's samplers can read among the gbuffer attachments ({@link #reachMask}). */
	public long reach() {
		return reach;
	}

	/**
	 * Sampler state kept across binds ({@code shaders.settled_bindings}): how each sampler resolves, the binding-table
	 * slot it occupies in a render pass, and its views as last resolved (for the render pass {@link #pass}). Made by the
	 * engine on the program's first bind, for one sampler table.
	 */
	static final class Settled {
		final Object table;
		/** Samplers resolved per pass, in binding order: {@code slots[i]} >= 0 are tracked in the pass's table. */
		final SamplerTable.Plan[] plans;
		final String[] names;
		/** Binding-table slot per sampler; -1: bound again on every bind (vanilla may bind that name too). */
		final int[] slots;
		final GpuTextureView[] views;
		final GpuSampler[] samplers;
		/** The material maps the program reads that follow the draw's albedo (normals, specular), and their slots. */
		final PbrKind[] materials;
		final int[] materialSlots;
		int pass = -1;

		Settled(Object table, SamplerTable.Plan[] plans, String[] names, int[] slots, PbrKind[] materials, int[] materialSlots) {
			this.table = table;
			this.plans = plans;
			this.names = names;
			this.slots = slots;
			this.views = new GpuTextureView[plans.length];
			this.samplers = new GpuSampler[plans.length];
			this.materials = materials;
			this.materialSlots = materialSlots;
		}
	}

	@Nullable Settled settled;

	private static int indexOf(int[] array, int value) {
		for (int i = 0; i < array.length; i++) {
			if (array[i] == value) {
				return i;
			}
		}
		return -1;
	}

	/** Per {@link GpuFormat} ordinal: an unsigned or signed integer format. */
	private static final boolean[] INTEGER_FORMATS = integerFormats();

	private static boolean[] integerFormats() {
		GpuFormat[] formats = GpuFormat.values();
		boolean[] integer = new boolean[formats.length];
		for (GpuFormat format : formats) {
			String n = format.name();
			integer[format.ordinal()] = n.endsWith("_UINT") || n.endsWith("_SINT");
		}
		return integer;
	}

	static boolean isIntegerFormat(GpuFormat format) {
		return INTEGER_FORMATS[format.ordinal()];
	}

	@Override
	public void close() {
		compiled.close();
	}
}
