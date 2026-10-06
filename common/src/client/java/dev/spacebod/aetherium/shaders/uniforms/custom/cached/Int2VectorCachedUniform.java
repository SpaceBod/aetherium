package dev.spacebod.aetherium.shaders.uniforms.custom.cached;

import kroppeb.stareval.function.FunctionReturn;
import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency;
import dev.spacebod.aetherium.shaders.parsing.VectorType;
import org.joml.Vector2i;

import java.util.function.Supplier;

public class Int2VectorCachedUniform extends VectorCachedUniform<Vector2i> {

	public Int2VectorCachedUniform(String name, UniformUpdateFrequency updateFrequency, Supplier<Vector2i> supplier) {
		super(name, updateFrequency, new Vector2i(), supplier);
	}

	@Override
	protected void setFrom(Vector2i other) {
		this.cached.set(other);
	}

	@Override
	public void push(int location) {
		ShaderRenderSystem.uniform2i(location, this.cached.x, this.cached.y);
	}

	@Override
	public void writeTo(FunctionReturn functionReturn) {
		functionReturn.objectReturn = this.cached;
	}

	@Override
	public VectorType getType() {
		return VectorType.I_VEC2;
	}
}
