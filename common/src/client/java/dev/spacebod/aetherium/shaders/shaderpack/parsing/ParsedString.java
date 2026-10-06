package dev.spacebod.aetherium.shaders.shaderpack.parsing;

import org.jetbrains.annotations.Nullable;

public class ParsedString {
	private String text;

	public ParsedString(String text) {
		this.text = text;
	}

	public boolean takeLiteral(String token) {
		if (!text.startsWith(token)) {
			return false;
		}

		text = text.substring(token.length());

		return true;
	}

	public boolean takeSomeWhitespace() {
		if (text.isEmpty() || !Character.isWhitespace(text.charAt(0))) {
			return false;
		}

		// Trims both ends: trailing whitespace is dropped too.
		text = text.trim();

		return true;
	}

	public boolean takeComments() {
		if (!text.startsWith("//")) {
			return false;
		}

		text = text.substring(2);

		while (text.startsWith("/")) {
			text = text.substring(1);
		}

		return true;
	}

	public boolean currentlyContains(String text) {
		return this.text.contains(text);
	}

	public boolean isEnd() {
		return text.isEmpty();
	}

	public String takeRest() {
		return text;
	}

	private String takeCharacters(int numChars) {
		String result = text.substring(0, numChars);
		text = text.substring(numChars);

		return result;
	}

	@Nullable
	public String takeWord() {
		if (isEnd()) {
			return null;
		}

		int position = 0;

		for (char character : text.toCharArray()) {
			if (!Character.isDigit(character)
				&& !Character.isAlphabetic(character)
				&& character != '_') {
				break;
			}

			position += 1;
		}

		if (position == 0) {
			return null;
		}

		return takeCharacters(position);
	}

	@Nullable
	public String takeNumber() {
		if (isEnd()) {
			return null;
		}

		int position = 0;

		while (position < text.length()) {
			if (position + 1 < text.length()) {
				if (!Character.isDigit(text.charAt(position)) && !Character.isDigit(text.charAt(position + 1))) {
					break;
				}
			} else if (!Character.isDigit(text.charAt(position))) {
				break;
			}

			position++;
		}

		// Accept an f/F float suffix after a number
		if (position > 0 && position + 1 < text.length() && (text.charAt(position) == 'f' || text.charAt(position) == 'F')) {
			position++;
		}

		try {
			Float.parseFloat(text.substring(0, position));
		} catch (Exception e) {
			return null;
		}

		return takeCharacters(position);
	}

	@Nullable
	public String takeWordOrNumber() {
		String number = takeNumber();

		if (number == null) {
			return takeWord();
		}

		return number;
	}
}
