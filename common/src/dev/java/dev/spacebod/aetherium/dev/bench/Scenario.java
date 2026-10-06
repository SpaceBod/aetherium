package dev.spacebod.aetherium.dev.bench;

import com.google.gson.JsonObject;

/**
 * One benchmark scenario, parsed from {@code benchmarks/scenarios/<id>.json} (the runner embeds the file in
 * the request). See {@code benchmarks/README.md} for the format.
 *
 * @param start           {@code settled}: measure once every section around the start pose is built (render
 *                        throughput); {@code joined}: measure from the moment the world opens (loading and
 *                        streaming cost included)
 * @param pregenerate     whether world preparation flies this path so its chunks already exist on disk; a
 *                        scenario that flies into new terrain (worldgen cost) sets this to false
 */
public record Scenario(
		String id,
		String description,
		String world,
		int renderDistance,
		int simulationDistance,
		int fov,
		long timeOfDay,
		String start,
		double settleTimeoutSeconds,
		double warmupSeconds,
		boolean pregenerate,
		CameraPath path,
		JsonObject raw) {

	public static Scenario parse(JsonObject o) {
		return new Scenario(
				o.get("id").getAsString(),
				str(o, "description", ""),
				str(o, "world", "overworld-a"),
				integer(o, "renderDistance", 16),
				integer(o, "simulationDistance", 8),
				integer(o, "fov", 70),
				o.has("timeOfDay") ? o.get("timeOfDay").getAsLong() : 6000L,
				str(o, "start", "settled"),
				dbl(o, "settleTimeoutSeconds", 180),
				dbl(o, "warmupSeconds", 3),
				!o.has("pregenerate") || o.get("pregenerate").getAsBoolean(),
				CameraPath.parse(o.getAsJsonArray("path")),
				o);
	}

	public boolean startsSettled() {
		return !"joined".equals(start);
	}

	private static String str(JsonObject o, String key, String fallback) {
		return o.has(key) ? o.get(key).getAsString() : fallback;
	}

	private static int integer(JsonObject o, String key, int fallback) {
		return o.has(key) ? o.get(key).getAsInt() : fallback;
	}

	private static double dbl(JsonObject o, String key, double fallback) {
		return o.has(key) ? o.get(key).getAsDouble() : fallback;
	}
}
