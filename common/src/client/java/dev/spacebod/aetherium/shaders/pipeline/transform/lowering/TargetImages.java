package dev.spacebod.aetherium.shaders.pipeline.transform.lowering;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names under which programs bind the pack's colour targets as images: {@code colorimgN} (colortexN) and
 * {@code shadowcolorimgN} (shadowcolorN).
 */
public final class TargetImages {
	/** {@code colorimgN}, or {@code shadowcolorimgN} (group 1 set); N in group 2. Matches a name or a word in source text. */
	public static final Pattern PATTERN = Pattern.compile("\\b(shadow)?colorimg(\\d+)\\b");

	private TargetImages() {
	}

	/** A matcher over {@code text}, for {@link Matcher#matches} on a name or {@link Matcher#find} in source text. */
	public static Matcher matcher(CharSequence text) {
		return PATTERN.matcher(text);
	}

	/** Whether a match is a shadow colour target ({@code shadowcolorimgN}). */
	public static boolean shadow(Matcher match) {
		return match.group(1) != null;
	}

	/** The target index N of a match. */
	public static int index(Matcher match) {
		return Integer.parseInt(match.group(2));
	}
}
