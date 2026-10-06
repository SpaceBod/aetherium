package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * The pack's depth textures: {@code depthtex0} (everything), {@code depthtex1} (no translucents), {@code depthtex2} (no
 * hand). Vanilla 26.3 renders reversed zero-to-one depth (1 = near); packs expect OpenGL window depth (1 = far), which
 * is exactly {@code 1 - d} for the same projection, so each copy is converted by a full-screen pass into an R32F
 * texture (packs read the value from {@code .r}). The LOD depth textures are converted the same way from the LOD mod's
 * depth.
 */
public final class DepthTargets implements AutoCloseable {
	public static final int ALL = 0;
	public static final int NO_TRANSLUCENTS = 1;
	public static final int NO_HAND = 2;
	/** Level-of-detail terrain's own depth ({@code dhDepthTex0}: with transparent LODs; {@code dhDepthTex1}: without). */
	public static final int LOD_ALL = 3;
	public static final int LOD_NO_TRANSLUCENTS = 4;
	private static final int COUNT = 5;
	private static final String VERTEX = """
			#version 330 core
			#extension GL_ARB_separate_shader_objects : enable
			layout(location = 0) in vec3 Position;
			layout(location = 1) in vec2 UV0;
			layout(location = 0) out vec2 uv;
			void main() {
			    uv = UV0;
			    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
			}
			""";
	private static final String FRAGMENT = """
			#version 330 core
			#extension GL_ARB_separate_shader_objects : enable
			uniform sampler2D Depth;
			layout(location = 0) in vec2 uv;
			layout(location = 0) out vec4 depth;
			void main() {
			    depth = vec4(1.0 - texture(Depth, uv).r, 0.0, 0.0, 1.0);
			}
			""";
	private final GpuTexture[] textures = new GpuTexture[COUNT];
	private final GpuTextureView[] views = new GpuTextureView[COUNT];
	/** Which texture each depthtex currently reads: itself, or another one it equals (no copy made). */
	private final int[] source = {ALL, NO_TRANSLUCENTS, NO_HAND, LOD_ALL, LOD_NO_TRANSLUCENTS};
	private @Nullable CompiledRenderPipeline pipeline;
	private int width;
	private int height;

	public void resize(int width, int height) {
		if (width == this.width && height == this.height && textures[0] != null) {
			return;
		}
		// The LOD depth textures exist only once something captured into them (the LOD mod renders under the pack).
		boolean[] had = new boolean[COUNT];
		for (int i = 0; i < COUNT; i++) {
			had[i] = textures[i] != null;
		}
		closeTextures();
		this.width = width;
		this.height = height;
		for (int i = 0; i < COUNT; i++) {
			if (i < LOD_ALL || had[i]) {
				create(i);
			}
		}
	}

	private void create(int index) {
		textures[index] = RenderSystem.getDevice().createTexture(() -> "Aetherium Shaders depthtex" + index,
				GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
				GpuFormat.R32_FLOAT, width, height, 1, 1);
		views[index] = RenderSystem.getDevice().createTextureView(textures[index]);
	}

	/** The texture of {@code depthtex<which>}, created on first use (the LOD ones are optional). */
	private void ensure(int which) {
		if (textures[which] == null && width > 0) {
			create(which);
		}
	}

	public GpuTextureView view(int which) {
		ensure(source[which]);
		return views[source[which]];
	}

	/** Start of a frame: every depthtex reads its own texture again. */
	public void beginFrame() {
		for (int i = 0; i < COUNT; i++) {
			source[i] = i;
		}
	}

	/** {@code depthtex<which>} reads {@code depthtex<to>} until it is captured again: a copy that costs nothing. */
	public void alias(int which, int to) {
		source[which] = source[to];
	}

	/** {@code depthtex<which>} := far (1.0), when vanilla's depth is known to be freshly cleared. Not inside a render pass. */
	public void clearToFar(int which) {
		ensure(which);
		RenderSystem.getDevice().createCommandEncoder().clearColorTexture(textures[which], FAR);
		source[which] = which;
	}

	/**
	 * {@link #clearToFar} as a load-op clear the caller records with others ({@link LoadOpClears#flush}): the view and
	 * the colour. {@code depthtex<which>} reads its own texture from here.
	 */
	LoadOpClears.Pending farClear(int which) {
		ensure(which);
		source[which] = which;
		return new LoadOpClears.Pending(views[which], FAR);
	}

	private static final Vector4f FAR = new Vector4f(1.0f, 0.0f, 0.0f, 1.0f);

	/** Converts vanilla's current depth into {@code depthtex<which>}. Not inside a render pass. */
	public void capture(int which, GpuTextureView vanillaDepth, GpuBuffer quad) {
		if (pipeline == null) {
			pipeline = build();
		}
		ensure(which);
		source[which] = which;
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(RenderPassDescriptor.builder(() -> "Aetherium Shaders depthtex" + which).withColorAttachment(views[which]).build())) {
			pass.setPipeline(pipeline);
			pass.setUniform("Depth", vanillaDepth, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.setVertexBuffer(0, quad.slice());
			pass.draw(6, 1, 0, 0);
		}
	}

	/** {@code depthtex<from>} into {@code depthtex<to>} (same format: a plain copy). */
	public void copy(int from, int to) {
		ensure(source[from]);
		ensure(to);
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(textures[source[from]], textures[to], 0, 0, 0, 0, 0, width, height);
		source[to] = to;
	}

	private static CompiledRenderPipeline build() {
		Identifier id = Identifier.fromNamespaceAndPath("aetherium_shaders", "depth_convert");
		RenderPipeline pipeline = RenderPipeline.builder()
				.withLocation(id).withVertexShader(id).withFragmentShader(id)
				.withBindGroupLayout(BindGroupLayout.builder().withUniform("Depth", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withDepthStencilState(Optional.empty())
				.withCull(false)
				.withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, 15))
				.build();
		ShaderSource source = new ShaderSource() {
			@Override
			public @Nullable String getShader(Identifier shaderId, ShaderType type) {
				return type == ShaderType.VERTEX ? VERTEX : type == ShaderType.FRAGMENT ? FRAGMENT : null;
			}

			@Override
			public ShaderSource.@Nullable CachedIncludeSource getInclude(Identifier includeId) {
				return null;
			}

			@Override
			public void close() {
			}
		};
		CompiledRenderPipeline compiled = RenderSystem.getDevice().compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
		if (compiled == null) {
			throw new IllegalStateException("Aetherium Shaders: depth conversion pipeline failed to build");
		}
		return compiled;
	}

	private void closeTextures() {
		for (int i = 0; i < COUNT; i++) {
			if (views[i] != null) {
				views[i].close();
				textures[i].close();
				views[i] = null;
				textures[i] = null;
			}
		}
	}

	@Override
	public void close() {
		closeTextures();
		if (pipeline != null) {
			pipeline.close();
			pipeline = null;
		}
	}
}
