package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
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
import dev.spacebod.aetherium.client.mixin.access.RenderTargetAccessor;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.config.ShaderPackSettings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The render scale: while a pack draws, the world renders at a share of the window and the pack's finished picture is
 * brought back to the window's size with FSR 1.0 (EASU, then RCAS) before the interface.
 * <p>
 * The whole mechanism is the size of the game's main render target. Every screen-sized thing of the pack follows that
 * target each frame (its colour targets and their relative sizes, the depth copies, custom images, {@code viewWidth},
 * {@code viewHeight} and {@code aspectRatio}, compute work groups, mip chains), and vanilla's world passes take their
 * render area from its attachments. So for the world phase the target's four textures are swapped for a smaller set,
 * inside the same object (the frame graph and the sky hold the object, not its textures), and its size fields follow.
 * A change of scale is a resize of all of that, as a window resize is: no pack reload.
 * <p>
 * The swap starts where {@code GameRenderer.render} clears the main target for the frame, and ends when
 * {@code renderLevel} returns: the level, the hand, the pack's composite and final passes and vanilla's screen effects
 * all draw at the scaled size; the entity outline blit, post effects, the world screenshot and the interface come after
 * and see the window-sized target. The entity outline target keeps the window's size (its pass has no depth
 * attachment, so it never meets the scaled depth). Vanilla's global settings block keeps the window's size too.
 * <p>
 * Frames with a post effect (spectating a mob) are not scaled: the hand then draws against a separate window-sized depth.
 * At 100% nothing here runs beyond one comparison per frame.
 */
public final class RenderScale {
	public static final int MIN_PERCENT = 50;
	public static final int MAX_PERCENT = 100;
	public static final int STEP_PERCENT = 5;
	/** Sharpness in hundredths of a stop of attenuation: 0 is RCAS at full strength, 100 is half of it. */
	public static final int MAX_SHARPNESS = 100;
	public static final int DEFAULT_SHARPNESS = 20;

	private static final String SOURCE_SAMPLER = "aeth_Source";
	private static final String RESOURCES = "/assets/aetherium/shaders/upscale/";
	private static final float[] QUAD = {0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 1, 0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 0, 1, 0, 0, 1};

	private static volatile int percent = MAX_PERCENT;
	private static volatile int sharpness = DEFAULT_SHARPNESS;
	private static volatile boolean configured;

	/** The main target holds the scaled set (never across frames). */
	private static boolean swapped;
	private static @Nullable GpuTexture fullColor;
	private static @Nullable GpuTextureView fullColorView;
	private static @Nullable GpuTexture fullDepth;
	private static @Nullable GpuTextureView fullDepthView;
	private static int fullWidth;
	private static int fullHeight;

	/** The scaled colour and depth the world renders into, kept while the scale is in use. */
	private static @Nullable GpuTexture scaledColor;
	private static @Nullable GpuTextureView scaledColorView;
	private static @Nullable GpuTexture scaledDepth;
	private static @Nullable GpuTextureView scaledDepthView;
	private static int scaledWidth;
	private static int scaledHeight;
	/** EASU's output at the window's size, which RCAS reads. */
	private static @Nullable GpuTexture upscaled;
	private static @Nullable GpuTextureView upscaledView;
	private static @Nullable GpuBuffer quad;

	private static @Nullable GpuFormat pipelineFormat;
	private static @Nullable CompiledRenderPipeline easu;
	private static @Nullable CompiledRenderPipeline rcas;
	private static int rcasSharpness = -1;
	/** A pipeline would not build, or an upscale failed: the scale stays off until the setting changes. */
	private static boolean refused;
	/** An allocation failed at this window size: retried when the window or the scale changes. */
	private static int refusedWidth;
	private static int refusedHeight;
	private static int refusedPercent;

	/** The last frame that scaled (for F3), or 0. */
	private static int lastPercent;

	private RenderScale() {
	}

	/** The player's choices, applied from the next frame (the settings screen, and the config on first use). */
	public static void configure(int scalePercent, int sharpnessHundredths) {
		int p = Math.clamp(scalePercent, MIN_PERCENT, MAX_PERCENT);
		int s = Math.clamp(sharpnessHundredths, 0, MAX_SHARPNESS);
		if (p != percent || s != sharpness) {
			refused = false;
		}
		percent = p;
		sharpness = s;
		configured = true;
	}

	public static int percent() {
		ensureConfigured();
		return percent;
	}

	public static int sharpness() {
		ensureConfigured();
		return sharpness;
	}

	private static void ensureConfigured() {
		if (!configured) {
			configure(ShaderPackSettings.renderScale(), ShaderPackSettings.upscaleSharpness());
		}
	}

	/**
	 * At the head of {@code GameRenderer.render}: puts the window-sized set back if the last frame ended inside the world
	 * phase (an exception), before the game compares the target's size with the window's.
	 */
	public static void recover(RenderTarget main) {
		if (swapped) {
			restore(main);
		}
	}

