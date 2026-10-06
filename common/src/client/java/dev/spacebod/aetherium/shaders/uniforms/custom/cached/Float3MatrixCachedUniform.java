package dev.spacebod.aetherium.shaders.uniforms.custom.cached;

import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency;
import dev.spacebod.aetherium.shaders.parsing.MatrixType;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;

import java.util.function.Supplier;

public class Float3MatrixCachedUniform extends VectorCachedUniform<Matrix3fc> {
	final private float[] buffer = new float[9];

	public Float3MatrixCachedUniform(String name, UniformUpdateFrequency updateFrequency, Supplier<Matrix3fc> supplier) {
		super(name, updateFrequency, new Matrix3f(), supplier);
	}

	@Override
	protected void setFrom(Matrix3fc other) {
		((Matrix3f) this.cached).set(other);
	}

	@Override
	public void push(int location) {
		this.cached.get(buffer);
		ShaderRenderSystem.uniformMatrix3fv(location, false, buffer);
	}

	@Override
	public MatrixType<Matrix3f> getType() {
		return MatrixType.MAT3;
	}
}
