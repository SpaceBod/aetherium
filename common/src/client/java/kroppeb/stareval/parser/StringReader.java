package kroppeb.stareval.parser;

import kroppeb.stareval.exception.UnexpectedCharacterException;

/**
 * Cursor over an expression string with single-character lookahead and mark/substring support.
 */
public class StringReader {
	private final String string;
	/** Index of the next character to read. */
	private int nextIndex = -1;
	/** Index of the last character read. */
	private int lastIndex;
	/** Start index of {@link #substring()}. */
	private int mark;


	public StringReader(String string) {
		this.string = string;
		this.advanceOneCharacter();
		this.skipWhitespace();

		// Start at the first non-whitespace character.
		this.lastIndex = this.nextIndex;
		this.mark();
	}

	/**
	 * Advances one character, recording the previous position as the last read; does not skip whitespace.
	 */
	private void advanceOneCharacter() {
		this.lastIndex = this.nextIndex;

		if (this.nextIndex >= this.string.length()) {
			return;
		}

		this.nextIndex++;
	}

	public void skipWhitespace() {
		while (this.nextIndex < this.string.length() && Character.isWhitespace(this.string.charAt(this.nextIndex))) {
			this.nextIndex++;
		}
	}

	/**
	 * @return The character that would be returned by the next call to {@link #read}
	 */
	public char peek() {
		return this.string.charAt(this.nextIndex);
	}

	public void skipOneCharacter() {
		this.advanceOneCharacter();
	}

	public char read() {
		char current = this.peek();
		this.skipOneCharacter();
		return current;
	}

	/**
	 * Reads a character, throwing if it is not {@code c}.
	 */
	public void read(char c) throws UnexpectedCharacterException {
		char read = this.read();

		if (read != c) {
			throw new UnexpectedCharacterException(c, read, this.getCurrentIndex());
		}
	}

	/**
	 * Consumes the next character only if it is {@code c}.
	 *
	 * @return whether it was consumed
	 */
	public boolean tryRead(char c) {
		if (!this.canRead()) {
			return false;
		}

		char read = this.peek();

		if (read != c) {
			return false;
		}

		this.skipOneCharacter();
		return true;
	}

	/**
	 * Marks the character just read as the start of {@link #substring()}.
	 */
	public void mark() {
		this.mark = this.lastIndex;
	}

	/**
	 * @return the text from the {@link #mark()} up to and including the last read character
	 */
	public String substring() {
		return this.string.substring(this.mark, this.lastIndex + 1);
	}

	public boolean canRead() {
		return this.nextIndex < this.string.length();
	}

	public int getCurrentIndex() {
		return this.lastIndex;
	}
}
