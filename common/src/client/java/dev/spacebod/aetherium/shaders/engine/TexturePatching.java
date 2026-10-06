package dev.spacebod.aetherium.shaders.engine;

import dev.spacebod.aetherium.shaders.gl.texture.TextureType;
import dev.spacebod.aetherium.shaders.helpers.Tri;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

/**
 * The active pack's raw custom textures as the transformer applies them: a raw {@code texture.<stage>.<sampler>} is a
 * pack-wide texture {@code customtexN}, and in that stage a sampler of that name and type is renamed to it
 * ({@code ShaderProperties} builds the map). Configured when a pack loads, before its programs are built.
 */
public final class TexturePatching {
	private static volatile Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> map = Object2ObjectMaps.emptyMap();

	private TexturePatching() {
	}

	public static void configure(Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> patching) {
		map = Object2ObjectMaps.unmodifiable(new Object2ObjectOpenHashMap<>(patching));
	}

	public static void reset() {
		map = Object2ObjectMaps.emptyMap();
	}

	/** A copy for one transform (the transformer takes a mutable map). */
	static Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> current() {
		return new Object2ObjectOpenHashMap<>(map);
	}
}
