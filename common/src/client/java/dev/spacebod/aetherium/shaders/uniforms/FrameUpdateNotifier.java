package dev.spacebod.aetherium.shaders.uniforms;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame hooks of one loaded pack's uniforms: {@link #onNewFrame} runs once at every frame start, {@link #reset}
 * when the history they keep (smoothed values, the previous camera position) must start over, e.g. on entering a world.
 */
public class FrameUpdateNotifier {
	private final List<Runnable> listeners = new ArrayList<>();
	private final List<Runnable> resetListeners = new ArrayList<>();

	public void addListener(Runnable onNewFrame) {
		listeners.add(onNewFrame);
	}

	public void addResetListener(Runnable onReset) {
		resetListeners.add(onReset);
	}

	public void onNewFrame() {
		for (Runnable listener : listeners) {
			listener.run();
		}
	}

	/** Forgets the history: the next frame's values are taken as they are, as on the pack's first frame. */
	public void reset() {
		for (Runnable listener : resetListeners) {
			listener.run();
		}
	}
}
