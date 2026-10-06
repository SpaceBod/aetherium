package dev.spacebod.aetherium.shaders.gl.uniform;

import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import dev.spacebod.aetherium.shaders.gl.state.ValueUpdateNotifier;

import java.util.Arrays;
import java.util.function.Supplier;

public class Vector4ArrayUniform extends Uniform {
	/** The notifier listener is registered (first update). */
	private boolean listening;

	private final Supplier<float[]> value;
	private final float[] cachedValue = new float[4];

	Vector4ArrayUniform(int location, Supplier<float[]> value) {
		this(location, value, null);
	}

	Vector4ArrayUniform(int location, Supplier<float[]> value, ValueUpdateNotifier notifier) {
		super(location, notifier);

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

	private void updateValue() {
		float[] newValue = value.get();

		if (!Arrays.equals(newValue, cachedValue)) {
			// A copy: a supplier returning one reused object would otherwise always compare equal to the cache.
			System.arraycopy(newValue, 0, cachedValue, 0, 4);
			ShaderRenderSystem.uniform4f(location, cachedValue[0], cachedValue[1], cachedValue[2], cachedValue[3]);
		}
	}
}
