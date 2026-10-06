package dev.spacebod.aetherium.shaders.pipeline.transform.parameter;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import dev.spacebod.aetherium.shaders.gl.texture.TextureType;
import dev.spacebod.aetherium.shaders.helpers.Tri;
import dev.spacebod.aetherium.shaders.pipeline.transform.Patch;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;

public class ComputeParameters extends TextureStageParameters {
	// WARNING: adding new fields requires updating hashCode and equals methods!

	public ComputeParameters(Patch patch, TextureStage stage,
							 Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap) {
		super(patch, stage, textureMap);
	}

	// No fields of its own, so hashCode() and equals() are inherited.
}
