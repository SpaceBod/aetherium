package dev.spacebod.aetherium.shaders.gl.uniform;

import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import dev.spacebod.aetherium.shaders.gl.state.ValueUpdateNotifier;

import java.util.function.IntSupplier;

public class IntUniform extends Uniform {
	/** The notifier listener is registered (first update). */
	private boolean listening;

	private final IntSupplier value;
	private int cachedValue;

	IntUniform(int location, IntSupplier value) {
		this(location, value, null);
	}

	IntUniform(int location, IntSupplier value, ValueUpdateNotifier notifier) {
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
		int newValue = value.getAsInt();

		if (cachedValue != newValue) {
			cachedValue = newValue;
			ShaderRenderSystem.uniform1i(location, newValue);
		}
	}
}
