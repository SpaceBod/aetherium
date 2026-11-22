package dev.spacebod.aetherium.client.gui;

import net.minecraft.network.chat.Component;

/** Text from the language files, in the game's current language (English where a key has no translation). */
public final class Lang {
	private Lang() {
	}

	public static String t(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}

	/** {@code key.one} for a count of one, {@code key.many} otherwise; the count is the first argument. */
	public static String plural(String key, long count, Object... rest) {
		Object[] args = new Object[rest.length + 1];
		args[0] = count;
		System.arraycopy(rest, 0, args, 1, rest.length);
		return t(key + (count == 1 ? ".one" : ".many"), args);
	}
}
