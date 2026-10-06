package dev.spacebod.aetherium.shaders.shaderpack.parsing;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import it.unimi.dsi.fastutil.floats.FloatConsumer;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import org.joml.Vector2f;
import org.joml.Vector3i;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class DispatchingDirectiveHolder implements DirectiveHolder {
	private final Map<String, BooleanConsumer> booleanConstVariables;
	private final Map<String, Consumer<String>> stringConstVariables;
	private final Map<String, IntConsumer> intConstVariables;
	private final Map<String, FloatConsumer> floatConstVariables;
	private final Map<String, Consumer<Vector2f>> vec2ConstVariables;
	private final Map<String, Consumer<Vector3i>> ivec3ConstVariables;
	private final Map<String, Consumer<Vector4f>> vec4ConstVariables;
	private final Map<String, Runnable> uniformDetectors;
	private final Map<String, Consumer<String>> commentStringVariables;
	private final Map<String, IntConsumer> commentIntVariables;
	private final Map<String, FloatConsumer> commentFloatVariables;

	public DispatchingDirectiveHolder() {
		booleanConstVariables = new HashMap<>();
		stringConstVariables = new HashMap<>();
		intConstVariables = new HashMap<>();
		floatConstVariables = new HashMap<>();
		vec2ConstVariables = new HashMap<>();
		ivec3ConstVariables = new HashMap<>();
		vec4ConstVariables = new HashMap<>();
		uniformDetectors = new HashMap<>();
		commentStringVariables = new HashMap<>();
		commentIntVariables = new HashMap<>();
		commentFloatVariables = new HashMap<>();
	}

	/**
	 * Dispatches the source-level directives of one shader stage: uniform declarations and
	 * {@code /* KEY:VALUE *<i></i>/} comments. Const directives go through {@link #processDirective}.
	 */
	public void processSource(String source) {
		uniformDetectors.forEach((name, onDetected) -> {
			// Any declaration counts, whatever its type and whether or not it is sampled. Only blanks may precede
			// "uniform" on its line: "\s*" would also cross newlines, and preprocessed sources hold long runs of blank
			// lines, which made the search quadratic (minutes on large packs). Same declarations match either way.
			if (declaresUniform(source, name)) {
				onDetected.run();
			}
		});

		commentStringVariables.forEach((name, consumer) ->
			CommentDirectiveParser.findValue(source, name).ifPresent(consumer));

		commentIntVariables.forEach((name, consumer) ->
			CommentDirectiveParser.findValue(source, name).ifPresent(value -> {
				try {
					consumer.accept(Integer.parseInt(value));
				} catch (NumberFormatException e) {
					AetheriumShaders.logger.error("Failed to process comment directive " + name + ":" + value, e);
				}
			}));

		commentFloatVariables.forEach((name, consumer) ->
			CommentDirectiveParser.findValue(source, name).ifPresent(value -> {
				try {
					consumer.accept(Float.parseFloat(value));
				} catch (NumberFormatException e) {
					AetheriumShaders.logger.error("Failed to process comment directive " + name + ":" + value, e);
				}
			}));
	}

	/**
	 * Whether {@code source} declares a uniform named {@code name} (any type, sampled or not): an occurrence of the name
	 * as a whole word inside a statement that starts with {@code uniform}. A linear scan from each occurrence back to the
	 * statement start: preprocessed sources hold long runs of blank lines that made the equivalent regex quadratic.
	 */
	public static boolean declaresUniform(String source, String name) {
		int at = -1;
		while ((at = source.indexOf(name, at + 1)) >= 0) {
			int end = at + name.length();
			if (at > 0 && isIdentifierPart(source.charAt(at - 1)) || end < source.length() && isIdentifierPart(source.charAt(end))) {
				continue;
			}
			int start = at - 1;
			while (start >= 0 && source.charAt(start) != ';' && source.charAt(start) != '{' && source.charAt(start) != '}') {
				start--;
			}
			int first = start + 1;
			while (first < at && Character.isWhitespace(source.charAt(first))) {
				first++;
			}
			if (source.startsWith("uniform", first) && first + 7 < at && Character.isWhitespace(source.charAt(first + 7))
					&& source.indexOf(';', end) >= 0) {
				return true;
			}
		}
		return false;
	}

	private static boolean isIdentifierPart(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	public void processDirective(ConstDirectiveParser.ConstDirective directive) {
		final ConstDirectiveParser.Type type = directive.getType();
		final String key = directive.getKey();
		final String value = directive.getValue();

		if (type == ConstDirectiveParser.Type.BOOL) {
			BooleanConsumer consumer = booleanConstVariables.get(key);

			if (consumer != null) {
				if ("true".equals(value)) {
					consumer.accept(true);
				} else if ("false".equals(value)) {
					consumer.accept(false);
				} else {
					AetheriumShaders.logger.error("Failed to process " + directive + ": " + value + " is not a valid boolean value");
				}

				return;
			}

			typeCheckHelper("int", intConstVariables, directive);
			typeCheckHelper("int", stringConstVariables, directive);
			typeCheckHelper("float", floatConstVariables, directive);
			typeCheckHelper("vec4", vec4ConstVariables, directive);
		} else if (type == ConstDirectiveParser.Type.INT) {
			// GLSL does not actually have a string type, so string constant directives use "const int" instead.
			Consumer<String> stringConsumer = stringConstVariables.get(key);

			if (stringConsumer != null) {
				stringConsumer.accept(value);

				return;
			}

			IntConsumer intConsumer = intConstVariables.get(key);

			if (intConsumer != null) {
				try {
					intConsumer.accept(Integer.parseInt(value));
				} catch (NumberFormatException e) {
					AetheriumShaders.logger.error("Failed to process " + directive, e);
				}

				return;
			}

			// A float setting declared with an integer ("const int voxelDistance = 32;") takes the integer's value.
			FloatConsumer widened = floatConstVariables.get(key);

			if (widened != null) {
				try {
					widened.accept(Integer.parseInt(value));
				} catch (NumberFormatException e) {
					AetheriumShaders.logger.error("Failed to process " + directive, e);
				}

				return;
			}

			typeCheckHelper("bool", booleanConstVariables, directive);
			typeCheckHelper("vec4", vec4ConstVariables, directive);
		} else if (type == ConstDirectiveParser.Type.FLOAT) {
			FloatConsumer consumer = floatConstVariables.get(key);

			if (consumer != null) {
				try {
					consumer.accept(Float.parseFloat(value));
				} catch (NumberFormatException e) {
					AetheriumShaders.logger.error("Failed to process " + directive, e);
				}

				return;
			}

			typeCheckHelper("bool", booleanConstVariables, directive);
			typeCheckHelper("int", intConstVariables, directive);
			typeCheckHelper("int", stringConstVariables, directive);
			typeCheckHelper("vec4", vec4ConstVariables, directive);
		} else if (type == ConstDirectiveParser.Type.VEC2) {
			Consumer<Vector2f> consumer = vec2ConstVariables.get(key);

			if (consumer != null) {
				if (!value.startsWith("vec2")) {
					AetheriumShaders.logger.error("Failed to process " + directive + ": value was not a valid vec2 constructor");
				}

				String vec2Args = value.substring("vec2".length()).trim();

				if (!vec2Args.startsWith("(") || !vec2Args.endsWith(")")) {
					AetheriumShaders.logger.error("Failed to process " + directive + ": value was not a valid vec2 constructor");
				}

				vec2Args = vec2Args.substring(1, vec2Args.length() - 1);

				String[] parts = vec2Args.split(",");

				for (int i = 0; i < parts.length; i++) {
					parts[i] = parts[i].trim();
				}

				if (parts.length != 2) {
					AetheriumShaders.logger.error("Failed to process " + directive +
						": expected 2 arguments to a vec2 constructor, got " + parts.length);
				}

				try {
					consumer.accept(new Vector2f(
						Float.parseFloat(parts[0]),
						Float.parseFloat(parts[1])
					));
				} catch (NumberFormatException e) {
					AetheriumShaders.logger.error("Failed to process " + directive, e);
				}

				return;
			}

			typeCheckHelper("bool", booleanConstVariables, directive);
			typeCheckHelper("int", intConstVariables, directive);
			typeCheckHelper("int", stringConstVariables, directive);
			typeCheckHelper("float", floatConstVariables, directive);
		} else if (type == ConstDirectiveParser.Type.IVEC3) {
			Consumer<Vector3i> consumer = ivec3ConstVariables.get(key);

			if (consumer != null) {
				if (!value.startsWith("ivec3")) {
					AetheriumShaders.logger.error("Failed to process " + directive + ": value was not a valid ivec3 constructor");
				}

				String ivec3Args = value.substring("ivec3".length()).trim();

				if (!ivec3Args.startsWith("(") || !ivec3Args.endsWith(")")) {
					AetheriumShaders.logger.error("Failed to process " + directive + ": value was not a valid ivec3 constructor");
				}

				ivec3Args = ivec3Args.substring(1, ivec3Args.length() - 1);

				String[] parts = ivec3Args.split(",");

				for (int i = 0; i < parts.length; i++) {
					parts[i] = parts[i].trim();
				}

				if (parts.length != 3) {
					AetheriumShaders.logger.error("Failed to process " + directive +
						": expected 3 arguments to a ivec3 constructor, got " + parts.length);
				}

				try {
					consumer.accept(new Vector3i(
						Integer.parseInt(parts[0]),
						Integer.parseInt(parts[1]),
						Integer.parseInt(parts[2])
					));
				} catch (NumberFormatException e) {
					AetheriumShaders.logger.error("Failed to process " + directive, e);
				}

				return;
			}

			typeCheckHelper("bool", booleanConstVariables, directive);
			typeCheckHelper("int", intConstVariables, directive);
			typeCheckHelper("int", stringConstVariables, directive);
			typeCheckHelper("float", floatConstVariables, directive);
		} else if (type == ConstDirectiveParser.Type.VEC4) {
			Consumer<Vector4f> consumer = vec4ConstVariables.get(key);

			if (consumer != null) {
				if (!value.startsWith("vec4")) {
					AetheriumShaders.logger.error("Failed to process " + directive + ": value was not a valid vec4 constructor");
				}

				String vec4Args = value.substring("vec4".length()).trim();

				if (!vec4Args.startsWith("(") || !vec4Args.endsWith(")")) {
					AetheriumShaders.logger.error("Failed to process " + directive + ": value was not a valid vec4 constructor");
				}

				vec4Args = vec4Args.substring(1, vec4Args.length() - 1);

				String[] parts = vec4Args.split(",");

				for (int i = 0; i < parts.length; i++) {
					parts[i] = parts[i].trim();
				}

				if (parts.length != 4) {
					AetheriumShaders.logger.error("Failed to process " + directive +
						": expected 4 arguments to a vec4 constructor, got " + parts.length);
				}

				try {
					consumer.accept(new Vector4f(
						Float.parseFloat(parts[0]),
						Float.parseFloat(parts[1]),
						Float.parseFloat(parts[2]),
						Float.parseFloat(parts[3])
					));
				} catch (NumberFormatException e) {
					AetheriumShaders.logger.error("Failed to process " + directive, e);
				}

				return;
			}

			typeCheckHelper("bool", booleanConstVariables, directive);
			typeCheckHelper("int", intConstVariables, directive);
			typeCheckHelper("int", stringConstVariables, directive);
			typeCheckHelper("float", floatConstVariables, directive);
		}
	}

	private void typeCheckHelper(String expected, Map<String, ?> candidates, ConstDirectiveParser.ConstDirective directive) {
		if (candidates.containsKey(directive.getKey())) {
			AetheriumShaders.logger.warn("Ignoring " + directive + " because it is of the wrong type, a type of " + expected + " is expected.");
		}
	}

	@Override
	public void acceptUniformDirective(String name, Runnable onDetected) {
		uniformDetectors.put(name, onDetected);
	}

	@Override
	public void acceptCommentStringDirective(String name, Consumer<String> consumer) {
		commentStringVariables.put(name, consumer);
	}

	@Override
	public void acceptCommentIntDirective(String name, IntConsumer consumer) {
		commentIntVariables.put(name, consumer);
	}

	@Override
	public void acceptCommentFloatDirective(String name, FloatConsumer consumer) {
		commentFloatVariables.put(name, consumer);
	}

	@Override
	public void acceptConstBooleanDirective(String name, BooleanConsumer consumer) {
		booleanConstVariables.put(name, consumer);
	}

	@Override
	public void acceptConstStringDirective(String name, Consumer<String> consumer) {
		stringConstVariables.put(name, consumer);
	}

	@Override
	public void acceptConstIntDirective(String name, IntConsumer consumer) {
		intConstVariables.put(name, consumer);
	}

	@Override
	public void acceptConstFloatDirective(String name, FloatConsumer consumer) {
		floatConstVariables.put(name, consumer);
	}

	@Override
	public void acceptConstVec2Directive(String name, Consumer<Vector2f> consumer) {
		vec2ConstVariables.put(name, consumer);
	}

	@Override
	public void acceptConstIVec3Directive(String name, Consumer<Vector3i> consumer) {
		ivec3ConstVariables.put(name, consumer);
	}

	@Override
	public void acceptConstVec4Directive(String name, Consumer<Vector4f> consumer) {
		vec4ConstVariables.put(name, consumer);
	}
}
