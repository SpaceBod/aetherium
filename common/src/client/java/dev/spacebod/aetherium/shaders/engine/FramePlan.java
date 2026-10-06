package dev.spacebod.aetherium.shaders.engine;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramArrayId;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ComputeSource;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The order of the full-screen side of a frame, resolved once when the pack loads: per stage (begin, prepare, deferred,
 * composite, shadowcomp) its steps in index order, each the compute programs of one index followed by the full-screen
 * pass of that index (either may be absent); the setup, shadow and final computes; and the final pass. The frame loop
 * walks it; clears, mip chains and barriers are decided there, not here.
 * <p>
 * Per step it resolves the targets that flip afterwards: every target the pass draws to, unless
 * {@code flip.<pass>.<buffer>=false}, plus each target {@code flip.<pass>.<buffer>=true} names (flipped once, drawn to
 * or not); per stage the targets {@code flip.<stage>_pre.<buffer>=true} flips before its first step. Per step it also
 * records the targets earlier steps of the stage flipped ({@link Step#written}; {@link #writtenBeforeFinal} for the
 * final pass): a custom texture a pack binds under such a target's name no longer replaces it.
 */
final class FramePlan implements AutoCloseable {
	/** One step: the computes of an index, then its pass. {@code written}: targets flipped by earlier steps of the stage. */
	record Step(int index, String name, ComputePass[] computes, @Nullable CompositePass pass, int[] flips, Set<Integer> written) {
	}

	/** A stage's steps in order, and the targets flipped before its first step. */
	record Stage(ProgramArrayId id, TextureStage textures, int[] preFlips, List<Step> steps) {
		static final Stage EMPTY_COMPOSITE = new Stage(ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, new int[0], List.of());
	}

	/** The stages that write colortex targets, in frame order. */
	private static final ProgramArrayId[] COLOUR_STAGES = {ProgramArrayId.Begin, ProgramArrayId.Prepare, ProgramArrayId.Deferred, ProgramArrayId.Composite};

	private final Map<ProgramArrayId, Stage> stages = new EnumMap<>(ProgramArrayId.class);
	final ComputePass[] setup;
	/** {@code shadow.csh}, {@code shadow_a.csh}...: at the start of the shadow pass. */
	final ComputePass[] shadow;
	final ComputePass[] finalComputes;
	final @Nullable CompositePass finalPass;
	/** Targets the composite stage wrote, for the final pass's custom-texture overrides. */
	final Set<Integer> writtenBeforeFinal;
	final int built;
	final int skipped;

	private FramePlan(ComputePass[] setup, ComputePass[] shadow, ComputePass[] finalComputes, @Nullable CompositePass finalPass,
			Set<Integer> writtenBeforeFinal, int built, int skipped) {
		this.setup = setup;
		this.shadow = shadow;
		this.finalComputes = finalComputes;
		this.finalPass = finalPass;
		this.writtenBeforeFinal = writtenBeforeFinal;
		this.built = built;
		this.skipped = skipped;
	}

	/** Builders the engine supplies: a full-screen pass for a source in a stage, and the computes of one index. */
	interface Builders {
		@Nullable CompositePass pass(ProgramSource source, ProgramArrayId stage);

		@Nullable CompositePass finalPass(ProgramSource source);

		ComputePass[] computes(ComputeSource @Nullable [] sources, TextureStage textures);
	}

	static FramePlan build(ProgramSet programs, PackDirectives directives, Builders builders) {
		int[] counts = new int[2]; // built, skipped
		Map<ProgramArrayId, Stage> stages = new EnumMap<>(ProgramArrayId.class);
		Set<Integer> composited = Set.of();
		for (ProgramArrayId id : new ProgramArrayId[]{ProgramArrayId.Begin, ProgramArrayId.Prepare, ProgramArrayId.Deferred, ProgramArrayId.Composite,
				ProgramArrayId.ShadowComposite}) {
			Stage stage = stage(id, programs, directives, builders, counts);
			stages.put(id, stage);
			if (id == ProgramArrayId.Composite) {
				Set<Integer> written = new LinkedHashSet<>();
				for (Step step : stage.steps()) {
					Arrays.stream(step.flips()).forEach(written::add);
				}
				composited = Set.copyOf(written);
			}
		}
		CompositePass finalPass = null;
		var fin = programs.get(ProgramId.Final);
		if (fin.isPresent()) {
			finalPass = builders.finalPass(fin.get());
			counts[finalPass == null ? 1 : 0]++;
		}
		FramePlan plan = new FramePlan(builders.computes(programs.getSetup(), TextureStage.SETUP),
				builders.computes(programs.getShadowCompute(), TextureStage.SHADOWCOMP),
				builders.computes(programs.getFinalCompute(), TextureStage.COMPOSITE_AND_FINAL), finalPass, composited, counts[0], counts[1]);
		plan.stages.putAll(stages);
		return plan;
	}

	private static Stage stage(ProgramArrayId id, ProgramSet programs, PackDirectives directives, Builders builders, int[] counts) {
		TextureStage textures = textureStage(id);
		ProgramSource[] sources = programs.getComposite(id);
		ComputeSource[][] computes = programs.getCompute(id);
		int length = Math.max(sources.length, computes == null ? 0 : computes.length);
		List<Step> steps = new ArrayList<>();
		Set<Integer> written = new LinkedHashSet<>();
		for (int index = 0; index < length; index++) {
			ProgramSource source = index < sources.length ? sources[index] : null;
			ComputePass[] stepComputes = builders.computes(computes != null && index < computes.length ? computes[index] : null, textures);
			CompositePass pass = null;
			if (source != null && source.isValid()) {
				pass = builders.pass(source, id);
				counts[pass == null ? 1 : 0]++;
			}
			if (pass == null && stepComputes.length == 0) {
				continue;
			}
			int[] flips = pass == null ? new int[0] : flips(pass.drawBuffers(), source.getDirectives().getExplicitFlips());
			String name = pass != null ? pass.name() : stepComputes[0].name();
			steps.add(new Step(index, name, stepComputes, pass, flips, Set.copyOf(written)));
			Arrays.stream(flips).forEach(written::add);
		}
		String pre = switch (id) {
			case Begin -> "begin_pre";
			case Prepare -> "prepare_pre";
			case Deferred -> "deferred_pre";
			case Composite -> "composite_pre";
			default -> "shadowcomp_pre";
		};
		int[] preFlips = directives.getExplicitFlips(pre).entrySet().stream().filter(Map.Entry::getValue).mapToInt(Map.Entry::getKey).toArray();
		return new Stage(id, textures, preFlips, List.copyOf(steps));
	}

	/** Draw buffers flip unless explicitly not; explicit {@code true} entries flip too. */
	static int[] flips(int[] drawBuffers, Map<Integer, Boolean> explicit) {
		Set<Integer> out = new LinkedHashSet<>();
		for (int buffer : drawBuffers) {
			if (explicit.get(buffer) != Boolean.FALSE) {
				out.add(buffer);
			}
		}
		explicit.forEach((buffer, flip) -> {
			if (flip) {
				out.add(buffer);
			}
		});
		return out.stream().mapToInt(Integer::intValue).toArray();
	}

	static TextureStage textureStage(ProgramArrayId stage) {
		return switch (stage) {
			case Begin -> TextureStage.BEGIN;
			case Prepare -> TextureStage.PREPARE;
			case Deferred -> TextureStage.DEFERRED;
			case Setup -> TextureStage.SETUP;
			case ShadowComposite -> TextureStage.SHADOWCOMP;
			default -> TextureStage.COMPOSITE_AND_FINAL;
		};
	}

	Stage stage(ProgramArrayId id) {
		return stages.getOrDefault(id, Stage.EMPTY_COMPOSITE);
	}

	/** Every full-screen pass that writes colortex targets, in frame order (begin to composite). */
	List<CompositePass> colourPasses() {
		List<CompositePass> out = new ArrayList<>();
		for (ProgramArrayId id : COLOUR_STAGES) {
			for (Step step : stage(id).steps()) {
				if (step.pass() != null) {
					out.add(step.pass());
				}
			}
		}
		return out;
	}

	/**
	 * Per colortex: some full-screen pass of the colour stages draws to it, or a step or a stage's pre-flips flip it.
	 * Only these targets ever read or write their second side ({@link PackTargets#setNeedsSecond}).
	 */
	boolean[] writtenOrFlipped(int count) {
		boolean[] out = new boolean[count];
		for (ProgramArrayId id : COLOUR_STAGES) {
			Stage stage = stage(id);
			for (int target : stage.preFlips()) {
				mark(out, target);
			}
			for (Step step : stage.steps()) {
				for (int target : step.flips()) {
					mark(out, target);
				}
				CompositePass pass = step.pass();
				if (pass != null && !pass.writesMain()) {
					for (int target : pass.drawBuffers()) {
						mark(out, target);
					}
				}
			}
		}
		return out;
	}

	private static void mark(boolean[] targets, int target) {
		if (target >= 0 && target < targets.length) {
			targets[target] = true;
		}
	}

	void log() {
		for (ProgramArrayId id : stages.keySet()) {
			Stage stage = stages.get(id);
			if (stage.steps().isEmpty()) {
				continue;
			}
			StringBuilder b = new StringBuilder();
			for (Step step : stage.steps()) {
				b.append(' ').append(step.name());
				if (step.computes().length > 0) {
					b.append("(+").append(step.computes().length).append(" compute)");
				}
				if (step.flips().length > 0) {
					b.append(" flips").append(Arrays.toString(step.flips()));
				}
			}
			AetheriumShaders.logger.debug("{}{}{}", id, stage.preFlips().length > 0 ? " pre-flips " + Arrays.toString(stage.preFlips()) : "", b);
		}
	}

	@Override
	public void close() {
		for (Stage stage : stages.values()) {
			for (Step step : stage.steps()) {
				for (ComputePass c : step.computes()) {
					c.close();
				}
				if (step.pass() != null) {
					step.pass().close();
				}
			}
		}
		stages.clear();
		for (ComputePass[] list : new ComputePass[][]{setup, shadow, finalComputes}) {
			for (ComputePass c : list) {
				c.close();
			}
		}
		if (finalPass != null) {
			finalPass.close();
		}
	}
}
