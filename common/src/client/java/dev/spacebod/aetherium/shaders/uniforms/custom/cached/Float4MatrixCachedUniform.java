package dev.spacebod.aetherium.shaders.uniforms.custom.cached;

import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency;
import dev.spacebod.aetherium.shaders.parsing.MatrixType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.function.Supplier;

public class Float4MatrixCachedUniform extends VectorCachedUniform<Matrix4fc> {
	final private float[] buffer = new float[16];

	public Float4MatrixCachedUniform(String name, UniformUpdateFrequency updateFrequency, Supplier<Matrix4fc> supplier) {
		super(name, updateFrequency, new Matrix4f(), supplier);
	}

	@Override
	protected void setFrom(Matrix4fc other) {
		((Matrix4f) this.cached).set(other);
	}

	@Override
	public void push(int location) {
		this.cached.get(buffer);
		ShaderRenderSystem.uniformMatrix4fv(location, false, buffer);
	}

	@Override
	public MatrixType<Matrix4f> getType() {
		return MatrixType.MAT4;
	}
}
