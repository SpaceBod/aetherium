package dev.spacebod.aetherium.dev.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import dev.spacebod.aetherium.Aetherium;
import dev.spacebod.aetherium.client.mixin.chunks.WorldRendererAccessor;
import dev.spacebod.aetherium.core.AetheriumConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Runs one benchmark request from start to exit, driven by three hooks on {@code Minecraft}: the client
 * tick (state machine, 20 Hz, where anything that opens screens or worlds happens), and the start and end
 * of every rendered frame (camera placement and measurement only, so nothing heavy re-enters
 * {@code renderFrame}).
 *
 * <p>Measure: title screen -> apply options -> open the copied golden world -> set time/weather/rules ->
 * wait until every section around the start pose is built ("settled") -> warm up -> fly the camera path
 * while recording -> drain late GPU results -> write JSON -> quit.
 *
 * <p>Prepare: title screen -> create the world from the seed -> record the anchor (spawn column, surface
 * height) -> for every pregenerate scenario, settle at its start, fly its path slowly, settle at its end ->
 * save -> quit. The runner then keeps that save as the golden copy.
 */
public final class BenchDriver {
	private static final String ANCHOR_FILE = "aetherium-anchor.json";
	private static final int SETTLE_STABLE_SECONDS = 3;
	private static final int DRAIN_FRAMES = 12;

	private static @Nullable BenchDriver active;

	private enum Phase { WAIT_TITLE, LOADING, SETTLING, WARMUP, RUNNING, DRAIN, PREP_FLY, SAVING, DONE, PLAY }

	/** One leg of the job: settle at the path start, optionally fly it (measure: record; prepare: slow flight). */
	private record Leg(Scenario scenario, boolean measure, double timeScale) {
	}

	private final BenchRequest request;
	private final AetheriumConfig config;
	private final Deque<Leg> legs = new ArrayDeque<>();
	private Leg leg;
	private Phase phase = Phase.WAIT_TITLE;
	private long phaseStart = System.nanoTime();

	private double anchorX;
	private double anchorY;
	private double anchorZ;

	// Frame measurement
	private @Nullable GpuTimer gpuTimer;
	private boolean gpuTimerTried;
	private long frameNo;
	private boolean inFrame;
	private long lastFrameEnd;
	private long runStart;
	private long runFirstFrame = -1;
	private long runLastFrame = Long.MAX_VALUE;
	private int drainFrames;
	/** Frames of the measured window that vanilla's frame limiter throttled (minimised, AFK): must be 0. */
	private long throttledFrames;
	private final FrameRecorder frames = new FrameRecorder();
	private final PassStats passStats = new PassStats();
	private final GpuTimer.Sink gpuSink = this::onGpuFrame;

	// Timeline (1 Hz, from world join)
	private long origin;
	private long lastSample;
	private int framesSinceSample;
	private BenchCounters.Snapshot lastCounters = BenchCounters.Snapshot.take();
	private BenchCounters.Snapshot runStartCounters = lastCounters;
	private final JsonArray timeline = new JsonArray();
	private @Nullable SystemSampler systemSampler;

