package dev.spacebod.aetherium.shaders.uniforms.custom.cached;

import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency;
import dev.spacebod.aetherium.shaders.parsing.VectorType;
import org.joml.Vector4f;

import java.util.function.Supplier;

public class Float4VectorCachedUniform extends VectorCachedUniform<Vector4f> {

	public Float4VectorCachedUniform(String name, UniformUpdateFrequency updateFrequency, Supplier<Vector4f> supplier) {
		super(name, updateFrequency, new Vector4f(), supplier);
	}

	@Override
	protected void setFrom(Vector4f other) {
		this.cached.set(other);
	}

	@Override
	public void push(int location) {
		ShaderRenderSystem.uniform4f(location, this.cached.x, this.cached.y, this.cached.z, this.cached.w);
	}

	@Override
	public VectorType getType() {
		return VectorType.VEC4;
	}
}
