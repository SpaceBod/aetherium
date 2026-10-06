package dev.spacebod.aetherium.client;

import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.Window;
import dev.spacebod.aetherium.shaders.config.ShaderPackSettings;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Centres the crosshair on the point the camera looks at. The game places its 15-pixel sprite at half the GUI width
 * less 15, rounded down, and the GUI is a whole number of GUI pixels wider than the window when the window's size is not
 * a multiple of the GUI scale; together that leaves the crosshair up to a few window pixels left of and above the
 * centre. The shift that puts its middle pixel on the window's centre is whole window pixels, so it stays sharp.
 */
public final class Crosshair {
	/** The crosshair sprite's size in GUI pixels; its middle pixel is the eighth. */
	private static final int SIZE = 15;

	private static @Nullable Boolean centred;

	private Crosshair() {
	}

	public static boolean centred() {
		if (centred == null) {
			centred = ShaderPackSettings.interfaceSetting("crosshair", "centred", new JsonPrimitive(true)).getAsBoolean();
		}
		return centred;
	}

	public static void setCentred(boolean on) {
		centred = on;
		ShaderPackSettings.setInterfaceSetting("crosshair", "centred", new JsonPrimitive(on));
	}

	/** The horizontal shift, in GUI pixels, that centres the crosshair (0 when off). */
	public static float shiftX() {
		Window window = Minecraft.getInstance().getWindow();
		return centred() ? shift(window.getWidth(), window.getGuiScaledWidth(), window.getGuiScale()) : 0.0f;
	}

	/** The vertical shift, in GUI pixels, that centres the crosshair (0 when off). */
	public static float shiftY() {
		Window window = Minecraft.getInstance().getWindow();
		return centred() ? shift(window.getHeight(), window.getGuiScaledHeight(), window.getGuiScale()) : 0.0f;
	}

	/**
	 * Along one axis: where the game puts the middle pixel ({@code (gui - 15) / 2 + 7} GUI pixels, so {@code scale} times
	 * that in window pixels) against where it belongs (its {@code scale} window pixels straddling half the window,
	 * rounded to a whole pixel), back in GUI pixels.
	 */
	private static float shift(int window, int gui, int scale) {
		int drawn = ((gui - SIZE) / 2 + SIZE / 2) * scale;
		int wanted = Math.round((window - scale) / 2.0f);
		return (wanted - drawn) / (float) scale;
	}
}