	// Settling
	private int stableSeconds;
	/** Prepare only: settling at the end of a flown path rather than at its start. */
	private boolean settleAtEnd;
	private int lastLoadedChunks = -1;
	/** {@code AETHERIUM_DOC_SHOTS} set: the documentation screenshot tour, run in play mode. */
	private final @Nullable DocShots docShots = System.getenv("AETHERIUM_DOC_SHOTS") != null ? new DocShots() : null;
	private int lastVisibleSections = -1;
	private double joinSeconds = -1;
	private double settledSeconds = -1;
	private double heapAfterGcSettledMb = -1;
	/** {@code AETHERIUM_BENCH_SCREENSHOT=1}: save one frame from mid-run to {@code screenshots/aetherium-<scenario>.png}. */
	private static final boolean SCREENSHOT = System.getenv("AETHERIUM_BENCH_SCREENSHOT") != null;
	private boolean screenshotTaken;
	/** {@code AETHERIUM_BENCH_ALTERNATE=<id>}: in-run A/B of one optimisation (see {@link AlternatingAb}). */
	private final @org.jspecify.annotations.Nullable AlternatingAb alternating = AlternatingAb.fromEnvironment();
	private boolean settleTimedOut;
	private long playStart;
	private static final int BURST = System.getenv("AETHERIUM_PLAY_BURST") == null ? 0 : Integer.parseInt(System.getenv("AETHERIUM_PLAY_BURST"));
	private int burstTaken;
	private boolean playMenuOpened;
	private boolean playScreenshot;
	/**
	 * {@code AETHERIUM_PLAY_PACKS="a.zip;b;off"}: in play mode, switch to each pack in turn ({@code off} = shaders off),
	 * one every {@code AETHERIUM_PLAY_PACK_SECONDS} (default 12) from 8 s in, as the pack screen's Apply does: a smoke
	 * test of every pack and of the switch between packs in one launch.
	 */
	private static final String[] PLAY_PACKS = System.getenv("AETHERIUM_PLAY_PACKS") == null ? new String[0]
			: java.util.Arrays.stream(System.getenv("AETHERIUM_PLAY_PACKS").split(";")).map(String::trim).filter(x -> !x.isEmpty()).toArray(String[]::new);
	private static final int PLAY_PACK_SECONDS = System.getenv("AETHERIUM_PLAY_PACK_SECONDS") == null ? 12
			: Integer.parseInt(System.getenv("AETHERIUM_PLAY_PACK_SECONDS"));
	private int playPacksApplied;

	private BenchDriver(BenchRequest request) {
		this.request = request;
		this.config = AetheriumConfig.get();
		for (Scenario s : request.scenarios()) {
			if (request.isPrepare()) {
				if (s.pregenerate()) {
					legs.add(new Leg(s, false, request.prepareTimeScale()));
				}
			} else {
				legs.add(new Leg(s, true, 1.0));
			}
		}
		if (request.isPrepare()) {
			// End at the anchor, so the player position saved in the world (where a "joined" scenario
			// starts loading chunks) is the same for every run.
			legs.add(new Leg(HOME, false, 1.0));
		}
	}

	private static final Scenario HOME = Scenario.parse(JsonParser.parseString(
			"{\"id\":\"home\",\"settleTimeoutSeconds\":120,\"path\":[{\"t\":0,\"dy\":40,\"yaw\":45,\"pitch\":15}]}").getAsJsonObject());

