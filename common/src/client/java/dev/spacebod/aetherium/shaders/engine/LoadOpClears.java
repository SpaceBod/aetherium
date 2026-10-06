package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joml.Vector4fc;

/**
 * Colour clears recorded as render-pass load operations ({@link EngineSwitches#LOADOP_CLEARS}): a clear the frame owes
 * a texture rides the load of the first render pass that attaches it, and the ones still owed when something else
 * needs the textures are paid together, as one empty render pass per texture size that clears up to the device's
 * colour attachment limit at once. A standalone clear is a full GPU barrier of its own; a load-op clear is part of a
 * pass.
 */
final class LoadOpClears {
	/** One level-0 view owed a clear to {@code colour}. */
	record Pending(GpuTextureView view, Vector4fc colour) {
	}

	private LoadOpClears() {
	}

	/** Clears every view of {@code pending} now: one empty pass per size and per attachment limit. Not inside a render pass. */
	static void flush(List<Pending> pending) {
		if (pending.isEmpty()) {
			return;
		}
		Map<Long, List<Pending>> bySize = new LinkedHashMap<>();
		for (Pending p : pending) {
			long key = ((long) p.view().getWidth(0) << 32) | (p.view().getHeight(0) & 0xFFFFFFFFL);
			bySize.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
		}
		var device = RenderSystem.getDevice();
		int perPass = Math.max(1, device.getDeviceInfo().limits().maxColorAttachments());
		CommandEncoder encoder = device.createCommandEncoder();
		for (List<Pending> group : bySize.values()) {
			for (int from = 0; from < group.size(); from += perPass) {
				int to = Math.min(group.size(), from + perPass);
				RenderPassDescriptor.Builder builder = RenderPassDescriptor.builder(() -> "Aetherium Shaders clear");
				for (int i = from; i < to; i++) {
					builder.withColorAttachment(group.get(i).view(), Optional.of(group.get(i).colour()));
				}
				try (RenderPass ignored = encoder.createRenderPass(builder.build())) {
					// The load operations are the clear.
				}
			}
		}
	}
}
