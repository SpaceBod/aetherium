package dev.spacebod.aetherium.client.zoom;

import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import dev.spacebod.aetherium.shaders.config.ShaderPackSettings;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Zoom while a key is held (Z unless rebound): the field of view divided by the magnification, eased in and out
 * independently of the frame rate. Meanwhile the mouse turns slower in step with the magnification (scaled by the
 * sensitivity setting) and, if chosen, the cinematic camera smooths it. The scroll wheel sets the magnification while
 * zoomed, between the minimum and maximum; the next zoom starts at the nominal magnification again. Those three are
 * settings in tenths ({@code "zoom": { "min", "max", "nominal" }}), with {@code "sensitivity"} (percent) and
 * {@code "cinematic"}.
 */
public final class Zoom {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("aetherium", "general"));
	public static final KeyMapping KEY = new KeyMapping("key.aetherium.zoom", InputConstants.KEY_Z, CATEGORY);

	/** Limits and defaults of the settings, in tenths of a magnification. */
	public static final int LOWEST = 10;
	public static final int HIGHEST = 640;
	public static final int DEFAULT_MIN = 10;
	public static final int DEFAULT_MAX = 320;
	public static final int DEFAULT_NOMINAL = 40;
	/** Limits and default of the zoomed mouse sensitivity, in percent. */
	public static final int MIN_SENSITIVITY = 10;
	public static final int MAX_SENSITIVITY = 200;
	public static final int DEFAULT_SENSITIVITY = 100;
	/** Multiplicative change per scroll notch. */
	private static final double SCROLL_STEP = 1.2;
	/** Fraction of the remaining distance closed per second, exponentially: higher is snappier. */
	private static final double SMOOTHING = 12.0;

	private static int min = -1;
	private static int max = -1;
	private static int nominal = -1;
	private static int sensitivity = -1;
	private static int cinematic = -1;

	private static boolean zooming;
	private static double target;
	private static double current = 1.0;
	private static long lastFrame;
	private static boolean savedSmoothCamera;

	private Zoom() {
	}

	/** Once per client tick: zoomed while the key is held in game (no screen open). */
	public static void tick(Minecraft mc) {
		set(KEY.isDown() && mc.gui.screen() == null && mc.player != null);
	}

	private static void set(boolean on) {
		if (on == zooming) {
			return;
		}
		zooming = on;
		var options = Minecraft.getInstance().options;
		if (on) {
			// Every zoom starts at the nominal magnification; scrolling only changes this one.
			target = Mth.clamp(nominal() / 10.0, min() / 10.0, max() / 10.0);
			savedSmoothCamera = options.smoothCamera;
			if (cinematic()) {
				options.smoothCamera = true;
			}
		} else {
			options.smoothCamera = savedSmoothCamera;
		}
	}

	public static boolean zooming() {
		return zooming;
	}

	/** A scroll notch while zoomed: positive zooms in. Returns whether the scroll was taken. */
	public static boolean scroll(double delta) {
		if (!zooming || delta == 0) {
			return false;
		}
		target = Mth.clamp(target * (delta > 0 ? SCROLL_STEP : 1.0 / SCROLL_STEP), min() / 10.0, max() / 10.0);
		return true;
	}

	/** Once per frame, from the field-of-view calculation: the factor to apply. */
	public static float fovMultiplier() {
		long now = System.nanoTime();
		double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1.0e9);
		lastFrame = now;
		double goal = zooming ? target : 1.0;
		current = Mth.lerp(1.0 - Math.exp(-SMOOTHING * dt), current, goal);
		if (Math.abs(current - goal) < 0.001) {
			current = goal;
		}
		return (float) (1.0 / current);
	}

	/** The least magnification scrolling reaches, in tenths. */
	public static int min() {
		if (min < 0) {
			min = read("min", DEFAULT_MIN);
		}
		return min;
	}

	/** The most magnification scrolling reaches, in tenths; never below the minimum. */
	public static int max() {
		if (max < 0) {
			max = read("max", DEFAULT_MAX);
		}
		return Math.max(max, min());
	}

	/** The magnification every zoom starts at, in tenths (kept between the minimum and maximum when zooming). */
	public static int nominal() {
		if (nominal < 0) {
			nominal = read("nominal", DEFAULT_NOMINAL);
		}
		return nominal;
	}

	public static void setMin(int tenths) {
		min = write("min", tenths);
	}

	public static void setMax(int tenths) {
		max = write("max", tenths);
	}

	/**
	 * The mouse's turning speed while zoomed, in percent: at 100 a mouse movement moves the picture as far across the
	 * screen at any magnification as it does unzoomed (the turn is divided by the magnification); lower is slower.
	 */
	public static int sensitivity() {
		if (sensitivity < 0) {
			sensitivity = Mth.clamp(ShaderPackSettings.interfaceSetting("zoom", "sensitivity", new JsonPrimitive(DEFAULT_SENSITIVITY)).getAsInt(),
					MIN_SENSITIVITY, MAX_SENSITIVITY);
		}
		return sensitivity;
	}

	public static void setSensitivity(int percent) {
		sensitivity = Mth.clamp(percent, MIN_SENSITIVITY, MAX_SENSITIVITY);
		ShaderPackSettings.setInterfaceSetting("zoom", "sensitivity", new JsonPrimitive(sensitivity));
	}

	/** Whether zooming turns on the cinematic camera (smoothed mouse movement) until the key is let go. */
	public static boolean cinematic() {
		if (cinematic < 0) {
			cinematic = ShaderPackSettings.interfaceSetting("zoom", "cinematic", new JsonPrimitive(true)).getAsBoolean() ? 1 : 0;
		}
		return cinematic == 1;
	}

	public static void setCinematic(boolean on) {
		cinematic = on ? 1 : 0;
		ShaderPackSettings.setInterfaceSetting("zoom", "cinematic", new JsonPrimitive(on));
	}

	/** The factor for the mouse's turn this frame: 1 unzoomed, else the sensitivity over the magnification shown now. */
	public static double turnFactor() {
		if (!zooming && current <= 1.0) {
			return 1.0;
		}
		return sensitivity() / 100.0 / Math.max(1.0, current);
	}

	public static void setNominal(int tenths) {
		nominal = write("nominal", tenths);
	}

	private static int read(String key, int fallback) {
		try {
			return Mth.clamp(ShaderPackSettings.interfaceSetting("zoom", key, new JsonPrimitive(fallback)).getAsInt(), LOWEST, HIGHEST);
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static int write(String key, int tenths) {
		int value = Mth.clamp(tenths, LOWEST, HIGHEST);
		ShaderPackSettings.setInterfaceSetting("zoom", key, new JsonPrimitive(value));
		return value;
	}
}
