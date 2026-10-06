package dev.spacebod.aetherium.shaders.pathways;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.spacebod.aetherium.shaders.engine.RenderPhase;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.pipeline.WorldRenderingPhase;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import dev.spacebod.aetherium.shaders.uniforms.CapturedRenderingState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * The horizon box a pack gets with {@code sky=true} (the default): an inverted octagonal cone around the camera, from a
 * point 16 blocks below it to a ring 16 blocks above it at the render distance (at most 256 blocks), drawn in the fog
 * colour (alpha 1) with the sky pipeline, so the pack's {@code gbuffers_skybasic} draws it with {@code renderStage} =
 * sky. It is drawn right after the frame's clear, before the sky pass, in dimensions with an overworld sky or sky light.
 * Render thread only.
 */
public final class HorizonRenderer {
	/** The cone's rim: the height of the top sky plane. */
	private static final float TOP = 16.0F;
	/** The cone's apex: the height of the bottom sky plane. */
	private static final float BOTTOM = -16.0F;
	/** Apex plus nine rim vertices (the first and last coincide), drawn as one triangle fan. */
	private static final int VERTICES = 10;
	private static final int MAX_RADIUS = 256;

	private static @Nullable GpuBuffer buffer;
	private static int bufferRadius = -1;

	private HorizonRenderer() {
	}

	/** Whether this frame draws the horizon: a pack draws the world, keeps {@code sky=true}, and the dimension has a sky. */
	public static boolean shouldDraw() {
		ShaderPackEngine engine = ShaderPackEngine.get();
		PackDirectives directives = engine.activeDirectives();
		if (!engine.worldActive() || directives == null || !directives.shouldRenderSkyDisc()) {
			return false;
		}
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return false;
		}
		DimensionType type = level.dimensionType();
		return type.skybox() == DimensionType.Skybox.OVERWORLD || type.hasSkyLight();
	}

	/**
	 * Draws the horizon into {@code target} (re-targeted to the pack's gbuffers while a pack draws the world), with the
	 * sky's fog bound.
	 */
	public static void draw(RenderTarget target, GpuBufferSlice skyFog) {
		if (!shouldDraw()) {
			return;
		}
		int radius = Math.min(Minecraft.getInstance().options.getEffectiveRenderDistance() * 16, MAX_RADIUS);
		GpuBuffer vertices = buffer;
		if (vertices == null || bufferRadius != radius) {
			if (vertices != null) {
				vertices.close();
			}
			vertices = build(radius);
			buffer = vertices;
			bufferRadius = radius;
		}
		Vector3d fog = CapturedRenderingState.INSTANCE.getFogColor();
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
				new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()), new Vector4f((float) fog.x, (float) fog.y, (float) fog.z, 1.0F));
		RenderSystem.setShaderFog(skyFog);
		RenderPhase.override(WorldRenderingPhase.SKY);
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Horizon",
				target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.SKY));
			pass.setUniform("DynamicTransforms", transforms);
			pass.setVertexBuffer(0, vertices.slice());
			pass.draw(VERTICES, 1, 0, 0);
		} finally {
			RenderPhase.override(null);
		}
	}

	/** The cone as {@code POSITION} vertices: apex, then the rim clockwise seen from above. */
	private static GpuBuffer build(int radius) {
		ByteBuffer bytes = ByteBuffer.allocateDirect(VERTICES * 3 * Float.BYTES).order(ByteOrder.nativeOrder());
		bytes.putFloat(0.0F).putFloat(BOTTOM).putFloat(0.0F);
		for (int i = 0; i <= 8; i++) {
			float angle = (float) (-i * Math.PI / 4.0);
			bytes.putFloat((float) (radius * Math.cos(angle))).putFloat(TOP).putFloat((float) (radius * Math.sin(angle)));
		}
		bytes.flip();
		return RenderSystem.getDevice().createBuffer(() -> "Aetherium Shaders horizon", GpuBuffer.USAGE_VERTEX, bytes);
	}
}
