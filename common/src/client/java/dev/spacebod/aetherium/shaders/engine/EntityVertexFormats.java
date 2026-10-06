package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.spacebod.aetherium.shaders.uniforms.CapturedRenderingState;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The entity and world-text vertex formats shader packs read, while a pack draws the world: vanilla's elements at their
 * own offsets, then
 * <ul>
 *   <li>{@code Entity} ({@code ivec3}: entity id, block entity id, item id from the pack's id maps, captured when the
 *       draw was submitted; packs read them as {@code entityId}, {@code blockEntityId}, {@code currentRenderedItemId});</li>
 *   <li>{@code mc_midTexCoord} (centre of the quad's texture) and {@code at_tangent} (xyz + handedness), per quad;</li>
 *   <li>world text also gets {@code Normal}, computed per quad (vanilla's glyphs carry none).</li>
 * </ul>
 * Vanilla's buffers switch to these formats only for world draws while a pack runs ({@link #extend}); its own shaders
 * still read their elements, at unchanged offsets, when a draw keeps a vanilla shader.
 */
public final class EntityVertexFormats {
	public static final int ENTITY_IDS = 36;
	public static final int ENTITY_MID_TEX = 44;
	public static final int ENTITY_TANGENT = 52;
	public static final VertexFormat EXTENDED_ENTITY = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV1", GpuFormat.RG16_SINT)
			.addAttribute("UV2", GpuFormat.RG16_SINT)
			.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
			.addAttribute("Entity", GpuFormat.RGBA16_SINT)
			.addAttribute("mc_midTexCoord", GpuFormat.RG32_FLOAT)
			.addAttribute("at_tangent", GpuFormat.RGBA8_SNORM)
			.build();

	public static final int TEXT_NORMAL = 28;
	public static final int TEXT_IDS = 32;
	public static final int TEXT_MID_TEX = 40;
	public static final int TEXT_TANGENT = 48;
	public static final VertexFormat EXTENDED_TEXT = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV2", GpuFormat.RG16_SINT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
			.addAttribute("Entity", GpuFormat.RGBA16_SINT)
			.addAttribute("mc_midTexCoord", GpuFormat.RG32_FLOAT)
			.addAttribute("at_tangent", GpuFormat.RGBA8_SNORM)
			.build();

	/** Set by the engine: a pack draws the world now (its draws are built in the extended formats). */
	private static volatile boolean active;

	private EntityVertexFormats() {
	}

	static void setActive(boolean value) {
		active = value;
	}

	/** Whether world entity and text draws are being built in the extended formats now. */
	public static boolean active() {
		return active;
	}

	/** The format a world draw of {@code vanilla}'s format is built in now: extended while a pack draws the world. */
	public static VertexFormat extend(VertexFormat vanilla) {
		if (!active) {
			return vanilla;
		}
		if (vanilla == DefaultVertexFormat.ENTITY) {
			return EXTENDED_ENTITY;
		}
		if (vanilla == DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR) {
			return EXTENDED_TEXT;
		}
		return vanilla;
	}

	/** The format a pack (or widened) pipeline for {@code vanilla}'s format reads, given whether world draws are extended. */
	public static @Nullable VertexFormat forPipeline(@Nullable VertexFormat vanilla, boolean extended) {
		if (vanilla == null || !extended) {
			return vanilla;
		}
		return vanilla == DefaultVertexFormat.ENTITY ? EXTENDED_ENTITY : vanilla == DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR ? EXTENDED_TEXT : vanilla;
	}

	public static boolean isExtended(VertexFormat format) {
		return format == EXTENDED_ENTITY || format == EXTENDED_TEXT;
	}

	/** At the start of a vertex: the ids of the entity, block entity and item being drawn. */
	public static void writeIds(VertexFormat format, long vertex) {
		long p = vertex + (format == EXTENDED_ENTITY ? ENTITY_IDS : TEXT_IDS);
		CapturedRenderingState state = CapturedRenderingState.INSTANCE;
		MemoryUtil.memPutShort(p, (short) state.getCurrentRenderedEntity());
		MemoryUtil.memPutShort(p + 2, (short) state.getCurrentRenderedBlockEntity());
		MemoryUtil.memPutShort(p + 4, (short) state.getCurrentRenderedItem());
		MemoryUtil.memPutShort(p + 6, (short) 0);
	}

	/**
	 * After the fourth vertex of a quad (the first at {@code quad}): every vertex gets the quad's mid-texture coordinate
	 * and tangent; text quads also their face normal.
	 */
	public static void finishQuad(VertexFormat format, long quad) {
		boolean text = format == EXTENDED_TEXT;
		int stride = format.getVertexSize();
		int uv = text ? 12 : 16;
		long p0 = quad, p1 = quad + stride, p2 = quad + 2L * stride, p3 = quad + 3L * stride;
		float x0 = MemoryUtil.memGetFloat(p0), y0 = MemoryUtil.memGetFloat(p0 + 4), z0 = MemoryUtil.memGetFloat(p0 + 8);
		float u0 = MemoryUtil.memGetFloat(p0 + uv), v0 = MemoryUtil.memGetFloat(p0 + uv + 4);
		float u1 = MemoryUtil.memGetFloat(p1 + uv), v1 = MemoryUtil.memGetFloat(p1 + uv + 4);
		float u2 = MemoryUtil.memGetFloat(p2 + uv), v2 = MemoryUtil.memGetFloat(p2 + uv + 4);
		float u3 = MemoryUtil.memGetFloat(p3 + uv), v3 = MemoryUtil.memGetFloat(p3 + uv + 4);
		float e1x = MemoryUtil.memGetFloat(p1) - x0, e1y = MemoryUtil.memGetFloat(p1 + 4) - y0, e1z = MemoryUtil.memGetFloat(p1 + 8) - z0;
		float e2x = MemoryUtil.memGetFloat(p2) - x0, e2y = MemoryUtil.memGetFloat(p2 + 4) - y0, e2z = MemoryUtil.memGetFloat(p2 + 8) - z0;
		float nx, ny, nz;
		if (text) {
			// Face normal of the glyph quad (counter-clockwise winding).
			nx = e1y * e2z - e1z * e2y;
			ny = e1z * e2x - e1x * e2z;
			nz = e1x * e2y - e1y * e2x;
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len > 1e-8f) {
				nx /= len;
				ny /= len;
				nz /= len;
			} else {
				nx = 0;
				ny = 0;
				nz = 1;
			}
		} else {
			long normal = quad + 32;
			nx = MemoryUtil.memGetByte(normal) / 127.0f;
			ny = MemoryUtil.memGetByte(normal + 1) / 127.0f;
			nz = MemoryUtil.memGetByte(normal + 2) / 127.0f;
		}
		float du1 = u1 - u0, dv1 = v1 - v0;
		float du2 = u2 - u0, dv2 = v2 - v0;
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
		float cx = ny * tz - nz * ty, cy = nz * tx - nx * tz, cz = nx * ty - ny * tx;
		float w = cx * bx + cy * by + cz * bz < 0 ? -1.0f : 1.0f;
		float mu = (u0 + u1 + u2 + u3) * 0.25f;
		float mv = (v0 + v1 + v2 + v3) * 0.25f;
		int midTex = text ? TEXT_MID_TEX : ENTITY_MID_TEX;
		int tangent = text ? TEXT_TANGENT : ENTITY_TANGENT;
		for (int i = 0; i < 4; i++) {
			long p = quad + (long) i * stride;
			MemoryUtil.memPutFloat(p + midTex, mu);
			MemoryUtil.memPutFloat(p + midTex + 4, mv);
			MemoryUtil.memPutByte(p + tangent, snorm(tx));
			MemoryUtil.memPutByte(p + tangent + 1, snorm(ty));
			MemoryUtil.memPutByte(p + tangent + 2, snorm(tz));
			MemoryUtil.memPutByte(p + tangent + 3, snorm(w));
			if (text) {
				MemoryUtil.memPutByte(p + TEXT_NORMAL, snorm(nx));
				MemoryUtil.memPutByte(p + TEXT_NORMAL + 1, snorm(ny));
				MemoryUtil.memPutByte(p + TEXT_NORMAL + 2, snorm(nz));
				MemoryUtil.memPutByte(p + TEXT_NORMAL + 3, (byte) 0);
			}
		}
	}

	private static byte snorm(float c) {
		return (byte) Math.round(Math.max(-1.0f, Math.min(1.0f, c)) * 127.0f);
	}
}
