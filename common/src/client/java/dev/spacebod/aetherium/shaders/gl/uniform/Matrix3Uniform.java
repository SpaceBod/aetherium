package dev.spacebod.aetherium.shaders.gl.uniform;

import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import dev.spacebod.aetherium.shaders.gl.state.ValueUpdateNotifier;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.function.Supplier;

public class Matrix3Uniform extends Uniform {
	/** The notifier listener is registered (first update). */
	private boolean listening;

	private final FloatBuffer buffer = BufferUtils.createFloatBuffer(9);
	private final Supplier<Matrix3fc> value;
	private final Matrix3f cachedValue;

	Matrix3Uniform(int location, Supplier<Matrix3fc> value) {
		super(location);

		this.cachedValue = new Matrix3f();
		this.value = value;
	}

	Matrix3Uniform(int location, Supplier<Matrix3fc> value, ValueUpdateNotifier notifier) {
		super(location, notifier);

		this.cachedValue = new Matrix3f();
		this.value = value;
	}

	@Override
	public void update() {
		updateValue();

		if (notifier != null && !listening) {
			// Registered once: the listener is a fixed method reference, so a bind allocates nothing.
			listening = true;
			notifier.setListener(this::updateValue);
		}
	}

	public void updateValue() {
		Matrix3fc newValue = value.get();

		if (!cachedValue.equals(newValue)) {
			cachedValue.set(newValue);

			cachedValue.get(buffer);
			buffer.rewind();

			ShaderRenderSystem.uniformMatrix3fv(location, false, buffer);
		}
	}
}