	/**
	 * Where the frame clears the main target: hands the target the scaled set when this frame is scaled, and answers
	 * whether it did (the clear then lands on the scaled set).
	 */
	public static boolean beginWorld(RenderTarget main, GameRenderState state) {
		ensureConfigured();
		int asked = percent;
		if (asked >= MAX_PERCENT) {
			if (scaledColor != null || upscaled != null) {
				release();
			}
			lastPercent = 0;
			return false;
		}
		if (swapped || !state.shouldRenderLevel || !state.requestedPostEffects.isEmpty() || !ShaderPackEngine.get().running() || refused) {
			if (!state.shouldRenderLevel || !ShaderPackEngine.get().running()) {
				release();
			}
			lastPercent = 0;
			return false;
		}
		GpuTexture color = main.getColorTexture();
		GpuTexture depth = main.getDepthTexture();
		if (color == null || depth == null) {
			return false;
		}
		int width = Math.max(1, Math.round(main.width * asked / 100.0f));
		int height = Math.max(1, Math.round(main.height * asked / 100.0f));
		if (width >= main.width && height >= main.height) {
			lastPercent = 0;
			return false;
		}
		if (refusedWidth == main.width && refusedHeight == main.height && refusedPercent == asked) {
			return false;
		}
		if (!pipelines(color.getFormat())) {
			return false;
		}
		try {
			allocate(color, depth, width, height, main.width, main.height);
		} catch (RuntimeException e) {
			AetheriumShaders.logger.error("render scale: could not allocate the {}x{} world for a {}x{} window; staying at full size",
					width, height, main.width, main.height, e);
			release();
			refusedWidth = main.width;
			refusedHeight = main.height;
			refusedPercent = asked;
			return false;
		}
		RenderTargetAccessor access = (RenderTargetAccessor) main;
		fullColor = access.aetherium$colorTexture();
		fullColorView = access.aetherium$colorTextureView();
		fullDepth = access.aetherium$depthTexture();
		fullDepthView = access.aetherium$depthTextureView();
		fullWidth = main.width;
		fullHeight = main.height;
		access.aetherium$setColorTexture(scaledColor);
		access.aetherium$setColorTextureView(scaledColorView);
		access.aetherium$setDepthTexture(scaledDepth);
		access.aetherium$setDepthTextureView(scaledDepthView);
		main.width = width;
		main.height = height;
		swapped = true;
		lastPercent = asked;
		return true;
	}

	/**
	 * When {@code renderLevel} returns: the window-sized set goes back into the main target and the scaled picture is
	 * upscaled onto its colour (EASU into the intermediate, RCAS onto the target). Outside any render pass.
	 */
	public static void endWorld(RenderTarget main) {
		if (!swapped) {
			return;
		}
		restore(main);
		GpuTextureView output = main.getColorTextureView();
		if (output == null || scaledColorView == null || upscaledView == null || easu == null || rcas == null || quad == null) {
			return;
		}
		try {
			draw("Aetherium upscale (FSR1 EASU)", easu, scaledColorView, upscaledView, FilterMode.LINEAR);
			draw("Aetherium upscale (FSR1 RCAS)", rcas, upscaledView, output, FilterMode.NEAREST);
		} catch (RuntimeException e) {
			AetheriumShaders.logger.error("render scale: the upscale failed; the world renders at full size until the setting changes", e);
			refused = true;
		}
	}

	/** The F3 line while the scale is in use, else null. */
	public static @Nullable String debugLine() {
		if (lastPercent == 0 || upscaled == null) {
			return null;
		}
		return String.format(Locale.ROOT, "Render scale: %d%% (%dx%d -> %dx%d, FSR1, sharpness %.2f)", lastPercent, scaledWidth, scaledHeight,
				upscaled.getWidth(0), upscaled.getHeight(0), rcasSharpness / 100.0);
	}

