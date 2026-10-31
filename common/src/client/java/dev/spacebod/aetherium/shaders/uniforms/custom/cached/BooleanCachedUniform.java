package dev.spacebod.aetherium.shaders.uniforms.custom.cached;

import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import kroppeb.stareval.function.FunctionReturn;
import kroppeb.stareval.function.Type;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformType;

import java.util.function.BooleanSupplier;

public class BooleanCachedUniform extends CachedUniform {

	final private BooleanSupplier supplier;
	private boolean cached;

	public BooleanCachedUniform(String name, UniformUpdateFrequency updateFrequency, BooleanSupplier supplier) {
		super(name, updateFrequency);
		this.supplier = supplier;
	}

	@Override
	protected boolean doUpdate() {
		boolean prev = this.cached;
		this.cached = this.supplier.getAsBoolean();
		return prev != cached;
	}

	@Override
	public void push(int location) {
		ShaderRenderSystem.uniform1i(location, this.cached ? 1 : 0);
	}

	@Override
	public void push(int location, UniformType type) {
		if (type == UniformType.FLOAT) {
			ShaderRenderSystem.uniform1f(location, this.cached ? 1.0f : 0.0f);
		} else {
			push(location);
		}
	}

	@Override
	public void writeTo(FunctionReturn functionReturn) {
		functionReturn.booleanReturn = this.cached;
	}

	@Override
	public Type getType() {
		return Type.Boolean;
	}
}
