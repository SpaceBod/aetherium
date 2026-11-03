package dev.spacebod.aetherium.client.diag;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import dev.spacebod.aetherium.Aetherium;
import dev.spacebod.aetherium.client.gpu.VulkanAccess;
import dev.spacebod.aetherium.client.mixin.access.VulkanDeviceFeaturesAccessor;
import dev.spacebod.aetherium.core.Optimisations;
import dev.spacebod.aetherium.shaders.config.ShaderPackSettings;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;

/**
 * Tools for tracking down rendering bugs on someone else's machine ({@code /aetherium diagnostics}): switches that rule
 * out whole classes of cause, and a recording of what every frame did, written to {@code aetherium-diagnostics/}.
 * <p>
 * A recording holds, per frame: the frame counter, the camera, the shadow matrices as the shadow pass draws with them
 * and as the pack reads them, the pack's scalar custom uniforms, the render passes, clears, copies and submits, and the
 * compute programs dispatched. With {@link #readback()} it also holds, a few frames later, a summary of a box of texels
 * at the centre of every pack image (voxel volumes are centred on the camera): how many texels are set, the values
 * they hold and a hash, so a volume that changes while nothing on screen should is visible in the numbers.
 * <p>
 * Nothing here is saved but {@link #validationAtLaunch()}: every other switch is off again after a restart.
 */
public final class Diagnostics {
	/** Frames one recording covers. */
	private static final int FRAMES = 600;
	/** Seconds between the start of a recording and its first frame (time to close the menu and stand still). */
	private static final double LEAD_SECONDS = 3.0;

	private static volatile boolean waitIdle;
	private static volatile boolean waitAfterShadowComposite;
	private static volatile boolean readback = true;
	/** {@link #readback} as the pack's images were created with it (a pack reload applies a change). */
	private static volatile boolean readbackActive;
	/** Saved in {@link #SETTINGS}: the only diagnostics setting that lasts, since it applies when the game starts. */
	private static final String VALIDATION = "vulkanValidation";
	private static final String SETTINGS = "aetherium-diagnostics.properties";
	private static @Nullable Boolean validation;
	/** Whether this run's device was created with validation requested (the setting as it was at launch). */
	private static boolean validationThisRun;

	private enum Phase {
		IDLE, WAITING, RECORDING
	}

	private static Phase phase = Phase.IDLE;
	private static long waitStart;
	private static int recorded;
	private static @Nullable StringBuilder frame;
	private static final List<String> lines = new ArrayList<>();
	private static @Nullable Path lastFile;
	private static String status = "Not recorded yet";

	private Diagnostics() {
	}

	/** Wait for the GPU to finish all work at the start of every frame: no two frames, nor parts of them, overlap. */
	public static boolean waitIdle() {
		return waitIdle;
	}

	public static void setWaitIdle(boolean on) {
		waitIdle = on;
	}

	/**
	 * Submit the frame so far and wait for the GPU right after the shadowcomp stage (where coloured-lighting packs spread
	 * their light), so nothing after it overlaps it.
	 */
	public static boolean waitAfterShadowComposite() {
		return waitAfterShadowComposite;
	}

	public static void setWaitAfterShadowComposite(boolean on) {
		waitAfterShadowComposite = on;
	}

	/**
	 * Request the Khronos validation layer when the game starts (vanilla's {@code --vulkanValidation}); its messages go
	 * to latest.log. Needs the layer installed (the Vulkan SDK, or a vulkan-validation-layers package).
	 */
	public static boolean validationAtLaunch() {
		if (validation == null) {
			validation = Boolean.parseBoolean(loadSettings().getProperty(VALIDATION, "false"));
			validationThisRun = validation;
		}
		return validation;
	}

