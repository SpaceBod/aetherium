package dev.spacebod.aetherium.shaders.gl.uniform;

import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import dev.spacebod.aetherium.shaders.gl.state.ValueUpdateNotifier;

public class FloatUniform extends Uniform {
	/** The notifier listener is registered (first update). */
	private boolean listening;

	private final FloatSupplier value;
	private float cachedValue;

	FloatUniform(int location, FloatSupplier value) {
		this(location, value, null);
	}

	FloatUniform(int location, FloatSupplier value, ValueUpdateNotifier notifier) {
		super(location, notifier);

		this.cachedValue = 0;
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
		float newValue = value.getAsFloat();

		if (cachedValue != newValue) {
			cachedValue = newValue;
			ShaderRenderSystem.uniform1f(location, newValue);
		}
	}
}
