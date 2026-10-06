package dev.spacebod.aetherium.shaders.shaderpack.option;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import dev.spacebod.aetherium.shaders.helpers.OptionalBoolean;
import dev.spacebod.aetherium.shaders.shaderpack.include.AbsolutePackPath;
import dev.spacebod.aetherium.shaders.shaderpack.option.values.OptionValues;
import dev.spacebod.aetherium.shaders.shaderpack.parsing.ParsedString;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackShadowDirectives;
import dev.spacebod.aetherium.shaders.shaderpack.transform.line.LineTransform;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One shader source file plus per-line annotations: the option declared on that line, or a diagnostic saying why a
 * plausible option line was rejected.
 * <p>
 * Options are configured by editing source lines, so this class covers both ends of that process: discovering
 * options, and rewriting lines to apply changed values. Deduplicating options across files and loading saved values
 * happen elsewhere.
 * <p>
 * Lines are parsed in isolation, so conflicting declarations (same name and type, different defaults) can occur even
 * within one file. The only cross-line state is
 * {@link OptionAnnotatedSource#getBooleanDefineReferences() boolean define reference tracking}.
 * <p>
 * Immutable: changed values can be applied repeatedly without re-parsing, and the source cannot drift from its
 * annotations.
 */
public final class OptionAnnotatedSource {
	private static final ImmutableSet<String> VALID_CONST_OPTION_NAMES;

	static {
		ImmutableSet.Builder<String> values = ImmutableSet.<String>builder().add(
			"shadowMapResolution",
			"shadowDistance",
			"voxelDistance",
			"shadowDistanceRenderMul",
			"entityShadowDistanceMul",
			"shadowIntervalSize",
			"generateShadowMipmap",
			"generateShadowColorMipmap",
			"shadowHardwareFiltering",
			"shadowtex0Mipmap",
			"shadowtexMipmap",
			"shadowtex1Mipmap",
			"shadowtex0Nearest",
			"shadowtexNearest",
			"shadow0MinMagNearest",
			"shadowtex1Nearest",
			"shadow1MinMagNearest",
			"wetnessHalflife",
			"drynessHalflife",
			"eyeBrightnessHalflife",
			"centerDepthHalflife",
			"sunPathRotation",
			"ambientOcclusionLevel",
			"superSamplingLevel",
			"noiseTextureResolution"
		);

		for (int i = 0; i < PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_PACK; i++) {
			values.add("shadowcolor" + i + "Mipmap");
			values.add("shadowColor" + i + "Mipmap");
			values.add("shadowcolor" + i + "Nearest");
			values.add("shadowColor" + i + "Nearest");
			values.add("shadowcolor" + i + "MinMagNearest");
			values.add("shadowColor" + i + "MinMagNearest");
			values.add("shadowHardwareFiltering" + i);
		}

		VALID_CONST_OPTION_NAMES = values.build();
	}

	private final ImmutableList<String> lines;
	private final ImmutableMap<Integer, BooleanOption> booleanOptions;
	private final ImmutableMap<Integer, StringOption> stringOptions;
	/**
	 * Per-line reasons a plausible option line was ignored, with hints for fixing it.
	 */
	private final ImmutableMap<Integer, String> diagnostics;
	/**
	 * Boolean #define option name to the lines of its {@code #ifdef}/{@code #ifndef} references.
	 * <p>
	 * References in plain {@code #if}/{@code #elif} directives are deliberately not counted: packs rely on that
	 * behaviour of the format. A boolean #define only counts as an option if it is referenced somewhere in the same
	 * logical file (after #include expansion), regardless of position.
	 */
	private final ImmutableMap<String, IntList> booleanDefineReferences;

	public OptionAnnotatedSource(final String source) {
		// \R matches any newline sequence.
		this(ImmutableList.copyOf(source.split("\\R")));
	}

	public OptionAnnotatedSource(final ImmutableList<String> lines) {
		this.lines = lines;

		AnnotationsBuilder builder = new AnnotationsBuilder();

		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index);
			parseLine(builder, index, line);
		}

		this.booleanOptions = builder.booleanOptions.build();
		this.stringOptions = builder.stringOptions.build();
		this.diagnostics = builder.diagnostics.build();
		this.booleanDefineReferences = ImmutableMap.copyOf(builder.booleanDefineReferences);
	}

	private static void parseLine(AnnotationsBuilder builder, int index, String lineText) {
		// Cheap pre-filter before parsing.
		if (!lineText.contains("#define")
			&& !lineText.contains("const")
			&& !lineText.contains("#ifdef")
			&& !lineText.contains("#ifndef")) {
			return;
		}

		// Parse the trimmed form of the line to ignore indentation and trailing whitespace.
		ParsedString line = new ParsedString(lineText.trim());

		if (line.takeLiteral("#ifdef") || line.takeLiteral("#ifndef")) {
			// Only #ifdef/#ifndef count as references (see booleanDefineReferences).
			parseIfdef(builder, index, line);
		} else if (line.takeLiteral("const")) {
			parseConst(builder, index, line);
		} else if (line.currentlyContains("#define")) {
			parseDefineOption(builder, index, line);
		}
	}

	private static void parseIfdef(AnnotationsBuilder builder, int index, ParsedString line) {
		if (!line.takeSomeWhitespace()) {
			return;
		}

		String name = line.takeWord();

		line.takeSomeWhitespace();

		if (name == null || !line.isEnd()) {
			return;
		}

		builder.booleanDefineReferences
			.computeIfAbsent(name, n -> new IntArrayList()).add(index);
	}

	private static void parseConst(AnnotationsBuilder builder, int index, ParsedString line) {
		// const is already taken.

		if (!line.takeSomeWhitespace()) {
			builder.diagnostics.put(index, "Expected whitespace after const and before type declaration");
			return;
		}

		boolean isString;

		if (line.takeLiteral("int") || line.takeLiteral("float")) {
			isString = true;
		} else if (line.takeLiteral("bool")) {
			isString = false;
		} else {
			builder.diagnostics.put(index, "Unexpected type declaration after const. " +
				"Expected int, float, or bool. " +
				"Vector const declarations cannot be configured using shader options.");
			return;
		}

		if (!line.takeSomeWhitespace()) {
			builder.diagnostics.put(index, "Expected whitespace after type declaration.");
			return;
		}

		String name = line.takeWord();

		if (name == null) {
			builder.diagnostics.put(index, "Expected name of option after type declaration, " +
				"but an unexpected character was detected first.");
			return;
		}

		line.takeSomeWhitespace();

		if (!line.takeLiteral("=")) {
			builder.diagnostics.put(index, "Unexpected characters before equals sign in const declaration.");
			return;
		}

		line.takeSomeWhitespace();

		String value = line.takeWordOrNumber();

		if (value == null) {
			builder.diagnostics.put(index, "Unexpected non-whitespace characters after equals sign");
			return;
		}

		line.takeSomeWhitespace();

		if (!line.takeLiteral(";")) {
			builder.diagnostics.put(index, "Value between the equals sign and the semicolon wasn't parsed as a valid word or number.");
			return;
		}

		line.takeSomeWhitespace();

		String comment;

		if (line.takeComments()) {
			comment = line.takeRest().trim();
		} else if (!line.isEnd()) {
			builder.diagnostics.put(index, "Unexpected non-whitespace characters outside of comment after semicolon");
			return;
		} else {
			comment = null;
		}

		if (!isString) {
			boolean booleanValue;

			if ("true".equals(value)) {
				booleanValue = true;
			} else if ("false".equals(value)) {
				booleanValue = false;
			} else {
				builder.diagnostics.put(index, "Expected true or false as the value of a boolean const option, but got "
					+ value + ".");
				return;
			}

			if (!VALID_CONST_OPTION_NAMES.contains(name)) {
				builder.diagnostics.put(index, "This was a valid const boolean option declaration, but " + name +
					" was not recognized as being a name of one of the configurable const options.");
				return;
			}

			builder.booleanOptions.put(index, new BooleanOption(OptionType.CONST, name, comment, booleanValue));
			return;
		}

		if (!VALID_CONST_OPTION_NAMES.contains(name)) {
			builder.diagnostics.put(index, "This was a valid const option declaration, but " + name +
				" was not recognized as being a name of one of the configurable const options.");
			return;
		}

		StringOption option = StringOption.create(OptionType.CONST, name, comment, value);

		if (option != null) {
			builder.stringOptions.put(index, option);
		} else {
			builder.diagnostics.put(index, "Ignoring this const option because it is missing an allowed values list" +
				"in a comment, but is not a boolean const option.");
		}
	}

	private static void parseDefineOption(AnnotationsBuilder builder, int index, ParsedString line) {
		// Remove the leading comment for processing.
		boolean hasLeadingComment = line.takeComments();

		// allow but do not require whitespace between comments and #define
		line.takeSomeWhitespace();

		if (!line.takeLiteral("#define")) {
			builder.diagnostics.put(index,
				"This line contains an occurrence of \"#define\" " +
					"but it wasn't in a place we expected, ignoring it.");
			return;
		}

		if (!line.takeSomeWhitespace()) {
			builder.diagnostics.put(index,
				"This line properly starts with a #define statement but doesn't have " +
					"any whitespace characters after the #define.");
			return;
		}

		String name = line.takeWord();

		if (name == null) {
			builder.diagnostics.put(index,
				"Invalid syntax after #define directive. " +
					"No alphanumeric or underscore characters detected.");
			return;
		}

		boolean tookWhitespace = line.takeSomeWhitespace();

		if (line.isEnd()) {
			// Plain define directive without a comment.
			builder.booleanOptions.put(index, new BooleanOption(OptionType.DEFINE, name, null, !hasLeadingComment));
			return;
		}

		if (line.takeComments()) {
			// Boolean options take a bare comment; no allowed-values list.
			String comment = line.takeRest().trim();

			builder.booleanOptions.put(index, new BooleanOption(OptionType.DEFINE, name, comment, !hasLeadingComment));
			return;
		} else if (!tookWhitespace) {
			// Invalid syntax.
			builder.diagnostics.put(index,
				"Invalid syntax after #define directive. Only alphanumeric or underscore " +
					"characters are allowed in option names.");

			return;
		}

		if (hasLeadingComment) {
			builder.diagnostics.put(index,
				"Ignoring potential non-boolean #define option since it has a leading comment. " +
					"Leading comments (//) are only allowed on boolean #define options.");
			return;
		}

		String value = line.takeWordOrNumber();

		if (value == null) {
			builder.diagnostics.put(index, "Ignoring this #define directive because it doesn't appear to be a boolean #define, " +
				"and its potential value wasn't a valid number or a valid word.");
			return;
		}

		tookWhitespace = line.takeSomeWhitespace();

		if (line.isEnd()) {
			builder.diagnostics.put(index, "Ignoring this #define because it doesn't have a comment containing" +
				" a list of allowed values afterwards, but it has a value so is therefore not a boolean.");
			return;
		} else if (!tookWhitespace) {
			if (!line.takeComments()) {
				builder.diagnostics.put(index,
					"Invalid syntax after value #define directive. " +
						"Invalid characters after number or word.");
				return;
			}
		} else if (!line.takeComments()) {
			builder.diagnostics.put(index,
				"Invalid syntax after value #define directive. " +
					"Only comments may come after the value.");
			return;
		}

		String comment = line.takeRest().trim();

		StringOption option = StringOption.create(OptionType.DEFINE, name, comment, value);

		if (option == null) {
			builder.diagnostics.put(index, "Ignoring this #define because it is missing an allowed values list" +
				"in a comment, but is not a boolean define.");
			return;
		}

		builder.stringOptions.put(index, option);
	}

	private static boolean hasLeadingComment(String line) {
		return line.trim().startsWith("//");
	}

	private static String removeLeadingComment(String line) {
		ParsedString parsed = new ParsedString(line);

		parsed.takeSomeWhitespace();
		parsed.takeComments();

		return parsed.takeRest();
	}

	private static String setBooleanDefineValue(String line, OptionalBoolean newValue, boolean defaultValue) {
		if (hasLeadingComment(line) && newValue.orElse(defaultValue)) {
			return removeLeadingComment(line);
		} else if (!newValue.orElse(defaultValue)) {
			return "//" + line;
		} else {
			return line;
		}
	}

	public ImmutableMap<Integer, BooleanOption> getBooleanOptions() {
		return booleanOptions;
	}

	public ImmutableMap<Integer, StringOption> getStringOptions() {
		return stringOptions;
	}

	public ImmutableMap<Integer, String> getDiagnostics() {
		return diagnostics;
	}

	public ImmutableMap<String, IntList> getBooleanDefineReferences() {
		return booleanDefineReferences;
	}

	public OptionSet getOptionSet(AbsolutePackPath filePath, Set<String> booleanDefineReferences) {
		OptionSet.Builder builder = OptionSet.builder();

		booleanOptions.forEach((lineIndex, option) -> {
			if (booleanDefineReferences.contains(option.getName())) {
				OptionLocation location = new OptionLocation(filePath, lineIndex);
				builder.addBooleanOption(location, option);
			}
		});

		stringOptions.forEach((lineIndex, option) -> {
			OptionLocation location = new OptionLocation(filePath, lineIndex);
			builder.addStringOption(location, option);
		});

		return builder.build();
	}

	public LineTransform asTransform(OptionValues values) {
		return (index, line) -> edit(values, index, line);
	}

	public String apply(OptionValues values) {
		StringBuilder source = new StringBuilder();

		for (int index = 0; index < lines.size(); index++) {
			source.append(edit(values, index, lines.get(index)));
			source.append('\n');
		}

		return source.toString();
	}

	private String edit(OptionValues values, int index, String existing) {
		BooleanOption booleanOption = booleanOptions.get(index);

		if (booleanOption != null) {
			OptionalBoolean value = values.getBooleanValue(booleanOption.getName());
			if (booleanOption.getType() == OptionType.DEFINE) {
				return setBooleanDefineValue(existing, value, booleanOption.getDefaultValue());
			} else if (booleanOption.getType() == OptionType.CONST) {
				if (value != OptionalBoolean.DEFAULT) {
					// Never DEFAULT here; orElse only unwraps it.
					return editConst(existing, Boolean.toString(booleanOption.getDefaultValue()), Boolean.toString(value.orElse(booleanOption.getDefaultValue())));
				} else {
					return existing;
				}
			} else {
				throw new AssertionError("Unknown option type " + booleanOption.getType());
			}
		}

		StringOption stringOption = stringOptions.get(index);

		if (stringOption != null) {
			return values.getStringValue(stringOption.getName()).map(value -> {
				if (stringOption.getType() == OptionType.DEFINE) {
					return "#define " + stringOption.getName() + " " + value + " // OptionAnnotatedSource: Changed option";
				} else if (stringOption.getType() == OptionType.CONST) {
					return editConst(existing, stringOption.getDefaultValue(), value);
				} else {
					throw new AssertionError("Unknown option type " + stringOption.getType());
				}
			}).orElse(existing);
		}

		return existing;
	}

	private String editConst(String line, String currentValue, String newValue) {
		int equalsIndex = line.indexOf('=');

		if (equalsIndex == -1) {
			// Unreachable: const options are only parsed from lines containing '='.
			throw new IllegalStateException();
		}

		String firstPart = line.substring(0, equalsIndex);
		String secondPart = line.substring(equalsIndex);

		secondPart = secondPart.replaceFirst(Pattern.quote(currentValue), Matcher.quoteReplacement(newValue));

		return firstPart + secondPart;
	}

	private static class AnnotationsBuilder {
		private final ImmutableMap.Builder<Integer, BooleanOption> booleanOptions;
		private final ImmutableMap.Builder<Integer, StringOption> stringOptions;
		private final ImmutableMap.Builder<Integer, String> diagnostics;
		private final Map<String, IntList> booleanDefineReferences;

		private AnnotationsBuilder() {
			booleanOptions = ImmutableMap.builder();
			stringOptions = ImmutableMap.builder();
			diagnostics = ImmutableMap.builder();
			booleanDefineReferences = new HashMap<>();
		}
	}
}
