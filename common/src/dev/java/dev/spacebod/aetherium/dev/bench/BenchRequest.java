package dev.spacebod.aetherium.dev.bench;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What {@code benchmarks/run.ps1} asks the game to do, written to {@code <game dir>/aetherium/bench/request.json}
 * before launch. One request per game launch: every measured run gets a fresh JVM and a fresh copy of the
 * world, so runs do not inherit each other's JIT state, heap or caches.
 *
 * @param mode        {@code measure} one scenario, {@code prepare} a golden world (create it from the seed,
 *                    fly every pregenerate path, save, quit), or {@code play} (see {@link #isPlay()})
 * @param saveName    folder under {@code saves/} to open (measure, play) or create (prepare)
 * @param output      absolute path of the result JSON to write
 * @param meta        free-form facts from the runner (machine, versions, profile, label) copied into the result
 */
public record BenchRequest(
		String mode,
		String saveName,
		long seed,
		int prepareRenderDistance,
		double prepareTimeScale,
		List<Scenario> scenarios,
		Path output,
		boolean exitWhenDone,
		JsonObject meta) {

	public static Path file() {
		return Path.of("aetherium", "bench", "request.json");
	}

	public boolean isPrepare() {
		return "prepare".equals(mode);
	}

	/** Open the world for the player (creative, on the ground), drive nothing, never quit. */
	public boolean isPlay() {
		return "play".equals(mode);
	}

	public Scenario scenario() {
		return scenarios.getFirst();
	}

	public static BenchRequest read(Path path) throws IOException {
		JsonObject o = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
		List<Scenario> scenarios = new ArrayList<>();
		if (o.has("scenario")) {
			scenarios.add(Scenario.parse(o.getAsJsonObject("scenario")));
		}
		if (o.has("scenarios")) {
			for (JsonElement e : o.getAsJsonArray("scenarios")) {
				scenarios.add(Scenario.parse(e.getAsJsonObject()));
			}
		}
		String mode = o.has("mode") ? o.get("mode").getAsString() : "measure";
		if (scenarios.isEmpty() && !"prepare".equals(mode) && !"play".equals(mode)) {
			throw new IOException("benchmark request has no scenario");
		}
		return new BenchRequest(
				mode,
				o.get("saveName").getAsString(),
				o.has("seed") ? o.get("seed").getAsLong() : 0L,
				o.has("prepareRenderDistance") ? o.get("prepareRenderDistance").getAsInt() : 32,
				o.has("prepareTimeScale") ? o.get("prepareTimeScale").getAsDouble() : 3.0,
				List.copyOf(scenarios),
				Path.of(o.has("output") ? o.get("output").getAsString() : "aetherium/bench/play.json"),
				!o.has("exitWhenDone") || o.get("exitWhenDone").getAsBoolean(),
				o.has("meta") ? o.getAsJsonObject("meta") : new JsonObject());
	}
}
