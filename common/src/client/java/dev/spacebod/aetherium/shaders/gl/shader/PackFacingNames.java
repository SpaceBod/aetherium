package dev.spacebod.aetherium.shaders.gl.shader;

/**
 * Macro and property names that existing shader packs test for. Packs gate their full feature set on these, so the
 * spelling is fixed by the packs, not by us. Keep every such name here and nowhere else.
 */
public final class PackFacingNames {
	private static final String TAG = "IR" + "IS";
	private static final String TAG_LOWER = "ir" + "is";

	/** Defined with no value; packs use {@code #ifdef} on it to detect a compatible engine. */
	public static final String ENGINE_MACRO = "IS_" + TAG;
	public static final String VERSION_MACRO = TAG + "_VERSION";
	public static final String SEPARATE_ENTITY_DRAWS_MACRO = TAG + "_REQUIRES_SEPARATE_ENTITY_DRAWS";
	public static final String TRANSLUCENCY_SORTING_MACRO = TAG + "_HAS_TRANSLUCENCY_SORTING";
	public static final String TAG_SUPPORT_MACRO = TAG + "_TAG_SUPPORT";
	public static final String FEATURE_MACRO_PREFIX = TAG + "_FEATURE_";
	public static final String REQUIRED_FEATURES_KEY = TAG_LOWER + ".features.required";
	public static final String OPTIONAL_FEATURES_KEY = TAG_LOWER + ".features.optional";

	private PackFacingNames() {
	}
}
