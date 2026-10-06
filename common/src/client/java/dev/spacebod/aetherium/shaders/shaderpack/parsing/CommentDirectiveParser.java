package dev.spacebod.aetherium.shaders.shaderpack.parsing;

import java.util.Optional;

/**
 * Parses comment-based directives found in shader source files of the form:
 *
 * <pre>/* KEY:VALUE *<i></i>/</pre>
 * <p>
 * A common example is draw buffer directives:
 *
 * <pre>/* DRAWBUFFERS:157 *<i></i>/</pre>
 * <p>
 * A given directive should only occur once in a shader file. If there are multiple occurrences of a directive with a
 * given key, the last occurrence is used.
 */
public class CommentDirectiveParser {
	private CommentDirectiveParser() {
		// cannot be constructed
	}

	public static Optional<CommentDirective> findDirective(String haystack, CommentDirective.Type type) {
		int index = haystack.lastIndexOf(type.name() + ":");

		return findValue(haystack, type.name()).map(value -> new CommentDirective(type, value, index));
	}

	/**
	 * Finds the value of the last {@code /* KEY:VALUE *<i></i>/} directive with an arbitrary key.
	 */
	public static Optional<String> findValue(String haystack, String needle) {
		String prefix = needle + ":";
		// The last well-formed occurrence wins; an occurrence that is not "/*" + blanks + KEY: (the key inside other text,
		// or after "//") is skipped rather than hiding an earlier valid one. Linear: no copies of the source.
		int indexOfPrefix = haystack.length();
		while ((indexOfPrefix = haystack.lastIndexOf(prefix, indexOfPrefix - 1)) >= 0) {
			int before = indexOfPrefix - 1;
			while (before >= 0 && Character.isWhitespace(haystack.charAt(before))) {
				before--;
			}
			if (before < 1 || haystack.charAt(before) != '*' || haystack.charAt(before - 1) != '/') {
				continue;
			}
			int valueStart = indexOfPrefix + prefix.length();
			int indexOfSuffix = haystack.indexOf("*/", valueStart);
			// Without a closing "*/" the directive is malformed.
			if (indexOfSuffix == -1) {
				continue;
			}
			return Optional.of(haystack.substring(valueStart, indexOfSuffix).trim());
		}
		return Optional.empty();
	}
}
