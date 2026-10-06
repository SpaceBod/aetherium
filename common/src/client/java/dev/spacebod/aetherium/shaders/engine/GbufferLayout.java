package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramGroup;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * The colour attachments of every world (gbuffers) render pass while a pack is active. A Vulkan render pass fixes its
 * attachments when it opens, so the world passes open with the union of the draw buffers of every gbuffers program the
 * pack has, and each program's pipeline masks off the targets it does not write. At most 8 attachments (vanilla's
 * limit); a pack needing more keeps the first 8 and is logged.
 */
public final class GbufferLayout implements TargetLayout {
	private static final int MAX = 8;
	private final PackTargets targets;
	private final int[] attachments;
	private final int[] sampled;
	private final Set<Integer> sampledAny;
	private final boolean samplesDepth;

	private GbufferLayout(PackTargets targets, int[] attachments, int[] sampled, Set<Integer> sampledAny, boolean samplesDepth) {
		this.targets = targets;
		this.attachments = attachments;
		this.sampled = sampled;
		this.sampledAny = sampledAny;
		this.samplesDepth = samplesDepth;
	}

	/** Whether some gbuffers program samples colortex {@code target}. */
	public boolean samples(int target) {
		return sampledAny.contains(target);
	}

	public static GbufferLayout of(ProgramSet programs, PackTargets targets) {
		TreeSet<Integer> union = new TreeSet<>();
		TreeSet<Integer> sampledTargets = new TreeSet<>();
		boolean depth = false;
		for (ProgramId id : ProgramId.values()) {
			// LOD terrain and objects (dh_terrain, dh_water, dh_generic) draw into the same world passes' attachments; dh_shadow into the shadow map.
			boolean lod = id == ProgramId.DhTerrain || id == ProgramId.DhWater || id == ProgramId.DhGeneric;
			if (id.getGroup() != ProgramGroup.Gbuffers && !lod) {
				continue;
			}
			Optional<ProgramSource> source = programs.get(id);
			if (source.isEmpty()) {
				continue;
			}
			for (int buffer : source.get().getDirectives().getDrawBuffers()) {
				if (buffer >= 0 && buffer < targets.count()) {
					union.add(buffer);
				}
			}
			String text = source.get().getFragmentSource().orElse("") + "\n" + source.get().getVertexSource().orElse("");
			for (String sampler : SamplerUsage.declaredSamplers(text)) {
				// "texture" is the pack's albedo sampler here, not colortex0.
				int t = sampler.equals("texture") ? -1 : targetIndex(sampler);
				if (t >= 0 && t < targets.count()) {
					sampledTargets.add(t);
				}
				depth |= sampler.equals("depthtex0") || sampler.equals("gdepthtex");
			}
		}
		if (union.isEmpty()) {
			union.add(0);
		}
		int[] attachments = union.stream().mapToInt(Integer::intValue).limit(MAX).toArray();
		if (union.size() > MAX) {
			AetheriumShaders.logger.warn("the pack's gbuffers programs write {} colour targets {}; world passes keep the first {}",
					union.size(), union, MAX);
		}
		int[] sampled = sampledTargets.stream().filter(t -> Arrays.stream(attachments).anyMatch(a -> a == t)).mapToInt(Integer::intValue).toArray();
		return new GbufferLayout(targets, attachments, sampled, Set.copyOf(sampledTargets), depth);
	}

	/** colortex index of a colour sampler name, including the legacy names for 0..7; -1 if none. */
	public static int targetIndex(String name) {
		return switch (name) {
			case "gcolor", "texture" -> 0;
			case "gdepth" -> 1;
			case "gnormal" -> 2;
			case "composite" -> 3;
			case "gaux1" -> 4;
			case "gaux2" -> 5;
			case "gaux3" -> 6;
			case "gaux4" -> 7;
			default -> {
				if (name.startsWith("colortex")) {
					try {
						yield Integer.parseInt(name.substring(8));
					} catch (NumberFormatException e) {
						yield -1;
					}
				}
				yield -1;
			}
		};
	}

	/** The render pass's attachments as colortex indices, in attachment order. */
	@Override
	public int[] attachments() {
		return attachments;
	}

	@Override
	public GpuFormat format(int target) {
		return targets.format(target);
	}

	/** Attachment targets some gbuffers program also samples (need a snapshot when a world pass opens). */
	public int[] sampledAttachments() {
		return sampled;
	}

	/** Whether some gbuffers program samples depthtex0 (the depth the pass renders into). */
	public boolean samplesDepth() {
		return samplesDepth;
	}

	/**
	 * Vanilla's world render pass, re-targeted: the pack's gbuffer attachments (current sides) and vanilla's depth.
	 * {@code taken}: when not null, each attachment carries its target's pending frame-start clear as its load operation
	 * ({@link PackTargets#loadClear}), and the clear colour is stored at the target's index (null where none was taken).
	 */
	public RenderPassDescriptor redirect(RenderPassDescriptor vanilla, @Nullable Vector4fc @Nullable [] taken) {
		Supplier<String> label = vanilla.label();
		RenderPassDescriptor.Builder builder = RenderPassDescriptor.builder(() -> "Aetherium Shaders gbuffers (" + label.get() + ")");
		RenderPass.RenderArea area = vanilla.renderArea();
		for (int target : attachments) {
			GpuTextureView view = targets.attachmentView(target);
			Optional<Vector4fc> clear = Optional.empty();
			if (taken != null) {
				boolean whole = area.x() == 0 && area.y() == 0 && area.width() == view.getWidth(0) && area.height() == view.getHeight(0);
				clear = targets.loadClear(target, view, whole);
				taken[target] = clear.orElse(null);
			}
			builder.withColorAttachment(view, clear);
		}
		RenderPassDescriptor.Attachment<OptionalDouble> depth = vanilla.depthAttachment();
		if (depth != null) {
			builder.withDepthAttachment(depth.textureView(), depth.clearValue());
		}
		builder.withRenderArea(vanilla.renderArea());
		return builder.build();
	}
}
