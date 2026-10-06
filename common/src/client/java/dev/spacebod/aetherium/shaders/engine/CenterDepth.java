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
import dev.spacebod.aetherium.shaders.uniforms.SystemTimeUniforms;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * {@code centerDepthSmooth}: {@code depthtex0} at the screen centre, eased towards each frame's value with the pack's
 * {@code centerDepthHalflife} (in tenths of a second). A 1x1 R32F ping-pong pair; sampled only once a program reads it.
 */
final class CenterDepth implements AutoCloseable {
	static final String SAMPLER = "aeth_centerDepthSmooth";
	private static final String VERTEX = """
			#version 330 core
			#extension GL_ARB_separate_shader_objects : enable
			layout(location = 0) in vec3 Position;
			layout(location = 1) in vec2 UV0;
			void main() {
			    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
			}
			""";
	private static final String FRAGMENT = """
			#version 330 core
			#extension GL_ARB_separate_shader_objects : enable
			uniform sampler2D Depth;
			uniform sampler2D Previous;
			layout(std140) uniform CenterDepthParams {
			    float Blend;
			    float Reversed;
			};
			layout(location = 0) out vec4 result;
			void main() {
			    float current = texture(Depth, vec2(0.5)).r;
			    if (Reversed > 0.5) {
			        current = 1.0 - current;
			    }
			    float previous = texture(Previous, vec2(0.5)).r;
			    result = vec4(Blend >= 1.0 || isnan(previous) ? current : mix(previous, current, Blend), 0.0, 0.0, 1.0);
			}
			""";
	private final float decay;
	private final GpuTexture[] textures = new GpuTexture[2];
	private final GpuTextureView[] views = new GpuTextureView[2];
	private final ByteBuffer params = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
	private @Nullable CompiledRenderPipeline pipeline;
	private int current;
	private boolean sampled;
	private boolean used;

	/** @param halfLife the pack's {@code centerDepthHalflife} (tenths of a second) */
	CenterDepth(float halfLife) {
		this.decay = (float) (Math.log(2) / Math.max(1e-4, halfLife * 0.1));
	}

	/** The view packs sample; marks the value as used so later frames keep it current. */
	GpuTextureView view(PackTargets targets) {
		used = true;
		return textures[current] == null ? targets.white() : views[current];
	}

	/**
	 * Updates the value from {@code depth}: OpenGL convention, or vanilla's reversed depth when {@code reversed} (the
	 * live depth buffer, sampled before the hand as packs expect). Not inside a render pass.
	 */
	void update(GpuTextureView depth, boolean reversed, UniformArena arena, GpuBuffer quad) {
		if (sampled && !used) {
			return;
		}
		if (textures[0] == null) {
			for (int i = 0; i < 2; i++) {
				int index = i;
				textures[i] = RenderSystem.getDevice().createTexture(() -> "Aetherium Shaders centerDepthSmooth " + index,
						GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.R32_FLOAT, 1, 1, 1, 1);
				views[i] = RenderSystem.getDevice().createTextureView(textures[i]);
			}
			pipeline = build();
		}
		float blend = sampled ? (float) (1.0 - Math.exp(-decay * SystemTimeUniforms.TIMER.getLastFrameTime())) : 1.0f;
		params.clear();
		params.putFloat(0, blend);
		params.putFloat(4, reversed ? 1.0f : 0.0f);
		int next = 1 - current;
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(RenderPassDescriptor.builder(() -> "Aetherium Shaders centerDepthSmooth").withColorAttachment(views[next]).build())) {
			pass.setPipeline(pipeline);
			var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			pass.setUniform("Depth", depth, nearest);
			pass.setUniform("Previous", views[current], nearest);
			pass.setUniform("CenterDepthParams", arena.upload(params));
			pass.setVertexBuffer(0, quad.slice());
			pass.draw(6, 1, 0, 0);
		}
		current = next;
		sampled = true;
	}

	private static CompiledRenderPipeline build() {
		Identifier id = Identifier.fromNamespaceAndPath("aetherium_shaders", "center_depth");
		RenderPipeline pipeline = RenderPipeline.builder()
				.withLocation(id).withVertexShader(id).withFragmentShader(id)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("Depth", UniformType.COMBINED_IMAGE_SAMPLER)
						.withUniform("Previous", UniformType.COMBINED_IMAGE_SAMPLER)
						.withUniform("CenterDepthParams", UniformType.UNIFORM_BUFFER)
						.build())
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
			throw new IllegalStateException("Aetherium Shaders: centerDepthSmooth pipeline failed to build");
		}
		return compiled;
	}

	@Override
	public void close() {
		for (int i = 0; i < 2; i++) {
			if (views[i] != null) {
				views[i].close();
				textures[i].close();
				views[i] = null;
				textures[i] = null;
			}
		}
		if (pipeline != null) {
			pipeline.close();
			pipeline = null;
		}
		sampled = false;
		used = false;
	}
}
