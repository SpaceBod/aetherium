package dev.spacebod.aetherium.shaders.gl.uniform;

import dev.spacebod.aetherium.shaders.gl.state.ValueUpdateNotifier;

public abstract class Uniform {
	protected final int location;
	protected final ValueUpdateNotifier notifier;

	Uniform(int location) {
		this(location, null);
	}

	Uniform(int location, ValueUpdateNotifier notifier) {
		this.location = location;
		this.notifier = notifier;
	}

	public abstract void update();

	public final int getLocation() {
		return location;
	}
}
