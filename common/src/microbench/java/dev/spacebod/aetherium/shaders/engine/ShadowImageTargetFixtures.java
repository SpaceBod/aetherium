package dev.spacebod.aetherium.shaders.engine;

import java.util.List;

public final class ShadowImageTargetFixtures {
	private ShadowImageTargetFixtures() {
	}

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		record Case(String source, int minimum, int expected) { }
		List<Case> cases = List.of(
				new Case("layout(rgba16f) uniform image2D shadowcolorimg2;", 2, 3),
				new Case("coherent uniform writeonly highp uimage2D shadowcolorimg4, shadowcolorimg6;", 2, 7),
				new Case("uniform layout(rgba16f) image2D shadowcolorimg7;", 2, 8),
				new Case("uniform image2D shadowcolorimg2;", 5, 5),
				new Case("uniform image2D colorimg7;", 2, 2),
				new Case("uniform sampler2D shadowcolor3, shadowcolor7;", 2, 2),
				new Case("/* uniform image2D shadowcolorimg7; */\n// uniform image2D shadowcolorimg6;", 2, 2),
				new Case("uniform image2D custom_shadowcolorimg7;", 2, 2),
				new Case("uniform image2D shadowcolorimg8;", 2, 2),
				new Case("uniform image2D shadowcolorimg1;", 0, 2));
		for (Case test : cases) {
			int actual = ShadowImageTargets.requiredCount(test.minimum(), List.of(test.source()));
			if (actual != test.expected()) {
				throw new AssertionError("shadow image target count: expected " + test.expected() + ", got " + actual + " for " + test.source());
			}
		}
		if (ShadowImageTargets.requiredCount(2, List.of("uniform image2D shadowcolorimg2;", "uniform image2D shadowcolorimg5;")) != 6) {
			throw new AssertionError("shadow image requirements from different programs were not combined");
		}
		System.out.println("shadow image targets: compute-only textures retained; attachment counts, sampler overrides, comments and index limits preserved");
	}
}
