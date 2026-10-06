package dev.spacebod.aetherium.shaders.pipeline.transform.parameter;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTest;
import dev.spacebod.aetherium.shaders.gl.state.ShaderAttributeInputs;
import dev.spacebod.aetherium.shaders.gl.texture.TextureType;
import dev.spacebod.aetherium.shaders.helpers.Tri;
import dev.spacebod.aetherium.shaders.pipeline.transform.Patch;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.VanillaInterface;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;

public class VanillaParameters extends GeometryInfoParameters {
	public final AlphaTest alpha;
	public final ShaderAttributeInputs inputs;
	/** Where the model-view matrix and model offset come from on 26.3 (terrain has its own uniform blocks). */
	public final VanillaInterface.TransformSource source;
	private final boolean isLines;
	private final boolean isClouds;
	// WARNING: adding new fields requires updating hashCode and equals methods!

	public VanillaParameters(
		Patch patch,
		Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
		AlphaTest alpha, boolean isLines, boolean isClouds,
		ShaderAttributeInputs inputs, VanillaInterface.TransformSource source, boolean hasGeometry, boolean hasTesselation) {
		super(patch, textureMap, hasGeometry, hasTesselation);
		this.alpha = alpha;
		this.isLines = isLines;
		this.isClouds = isClouds;
		this.inputs = inputs;
		this.source = source;
	}

	public boolean isLines() {
		return isLines;
	}

	public boolean isClouds() {
		return isClouds;
	}

	@Override
	public AlphaTest getAlphaTest() {
		return alpha;
	}

	@Override
	public TextureStage getTextureStage() {
		return TextureStage.GBUFFERS_AND_SHADOW;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + ((alpha == null) ? 0 : alpha.hashCode());
		result = prime * result + ((inputs == null) ? 0 : inputs.hashCode());
		result = prime * result + (isLines ? 1231 : 1237);
		result = prime * result + (isClouds ? 1231 : 1237);
		result = prime * result + source.hashCode();
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		VanillaParameters other = (VanillaParameters) obj;
		if (alpha == null) {
			if (other.alpha != null)
				return false;
		} else if (!alpha.equals(other.alpha))
			return false;
		if (inputs == null) {
			if (other.inputs != null)
				return false;
		} else if (!inputs.equals(other.inputs))
			return false;
		if (isClouds != other.isClouds)
			return false;
		if (source != other.source)
			return false;
		return isLines == other.isLines;
	}
}
