package dev.spacebod.aetherium.shaders.engine;

import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import java.util.stream.IntStream;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import org.jspecify.annotations.Nullable;

/**
 * Where the block-breaking crack is drawn while a pack runs. 26.3 files the crack over a solid block among the solid
 * features, before the deferred passes, and over a block with translucent material (glass) with the breaking overlay,
 * after them. A pack whose {@code gbuffers_damagedblock} draws into colortex0 multiplies its crack into the shaded
 * colour, which its deferred pass rewrites (Photon 1.3b, Complementary, BSL): its crack goes after the deferred passes,
 * with the breaking overlay, whatever block it lies on. A pack whose crack draws into another target leaves a mark there
 * for a deferred pass to read (Photon 1.4 on 26.3: colortex3, read by deferred4): its crack goes with the solid
 * features, before them, whatever block it lies on. While the level's cracks are submitted, a submit to either phase of
 * a collection goes to the one the pack wants, whichever submit method a loader's renderer API routes the crack through.
 * Render thread only.
 */
public final class CrackRouting {
	private static boolean submitting;

	private CrackRouting() {
	}

	/** A collection's solid phase or breaking overlay, linked to both. */
	public interface CrackPhase {
		void aetherium$linkCrackPhases(SimpleFeatureRenderPhase solid, SimpleFeatureRenderPhase breakingOverlay);
	}

	/**
	 * Whether {@code programs} want their crack after the deferred passes: their {@code gbuffers_damagedblock} draws into
	 * colortex0, or they have none (the crack then draws with their terrain program, as before this choice existed).
	 */
	public static boolean afterDeferred(ProgramSet programs) {
		return programs.get(ProgramId.DamagedBlock)
				.map(source -> IntStream.of(source.getDirectives().getDrawBuffers()).anyMatch(buffer -> buffer == 0))
				.orElse(true);
	}

	/** Around the level's block-breaking submits. */
	public static void submitting(boolean on) {
		submitting = on;
	}

	/**
	 * The phase a submit to a collection's {@code solid} phase or {@code breakingOverlay} now goes to: one of the two while
	 * the level's cracks are submitted and a pack runs, else null (where it was submitted).
	 */
	public static @Nullable SimpleFeatureRenderPhase target(SimpleFeatureRenderPhase solid, SimpleFeatureRenderPhase breakingOverlay) {
		ShaderPackEngine engine = ShaderPackEngine.get();
		if (!submitting || !engine.running()) {
			return null;
		}
		return engine.cracksAfterDeferred() ? breakingOverlay : solid;
	}
}
