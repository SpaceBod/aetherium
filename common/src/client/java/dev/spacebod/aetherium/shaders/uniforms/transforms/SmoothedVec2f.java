package dev.spacebod.aetherium.shaders.uniforms.transforms;

import dev.spacebod.aetherium.shaders.uniforms.FrameUpdateNotifier;
import org.joml.Vector2f;
import org.joml.Vector2i;

import java.util.function.Supplier;

/** {@link SmoothedFloat} per component. {@link #get} returns one reused vector: copy it to keep it. */
public class SmoothedVec2f implements Supplier<Vector2f> {
	private final SmoothedFloat x;
	private final SmoothedFloat y;
	private final Vector2f value = new Vector2f();

	public SmoothedVec2f(float halfLifeUp, float halfLifeDown, Supplier<Vector2i> unsmoothed, FrameUpdateNotifier updateNotifier) {
		x = new SmoothedFloat(halfLifeUp, halfLifeDown, () -> unsmoothed.get().x, updateNotifier);
		y = new SmoothedFloat(halfLifeUp, halfLifeDown, () -> unsmoothed.get().y, updateNotifier);
	}

	@Override
	public Vector2f get() {
		return value.set(x.getAsFloat(), y.getAsFloat());
	}
}
