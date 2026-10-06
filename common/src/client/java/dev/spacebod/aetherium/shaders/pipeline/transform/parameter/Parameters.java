package dev.spacebod.aetherium.shaders.pipeline.transform.parameter;

import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTest;
import dev.spacebod.aetherium.shaders.gl.texture.TextureType;
import dev.spacebod.aetherium.shaders.helpers.Tri;
import dev.spacebod.aetherium.shaders.pipeline.transform.Patch;
import dev.spacebod.aetherium.shaders.pipeline.transform.PatchShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.LoweringParameters;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.VulkanLowering;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;

public abstract class Parameters implements JobParameters {
	public final Patch patch;
	private final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap;
	public PatchShaderType type; // may only be set by TransformPatcher
	// WARNING: adding new fields requires updating hashCode and equals methods!

	// Set by TransformPatcher; deliberately excluded from hashCode/equals.
	public String name;
	/** Set by TransformPatcher for one transform (part of its cache key separately): the Vulkan lowering's parameters. */
	public LoweringParameters lowering;
	/** Written by the lowering during the transform; read back by TransformPatcher. */
	public VulkanLowering.Result lowered;

	public Parameters(Patch patch, Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap) {
		this.patch = patch;
		this.textureMap = textureMap;
	}

	public AlphaTest getAlphaTest() {
		return AlphaTest.ALWAYS;
	}

	public abstract TextureStage getTextureStage();

	public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() {
		return textureMap;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((patch == null) ? 0 : patch.hashCode());
		result = prime * result + ((textureMap == null) ? 0 : textureMap.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Parameters other = (Parameters) obj;
		if (patch != other.patch)
			return false;
		if (textureMap == null) {
			return other.textureMap == null;
		} else return textureMap.equals(other.textureMap);
	}
}
