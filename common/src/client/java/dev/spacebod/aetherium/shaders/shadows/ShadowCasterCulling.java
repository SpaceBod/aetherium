package dev.spacebod.aetherium.shaders.shadows;

import dev.spacebod.aetherium.shaders.shaderpack.properties.PackShadowDirectives;
import dev.spacebod.aetherium.shaders.shaderpack.properties.ShadowCullState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Chooses which terrain sections are drawn into the shadow map.
 *
 * <p>Nothing here tests against the shadow projection: packs distort shadow space (logarithmic xy, scaled z), so the
 * nominal shadow frustum is not the volume that lands in the map. The tight cull instead asks whether a caster can
 * shadow anything the player sees: the player's view frustum is swept along the light direction, keeping the view
 * planes that face the light plus one edge plane per silhouette edge (L. Spiro, "Tightly Culling Shadow Casters for
 * Directional Lights", parts 1 and 2). This assumes the map is only sampled for direct shadows and similar effects
 * (volumetric light), not for sun-bounce GI; packs that need more set {@code shadow.culling=false}.</p>
 *
 * <p>{@code shadow.culling}: {@code false} gives distance only, {@code true} the swept frustum, {@code reversed} /
 * {@code safe_zone} the swept frustum plus an always-drawn box of {@code voxelDistance} around the camera. When the
 * pack sets nothing, a pack that voxelises (a geometry stage in its shadow program) gets distance only, since it
 * reads the map for more than shadows.</p>
 */
public abstract class ShadowCasterCulling {
	/** Section bounds are grown by the largest model overhang (1 block) and an epsilon, as for the main view. */
	private static final float SECTION_MARGIN = 1.0F + 0.125F;

	protected double cameraX;
	protected double cameraY;
	protected double cameraZ;

	/**
	 * @param voxelises     the pack writes the map for more than shadows (a geometry stage in its shadow program)
	 * @param renderBlocks  the render distance in blocks
	 * @param playerView    the player's projection x view rotation (OpenGL clip convention, camera-relative)
	 * @param lightFromOrigin world-space direction towards the shadow light (sun or moon)
	 */
	public static ShadowCasterCulling create(PackShadowDirectives directives, boolean voxelises, double renderBlocks,
			Matrix4fc playerView, Vector3f lightFromOrigin) {
		ShadowCullState state = directives.getCullingState();
		float halfPlane = directives.getDistance();
		float mul = directives.getDistanceRenderMul();
		if ((state == ShadowCullState.DEFAULT && voxelises) || state == ShadowCullState.DISTANCE) {
			double distance = halfPlane * mul;
			if (distance <= 0 || distance > renderBlocks) {
				return new None(voxelises ? "disabled (voxelisation detected)" : "disabled (set by shader pack)");
			}
			return new Distance(distance, voxelises && state == ShadowCullState.DEFAULT ? "distance only (voxelisation detected)" : "distance only (set by shader pack)");
		}
		boolean safeZone = state == ShadowCullState.SAFE_ZONE;
		if (safeZone && mul < 0) {
			// A safe zone implies a multiplier of 1.
			mul = 1.0F;
		}
		double distance = (safeZone ? directives.getVoxelDistance() : halfPlane) * mul;
		if (mul < 0) {
			// No distance from the pack: casters come from the whole render distance.
			distance = renderBlocks;
		}
		Box box;
		if (distance >= renderBlocks && !safeZone) {
			box = null;
		} else if (distance == 0.0 && !safeZone) {
			return new Nothing();
		} else {
			box = new Box(distance);
		}
		if (!playerView.isFinite()) {
			// The camera matrices are not captured yet (first frame): draw everything rather than guess.
			return new None("disabled for this frame (no camera matrices yet)");
		}
		Vector3f light = new Vector3f(lightFromOrigin).normalize();
		if (safeZone) {
			return new SafeZone(playerView, light, box, new Box(halfPlane * mul));
		}
		return new LightSwept(playerView, light, box, "light-swept view frustum");
	}

	/** Centres the culler on the camera; called once per frame before testing. */
	public void prepare(double x, double y, double z) {
		cameraX = x;
		cameraY = y;
		cameraZ = z;
	}

	/** Whether the 16³ section with minimum corner (x, y, z), in block coordinates, may cast into the view. */
	public boolean testSection(int minBlockX, int minBlockY, int minBlockZ) {
		float minX = (float) (minBlockX - cameraX) - SECTION_MARGIN;
		float minY = (float) (minBlockY - cameraY) - SECTION_MARGIN;
		float minZ = (float) (minBlockZ - cameraZ) - SECTION_MARGIN;
		float size = 16.0F + 2.0F * SECTION_MARGIN;
		return test(minX, minY, minZ, minX + size, minY + size, minZ + size);
	}

	/** Camera-relative box test. */
	protected abstract boolean test(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);

	/** The horizontal radius, in blocks, beyond which nothing passes (the walk's bound); negative when unbounded. */
	public abstract double radius();

	public abstract String describe();

	/** Axis-aligned cube of half-size {@code distance} around the camera (camera-relative coordinates). */
	static final class Box {
		final double distance;

		Box(double distance) {
			this.distance = distance;
		}

		boolean outside(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			return maxX < -distance || minX > distance || maxY < -distance || minY > distance || maxZ < -distance || minZ > distance;
		}
	}

	static final class None extends ShadowCasterCulling {
		private final String reason;

		None(String reason) {
			this.reason = reason;
		}

		@Override
		protected boolean test(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			return true;
		}

		@Override
		public double radius() {
			return -1;
		}

		@Override
		public String describe() {
			return reason;
		}
	}

	static final class Nothing extends ShadowCasterCulling {
		@Override
		protected boolean test(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			return false;
		}

		@Override
		public double radius() {
			return 0;
		}

		@Override
		public String describe() {
			return "no shadows rendered (distance 0)";
		}
	}

	static final class Distance extends ShadowCasterCulling {
		private final Box box;
		private final String reason;

		Distance(double distance, String reason) {
			this.box = new Box(distance);
			this.reason = reason;
		}

		@Override
		protected boolean test(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			return !box.outside(minX, minY, minZ, maxX, maxY, maxZ);
		}

		@Override
		public double radius() {
			return box.distance;
		}

		@Override
		public String describe() {
			return reason + ", " + Math.round(box.distance) + " blocks";
		}
	}

	/**
	 * The player's frustum swept along the light: the view planes facing the light, plus an edge plane through each
	 * edge shared by a light-facing and a light-averted plane, parallel to the light direction.
	 */
	static class LightSwept extends ShadowCasterCulling {
		private static final int MAX_PLANES = 13;
		/** For the plane pair of each axis (±x, ±y, ±z), the indices of the four planes adjacent to it. */
		private static final int[][] NEIGHBOURS = {{2, 3, 4, 5}, {0, 1, 4, 5}, {0, 1, 2, 3}};

		/** Plane (a, b, c, w): a point p is inside when a·px + b·py + c·pz + w ≥ 0. */
		private final float[][] planes = new float[MAX_PLANES][];
		private int planeCount;
		protected final Box box;
		private final String reason;

		LightSwept(Matrix4fc playerView, Vector3f light, Box box, String reason) {
			this.box = box;
			this.reason = reason;
			Vector4f[] view = viewPlanes(playerView);
			boolean[] facing = new boolean[view.length];
			for (int i = 0; i < view.length; i++) {
				Vector4f p = view[i];
				float dot = p.x * light.x + p.y * light.y + p.z * light.z;
				facing[i] = dot > 0.0F;
				if (dot >= 0.0F) {
					addPlane(p.x, p.y, p.z, p.w);
				}
			}
			for (int i = 0; i < view.length; i++) {
				if (!facing[i]) {
					continue;
				}
				for (int n : NEIGHBOURS[i >>> 1]) {
					if (!facing[n]) {
						addEdgePlane(view[i], view[n], light);
					}
				}
			}
		}

		/** The six clip planes of a projection x view matrix: -x, +x, -y, +y, far, near (OpenGL clip space). */
		private static Vector4f[] viewPlanes(Matrix4fc projView) {
			Matrix4f t = new Matrix4f(projView).transpose();
			return new Vector4f[] {
					plane(t, -1, 0, 0), plane(t, 1, 0, 0),
					plane(t, 0, -1, 0), plane(t, 0, 1, 0),
					plane(t, 0, 0, -1), plane(t, 0, 0, 1),
			};
		}

		private static Vector4f plane(Matrix4f transposed, float x, float y, float z) {
			return new Vector4f(x, y, z, 1.0F).mul(transposed).normalize();
		}

		private void addPlane(float a, float b, float c, float w) {
			planes[planeCount++] = new float[] {a, b, c, w};
		}

		/** The plane through the line where {@code back} and {@code front} meet, parallel to the light direction. */
		private void addEdgePlane(Vector4f back, Vector4f front, Vector3f light) {
			Vector3f backNormal = new Vector3f(back.x, back.y, back.z);
			Vector3f frontNormal = new Vector3f(front.x, front.y, front.z);
			Vector3f line = new Vector3f(backNormal).cross(frontNormal);
			Vector3f normal = new Vector3f(line).cross(light);
			// A point on the line: where the two planes meet a third plane through the origin with the line as its
			// normal (intersection of two planes, Graphics Gems I p. 305).
			Vector3f point = new Vector3f(line).cross(backNormal).mul(-front.w)
					.add(new Vector3f(frontNormal).cross(line).mul(-back.w))
					.mul(1.0F / line.lengthSquared());
			addPlane(normal.x, normal.y, normal.z, -normal.dot(point));
		}

		@Override
		protected boolean test(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			if (box != null && box.outside(minX, minY, minZ, maxX, maxY, maxZ)) {
				return false;
			}
			return insidePlanes(minX, minY, minZ, maxX, maxY, maxZ);
		}

		/** False when the box lies wholly behind any plane (its most positive corner along the normal is outside). */
		protected final boolean insidePlanes(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			for (int i = 0; i < planeCount; i++) {
				float[] p = planes[i];
				float x = p[0] < 0 ? minX : maxX;
				float y = p[1] < 0 ? minY : maxY;
				float z = p[2] < 0 ? minZ : maxZ;
				if (Math.fma(p[0], x, Math.fma(p[1], y, p[2] * z)) < -p[3]) {
					return false;
				}
			}
			return true;
		}

		@Override
		public double radius() {
			return box == null ? -1 : box.distance;
		}

		@Override
		public String describe() {
			return reason + (box == null ? "" : ", " + Math.round(box.distance) + " blocks") + ", " + planeCount + " planes";
		}
	}

	/**
	 * The swept frustum, except that everything within {@code voxelDistance} of the camera is always drawn (packs that
	 * voxelise the nearby world read it all), and nothing beyond the pack's shadow distance.
	 */
	static final class SafeZone extends LightSwept {
		private final Box outer;

		SafeZone(Matrix4fc playerView, Vector3f light, Box safe, Box outer) {
			super(playerView, light, safe, "light-swept view frustum with a safe zone");
			this.outer = outer;
		}

		@Override
		protected boolean test(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			if (outer.outside(minX, minY, minZ, maxX, maxY, maxZ)) {
				return false;
			}
			if (!box.outside(minX, minY, minZ, maxX, maxY, maxZ)) {
				return true;
			}
			return insidePlanes(minX, minY, minZ, maxX, maxY, maxZ);
		}

		@Override
		public double radius() {
			return outer.distance;
		}
	}
}
