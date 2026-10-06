package dev.spacebod.aetherium.shaders.gl.state;

public interface ValueUpdateNotifier {
	/**
	 * Registers the listener to run when the value changes.
	 */
	void setListener(Runnable listener);
}
