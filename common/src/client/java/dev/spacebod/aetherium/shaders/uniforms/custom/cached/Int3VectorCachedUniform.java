package dev.spacebod.aetherium.shaders.uniforms.custom.cached;

import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency;
import dev.spacebod.aetherium.shaders.parsing.VectorType;
import org.joml.Vector3i;

import java.util.function.Supplier;

public class Int3VectorCachedUniform extends VectorCachedUniform<Vector3i> {

	public Int3VectorCachedUniform(String name, UniformUpdateFrequency updateFrequency, Supplier<Vector3i> supplier) {
		super(name, updateFrequency, new Vector3i(), supplier);
	}

	@Override
	protected void setFrom(Vector3i other) {
		this.cached.set(other);
	}

	@Override
	public void push(int location) {
		ShaderRenderSystem.uniform3i(location, this.cached.x, this.cached.y, this.cached.z);
	}

	@Override
	public VectorType getType() {
		return VectorType.I_VEC3;
	}
}
