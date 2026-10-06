package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.lwjgl.system.MemoryUtil;

/**
 * The block vertex format shader packs read: vanilla 26.3's block format plus {@code Normal}, {@code mc_Entity}
 * (block ID from block.properties), {@code mc_midTexCoord} (centre of the quad's sprite), {@code at_tangent} (xyz +
 * handedness) and {@code at_midBlock} (offset to the block centre in 1/64 blocks, light emission in w); 48 bytes, for the block geometry vanilla still builds itself (moving pistons, falling blocks); chunk terrain is
 * meshed by the chunk renderer ({@code ChunkMeshFormat}). That geometry is outside a section mesh, so it reads the
 * values the pack format gives such geometry: {@code mc_Entity} (-1, -1), {@code at_midBlock} relative to the origin
 * with emission -1.
 *
 * <p>The block pipelines' shared vertex format (see {@code RenderPipelinesMixin}); vanilla's own shaders draw correctly
 * from it when no pack is on.
 */
public final class TerrainVertexFormat {
	public static final int SIZE = 48;
	public static final int NORMAL = 28;
	public static final int ENTITY = 32;
	public static final int MID_TEX = 36;
	public static final int TANGENT = 40;
	public static final int MID_BLOCK = 44;
	public static final VertexFormat EXTENDED = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV2", GpuFormat.RG16_SINT)
			.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
			.addAttribute("mc_Entity", GpuFormat.RG16_SINT)
			.addAttribute("mc_midTexCoord", GpuFormat.RG16_UNORM)
			.addAttribute("at_tangent", GpuFormat.RGBA8_SNORM)
			.addAttribute("at_midBlock", GpuFormat.RGBA8_SINT)
			.build();

	/** The quad being written on this thread, and the block values of geometry outside a section. */
	private static final ThreadLocal<Current> CURRENT = ThreadLocal.withInitial(Current::new);

	private static final class Current {
		int blockId = -1;
		/** {@code mc_Entity.y}: 0 for a block, 1 for a fluid, -1 outside a section mesh. */
		int renderType = -1;
		int emission = -1;
		int x;
		int y;
		int z;
		/** x, y, z, u, v of the quad's four vertices. */
		final float[] quad = new float[20];
		float nx;
		float ny;
		float nz;
	}

	private TerrainVertexFormat() {
	}








	/** Writes one {@link #EXTENDED} vertex; after the fourth vertex of a quad, fills in its mid-texture coordinate and tangent. */
	public static void write(long p, int vertexIndex, float x, float y, float z, int abgr, float u, float v, int light, float nx, float ny, float nz) {
		Current c = CURRENT.get();
		int k = remember(c, vertexIndex, x, y, z, u, v, nx, ny, nz);
		MemoryUtil.memPutFloat(p, x);
		MemoryUtil.memPutFloat(p + 4, y);
		MemoryUtil.memPutFloat(p + 8, z);
		MemoryUtil.memPutInt(p + 12, abgr);
		MemoryUtil.memPutFloat(p + 16, u);
		MemoryUtil.memPutFloat(p + 20, v);
		MemoryUtil.memPutInt(p + 24, light);
		putNormal(p + NORMAL, nx, ny, nz);
		MemoryUtil.memPutShort(p + ENTITY, (short) c.blockId);
		MemoryUtil.memPutShort(p + ENTITY + 2, (short) c.renderType);
		putMidBlock(p + MID_BLOCK, c, x, y, z);
		if (k == 3) {
			finishQuad(c, p - 3L * SIZE, SIZE, MID_TEX, TANGENT);
		}
	}


	private static int remember(Current c, int vertexIndex, float x, float y, float z, float u, float v, float nx, float ny, float nz) {
		int k = vertexIndex & 3;
		float[] q = c.quad;
		q[5 * k] = x;
		q[5 * k + 1] = y;
		q[5 * k + 2] = z;
		q[5 * k + 3] = u;
		q[5 * k + 4] = v;
		if (k == 0) {
			c.nx = nx;
			c.ny = ny;
			c.nz = nz;
		}
		return k;
	}

