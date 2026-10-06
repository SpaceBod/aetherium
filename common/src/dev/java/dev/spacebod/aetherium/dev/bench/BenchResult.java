package dev.spacebod.aetherium.dev.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import dev.spacebod.aetherium.Aetherium;
import dev.spacebod.aetherium.core.AetheriumConfig;
import dev.spacebod.aetherium.core.Optimisations;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * The result file of one measured run. Raw per-frame data is kept (microseconds, as integers) so the
 * report can compute any statistic later; the small {@code quick} block is only for the log line and for
 * eyeballing the file. Format documented in {@code benchmarks/README.md}.
 */
final class BenchResult {
	static final int FORMAT = 1;
	private static final Gson GSON = new GsonBuilder().create();

	private BenchResult() {
	}

	static JsonObject build(Minecraft mc, BenchRequest request, Scenario scenario, FrameRecorder frames, PassStats passes,
							JsonArray timeline, JsonArray system, BenchCounters.Snapshot work,
							double joinSeconds, double settledSeconds, boolean settleTimedOut, long untimedFrames, long throttledFrames,
							double heapAfterGcSettledMb, double heapAfterGcEndMb) {
		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT);
		root.addProperty("mode", "measure");
		root.addProperty("finishedAt", Instant.now().toString());
		root.add("meta", meta(mc, request));
		root.add("scenario", scenario.raw());

		JsonObject load = new JsonObject();
		load.addProperty("joinSeconds", joinSeconds);
		load.addProperty("settledSeconds", settledSeconds);
		load.addProperty("settleTimedOut", settleTimedOut);
		// Live heap after full GCs: retained memory, the number memory optimisations change (heap samples include garbage).
		load.addProperty("heapAfterGcSettledMB", heapAfterGcSettledMb);
		load.addProperty("heapAfterGcEndMB", heapAfterGcEndMb);
		root.add("load", load);

		JsonObject w = new JsonObject();
		w.addProperty("sectionsCompiled", work.compiled());
		w.addProperty("sectionsCancelled", work.cancelled());
		w.addProperty("compileMs", work.compileNanos() / 1e6);
		w.addProperty("resorts", work.resorts());
		w.addProperty("resortMs", work.resortNanos() / 1e6);
		w.addProperty("rebuiltWithin1s", work.rebuiltWithin1s());
		JsonObject dirtiedBy = new JsonObject();
		JsonObject rebuiltBy = new JsonObject();
		for (int i = 0; i < DirtySources.NAMES.length; i++) {
			dirtiedBy.addProperty(DirtySources.NAMES[i], work.dirtiedBy()[i]);
			rebuiltBy.addProperty(DirtySources.NAMES[i], work.rebuiltBy()[i]);
		}
		w.add("dirtiedBy", dirtiedBy);
		w.add("rebuiltWithin1sBy", rebuiltBy);
		JsonObject changes = new JsonObject();
		DirtySources.BLOCK_CHANGES.entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
				.limit(25)
				.forEach(e -> changes.addProperty(e.getKey(), e.getValue().sum()));
		w.add("blockChangesTop", changes);
		root.add("work", w);

		JsonObject f = new JsonObject();
		int n = frames.size();
		f.addProperty("count", n);
		f.addProperty("untimedGpuFrames", untimedFrames);
		f.addProperty("throttledFrames", throttledFrames);
		f.addProperty("unit", "us");
		f.add("interval", micros(frames, n, 0));
		f.add("cpu", micros(frames, n, 1));
		f.add("gpu", micros(frames, n, 2));
		root.add("frames", f);

