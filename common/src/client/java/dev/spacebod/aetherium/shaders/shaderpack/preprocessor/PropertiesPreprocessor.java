package dev.spacebod.aetherium.shaders.shaderpack.preprocessor;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.helpers.StringPair;
import dev.spacebod.aetherium.shaders.shaderpack.option.ShaderPackOptions;
import org.anarres.cpp.Feature;
import org.anarres.cpp.LexerException;
import org.anarres.cpp.Preprocessor;
import org.anarres.cpp.PreprocessorCommand;
import org.anarres.cpp.StringLexerSource;
import org.anarres.cpp.Token;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Runs the C preprocessor over a properties file (shaders.properties, the ID maps), with the pack's options and the
 * environment macros defined.
 */
public class PropertiesPreprocessor {
	/** Shared by the internal markers; reserved, so pack text never contains it. */
	private static final String RESERVED_PREFIX = "AETH_PASSTHROUGH";
	private static final String BACKSLASH = RESERVED_PREFIX + "BACKSLASH";

	public static final Pattern BACKSLASH_MATCHER = Pattern.compile("(?<!\\\\\\n)^(?![ \\t]*(#|block\\.\\d*|layer\\.\\d*|item\\.\\d*|entity\\.\\d*|dimension\\.\\d*)).+", Pattern.MULTILINE);

	public static String preprocessSource(String source, ShaderPackOptions shaderPackOptions, Iterable<StringPair> environmentDefines) {
		source = withoutReservedText(source);

		List<String> booleanValues = getBooleanValues(shaderPackOptions);
		Map<String, String> stringValues = getStringValues(shaderPackOptions);

		try (Preprocessor pp = new Preprocessor()) {
			for (String value : booleanValues) {
				pp.addMacro(value);
			}

			for (StringPair envDefine : environmentDefines) {
				if (envDefine.value().isEmpty()) {
					pp.addMacro(envDefine.key());
				} else {
					pp.addMacro(envDefine.key(), envDefine.value());
				}
			}

			stringValues.forEach((name, value) -> {
				try {
					pp.addMacro(name, value);
				} catch (LexerException e) {
					AetheriumShaders.logger.error("Failed to preprocess property file!", e);
				}
			});

			return process(pp, source);
		} catch (IOException e) {
			throw new RuntimeException("Unexpected IOException while processing macros", e);
		} catch (LexerException e) {
			throw new RuntimeException("Unexpected LexerException processing macros", e);
		}
	}

	public static String preprocessSource(String source, Iterable<StringPair> environmentDefines) {
		source = withoutReservedText(source);

		Preprocessor preprocessor = new Preprocessor();

		try {
			for (StringPair envDefine : environmentDefines) {
				preprocessor.addMacro(envDefine.key(), envDefine.value());
			}
		} catch (LexerException e) {
			AetheriumShaders.logger.error("Failed to preprocess property file!", e);
		}

		return process(preprocessor, source);
	}

	/** The pack's text with the markers below removed, so it can never pose as one. */
	private static String withoutReservedText(String source) {
		if (source.contains(RESERVED_PREFIX)) {
			AetheriumShaders.logger.warn("removed the reserved text {} from a properties file", RESERVED_PREFIX);
			return source.replace(RESERVED_PREFIX, "");
		}
		return source;
	}

	private static String process(Preprocessor preprocessor, String source) {
		PropertyCollectingListener listener = new PropertyCollectingListener();
		preprocessor.setListener(listener);

		// Trim lines so whitespace after a line-continuation backslash doesn't break it (needed by Voyager Shader).
		// Non-directive '#' lines are comments in .properties and are dropped.
		source = Arrays.stream(source.split("\\R")).map(String::trim).filter(s -> !s.isBlank())
			.map(line -> {
				if (line.startsWith("#")) {
					for (PreprocessorCommand command : PreprocessorCommand.values()) {
						if (line.startsWith("#" + (command.name().replace("PP_", "").toLowerCase(Locale.ROOT)))) {
							return line;
						}
					}
					return "";
				}
				// '#' also marks comments in .properties; PropertyCollectingListener suppresses the resulting
				// unknown-directive errors.
				return line.replace("#", "");
			}).collect(Collectors.joining("\n")) + "\n";
		// Backslashes are hidden from the preprocessor (it would read them as escapes) and restored afterwards.
		source = source.replace("\\", BACKSLASH);

		preprocessor.addInput(new StringLexerSource(source, true));
		preprocessor.addFeature(Feature.KEEPCOMMENTS);

		final StringBuilder builder = new StringBuilder();

		try {
			for (; ; ) {
				final Token tok = preprocessor.token();
				if (tok == null) break;
				if (tok.getType() == Token.EOF) break;
				builder.append(tok.getText());
			}
		} catch (final Exception e) {
			AetheriumShaders.logger.error("Properties pre-processing failed", e);
		}

		source = builder.toString();

		return (listener.collectLines() + source).replace(BACKSLASH, "\\");
	}

	private static List<String> getBooleanValues(ShaderPackOptions shaderPackOptions) {
		List<String> booleanValues = new ArrayList<>();

		shaderPackOptions.getOptionSet().getBooleanOptions().forEach((string, value) -> {
			boolean trueValue = shaderPackOptions.getOptionValues().getBooleanValueOrDefault(string);

			if (trueValue) {
				booleanValues.add(string);
			}
		});

		return booleanValues;
	}

	private static Map<String, String> getStringValues(ShaderPackOptions shaderPackOptions) {
		Map<String, String> stringValues = new HashMap<>();

		shaderPackOptions.getOptionSet().getStringOptions().forEach(
			(optionName, value) -> stringValues.put(optionName, shaderPackOptions.getOptionValues().getStringValueOrDefault(optionName)));

		return stringValues;
	}
}