	/** Called once from client init. Picks up a pending request, if the runner left one. */
	public static void bootstrap() {
		Path file = BenchRequest.file();
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			BenchRequest request = BenchRequest.read(file);
			// Consumed: a crash or a manual relaunch must not re-run the benchmark.
			Files.move(file, file.resolveSibling("request.taken.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			active = new BenchDriver(request);
			Aetherium.LOG.info("Benchmark request: {} {} ({} leg(s))", request.mode(), request.saveName(), active.legs.size());
		} catch (IOException | RuntimeException e) {
			Aetherium.LOG.error("Invalid benchmark request {}", file, e);
		}
	}

	public static @Nullable BenchDriver active() {
		return active;
	}

	// ---------------------------------------------------------------- frame hooks (render thread)

	public void frameBegin(Minecraft mc) {
		if (phase == Phase.SAVING || phase == Phase.DONE || phase == Phase.PLAY) {
			return;
		}
		inFrame = true;
		frameNo++;
		if (!gpuTimerTried) {
			gpuTimerTried = true;
			if (config.gpuTimers) {
				gpuTimer = GpuTimer.create(config.passTimers);
			}
		}
		if (gpuTimer != null) {
			gpuTimer.beginFrame(frameNo);
		}
		placeCamera(mc, System.nanoTime());
	}

	/** GPU-timed stage outside vanilla's frame graph (shader engine passes, hand); stages may nest in frame-graph passes. */
	public static void stageBegin(String name) {
		BenchDriver d = active;
		if (d != null && d.inFrame && d.gpuTimer != null) {
			d.gpuTimer.beforePass(name);
		}
	}

	public static void stageEnd() {
		BenchDriver d = active;
		if (d != null && d.inFrame && d.gpuTimer != null) {
			d.gpuTimer.afterPass();
		}
	}

	public FrameGraphBuilder.Inspector wrap(FrameGraphBuilder.Inspector inspector) {
		return inFrame ? new BenchInspector(inspector, this) : inspector;
	}

	void beforePass(String name) {
		if (gpuTimer != null) {
			gpuTimer.beforePass(name);
		}
	}

	void afterPass(String name, long cpuNanos) {
		if (gpuTimer != null) {
			gpuTimer.afterPass();
		}
		if (phase == Phase.RUNNING) {
			passStats.cpu(name, cpuNanos);
		}
	}

	public void beforeSubmit() {
		if (inFrame && gpuTimer != null) {
			gpuTimer.endFrame();
		}
	}

	public void frameEnd(Minecraft mc) {
		if (!inFrame) {
			return;
		}
		inFrame = false;
		long now = System.nanoTime();
		long interval = lastFrameEnd == 0 ? 0 : now - lastFrameEnd;
		lastFrameEnd = now;
		framesSinceSample++;
		if (phase == Phase.RUNNING) {
			if (runFirstFrame < 0) {
				runFirstFrame = frameNo;
			}
			if (mc.getFramerateLimitTracker().getThrottleReason() != FramerateLimitTracker.FramerateThrottleReason.NONE) {
				throttledFrames++;
			}
			frames.record(frameNo, now - runStart, interval, mc.getFrameTimeNs());
			if (alternating != null) {
				alternating.frameEnd(frameNo, now - runStart, interval, mc.getFrameTimeNs());
			}
			if (SCREENSHOT && screenshotTaken && burstTaken < BURST) {
				// Consecutive frames of a still camera (idle-vista): anything that differs is flicker.
				net.minecraft.client.Screenshot.grab(mc.gameDirectory, "aetherium-burst-" + burstTaken + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> {
				});
				burstTaken++;
			}
			if (SCREENSHOT && !screenshotTaken && (now - runStart) / 1e9 >= leg.scenario().path().duration() / 2) {
				// Visual check of render-side optimisations: one frame from the middle of the measured window.
				screenshotTaken = true;
				net.minecraft.client.Screenshot.grab(mc.gameDirectory, "aetherium-" + leg.scenario().id() + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> {
				});
			}
			if ((now - runStart) / 1e9 >= leg.scenario().path().duration()) {
				runLastFrame = frameNo;
				enter(Phase.DRAIN);
				drainFrames = 0;
			}
		} else if (phase == Phase.DRAIN) {
			drainFrames++;
		}
		if (gpuTimer != null) {
			gpuTimer.poll(gpuSink);
		}
	}

	private void onGpuFrame(long frame, long gpuNanos, String[] names, long[] passNanos, int passCount) {
		if (runFirstFrame < 0 || frame < runFirstFrame || frame > runLastFrame) {
			return;
		}
		frames.setGpu(frame, gpuNanos);
		for (int i = 0; i < passCount; i++) {
			passStats.gpu(names[i], passNanos[i]);
		}
		if (alternating != null) {
			alternating.gpuFrame(frame, gpuNanos, names, passNanos, passCount);
		}
	}

	private void placeCamera(Minecraft mc, long now) {
		LocalPlayer player = mc.player;
		if (player == null || leg == null) {
			return;
		}
		double t;
		switch (phase) {
			case SETTLING -> t = settleAtEnd ? leg.scenario().path().duration() : 0;
			case WARMUP -> t = 0;
			case RUNNING -> t = (now - runStart) / 1e9;
			case PREP_FLY -> t = (now - phaseStart) / 1e9 / leg.timeScale();
			case DRAIN -> t = leg.scenario().path().duration();
			default -> {
				return;
			}
		}
		CameraPath.Pose pose = leg.scenario().path().sample(t, anchorX, anchorY, anchorZ);
		player.getAbilities().flying = true;
		player.setDeltaMovement(Vec3.ZERO);
		player.snapTo(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
	}

	// ---------------------------------------------------------------- tick hook (state machine)

	public void clientTick(Minecraft mc) {
		long now = System.nanoTime();
		try {
			switch (phase) {
				case WAIT_TITLE -> waitTitle(mc);
				case LOADING -> loading(mc, now);
				case SETTLING -> settling(mc, now);
				case WARMUP -> {
					if (seconds(now - phaseStart) >= leg.scenario().warmupSeconds()) {
						startRun(now);
					}
				}
				case PREP_FLY -> {
					if (seconds(now - phaseStart) >= leg.scenario().path().duration() * leg.timeScale()) {
						// Settle again at the end of the path so its last chunks are generated and saved.
						beginSettle(now, true);
					}
				}
				case DRAIN -> {
					if (drainFrames >= DRAIN_FRAMES) {
						finishMeasuredLeg(mc);
					}
				}
				case PLAY -> {
					if (docShots != null) {
						docShots.tick(mc, seconds(now - playStart));
					}
					// AETHERIUM_PLAY_MENU=1: open the shader settings (visual check of the menu in the screenshot);
					// =settings: open the video settings screen and step through every page, one per second (smoke test).
					String menu = System.getenv("AETHERIUM_PLAY_MENU");
					if (menu != null && !playMenuOpened && seconds(now - playStart) >= 8) {
						playMenuOpened = true;
						mc.gui.setScreen(switch (menu) {
							case "settings" -> new dev.spacebod.aetherium.client.gui.AetheriumSettingsScreen(null);
							// =video: vanilla's Video Settings (with our entry buttons), then ours from it after 4 s.
							case "video" -> new net.minecraft.client.gui.screens.options.VideoSettingsScreen(null, mc, mc.options);
							default -> new dev.spacebod.aetherium.shaders.gui.ShaderPackScreen(null);
						});
					}
					if (playMenuOpened && "video".equals(menu) && seconds(now - playStart) >= 12
							&& mc.gui.screen() instanceof net.minecraft.client.gui.screens.options.VideoSettingsScreen video) {
						mc.gui.setScreen(new dev.spacebod.aetherium.client.gui.AetheriumSettingsScreen(video));
					}
					if (playMenuOpened && mc.gui.screen() instanceof dev.spacebod.aetherium.client.gui.AetheriumSettingsScreen settings) {
						settings.showPage((int) seconds(now - playStart) - 8);
					}
					if (!playScreenshot && seconds(now - playStart) >= 15) {
						playScreenshot = true;
						net.minecraft.client.Screenshot.grab(mc.gameDirectory, "aetherium-play.png", mc.gameRenderer.mainRenderTarget(), 1, message -> {
						});
					}
					if (playPacksApplied < PLAY_PACKS.length && seconds(now - playStart) >= 8 + playPacksApplied * PLAY_PACK_SECONDS) {
						String pack = PLAY_PACKS[playPacksApplied++];
						Aetherium.LOG.info("Play: switching to shader pack {} ({} of {})", pack, playPacksApplied, PLAY_PACKS.length);
						dev.spacebod.aetherium.shaders.config.ShaderPackSettings.setActivePack(pack.equalsIgnoreCase("off") ? null : pack);
						dev.spacebod.aetherium.shaders.engine.ShaderPackEngine.get().reload();
					}
					// AETHERIUM_PLAY_BURST=N: N more shots on consecutive ticks (flicker diagnosis: nothing moves, time frozen).
					if (playScreenshot && burstTaken < BURST && seconds(now - playStart) >= 16) {
						net.minecraft.client.Screenshot.grab(mc.gameDirectory, "aetherium-burst-" + burstTaken + ".png", mc.gameRenderer.mainRenderTarget(), 1,
								message -> {
								});
						burstTaken++;
					}
				}
				default -> {
				}
			}
			if (origin != 0 && seconds(now - lastSample) >= 1.0 && phase != Phase.SAVING && phase != Phase.DONE && phase != Phase.PLAY) {
				sample(mc, now);
			}
		} catch (RuntimeException e) {
			fail(mc, "benchmark driver crashed in " + phase + ": " + e, e);
		}
	}

	private void waitTitle(Minecraft mc) {
		if (!(mc.gui.screen() instanceof TitleScreen) || mc.gui.overlay() != null) {
			return;
		}
		Scenario first = legs.isEmpty() ? null : legs.peekFirst().scenario();
		applyOptions(mc, request.isPrepare() || first == null ? request.prepareRenderDistance() : first.renderDistance(),
				first == null ? 8 : first.simulationDistance(), first == null ? 70 : first.fov());
		enter(Phase.LOADING);
		if (request.isPrepare()) {
			Aetherium.LOG.info("Benchmark: creating world '{}' from seed {}", request.saveName(), request.seed());
			LevelSettings settings = new LevelSettings(request.saveName(), GameType.SPECTATOR,
					new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
			mc.createWorldOpenFlows().createFreshLevel(request.saveName(), settings, new WorldOptions(request.seed(), true, false),
					WorldPresets::createNormalWorldDimensions, new TitleScreen());
		} else {
			Aetherium.LOG.info("Benchmark: opening world '{}'", request.saveName());
			mc.createWorldOpenFlows().openWorld(request.saveName(), () -> fail(mc, "world open cancelled", null));
		}
	}

	private static void applyOptions(Minecraft mc, int renderDistance, int simulationDistance, int fov) {
		Options o = mc.options;
		o.renderDistance().set(renderDistance);
		o.simulationDistance().set(simulationDistance);
		o.fov().set(fov);
		o.framerateLimit().set(260); // 260 = unlimited
		o.enableVsync().set(false);
		o.pauseOnLostFocus = false;
		// Vanilla caps the game at 30 fps after 60 s without input (10 fps after 10 min). A benchmark never
		// touches the input, so without this every run longer than a minute would end throttled.
		o.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
	}

	private void loading(Minecraft mc, long now) {
		if (mc.player == null || mc.level == null || mc.gui.screen() != null || mc.gui.overlay() != null) {
			if (seconds(now - phaseStart) > 300) {
				fail(mc, "world did not open within 300 s", null);
			}
			return;
		}
		origin = now;
		lastSample = now;
		lastCounters = BenchCounters.Snapshot.take();
		systemSampler = new SystemSampler(origin);
		systemSampler.start();
		Path save = mc.getLevelSource().getBaseDir().resolve(request.saveName());
		if (request.isPrepare()) {
			anchorX = Math.floor(mc.player.getX()) + 0.5;
			anchorY = Math.floor(mc.player.getY());
			anchorZ = Math.floor(mc.player.getZ()) + 0.5;
			writeAnchor(save.resolve(ANCHOR_FILE));
		} else if (!readAnchor(save.resolve(ANCHOR_FILE))) {
			fail(mc, "world '" + request.saveName() + "' has no " + ANCHOR_FILE + " (prepare it with run.ps1 -Prepare)", null);
			return;
		}
		joinSeconds = seconds(now - phaseStart);
		if (request.isPlay()) {
			startPlay(mc, now);
			return;
		}
		leg = legs.pollFirst();
		if (leg == null) {
			enter(Phase.SAVING);
			saveAndQuit(mc);
			return;
		}
		setupWorld(mc, leg.scenario());
		if (leg.measure() && !leg.scenario().startsSettled()) {
			startRun(now);
		} else {
			beginSettle(now, false);
		}
	}

	/**
	 * Play mode ({@code run.ps1 -Play}): the player is handed the world on the ground at the anchor in creative mode, at
	 * noon; nothing is driven or measured and the game does not quit. One screenshot is saved 15 s in
	 * ({@code screenshots/aetherium-play.png}) so a visual check does not need the player.
	 */
	private void startPlay(Minecraft mc, long now) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			String[] commands = {
					"gamemode creative @a",
					"gamerule advance_weather false",
					// Burst captures (flicker diagnosis) freeze time so nothing but the renderer can change between frames.
					BURST > 0 ? "gamerule advance_time false" : "gamerule advance_time true",
					"weather clear",
					"time set 6000",
					String.format(java.util.Locale.ROOT, "tp @a %.1f %.1f %.1f 45 10", anchorX, anchorY, anchorZ),
			};
			// AETHERIUM_PLAY_COMMANDS="cmd1;cmd2": run after the teleport, e.g. "execute at @p run summon cow ^ ^ ^6" to
			// put mobs in front of the camera for an entity check.
			String extra = System.getenv("AETHERIUM_PLAY_COMMANDS");
			server.execute(() -> {
				for (String command : commands) {
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
				}
				if (extra != null) {
					for (String command : extra.split(";")) {
						if (!command.isBlank()) {
							server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command.trim());
						}
					}
				}
			});
		}
		if (mc.player != null) {
			mc.player.getAbilities().flying = false;
		}
		playStart = now;
		enter(Phase.PLAY);
	}

