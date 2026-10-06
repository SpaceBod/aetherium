package dev.spacebod.aetherium.shaders.chunks;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;

/**
 * A chunk quad's face normal, tangent and texture centre, packed as their vertex elements store them. One instance per
 * meshing thread, refilled for every quad.
 *
 * <p>The normal is Newell's: the sum over the quad's edges, so a quad folded into a triangle (two corners on one point)
 * still gets its plane. The tangent follows the texture's u direction across one of the quad's two triangles (the
 * other when the first has no texture area), made perpendicular to the normal; its w is the handedness, +1 when
 * normal x tangent runs along the texture's v direction, as {@code TerrainVertexFormat} writes it for block geometry.
 */
final class QuadFrame {
	private static final ThreadLocal<QuadFrame> CURRENT = ThreadLocal.withInitial(QuadFrame::new);

	/** RGBA8_SNORM normal (w 0). */
	int normal;
	/** RGBA8_SNORM tangent and handedness. */
	int tangent;
	/** RG16_UNORM texture centre. */
	int midTexture;

	private float nx;
	private float ny;
	private float nz;
	private float tx;
	private float ty;
	private float tz;
	private float handedness;

	private QuadFrame() {
	}

	static QuadFrame of(ChunkVertexEncoder.Vertex[] q) {
		QuadFrame f = CURRENT.get();
		f.fill(q);
		return f;
	}

	private void fill(ChunkVertexEncoder.Vertex[] q) {
		float x = 0, y = 0, z = 0, u = 0, v = 0;
		for (int i = 0; i < q.length; i++) {
			ChunkVertexEncoder.Vertex a = q[i];
			ChunkVertexEncoder.Vertex b = q[(i + 1) % q.length];
			x += (a.y - b.y) * (a.z + b.z);
			y += (a.z - b.z) * (a.x + b.x);
			z += (a.x - b.x) * (a.y + b.y);
			u += a.u;
			v += a.v;
		}
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		if (length > 1.0E-12F) {
			nx = x / length;
			ny = y / length;
			nz = z / length;
		} else {
			nx = 0;
			ny = 1;
			nz = 0;
		}
		if (!tangent(q[0], q[1], q[2]) && !tangent(q[2], q[3], q[0])) {
			// No texture area: any direction in the plane.
			if (Math.abs(ny) < 0.999F) {
				tx = nz;
				ty = 0;
				tz = -nx;
			} else {
				tx = 1;
				ty = 0;
				tz = 0;
			}
			handedness = 1;
		}
		float along = nx * tx + ny * ty + nz * tz;
		tx -= nx * along;
		ty -= ny * along;
		tz -= nz * along;
		float t = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
		if (t > 1.0E-12F) {
			tx /= t;
			ty /= t;
			tz /= t;
		} else {
			tx = 1;
			ty = 0;
			tz = 0;
		}
		normal = snorm(nx) | snorm(ny) << 8 | snorm(nz) << 16;
		tangent = snorm(tx) | snorm(ty) << 8 | snorm(tz) << 16 | snorm(handedness) << 24;
		int n = q.length;
		midTexture = unorm16(u / n) | unorm16(v / n) << 16;
	}

	/** The u direction across triangle a, b, c; false when the triangle has no texture area. */
	private boolean tangent(ChunkVertexEncoder.Vertex a, ChunkVertexEncoder.Vertex b, ChunkVertexEncoder.Vertex c) {
		float du1 = b.u - a.u, dv1 = b.v - a.v;
		float du2 = c.u - a.u, dv2 = c.v - a.v;
		float det = du1 * dv2 - du2 * dv1;
		if (Math.abs(det) < 1.0E-12F) {
			return false;
		}
		float f = 1.0F / det;
		float e1x = b.x - a.x, e1y = b.y - a.y, e1z = b.z - a.z;
		float e2x = c.x - a.x, e2y = c.y - a.y, e2z = c.z - a.z;
		tx = f * (dv2 * e1x - dv1 * e2x);
		ty = f * (dv2 * e1y - dv1 * e2y);
		tz = f * (dv2 * e1z - dv1 * e2z);
		if (Math.abs(tx) + Math.abs(ty) + Math.abs(tz) < 1.0E-12F) {
			return false;
		}
		float bx = f * (-du2 * e1x + du1 * e2x);
		float by = f * (-du2 * e1y + du1 * e2y);
		float bz = f * (-du2 * e1z + du1 * e2z);
		float cx = ny * tz - nz * ty, cy = nz * tx - nx * tz, cz = nx * ty - ny * tx;
		handedness = cx * bx + cy * by + cz * bz < 0 ? -1 : 1;
		return true;
	}

	private static int snorm(float c) {
		return Math.round(Math.max(-1.0F, Math.min(1.0F, c)) * 127.0F) & 0xFF;
	}

	private static int unorm16(float c) {
		return Math.round(Math.max(0.0F, Math.min(1.0F, c)) * 65535.0F) & 0xFFFF;
	}
}
