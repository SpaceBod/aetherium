package dev.spacebod.aetherium.shaders.shaderpack.preprocessor;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.helpers.StringPair;
import org.anarres.cpp.Feature;
import org.anarres.cpp.LexerException;
import org.anarres.cpp.Preprocessor;
import org.anarres.cpp.StringLexerSource;
import org.anarres.cpp.Token;

/** Runs the C preprocessor over a flattened program source, with the environment macros defined. */
public class JcppProcessor {
	public static String glslPreprocessSource(String source, Iterable<StringPair> environmentDefines) {
		if (source.contains(GlslCollectingListener.MARKER_PREFIX)) {
			// The pack's own text must not pose as one of the markers below.
			AetheriumShaders.logger.warn("removed the reserved text {} from a pack source", GlslCollectingListener.MARKER_PREFIX);
			source = source.replace(GlslCollectingListener.MARKER_PREFIX, "");
		}

		// #version and #extension are disguised as #warning so the listener can hoist the ones that survive
		// preprocessing to the top. Packs written on lenient drivers place #extension anywhere; strict drivers require it
		// at the top. The hoisted #version need not have been the first line of the pack's file.
		source = source.replace("#version", GlslCollectingListener.VERSION_MARKER);
		source = source.replace("#extension", GlslCollectingListener.EXTENSION_MARKER);

		// Some packs (e.g. Chocapic High Performance) contain stray NUL characters that trip up JCPP.
		source = source.replace("\u0000", "");

		GlslCollectingListener listener = new GlslCollectingListener();

		@SuppressWarnings("resource") final Preprocessor pp = new Preprocessor();

		// Environment defines go in as macros rather than source text, keeping error line numbers accurate.
		try {
			for (StringPair envDefine : environmentDefines) {
				pp.addMacro(envDefine.key(), envDefine.value());
			}
		} catch (LexerException e) {
			throw new RuntimeException("Unexpected LexerException processing macros", e);
		}

		pp.setListener(listener);
		pp.addInput(new StringLexerSource(source, true));
		pp.addFeature(Feature.KEEPCOMMENTS);

		final StringBuilder builder = new StringBuilder();

		try {
			for (; ; ) {
				final Token tok = pp.token();
				if (tok == null) break;
				if (tok.getType() == Token.EOF) break;
				builder.append(tok.getText());
			}
		} catch (final Exception e) {
			throw new RuntimeException("GLSL source pre-processing failed", e);
		}

		builder.append("\n");

		source = listener.collectLines() + builder;

		return source;
	}
}
