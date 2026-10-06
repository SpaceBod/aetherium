package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramArrayId;
import org.jspecify.annotations.Nullable;

/**
 * Diagnostic views for pack development. The engine calls this class only when {@link DevFlags#DEBUG_VIEW} is set:
 * <ul>
 *   <li>{@code AETHERIUM_SHADERS_DEBUG}: instead of the composite stage, "scene"/"sceneN" shows colortexN (default 0) as
 *       the gbuffers left it, "depth" depthtex0, "shadowtex0"/"shadowtex1"/"shadowcolorN" a shadow map as passes read
 *       it; "final" skips the composite stage and runs only final;</li>
 *   <li>{@code AETHERIUM_SHADERS_SHOW=<pass>:<N>[a]}: stops after the named full-screen pass and shows colortexN's colour
 *       (or alpha as grey). Passes before the composite stage run inside the world frame: a copy of the target is shown
 *       in place of the composite stage.</li>
 * </ul>
 */
final class DebugView implements AutoCloseable {
	private static final String DEBUG = DevFlags.DEBUG == null ? "" : DevFlags.DEBUG;
	private static final @Nullable String SHOW = DevFlags.SHOW;

	/** A SHOW stop happened in the composite stage this frame: the rest of the frame is skipped. */
	private boolean shown;
	/** A SHOW copy taken before the composite stage, shown there (its own texture: the target's snapshot stays the pack's). */
	private @Nullable GpuTexture captureTexture;
	private @Nullable GpuTextureView capture;
	private boolean captured;
	private boolean captureAlpha;

	/** Whether the stage being run must stop (a SHOW view was taken this frame). */
	boolean stopped() {
		return shown;
	}

	/** The composite stage is skipped ({@code DEBUG=final}). */
	boolean finalOnly() {
		return DEBUG.equals("final");
	}

	/** After a full-screen pass of {@code stage}: takes the SHOW view when this is the pass it names. */
	void afterPass(ProgramArrayId stage, CompositePass pass, PackTargets targets, BlitPass blit, GpuBuffer quad, RenderTarget main) {
		if (SHOW == null || !SHOW.startsWith(pass.name() + ":")) {
			return;
		}
		String which = SHOW.substring(pass.name().length() + 1);
		boolean alpha = which.endsWith("a");
		int target = Integer.parseInt(alpha ? which.substring(0, which.length() - 1) : which);
		if (stage == ProgramArrayId.Composite) {
			show(targets.readView(target), alpha, main, blit, quad);
			shown = true;
		} else {
			copy(targets, target);
			captured = true;
			captureAlpha = alpha;
		}
	}

	private void copy(PackTargets targets, int target) {
		int width = targets.width(target);
		int height = targets.height(target);
		if (captureTexture == null || captureTexture.getFormat() != targets.format(target) || captureTexture.getWidth(0) != width
				|| captureTexture.getHeight(0) != height) {
			close();
			captureTexture = RenderSystem.getDevice().createTexture(() -> "Aetherium Shaders debug capture",
					GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, targets.format(target), width, height, 1, 1);
			capture = RenderSystem.getDevice().createTextureView(captureTexture);
		}
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(targets.read(target), captureTexture, 0, 0, 0, 0, 0, width, height);
	}

	/**
	 * At the composite stage: draws a DEBUG view or a capture onto the main target instead of the stage; true when it
	 * did (the frame's composite and final are skipped).
	 */
	boolean replaceComposite(RenderTarget main, PackTargets targets, DepthTargets depth, @Nullable ShadowPass shadow, BlitPass blit, GpuBuffer quad) {
		if (DEBUG.startsWith("scene")) {
			int target = DEBUG.length() > 5 ? Integer.parseInt(DEBUG.substring(5)) : 0;
			show(targets.readView(target), false, main, blit, quad);
			return true;
		}
		if (DEBUG.startsWith("shadow") && shadow != null) {
			GpuTextureView view = shadow.samplerView(DEBUG, false);
			if (view != null) {
				show(view, false, main, blit, quad);
				return true;
			}
		}
		if (DEBUG.equals("depth")) {
			show(depth.view(DepthTargets.ALL), false, main, blit, quad);
			return true;
		}
		if (captured && capture != null) {
			show(capture, captureAlpha, main, blit, quad);
			captured = false;
			return true;
		}
		return false;
	}

	/** End of the composite stage: whether a SHOW stop ends the frame here (cleared for the next frame). */
	boolean endComposite() {
		boolean stop = shown;
		shown = false;
		return stop;
	}

	void reset() {
		shown = false;
		captured = false;
	}

	private static void show(GpuTextureView view, boolean alpha, RenderTarget main, BlitPass blit, GpuBuffer quad) {
		if (alpha) {
			blit.blitAlpha(view, main.getColorTextureView(), main.getColorTexture().getFormat(), quad);
		} else {
			blit.blit(view, main.getColorTextureView(), main.getColorTexture().getFormat(), quad);
		}
	}

	/** Frees the capture texture (the engine calls this once the GPU is done with the pack's frames). */
	@Override
	public void close() {
		if (capture != null) {
			capture.close();
			capture = null;
		}
		if (captureTexture != null) {
			captureTexture.close();
			captureTexture = null;
		}
	}
}
