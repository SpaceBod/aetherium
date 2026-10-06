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
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Copies one colour texture into another through a shader, so the GPU converts between formats (vanilla's 8-bit
 * scene into a pack's float target and back). A raw texture copy would reinterpret the bits instead: Vulkan image
 * copies require identical texel sizes. One pipeline per destination format.
 */
final class BlitPass implements AutoCloseable {
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
			uniform sampler2D Source;
			layout(location = 0) in vec2 uv;
			layout(location = 0) out vec4 color;
			void main() {
			    color = texture(Source, uv);
			}
			""";
	private static final String FRAGMENT_ALPHA = FRAGMENT.replace("color = texture(Source, uv);", "color = vec4(texture(Source, uv).aaa, 1.0);");
	private final Map<GpuFormat, CompiledRenderPipeline> pipelines = new HashMap<>();
	private final Map<GpuFormat, CompiledRenderPipeline> alphaPipelines = new HashMap<>();

	/** Debug: the source's alpha as grey. */
	void blitAlpha(GpuTextureView source, GpuTextureView destination, GpuFormat destinationFormat, GpuBuffer quad) {
		CompiledRenderPipeline pipeline = alphaPipelines.computeIfAbsent(destinationFormat, f -> build(f, FRAGMENT_ALPHA, "blit_alpha_"));
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(RenderPassDescriptor.builder(() -> "Aetherium Shaders blit alpha").withColorAttachment(destination).build())) {
			pass.setPipeline(pipeline);
			pass.setUniform("Source", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.setVertexBuffer(0, quad.slice());
			pass.draw(6, 1, 0, 0);
		}
	}

	void blit(GpuTextureView source, GpuTextureView destination, GpuFormat destinationFormat, GpuBuffer quad) {
		blit(source, destination, destinationFormat, quad, FilterMode.NEAREST);
	}

	/** Linear sampling: a destination half the source's size gets each texel as the average of a 2x2 block. */
	void blitLinear(GpuTextureView source, GpuTextureView destination, GpuFormat destinationFormat, GpuBuffer quad) {
		blit(source, destination, destinationFormat, quad, FilterMode.LINEAR);
	}

	private void blit(GpuTextureView source, GpuTextureView destination, GpuFormat destinationFormat, GpuBuffer quad, FilterMode filter) {
		CompiledRenderPipeline pipeline = pipelines.computeIfAbsent(destinationFormat, f -> build(f, FRAGMENT, "blit_"));
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(RenderPassDescriptor.builder(() -> "Aetherium Shaders blit").withColorAttachment(destination).build())) {
			pass.setPipeline(pipeline);
			pass.setUniform("Source", source, RenderSystem.getSamplerCache().getClampToEdge(filter));
			pass.setVertexBuffer(0, quad.slice());
			pass.draw(6, 1, 0, 0);
		}
	}

	private static CompiledRenderPipeline build(GpuFormat format, String fragment, String prefix) {
		Identifier id = Identifier.fromNamespaceAndPath("aetherium_shaders", prefix + format.name().toLowerCase(Locale.ROOT));
		RenderPipeline pipeline = RenderPipeline.builder()
				.withLocation(id).withVertexShader(id).withFragmentShader(id)
				.withBindGroupLayout(BindGroupLayout.builder().withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withDepthStencilState(Optional.empty())
				.withCull(false)
				.withColorTargetState(0, new ColorTargetState(Optional.empty(), format, 15))
				.build();
		ShaderSource source = new ShaderSource() {
			@Override
			public @Nullable String getShader(Identifier shaderId, ShaderType type) {
				return type == ShaderType.VERTEX ? VERTEX : type == ShaderType.FRAGMENT ? fragment : null;
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
			throw new IllegalStateException("Aetherium Shaders: blit pipeline for " + format + " failed to build");
		}
		return compiled;
	}

	@Override
	public void close() {
		pipelines.values().forEach(CompiledRenderPipeline::close);
		pipelines.clear();
		alphaPipelines.values().forEach(CompiledRenderPipeline::close);
		alphaPipelines.clear();
	}
}
