package dev.spacebod.aetherium.shaders.shaderpack;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The player's shadow distance and shadow map scale. Both are applied by rewriting the pack's own declarations in each
 * preprocessed program source, before the directives are read and before translation: the map is allocated at the size
 * the pack's code divides by, and the projection covers the distance its filters are computed for. A pack that does not
 * declare a value keeps the format's default for it.
 *
 * @param distanceChunks the shadow half plane ({@code shadowDistance}, {@code SHADOWHPL}) in chunks, 16 blocks each;
 *                       0 keeps the pack's value
 * @param mapScale       percent of the pack's {@code shadowMapResolution} / {@code SHADOWRES} on each axis; 100 keeps it
 */
public record ShadowOverrides(int distanceChunks, int mapScale) {
	public static final ShadowOverrides NONE = new ShadowOverrides(0, 100);
	public static final int MAX_DISTANCE_CHUNKS = 32;
	public static final int MIN_MAP_SCALE = 25;
	public static final int MAX_MAP_SCALE = 100;

	private static final Pattern CONST_RESOLUTION = Pattern.compile("(?m)^(\\s*const\\s+int\\s+shadowMapResolution\\s*=\\s*)([^;\\n]*?)(\\s*;)");
	private static final Pattern COMMENT_RESOLUTION = Pattern.compile("(/\\*\\s*SHADOWRES:\\s*)([^*\\n]*?)(\\s*\\*/)");
	private static final Pattern CONST_DISTANCE = Pattern.compile("(?m)^(\\s*const\\s+float\\s+shadowDistance\\s*=\\s*)([^;\\n]*?)(\\s*;)");
	private static final Pattern COMMENT_DISTANCE = Pattern.compile("(/\\*\\s*SHADOWHPL:\\s*)([^*\\n]*?)(\\s*\\*/)");

	public ShadowOverrides {
		distanceChunks = Math.max(0, Math.min(MAX_DISTANCE_CHUNKS, distanceChunks));
		mapScale = Math.max(MIN_MAP_SCALE, Math.min(MAX_MAP_SCALE, mapScale));
	}

	/** Whether anything is changed (otherwise sources pass through untouched). */
	public boolean active() {
		return distanceChunks > 0 || mapScale != 100;
	}

	/** {@code source} with the shadow declarations rewritten. */
	public String apply(String source) {
		if (!active()) {
			return source;
		}
		if (mapScale != 100 && (source.contains("SHADOWRES") || source.contains("shadowMapResolution"))) {
			source = replace(source, CONST_RESOLUTION, this::scaledResolution);
			source = replace(source, COMMENT_RESOLUTION, this::scaledResolution);
		}
		if (distanceChunks > 0 && (source.contains("shadowDistance") || source.contains("SHADOWHPL"))) {
			String blocks = (distanceChunks * 16) + ".0";
			source = replace(source, CONST_DISTANCE, value -> blocks);
			source = replace(source, COMMENT_DISTANCE, value -> blocks);
		}
		return source;
	}

	/** The pack's map size through the scale (at least one texel); a value that is not a whole number stays as it is. */
	private String scaledResolution(String value) {
		try {
			int declared = Integer.parseInt(value.trim());
			return Integer.toString(Math.max(1, Math.round(declared * mapScale / 100.0f)));
		} catch (NumberFormatException e) {
			return value;
		}
	}

	private static String replace(String source, Pattern pattern, UnaryOperator<String> value) {
		Matcher m = pattern.matcher(source);
		if (!m.find()) {
			return source;
		}
		StringBuilder out = new StringBuilder(source.length() + 16);
		do {
			m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + value.apply(m.group(2)) + m.group(3)));
		} while (m.find());
		m.appendTail(out);
		return out.toString();
	}
}