	private void setupWorld(Minecraft mc, Scenario scenario) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			return;
		}
		java.util.List<String> commands = new java.util.ArrayList<>(java.util.List.of(
				"gamemode spectator @a",
				"gamerule advance_time false",
				"gamerule advance_weather false",
				"gamerule spawn_mobs false",
				"weather clear",
				"time set " + scenario.timeOfDay()));
		if (scenario.raw().has("mobs")) {
			// Hostile mobs cannot be summoned in peaceful.
			commands.add("difficulty easy");
			commands.addAll(mobCommands(scenario.raw().getAsJsonObject("mobs")));
		}
		server.execute(() -> {
			for (String command : commands) {
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
			}
		});
	}

	/**
	 * Scenario {@code "mobs"}: a grid of motionless mobs on the ground ahead of the anchor, {@code rows} deep from
	 * {@code distance} blocks along {@code yaw}, {@code columns} wide, {@code spacing} apart; {@code types} in turn.
	 */
	private java.util.List<String> mobCommands(JsonObject mobs) {
		var types = mobs.getAsJsonArray("types");
		int rows = mobs.get("rows").getAsInt();
		int columns = mobs.get("columns").getAsInt();
		double spacing = mobs.get("spacing").getAsDouble();
		double distance = mobs.get("distance").getAsDouble();
		double yaw = Math.toRadians(mobs.get("yaw").getAsDouble());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);
		java.util.List<String> out = new java.util.ArrayList<>();
		// "underground": N puts the grid in a sealed air pocket N blocks below the anchor (occlusion tests); the pocket
		// is carved as one axis-aligned box around the grid, so use a yaw of 0/90/180/270 with it.
		int underground = mobs.has("underground") ? mobs.get("underground").getAsInt() : 0;
		if (underground > 0) {
			double reach = distance + (rows - 1) * spacing, half = (columns - 1) / 2.0 * spacing;
			double[] xs = {anchorX + fx * distance - fz * half, anchorX + fx * distance + fz * half, anchorX + fx * reach - fz * half, anchorX + fx * reach + fz * half};
			double[] zs = {anchorZ + fz * distance + fx * half, anchorZ + fz * distance - fx * half, anchorZ + fz * reach + fx * half, anchorZ + fz * reach - fx * half};
			int y = (int) anchorY - underground;
			out.add(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
					(int) Math.floor(java.util.Arrays.stream(xs).min().orElseThrow()) - 2, y, (int) Math.floor(java.util.Arrays.stream(zs).min().orElseThrow()) - 2,
					(int) Math.ceil(java.util.Arrays.stream(xs).max().orElseThrow()) + 2, y + 5, (int) Math.ceil(java.util.Arrays.stream(zs).max().orElseThrow()) + 2));
		}
		int n = 0;
		for (int row = 0; row < rows; row++) {
			for (int column = 0; column < columns; column++) {
				double ahead = distance + row * spacing;
				double side = (column - (columns - 1) / 2.0) * spacing;
				double x = anchorX + fx * ahead - fz * side;
				double z = anchorZ + fz * ahead + fx * side;
				String type = types.get(n++ % types.size()).getAsString();
				if (underground > 0) {
					out.add(String.format(java.util.Locale.ROOT,
							"summon minecraft:%s %.2f %d %.2f {NoAI:1b,Silent:1b,PersistenceRequired:1b,Rotation:[%.1ff,0f]}",
							type, x, (int) anchorY - underground, z, Math.toDegrees(yaw) + 180.0));
					continue;
				}
				out.add(String.format(java.util.Locale.ROOT,
						"execute positioned %.2f %.2f %.2f positioned over motion_blocking run summon minecraft:%s ~ ~ ~ {NoAI:1b,Silent:1b,PersistenceRequired:1b,Rotation:[%.1ff,0f]}",
						x, anchorY, z, type, Math.toDegrees(yaw) + 180.0));
			}
		}
		return out;
	}

	private void beginSettle(long now, boolean atEnd) {
		enter(Phase.SETTLING);
		settleAtEnd = atEnd;
		stableSeconds = 0;
		lastLoadedChunks = -1;
		lastVisibleSections = -1;
	}

	private void settling(Minecraft mc, long now) {
		// stableSeconds is updated once a second by sample().
		boolean atEnd = settleAtEnd;
		// A shader pack loads in the background (vanilla visuals until then): settle only once it draws.
		boolean settled = stableSeconds >= SETTLE_STABLE_SECONDS && !dev.spacebod.aetherium.shaders.engine.ShaderPackEngine.get().loading();
		boolean timedOut = seconds(now - phaseStart) > leg.scenario().settleTimeoutSeconds();
		if (!settled && !timedOut) {
			return;
		}
		if (timedOut) {
			Aetherium.LOG.warn("Benchmark: world did not settle within {} s, continuing", leg.scenario().settleTimeoutSeconds());
		}
		if (atEnd) {
			nextPrepareLeg(mc, now);
			return;
		}
		settleTimedOut = timedOut;
		settledSeconds = seconds(now - origin) - (timedOut ? 0 : SETTLE_STABLE_SECONDS);
		if (leg.measure()) {
			heapAfterGcSettledMb = liveHeapMb();
			enter(Phase.WARMUP);
		} else if (leg.scenario().path().length() < 16) {
			// A stationary path is fully generated by settling at its start.
			nextPrepareLeg(mc, now);
		} else {
			Aetherium.LOG.info("Benchmark: preparing '{}', flying {} blocks", leg.scenario().id(), Math.round(leg.scenario().path().length()));
			enter(Phase.PREP_FLY);
		}
	}

	private void nextPrepareLeg(Minecraft mc, long now) {
		leg = legs.pollFirst();
		if (leg == null) {
			enter(Phase.SAVING);
			saveAndQuit(mc);
			return;
		}
		beginSettle(now, false);
	}

	private void startRun(long now) {
		frames.reset();
		passStats.reset();
		throttledFrames = 0;
		runStart = now;
		runFirstFrame = -1;
		runLastFrame = Long.MAX_VALUE;
		runStartCounters = BenchCounters.Snapshot.take();
		enter(Phase.RUNNING);
		Aetherium.LOG.info("Benchmark: measuring '{}' for {} s", leg.scenario().id(), leg.scenario().path().duration());
	}

	private void sample(Minecraft mc, long now) {
		double elapsed = seconds(now - lastSample);
		lastSample = now;
		BenchCounters.Snapshot counters = BenchCounters.Snapshot.take();
		BenchCounters.Snapshot delta = counters.minus(lastCounters);
		lastCounters = counters;

		// The terrain renderer builds and draws the chunks; vanilla's own section pipeline stays idle.
		SodiumWorldRenderer terrain = SodiumWorldRenderer.instanceNullable();
		int queue = terrain == null ? 0 : terrainJobs(terrain);
		int visible = terrain == null ? 0 : terrain.getVisibleChunkCount();
		boolean terrainDone = terrain != null && terrain.isTerrainRenderComplete();
		int loaded = mc.level == null ? 0 : mc.level.getChunkSource().getLoadedChunksCount();

		JsonObject s = new JsonObject();
		s.addProperty("t", seconds(now - origin));
		s.addProperty("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
		s.addProperty("fps", framesSinceSample / elapsed);
		s.addProperty("loadedChunks", loaded);
		s.addProperty("compileQueue", queue);
		s.addProperty("visibleSections", visible);
		s.addProperty("terrainComplete", terrainDone);
		if (dev.spacebod.aetherium.shaders.engine.ShaderPackEngine.get().running()) {
			var engine = dev.spacebod.aetherium.shaders.engine.ShaderPackEngine.get();
			s.addProperty("shadowCasterSections", engine.shadowCasterSections());
			s.addProperty("shadowCacheHits", engine.shadowCacheHits());
			s.addProperty("shadowCacheMisses", engine.shadowCacheMisses());
		}
		s.addProperty("sectionsCompiled", delta.compiled());
		s.addProperty("sectionsCancelled", delta.cancelled());
		s.addProperty("compileMs", delta.compileNanos() / 1e6);
		s.addProperty("resorts", delta.resorts());
		s.addProperty("resortMs", delta.resortNanos() / 1e6);
		s.addProperty("rebuiltWithin1s", delta.rebuiltWithin1s());
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			s.addProperty("serverTickMs", server.getAverageTickTimeNanos() / 1e6);
		}
		if (mc.player != null) {
			s.addProperty("x", Math.round(mc.player.getX()));
			s.addProperty("y", Math.round(mc.player.getY()));
			s.addProperty("z", Math.round(mc.player.getZ()));
		}
		timeline.add(s);
		framesSinceSample = 0;

		if (phase == Phase.SETTLING) {
			// Loaded chunks and visible sections must hold still and the terrain renderer must have nothing left to
			// build: shader passes (shadow casters, translucent water) scale with how much world is drawn.
			boolean quiet = terrainDone && queue == 0 && loaded > 0 && loaded == lastLoadedChunks
					&& visible > 10 && Math.abs(visible - lastVisibleSections) <= 2;
			stableSeconds = quiet ? stableSeconds + 1 : 0;
			lastLoadedChunks = loaded;
			lastVisibleSections = visible;
		}
	}

	/** Chunk build jobs the terrain renderer has scheduled and not finished. */
	private static int terrainJobs(SodiumWorldRenderer terrain) {
		RenderSectionManager sections = ((WorldRendererAccessor) terrain).aetherium$sections();
		return sections == null ? 0 : sections.getBuilder().getScheduledJobCount();
	}

	/** Used heap after full GCs (the run is paused on this thread meanwhile), in MB. */
	private static double liveHeapMb() {
		for (int i = 0; i < 3; i++) {
			System.gc();
		}
		return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0);
	}

	private void finishMeasuredLeg(Minecraft mc) {
		enter(Phase.DONE);
		JsonArray system = systemSampler == null ? new JsonArray() : systemSampler.stop();
		BenchCounters.Snapshot work = BenchCounters.Snapshot.take().minus(runStartCounters);
		double heapAfterGcEndMb = liveHeapMb();
		JsonObject result = BenchResult.build(mc, request, leg.scenario(), frames, passStats, timeline, system, work,
				joinSeconds, settledSeconds, settleTimedOut, gpuTimer == null ? -1 : gpuTimer.untimedFrames(), throttledFrames,
				heapAfterGcSettledMb, heapAfterGcEndMb);
		if (alternating != null) {
			alternating.finish();
			result.add("alternate", alternating.toJson());
			Aetherium.LOG.info("Benchmark: {}", alternating.summary());
		}
		if (throttledFrames > 0) {
			Aetherium.LOG.warn("Benchmark: {} frames were throttled by vanilla's frame limiter; this run is not valid", throttledFrames);
		}
		BenchResult.write(request.output(), result);
		Aetherium.LOG.info("Benchmark: '{}' done, {}", leg.scenario().id(), BenchResult.oneLine(frames));
		quit(mc);
	}

	private void saveAndQuit(Minecraft mc) {
		Aetherium.LOG.info("Benchmark: world '{}' prepared, saving", request.saveName());
		if (systemSampler != null) {
			systemSampler.stop();
		}
		JsonObject result = new JsonObject();
		result.addProperty("mode", "prepare");
		result.addProperty("saveName", request.saveName());
		result.addProperty("seed", request.seed());
		result.addProperty("seconds", seconds(System.nanoTime() - origin));
		result.add("timeline", timeline);
		mc.disconnectWithSavingScreen();
		BenchResult.write(request.output(), result);
		enter(Phase.DONE);
		quit(mc);
	}

	private void quit(Minecraft mc) {
		active = null;
		if (request.exitWhenDone()) {
			mc.stop();
		}
	}

	private void fail(Minecraft mc, String message, @Nullable Throwable cause) {
		Aetherium.LOG.error("Benchmark failed: {}", message, cause);
		JsonObject result = new JsonObject();
		result.addProperty("error", message);
		result.add("meta", request.meta());
		BenchResult.write(request.output(), result);
		enter(Phase.DONE);
		if (systemSampler != null) {
			systemSampler.stop();
		}
		quit(mc);
	}

	private void enter(Phase next) {
		phase = next;
		phaseStart = System.nanoTime();
	}

	private static double seconds(long nanos) {
		return nanos / 1e9;
	}

	private void writeAnchor(Path path) {
		JsonObject o = new JsonObject();
		o.addProperty("x", anchorX);
		o.addProperty("y", anchorY);
		o.addProperty("z", anchorZ);
		o.addProperty("seed", request.seed());
		try {
			Files.writeString(path, o.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("cannot write " + path, e);
		}
	}

	private boolean readAnchor(Path path) {
		if (!Files.isRegularFile(path)) {
			return false;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			anchorX = o.get("x").getAsDouble();
			anchorY = o.get("y").getAsDouble();
			anchorZ = o.get("z").getAsDouble();
			return true;
		} catch (IOException | RuntimeException e) {
			Aetherium.LOG.error("Bad anchor file {}", path, e);
			return false;
		}
	}
}
