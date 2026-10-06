package dev.spacebod.aetherium.shaders.chunks;

import dev.spacebod.aetherium.shaders.shadows.ShadowCasterCulling;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.joml.FrustumIntersection;
import org.jspecify.annotations.Nullable;

/**
 * The shadow casters as a frustum the chunk renderer's section trees can walk: every section within {@code radius}
 * blocks of the camera horizontally (any height), narrowed by the pack's caster culling when it culls. Boxes arrive
 * camera-relative; a box the size of one section is tested as that section, larger tree nodes only against the radius
 * (their sections are tested on the way down).
 */
final class CasterFrustum implements Frustum {
	/** Half the size of the box the trees test a single section with, plus slack. */
	private static final float SECTION_BOX = 10.0F;

	private final double cameraX;
	private final double cameraY;
	private final double cameraZ;
	private final @Nullable ShadowCasterCulling culling;
	private final float radius;

	CasterFrustum(double cameraX, double cameraY, double cameraZ, @Nullable ShadowCasterCulling culling, double radius) {
		this.cameraX = cameraX;
		this.cameraY = cameraY;
		this.cameraZ = cameraZ;
		this.culling = culling;
		this.radius = (float) radius;
	}

	@Override
	public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		if (!withinRadius(minX, minZ, maxX, maxZ)) {
			return false;
		}
		if (culling == null || maxX - minX > 2.0F * SECTION_BOX) {
			return true;
		}
		return culling.testSection(sectionOrigin(cameraX, minX, maxX), sectionOrigin(cameraY, minY, maxY), sectionOrigin(cameraZ, minZ, maxZ));
	}

	@Override
	public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		if (!withinRadius(minX, minZ, maxX, maxZ)) {
			return FrustumIntersection.OUTSIDE;
		}
		boolean inside = culling == null && minX >= -radius && maxX <= radius && minZ >= -radius && maxZ <= radius;
		return inside ? FrustumIntersection.INSIDE : FrustumIntersection.INTERSECT;
	}

	@Override
	public boolean testSection(float x, float y, float z) {
		return testAab(x, y, z, x + 16.0F, y + 16.0F, z + 16.0F);
	}

	@Override
	public boolean testSectionExpanded(float x, float y, float z, float extend) {
		return testAab(x - extend, y - extend, z - extend, x + 16.0F + extend, y + 16.0F + extend, z + 16.0F + extend);
	}

	private boolean withinRadius(float minX, float minZ, float maxX, float maxZ) {
		return maxX >= -radius && minX <= radius && maxZ >= -radius && minZ <= radius;
	}

	/** The block coordinate of the section a camera-relative box centres on. */
	private static int sectionOrigin(double camera, float min, float max) {
		return ((int) Math.floor(camera + (min + max) * 0.5)) & ~15;
	}
}
