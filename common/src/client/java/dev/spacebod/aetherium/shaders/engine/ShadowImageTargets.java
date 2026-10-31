package dev.spacebod.aetherium.shaders.engine;

import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.TargetImages;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackShadowDirectives;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shadow colour textures required by image declarations, independently of render attachments. */
final class ShadowImageTargets {
	private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
	private static final Pattern UNIFORM = Pattern.compile("\\buniform\\b");
	private static final Pattern IMAGE = Pattern.compile("\\b[iu]?image2D\\b");
	private ShadowImageTargets() {
	}

	static int requiredCount(int minimum, List<String> sources) {
		int count = Math.max(2, minimum);
		for (String source : sources) {
			for (String statement : COMMENTS.matcher(source).replaceAll(" ").split("[;{}]")) {
				Matcher type = IMAGE.matcher(statement);
				if (!UNIFORM.matcher(statement).find() || !type.find()) {
					continue;
				}
				for (String declarator : statement.substring(type.end()).split(",")) {
					Matcher name = TargetImages.matcher(declarator.strip());
					if (name.matches() && TargetImages.shadow(name)) {
						int index = TargetImages.index(name);
						if (index < PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_PACK) {
							count = Math.max(count, index + 1);
						}
					}
				}
			}
		}
		return count;
	}
}
