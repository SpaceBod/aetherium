package dev.spacebod.aetherium.shaders.chunks;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The block a chunk mesh vertex was meshed from, carried on the vertex itself (added to the mesh vertex by a mixin, and
 * copied with it): translucent quads are buffered by the sorter, which keeps copies of their vertices and encodes them
 * later with a material of its own, so only the vertex reaches the encoder on every path.
 *
 * <ul>
 *   <li>{@code entity}: {@code mc_Entity} as stored, block ID in the low 16 bits (-1 unmapped), 0 for a block or 1 for a
 *       fluid in the high 16;</li>
 *   <li>{@code block}: the block's position in its section (one byte per axis) and its light emission (top byte).</li>
 * </ul>
 */
public interface ChunkVertexData {
	int aetherium$entity();

	int aetherium$block();

	void aetherium$set(int entity, int block);

	/** Stamps every vertex of a quad (the meshers' reused vertex array) with the block it belongs to. */
	static ChunkVertexEncoder.Vertex[] stamp(ChunkVertexEncoder.Vertex[] vertices, int entity, int block) {
		for (ChunkVertexEncoder.Vertex vertex : vertices) {
			((ChunkVertexData) vertex).aetherium$set(entity, block);
		}
		return vertices;
	}

	/** {@code mc_Entity} for {@code state}: its block ID ({@code -1} unmapped) and whether it is meshed as a fluid. */
	static int entity(@Nullable BlockState state, boolean fluid) {
		int id = -1;
		if (state != null) {
			Object2IntMap<BlockState> ids = WorldRenderingSettings.INSTANCE.getBlockStateIds();
			if (ids != null) {
				id = ids.getInt(state);
			}
		}
		return (id & 0xFFFF) | (fluid ? 1 : 0) << 16;
	}

	/** Position in the section and light emission of the block at {@code pos}. */
	static int block(@Nullable BlockPos pos, @Nullable BlockState state) {
		if (pos == null) {
			return 0;
		}
		int emission = state == null ? 0 : state.getLightEmission();
		return (pos.getX() & 15) | (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 16 | (emission & 0xFF) << 24;
	}

	/**
	 * {@link #block} for geometry whose {@code at_midBlock} offset is zero by definition rather than measured: the light
	 * block's voxel quad.
	 */
	static int unmeasured(int block) {
		return block | UNMEASURED;
	}

	/** Set in a {@link #block} value whose offset is written as zero (a spare bit above the z byte's four used bits). */
	int UNMEASURED = 1 << 23;

	static boolean measured(int block) {
		return (block & UNMEASURED) == 0;
	}

	static int blockX(int block) {
		return block & 0xFF;
	}

	static int blockY(int block) {
		return block >> 8 & 0xFF;
	}

	static int blockZ(int block) {
		return block >> 16 & 0x0F;
	}

	static int emission(int block) {
		return block >>> 24;
	}
}
