package dev.spacebod.aetherium.shaders.gl.blending;

import java.util.Optional;

/** The comparison of an alpha test ({@code alphaTest.<program>=<function> <reference>}). */
public enum AlphaTestFunction {
	NEVER(null),
	LESS("<"),
	EQUAL("=="),
	LEQUAL("<="),
	GREATER(">"),
	NOTEQUAL("!="),
	GEQUAL(">="),
	ALWAYS(null);

	/** The GLSL comparison (alpha on the left, the reference on the right); null for NEVER and ALWAYS. */
	private final String expression;

	AlphaTestFunction(String expression) {
		this.expression = expression;
	}

	public static Optional<AlphaTestFunction> fromString(String name) {
		if ("GL_ALWAYS".equals(name)) {
			// The shaders.properties spec names GL_ALWAYS although no other function carries the GL_ prefix; accept
			// both spellings.
			return Optional.of(AlphaTestFunction.ALWAYS);
		}

		try {
			return Optional.of(AlphaTestFunction.valueOf(name));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	public String getExpression() {
		return expression;
	}
}