	private static void putNormal(long p, float nx, float ny, float nz) {
		MemoryUtil.memPutByte(p, snorm(nx));
		MemoryUtil.memPutByte(p + 1, snorm(ny));
		MemoryUtil.memPutByte(p + 2, snorm(nz));
		MemoryUtil.memPutByte(p + 3, (byte) 0);
	}

	private static void putMidBlock(long p, Current c, float x, float y, float z) {
		MemoryUtil.memPutByte(p, midBlock(c.x + 0.5f - x));
		MemoryUtil.memPutByte(p + 1, midBlock(c.y + 0.5f - y));
		MemoryUtil.memPutByte(p + 2, midBlock(c.z + 0.5f - z));
		MemoryUtil.memPutByte(p + 3, (byte) c.emission);
	}


	/** Mid-texture coordinate and tangent for the quad whose first vertex is at {@code q}, from the remembered vertices. */
	private static void finishQuad(Current c, long q, int stride, int midTexOffset, int tangentOffset) {
		float[] v = c.quad;
		float mu = (v[3] + v[8] + v[13] + v[18]) * 0.25f;
		float mv = (v[4] + v[9] + v[14] + v[19]) * 0.25f;
		float e1x = v[5] - v[0], e1y = v[6] - v[1], e1z = v[7] - v[2];
		float e2x = v[10] - v[0], e2y = v[11] - v[1], e2z = v[12] - v[2];
		float du1 = v[8] - v[3], dv1 = v[9] - v[4];
		float du2 = v[13] - v[3], dv2 = v[14] - v[4];
		float det = du1 * dv2 - du2 * dv1;
		float f = det == 0 ? 1.0f : 1.0f / det;
		float tx = f * (dv2 * e1x - dv1 * e2x);
		float ty = f * (dv2 * e1y - dv1 * e2y);
		float tz = f * (dv2 * e1z - dv1 * e2z);
		float bx = f * (-du2 * e1x + du1 * e2x);
		float by = f * (-du2 * e1y + du1 * e2y);
		float bz = f * (-du2 * e1z + du1 * e2z);
		float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
		if (len > 1e-8f) {
			tx /= len;
			ty /= len;
			tz /= len;
		} else {
			tx = 1;
			ty = 0;
			tz = 0;
		}
		// The normal as stored (8-bit), so the handedness matches what the shader reads.
		float nx = snorm(c.nx) / 127.0f;
		float ny = snorm(c.ny) / 127.0f;
		float nz = snorm(c.nz) / 127.0f;
		// Handedness: does (N x T) point along the bitangent?
		float cx = ny * tz - nz * ty, cy = nz * tx - nx * tz, cz = nx * ty - ny * tx;
		float w = cx * bx + cy * by + cz * bz < 0 ? -1.0f : 1.0f;
		for (int i = 0; i < 4; i++) {
			long p = q + (long) i * stride;
			MemoryUtil.memPutShort(p + midTexOffset, unorm16(mu));
			MemoryUtil.memPutShort(p + midTexOffset + 2, unorm16(mv));
			MemoryUtil.memPutByte(p + tangentOffset, snorm(tx));
			MemoryUtil.memPutByte(p + tangentOffset + 1, snorm(ty));
			MemoryUtil.memPutByte(p + tangentOffset + 2, snorm(tz));
			MemoryUtil.memPutByte(p + tangentOffset + 3, snorm(w));
		}
	}



	private static short unorm16(float c) {
		return (short) Math.round(Math.max(0.0f, Math.min(1.0f, c)) * 65535.0f);
	}

	private static byte snorm(float c) {
		return (byte) Math.round(Math.max(-1.0f, Math.min(1.0f, c)) * 127.0f);
	}

	private static byte midBlock(float offset) {
		return (byte) Math.max(-128, Math.min(127, Math.round(offset * 64.0f)));
	}

}
