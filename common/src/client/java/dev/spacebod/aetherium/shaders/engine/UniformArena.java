package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Per-frame storage for pack uniform blocks: every bind of a pack program gets its own aligned slice, because the GPU
 * reads uniform buffers when the frame's commands execute, after recording, so a buffer rewritten mid-frame would only
 * show its last contents. Three frames in flight, each fenced before reuse. A frame that outgrows its buffer moves
 * to a bigger one on the spot; the full one stays alive (earlier binds still read it) until that slot's fence.
 */
final class UniformArena implements AutoCloseable {
	private static final int FRAMES = 3;
	private final GpuBuffer[] buffers = new GpuBuffer[FRAMES];
	private final @Nullable GpuFence[] fences = new GpuFence[FRAMES];
	private final int[] sizes = new int[FRAMES];
	private int frame = -1;
	private int offset;
	private int needed;
	private int alignment = 256;
	/** Buffers a frame outgrew, closed once the frame's fence has passed. */
	@SuppressWarnings("unchecked")
	private final List<GpuBuffer>[] retired = new List[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};
	private int generation;
	/** Bytes used this frame in buffers it has already outgrown. */
	private int spilled;

	/** Changes every frame: a slice uploaded in the same generation is still valid to bind. */
	int generation() {
		return generation;
	}

	/** Start of a frame: waits for the frame that last used this slot. */
	void beginFrame() {
		if (frame >= 0) {
			fences[frame] = RenderSystem.getDevice().createCommandEncoder().createFence();
		}
		frame = (frame + 1) % FRAMES;
		GpuFence fence = fences[frame];
		if (fence != null) {
			fence.awaitCompletion(-1L);
			fence.close();
			fences[frame] = null;
		}
		for (GpuBuffer old : retired[frame]) {
			old.close();
		}
		retired[frame].clear();
		generation++;
		alignment = Math.max(16, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment());
		int want = Math.max(2 * 1024 * 1024, Integer.highestOneBit(Math.max(1, needed) * 2 - 1) * 2);
		if (buffers[frame] == null || sizes[frame] < want) {
			if (buffers[frame] != null) {
				buffers[frame].close();
			}
			int slot = frame;
			buffers[frame] = RenderSystem.getDevice().createBuffer(() -> "Aetherium Shaders uniforms #" + slot,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, want);
			sizes[frame] = want;
		}
		offset = 0;
		needed = 0;
		spilled = 0;
	}

	/** Copies {@code bytes} (position..limit) into this frame's buffer and returns the slice to bind. */
	GpuBufferSlice upload(ByteBuffer bytes) {
		if (frame < 0) {
			beginFrame();
		}
		int size = Math.max(16, bytes.remaining());
		int at = (offset + alignment - 1) / alignment * alignment;
		if (at + size > sizes[frame]) {
			// Out of room: continue in a bigger buffer. Commands recorded earlier this frame keep reading the old one.
			retired[frame].add(buffers[frame]);
			int bigger = Math.max(sizes[frame] * 2, Integer.highestOneBit(size * 2 - 1) * 2);
			int slot = frame;
			buffers[frame] = RenderSystem.getDevice().createBuffer(() -> "Aetherium Shaders uniforms #" + slot,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, bigger);
			sizes[frame] = bigger;
			spilled += offset;
			at = 0;
		}
		// The whole frame's use, so the slot comes round with one buffer big enough for all of it.
		needed = Math.max(needed, spilled + at + size);
		GpuBufferSlice slice = buffers[frame].slice(at, size);
		try (GpuBufferSlice.MappedView view = slice.map(false, true)) {
			view.data().put(bytes.duplicate());
		}
		offset = at + size;
		return slice;
	}

	private int[] batchOffsets = new int[8];

	/**
	 * Copies {@code blocks[0..count)} (each position..limit) into this frame's buffer through one mapping, at the offsets
	 * consecutive {@link #upload} calls would give them; their slices go to {@code out}.
	 */
	void upload(ByteBuffer[] blocks, int count, GpuBufferSlice[] out) {
		if (count == 0) {
			return;
		}
		if (frame < 0) {
			beginFrame();
		}
		if (batchOffsets.length < count) {
			batchOffsets = new int[Math.max(count, batchOffsets.length * 2)];
		}
		int start = (offset + alignment - 1) / alignment * alignment;
		int end = layout(blocks, count, start);
		if (end > sizes[frame]) {
			// Out of room: continue in a buffer big enough for the whole batch. Commands recorded earlier keep the old one.
			retired[frame].add(buffers[frame]);
			int bigger = Math.max(sizes[frame] * 2, Integer.highestOneBit((end - start) * 2 - 1) * 2);
			int slot = frame;
			buffers[frame] = RenderSystem.getDevice().createBuffer(() -> "Aetherium Shaders uniforms #" + slot,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, bigger);
			sizes[frame] = bigger;
			spilled += offset;
			start = 0;
			end = layout(blocks, count, start);
		}
		needed = Math.max(needed, spilled + end);
		GpuBuffer buffer = buffers[frame];
		try (GpuBufferSlice.MappedView view = buffer.slice(start, end - start).map(false, true)) {
			ByteBuffer data = view.data();
			for (int i = 0; i < count; i++) {
				data.position(batchOffsets[i] - start);
				data.put(blocks[i].duplicate());
				out[i] = buffer.slice(batchOffsets[i], Math.max(16, blocks[i].remaining()));
			}
		}
		offset = end;
	}

	/** Aligned offsets of a batch starting at {@code start} into {@link #batchOffsets}; returns where it ends. */
	private int layout(ByteBuffer[] blocks, int count, int start) {
		int at = start;
		int end = start;
		for (int i = 0; i < count; i++) {
			at = (end + alignment - 1) / alignment * alignment;
			batchOffsets[i] = at;
			end = at + Math.max(16, blocks[i].remaining());
		}
		return end;
	}

	@Override
	public void close() {
		for (int i = 0; i < FRAMES; i++) {
			if (buffers[i] != null) {
				buffers[i].close();
				buffers[i] = null;
			}
			if (fences[i] != null) {
				fences[i].close();
				fences[i] = null;
			}
			for (GpuBuffer old : retired[i]) {
				old.close();
			}
			retired[i].clear();
		}
		frame = -1;
	}
}
