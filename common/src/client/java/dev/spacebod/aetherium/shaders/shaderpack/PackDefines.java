package dev.spacebod.aetherium.shaders.shaderpack;

import com.google.common.collect.ImmutableList;
import dev.spacebod.aetherium.shaders.gl.shader.StandardMacros;
import dev.spacebod.aetherium.shaders.helpers.StringPair;
import dev.spacebod.aetherium.shaders.parsing.BiomeCategories;
import dev.spacebod.aetherium.shaders.uniforms.BiomeUniforms;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PackDefines {
	private static void define(List<StringPair> defines, String key) {
		defines.add(new StringPair(key, ""));
	}

	private static void define(List<StringPair> defines, String key, String value) {
		defines.add(new StringPair(key, value));
	}

	public static ImmutableList<StringPair> createDefines() {
		ArrayList<StringPair> s = new ArrayList<>(StandardMacros.createStandardEnvironmentDefines());

		BiomeUniforms.getBiomeMap().forEach((biome, id) -> define(s, "BIOME_" + biome.identifier().getPath().toUpperCase(Locale.ROOT), String.valueOf(id)));

		BiomeCategories[] categories = BiomeCategories.values();
		for (int i = 0; i < categories.length; i++) {
			define(s, "CAT_" + categories[i].name().toUpperCase(Locale.ROOT), String.valueOf(i));
		}

		define(s, "PPT_NONE", "0");
		define(s, "PPT_RAIN", "1");
		define(s, "PPT_SNOW", "2");

		return ImmutableList.copyOf(s);
	}
}
