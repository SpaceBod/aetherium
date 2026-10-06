package dev.spacebod.aetherium.shaders.gl.uniform;

import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import dev.spacebod.aetherium.shaders.gl.state.ValueUpdateNotifier;
import org.joml.Vector2f;

import java.util.function.Supplier;

public class Vector2Uniform extends Uniform {
	/** The notifier listener is registered (first update). */
	private boolean listening;

	private final Supplier<Vector2f> value;
	private final Vector2f cachedValue = new Vector2f();
	/** Something was written (the first value is always written). */
	private boolean written;

	Vector2Uniform(int location, Supplier<Vector2f> value) {
		this(location, value, null);
	}

	Vector2Uniform(int location, Supplier<Vector2f> value, ValueUpdateNotifier notifier) {
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
		Vector2f newValue = value.get();

		if (!written || !newValue.equals(cachedValue)) {
			// A copy: a supplier returning one reused object would otherwise always compare equal to the cache.
			written = true;
			cachedValue.set(newValue);
			ShaderRenderSystem.uniform2f(this.location, newValue.x, newValue.y);
		}
	}
}
