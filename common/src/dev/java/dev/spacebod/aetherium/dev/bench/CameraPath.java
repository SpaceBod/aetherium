package dev.spacebod.aetherium.dev.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * A scripted camera: keyframes at times in seconds, positions as offsets from the world's anchor point
 * (spawn column, surface height), yaw/pitch in degrees. Linear interpolation, so a straight segment is a
 * constant speed; yaw is interpolated on the raw values, so 0 -> 360 is one full turn.
 */
public final class CameraPath {
	public record Key(double t, double dx, double dy, double dz, float yaw, float pitch) {
	}

	public record Pose(double x, double y, double z, float yaw, float pitch) {
	}

	private final List<Key> keys;

	private CameraPath(List<Key> keys) {
		this.keys = keys;
	}

	public static CameraPath parse(JsonArray array) {
		List<Key> keys = new ArrayList<>();
		for (JsonElement e : array) {
			JsonObject o = e.getAsJsonObject();
			keys.add(new Key(
					o.get("t").getAsDouble(),
					num(o, "dx"), num(o, "dy"), num(o, "dz"),
					(float) num(o, "yaw"), (float) num(o, "pitch")));
		}
		if (keys.isEmpty()) {
			throw new IllegalArgumentException("camera path needs at least one keyframe");
		}
		keys.sort((a, b) -> Double.compare(a.t(), b.t()));
		return new CameraPath(List.copyOf(keys));
	}

	private static double num(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsDouble() : 0.0;
	}

	public double duration() {
		return keys.getLast().t();
	}

	/** Distance flown along the path in blocks (3D), for the report and for pacing world preparation. */
	public double length() {
		double sum = 0;
		for (int i = 1; i < keys.size(); i++) {
			Key a = keys.get(i - 1);
			Key b = keys.get(i);
			sum += Math.sqrt(sq(b.dx() - a.dx()) + sq(b.dy() - a.dy()) + sq(b.dz() - a.dz()));
		}
		return sum;
	}

	private static double sq(double v) {
		return v * v;
	}

	public Pose sample(double t, double anchorX, double anchorY, double anchorZ) {
		Key a = keys.getFirst();
		Key b = a;
		if (t <= a.t()) {
			return pose(a, anchorX, anchorY, anchorZ);
		}
		for (int i = 1; i < keys.size(); i++) {
			b = keys.get(i);
			if (t <= b.t()) {
				a = keys.get(i - 1);
				double f = (t - a.t()) / Math.max(1e-9, b.t() - a.t());
				return new Pose(
						anchorX + lerp(a.dx(), b.dx(), f),
						anchorY + lerp(a.dy(), b.dy(), f),
						anchorZ + lerp(a.dz(), b.dz(), f),
						(float) lerp(a.yaw(), b.yaw(), f),
						(float) lerp(a.pitch(), b.pitch(), f));
			}
		}
		return pose(keys.getLast(), anchorX, anchorY, anchorZ);
	}

	private static Pose pose(Key k, double x, double y, double z) {
		return new Pose(x + k.dx(), y + k.dy(), z + k.dz(), k.yaw(), k.pitch());
	}

	private static double lerp(double a, double b, double f) {
		return a + (b - a) * f;
	}
}
