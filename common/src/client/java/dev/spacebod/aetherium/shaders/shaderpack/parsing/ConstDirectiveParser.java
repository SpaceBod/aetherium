package dev.spacebod.aetherium.shaders.shaderpack.parsing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ConstDirectiveParser {
	public static List<ConstDirective> findDirectives(String source) {
		List<ConstDirective> directives = new ArrayList<>();

		// \R matches any newline sequence
		for (String line : source.split("\\R")) {
			findDirectiveInLine(line).ifPresent(directives::add);
		}

		return directives;
	}

	public static Optional<ConstDirective> findDirectiveInLine(String line) {
		// Form: [ws] const <ws> <int|float|vec2|ivec3|vec4|bool> <ws> <key> [ws] = <value> ; [anything]
		// where key is alphanumeric/underscore.

		// Cheap rejection before any processing
		if (!line.contains("const") || !line.contains("=") || !line.contains(";")) {
			return Optional.empty();
		}

		line = line.trim();

		if (!line.startsWith("const")) {
			return Optional.empty();
		}

		line = line.substring("const".length());

		if (!startsWithWhitespace(line)) {
			return Optional.empty();
		}

		line = line.trim();

		Type type;

		if (line.startsWith("int")) {
			type = Type.INT;
			line = line.substring("int".length());
		} else if (line.startsWith("float")) {
			type = Type.FLOAT;
			line = line.substring("float".length());
		} else if (line.startsWith("vec2")) {
			type = Type.VEC2;
			line = line.substring("vec2".length());
		} else if (line.startsWith("ivec3")) {
			type = Type.IVEC3;
			line = line.substring("ivec3".length());
		} else if (line.startsWith("vec4")) {
			type = Type.VEC4;
			line = line.substring("vec4".length());
		} else if (line.startsWith("bool")) {
			type = Type.BOOL;
			line = line.substring("bool".length());
		} else {
			return Optional.empty();
		}

		if (!startsWithWhitespace(line)) {
			return Optional.empty();
		}

		int equalsIndex = line.indexOf('=');

		if (equalsIndex == -1) {
			return Optional.empty();
		}

		String key = line.substring(0, equalsIndex).trim();

		if (!isWord(key)) {
			return Optional.empty();
		}

		String remaining = line.substring(equalsIndex + 1);

		int semicolonIndex = remaining.indexOf(';');

		if (semicolonIndex == -1) {
			return Optional.empty();
		}

		String value = remaining.substring(0, semicolonIndex).trim();

		// The value is parsed and validated by whoever consumes the directive
		return Optional.of(new ConstDirective(type, key, value));
	}

	private static boolean startsWithWhitespace(String text) {
		return !text.isEmpty() && Character.isWhitespace(text.charAt(0));
	}

	private static boolean isWord(String text) {
		if (text.isEmpty()) {
			return false;
		}

		for (char character : text.toCharArray()) {
			if (!Character.isDigit(character) && !Character.isAlphabetic(character) && character != '_') {
				return false;
			}
		}

		return true;
	}

	public enum Type {
		INT,
		FLOAT,
		VEC2,
		IVEC3,
		VEC4,
		BOOL
	}

	public static class ConstDirective {
		private final Type type;
		private final String key;
		private final String value;

		ConstDirective(Type type, String key, String value) {
			this.type = type;
			this.key = key;
			this.value = value;
		}

		public Type getType() {
			return type;
		}

		public String getKey() {
			return key;
		}

		public String getValue() {
			return value;
		}

		public String toString() {
			return "ConstDirective { " + type + " " + key + " = " + value + "; }";
		}
	}
}