	public static void setValidationAtLaunch(boolean on) {
		validationAtLaunch();
		validation = on;
		java.util.Properties p = loadSettings();
		p.setProperty(VALIDATION, Boolean.toString(on));
		try (var out = Files.newBufferedWriter(settingsFile(), StandardCharsets.UTF_8)) {
			p.store(out, "Aetherium diagnostics (see /aetherium diagnostics)");
		} catch (IOException e) {
			Aetherium.LOG.error("could not write {}", settingsFile(), e);
		}
	}

	/** The validation setting differs from what this run started with (shown as a pending restart). */
	public static boolean validationRestartPending() {
		return validationAtLaunch() != validationThisRun;
	}

	private static Path settingsFile() {
		Minecraft mc = Minecraft.getInstance();
		Path game = mc != null && mc.gameDirectory != null ? mc.gameDirectory.toPath() : Path.of(".");
		return game.resolve("config").resolve(SETTINGS);
	}

	private static java.util.Properties loadSettings() {
		java.util.Properties p = new java.util.Properties();
		Path file = settingsFile();
		if (Files.isRegularFile(file)) {
			try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				p.load(in);
			} catch (IOException e) {
				Aetherium.LOG.warn("could not read {}", file, e);
			}
		}
		return p;
	}

	/** Pack images can be copied back to the CPU (they are created with transfer-source usage; applies on pack load). */
	public static boolean readback() {
		return readback;
	}

	public static void setReadback(boolean on) {
		readback = on;
	}

	/** Called by the pack's storage when it creates its images: whether to add the usage readback needs. */
	public static boolean readbackForNewImages() {
		readbackActive = readback;
		return readbackActive;
	}

	public static boolean recording() {
		return phase == Phase.RECORDING;
	}

	public static String status() {
		return status;
	}

	public static @Nullable Path lastFile() {
		return lastFile;
	}

	public static Path folder() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("aetherium-diagnostics");
	}

	/**
	 * Starts a recording: it begins {@link #LEAD_SECONDS} after the next world frame drawn with no menu open. With
	 * readback on but the pack's images created without it, the pack is reloaded first.
	 */
	public static void requestRecording() {
		if (readback && !readbackActive && ShaderPackSettings.activePack().isPresent()) {
			ShaderPackEngine.get().reload();
		}
		phase = Phase.WAITING;
		waitStart = 0;
		status = "Starts " + (int) LEAD_SECONDS + " s after you close this screen; stand still near the problem";
	}

	// ---------------------------------------------------------------- frame hooks (render thread, engine running)

	/** The start of a world frame with the pack active. Applies {@link #waitIdle} and moves the recording on. */
	public static void beginFrame() {
		if (waitIdle) {
			VulkanDevice device = VulkanAccess.device();
			if (device != null) {
				VK10.vkDeviceWaitIdle(device.vkDevice());
			}
		}
		Minecraft mc = Minecraft.getInstance();
		switch (phase) {
			case WAITING -> {
				if (mc.gui.screen() != null || !ShaderPackEngine.get().running()) {
					waitStart = 0;
					return;
				}
				long now = System.nanoTime();
				if (waitStart == 0) {
					waitStart = now;
				}
				if ((now - waitStart) / 1e9 >= LEAD_SECONDS) {
					start();
				}
			}
			case RECORDING -> {
				flushFrame();
				if (recorded >= FRAMES) {
					finish();
					return;
				}
				recorded++;
				frame = new StringBuilder(512).append("frame ").append(recorded);
			}
			default -> {
			}
		}
	}

	/** Adds {@code key=value} to this frame's line (while recording). */
	public static void put(String key, Object value) {
		StringBuilder f = frame;
		if (f != null) {
			f.append(' ').append(key).append('=').append(value);
		}
	}

	/** A line of its own (readback results, which arrive frames after the frame they describe). */
	public static void line(String text) {
		if (phase == Phase.RECORDING) {
			lines.add(text);
		}
	}

	/** The frame number the line being built belongs to, or -1 when not recording. */
	public static int frameNumber() {
		return phase == Phase.RECORDING ? recorded : -1;
	}

	private static void flushFrame() {
		StringBuilder f = frame;
		if (f != null) {
			lines.add(f.toString());
			frame = null;
		}
	}

	private static void start() {
		lines.clear();
		recorded = 0;
		phase = Phase.RECORDING;
		lines.addAll(header());
		recorded = 1;
		frame = new StringBuilder(512).append("frame 1");
		status = "Recording…";
		message("Aetherium diagnostics: recording " + FRAMES + " frames. Stand still, then walk a few blocks.");
	}

	private static void finish() {
		flushFrame();
		phase = Phase.IDLE;
		String name = "diag-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)) + ".txt";
		Path file = folder().resolve(name);
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, lines, StandardCharsets.UTF_8);
			lastFile = file;
			status = "Saved " + name;
			message("Aetherium diagnostics: saved " + file.toAbsolutePath());
		} catch (IOException e) {
			status = "Could not save: " + e.getMessage();
			Aetherium.LOG.error("could not write {}", file, e);
			message("Aetherium diagnostics: could not save the recording (" + e.getMessage() + ")");
		}
		lines.clear();
	}

	private static void message(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal(text));
		}
		Aetherium.LOG.info(text);
	}

	/** What the machine, the game and the pack are: everything a recording is read against. */
	private static List<String> header() {
		List<String> out = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		out.add("# Aetherium diagnostics " + LocalDateTime.now());
		out.add("jar " + Diagnostics.class.getProtectionDomain().getCodeSource().getLocation());
		out.add("os " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch")
				+ ", java " + System.getProperty("java.version"));
		var info = RenderSystem.getDevice().getDeviceInfo();
		out.add("gpu " + info.name() + " | backend " + info.backendName() + " | driver " + info.driverInfo().replace('\n', ' '));
		out.add("window " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight()
				+ " | session " + System.getenv("XDG_SESSION_TYPE") + " | wayland " + System.getenv("WAYLAND_DISPLAY"));
		VulkanDevice device = VulkanAccess.device();
		if (device != null) {
			FeatureSet enabled = ((VulkanDeviceFeaturesAccessor) device).aetherium$enabledFeatures();
			out.add("vulkan extensions " + new TreeSet<>(enabled.extensions()));
			TreeSet<String> features = new TreeSet<>();
			for (VulkanFeature f : enabled.features()) {
				features.add(f.name());
			}
			out.add("vulkan features " + features);
		}
		StringBuilder opts = new StringBuilder("optimisations");
		for (Optimisations.Optimisation o : Optimisations.ALL) {
			opts.append(' ').append(o.id()).append('=').append(Optimisations.isEnabled(o.id()) ? "on" : "off");
		}
		out.add(opts.toString());
		out.add("diagnostics waitIdle=" + waitIdle + " waitAfterShadowComposite=" + waitAfterShadowComposite + " readback=" + readbackActive + " validationRequested=" + validationThisRun
				+ " | frame limit " + mc.options.framerateLimit().get() + " vsync " + mc.options.enableVsync().get());
		String pack = ShaderPackSettings.activePack().orElse("none");
		out.add("pack " + pack + " | render scale " + ShaderPackSettings.renderScale() + "%");
		if (!pack.equals("none")) {
			StringBuilder o = new StringBuilder("pack options");
			for (Map.Entry<String, String> e : ShaderPackSettings.readOptions(pack).entrySet()) {
				o.append(' ').append(e.getKey()).append('=').append(e.getValue());
			}
			out.add(o.toString());
		}
		out.addAll(ShaderPackEngine.get().diagnosticsHeader());
		out.add("# frame lines: values at the start of the frame, then counts of what the frame did (passes, clears, copies, submits");
		out.add("# and dispatched computes appear on the next frame's line as prev*). readback lines describe the pack images as");
		out.add("# the given frame left them; readback-mid lines the same images right after that frame's shadowcomp stage.");
		return out;
	}
}
