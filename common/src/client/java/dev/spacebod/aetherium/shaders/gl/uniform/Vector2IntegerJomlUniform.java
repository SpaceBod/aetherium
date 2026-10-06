package dev.spacebod.aetherium.shaders.gl.uniform;

import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import dev.spacebod.aetherium.shaders.gl.state.ValueUpdateNotifier;
import org.joml.Vector2i;

import java.util.function.Supplier;

public class Vector2IntegerJomlUniform extends Uniform {
	/** The notifier listener is registered (first update). */
	private boolean listening;

	private final Supplier<Vector2i> value;
	private final Vector2i cachedValue = new Vector2i();
	/** Something was written (the first value is always written). */
	private boolean written;

	Vector2IntegerJomlUniform(int location, Supplier<Vector2i> value) {
		this(location, value, null);
	}

	Vector2IntegerJomlUniform(int location, Supplier<Vector2i> value, ValueUpdateNotifier notifier) {
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
		Vector2i newValue = value.get();

		if (!written || !newValue.equals(cachedValue)) {
			// A copy: a supplier returning one reused object would otherwise always compare equal to the cache.
			written = true;
			cachedValue.set(newValue);
			ShaderRenderSystem.uniform2i(this.location, newValue.x, newValue.y);
		}
	}
}