	private static void draw(String label, CompiledRenderPipeline pipeline, GpuTextureView source, GpuTextureView destination, FilterMode filter) {
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(RenderPassDescriptor.builder(() -> label).withColorAttachment(destination).build())) {
			pass.setPipeline(pipeline);
			pass.setUniform(SOURCE_SAMPLER, source, RenderSystem.getSamplerCache().getClampToEdge(filter));
			pass.setVertexBuffer(0, quad.slice());
			pass.draw(6, 1, 0, 0);
		}
	}

	private static void restore(RenderTarget main) {
		RenderTargetAccessor access = (RenderTargetAccessor) main;
		access.aetherium$setColorTexture(fullColor);
		access.aetherium$setColorTextureView(fullColorView);
		access.aetherium$setDepthTexture(fullDepth);
		access.aetherium$setDepthTextureView(fullDepthView);
		main.width = fullWidth;
		main.height = fullHeight;
		fullColor = null;
		fullColorView = null;
		fullDepth = null;
		fullDepthView = null;
		swapped = false;
	}

	/** The scaled set and the window-sized intermediate at their sizes and formats, reallocated when either moved. */
	private static void allocate(GpuTexture color, GpuTexture depth, int width, int height, int outWidth, int outHeight) {
		GpuDevice device = RenderSystem.getDevice();
		if (scaledColor == null || scaledWidth != width || scaledHeight != height || scaledColor.getFormat() != color.getFormat()
				|| scaledDepth == null || scaledDepth.getFormat() != depth.getFormat()) {
			releaseScaled();
			int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
			scaledColor = device.createTexture(() -> "Aetherium scaled world / Color", usage, color.getFormat(), width, height, 1, 1);
			scaledColorView = device.createTextureView(scaledColor);
			scaledDepth = device.createTexture(() -> "Aetherium scaled world / Depth", usage, depth.getFormat(), width, height, 1, 1);
			scaledDepthView = device.createTextureView(scaledDepth);
			scaledWidth = width;
			scaledHeight = height;
			AetheriumShaders.logger.info("render scale {}%: the world renders at {}x{} for a {}x{} window", percent, width, height, outWidth, outHeight);
		}
		if (upscaled == null || upscaled.getWidth(0) != outWidth || upscaled.getHeight(0) != outHeight || upscaled.getFormat() != color.getFormat()) {
			releaseUpscaled();
			upscaled = device.createTexture(() -> "Aetherium upscale", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
					color.getFormat(), outWidth, outHeight, 1, 1);
			upscaledView = device.createTextureView(upscaled);
		}
		if (quad == null) {
			ByteBuffer bytes = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder());
			for (float f : QUAD) {
				bytes.putFloat(f);
			}
			bytes.flip();
			quad = device.createBuffer(() -> "Aetherium upscale quad", GpuBuffer.USAGE_VERTEX, bytes);
		}
	}

	/** Builds EASU and RCAS for {@code format} (RCAS again when the sharpness moved); false when either will not build. */
	private static boolean pipelines(GpuFormat format) {
		int wantedSharpness = sharpness;
		try {
			if (pipelineFormat != format) {
				closePipelines();
				pipelineFormat = format;
			}
			if (easu == null) {
				easu = build("easu", read("fsr1_easu.fsh"), format);
			}
			if (rcas == null || rcasSharpness != wantedSharpness) {
				if (rcas != null) {
					rcas.close();
					rcas = null;
				}
				String source = read("fsr1_rcas.fsh");
				int line = source.indexOf('\n');
				source = source.substring(0, line + 1)
						+ String.format(Locale.ROOT, "#define AETH_RCAS_SHARPNESS %.4f%n", wantedSharpness / 100.0f)
						+ source.substring(line + 1);
				rcas = build("rcas_" + wantedSharpness, source, format);
				rcasSharpness = wantedSharpness;
			}
			return true;
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.error("render scale: the FSR upscale pipelines did not build; the world renders at full size", e);
			closePipelines();
			refused = true;
			return false;
		}
	}

	private static String read(String name) throws IOException {
		try (InputStream in = RenderScale.class.getResourceAsStream(RESOURCES + name)) {
			if (in == null) {
				throw new IOException("missing resource " + RESOURCES + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static CompiledRenderPipeline build(String name, String fragment, GpuFormat format) throws IOException {
		String vertex = read("fullscreen.vsh");
		Identifier id = Identifier.fromNamespaceAndPath("aetherium_shaders", "upscale_" + name + "_" + format.name().toLowerCase(Locale.ROOT));
		RenderPipeline pipeline = RenderPipeline.builder()
				.withLocation(id).withVertexShader(id).withFragmentShader(id)
				.withBindGroupLayout(BindGroupLayout.builder().withUniform(SOURCE_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withDepthStencilState(Optional.empty())
				.withCull(false)
				.withColorTargetState(0, new ColorTargetState(Optional.empty(), format, 15))
				.build();
		ShaderSource source = new ShaderSource() {
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
		CompiledRenderPipeline compiled = RenderSystem.getDevice().compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
		if (compiled == null) {
			throw new IllegalStateException("the " + name + " pipeline for " + format + " failed to build");
		}
		return compiled;
	}

	private static void closePipelines() {
		if (easu != null) {
			easu.close();
			easu = null;
		}
		if (rcas != null) {
			rcas.close();
			rcas = null;
		}
		rcasSharpness = -1;
		pipelineFormat = null;
	}

	/** Frees the images (never while the main target holds them); the pipelines and the quad stay. */
	private static void release() {
		if (swapped) {
			return;
		}
		releaseScaled();
		releaseUpscaled();
	}

	private static void releaseScaled() {
		if (scaledColorView != null) {
			scaledColorView.close();
			scaledColorView = null;
		}
		if (scaledColor != null) {
			scaledColor.close();
			scaledColor = null;
		}
		if (scaledDepthView != null) {
			scaledDepthView.close();
			scaledDepthView = null;
		}
		if (scaledDepth != null) {
			scaledDepth.close();
			scaledDepth = null;
		}
		scaledWidth = 0;
		scaledHeight = 0;
	}

	private static void releaseUpscaled() {
		if (upscaledView != null) {
			upscaledView.close();
			upscaledView = null;
		}
		if (upscaled != null) {
			upscaled.close();
			upscaled = null;
		}
	}
}
