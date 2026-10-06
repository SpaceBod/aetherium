package dev.spacebod.aetherium.shaders.gl.shader;

/** A pack program's source could not be transformed; the message names the program. */
public class ShaderCompileException extends RuntimeException {
	private final String filename;

	public ShaderCompileException(String filename, Exception error) {
		super(error);

		this.filename = filename;
	}

	@Override
	public String getMessage() {
		return filename + ": " + super.getMessage();
	}
}