		JsonObject p = new JsonObject();
		for (Map.Entry<String, PassStats.Pass> e : passes.passes().entrySet()) {
			JsonObject pass = new JsonObject();
			pass.add("cpu", micros(e.getValue().cpu.toArray()));
			pass.add("gpu", micros(e.getValue().gpu.toArray()));
			p.add(e.getKey(), pass);
		}
		root.add("passes", p);
		root.add("timeline", timeline);
		root.add("system", system);
		root.add("quick", quick(frames));
		return root;
	}

	private static JsonObject meta(Minecraft mc, BenchRequest request) {
		JsonObject meta = request.meta().deepCopy();
		meta.addProperty("minecraft", SharedConstants.getCurrentVersion().name());
		meta.addProperty("profile", AetheriumConfig.get().profile);
		JsonObject opts = new JsonObject();
		Optimisations.snapshot().forEach(opts::addProperty);
		meta.add("optimisations", opts);

		DeviceInfo device = RenderSystem.getDevice().getDeviceInfo();
		JsonObject gpu = new JsonObject();
		gpu.addProperty("backend", device.backendName());
		gpu.addProperty("name", device.name());
		gpu.addProperty("vendor", device.vendorName());
		gpu.addProperty("driver", device.driverInfo());
		gpu.addProperty("type", String.valueOf(device.type()));
		gpu.addProperty("terrainMultiDrawIndirect", mc.levelRenderer.isChunkRenderingUsingMultiDrawIndirect());
		meta.add("device", gpu);

		Options o = mc.options;
		JsonObject options = new JsonObject();
		options.addProperty("renderDistance", o.renderDistance().get());
		options.addProperty("simulationDistance", o.simulationDistance().get());
		options.addProperty("fov", o.fov().get());
		options.addProperty("graphicsPreset", String.valueOf(o.graphicsPreset().get()));
		options.addProperty("framebufferWidth", mc.getWindow().getWidth());
		options.addProperty("framebufferHeight", mc.getWindow().getHeight());
		meta.add("options", options);

		JsonObject jvm = new JsonObject();
		jvm.addProperty("version", Runtime.version().toString());
		jvm.addProperty("vendor", System.getProperty("java.vendor"));
		jvm.addProperty("maxHeapMB", Runtime.getRuntime().maxMemory() / 1048576);
		jvm.addProperty("availableProcessors", Runtime.getRuntime().availableProcessors());
		JsonArray args = new JsonArray();
		ManagementFactory.getRuntimeMXBean().getInputArguments().forEach(args::add);
		jvm.add("args", args);
		meta.add("jvm", jvm);
		meta.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
		return meta;
	}

	private static JsonArray micros(FrameRecorder frames, int n, int which) {
		JsonArray a = new JsonArray(n);
		for (int i = 0; i < n; i++) {
			long v = switch (which) {
				case 0 -> frames.interval(i);
				case 1 -> frames.cpu(i);
				default -> frames.gpu(i);
			};
			a.add(v < 0 ? -1 : Math.round(v / 1000.0));
		}
		return a;
	}

	private static JsonArray micros(long[] values) {
		JsonArray a = new JsonArray(values.length);
		for (long v : values) {
			a.add(Math.round(v / 1000.0));
		}
		return a;
	}

	private static JsonObject quick(FrameRecorder frames) {
		JsonObject q = new JsonObject();
		int n = frames.size();
		if (n < 2) {
			return q;
		}
		long[] intervals = new long[n - 1];
		long total = 0;
		for (int i = 1; i < n; i++) {
			intervals[i - 1] = frames.interval(i);
			total += frames.interval(i);
		}
		Arrays.sort(intervals);
		q.addProperty("avgFps", (n - 1) / (total / 1e9));
		q.addProperty("p50Ms", intervals[(int) (intervals.length * 0.50)] / 1e6);
		q.addProperty("p99Ms", intervals[Math.min(intervals.length - 1, (int) (intervals.length * 0.99))] / 1e6);
		return q;
	}

	static String oneLine(FrameRecorder frames) {
		JsonObject q = quick(frames);
		if (!q.has("avgFps")) {
			return "no frames recorded";
		}
		return String.format(Locale.ROOT, "%d frames, %.1f fps avg, p50 %.2f ms, p99 %.2f ms",
				frames.size(), q.get("avgFps").getAsDouble(), q.get("p50Ms").getAsDouble(), q.get("p99Ms").getAsDouble());
	}

	static void write(Path path, JsonObject result) {
		try {
			Files.createDirectories(path.toAbsolutePath().getParent());
			Files.writeString(path, GSON.toJson(result), StandardCharsets.UTF_8);
			Aetherium.LOG.info("Benchmark result written to {}", path);
		} catch (IOException e) {
			Aetherium.LOG.error("Could not write benchmark result {}", path, e);
		}
	}
}
