package dev.spacebod.aetherium.shaders.pipeline.transform.lowering;

import com.mojang.renderpearl.api.GpuFormat;
import java.util.regex.Pattern;

/** GLSL type facts the lowering needs: interface slots, std140 layout, vertex attribute conversions and defaults. */
public final class GlslTypes {
	private GlslTypes() {
	}

	/** Interface locations one value of {@code type} takes. */
	public static int slots(String type) {
		// Matrices take one location per column (matCxR: C columns of R rows); double vectors of 3 or 4 take two.
		var m = Pattern.compile("(d?)mat([234])(?:x([234]))?").matcher(type);
		if (m.matches()) {
			int columns = Integer.parseInt(m.group(2));
			int rows = m.group(3) == null ? columns : Integer.parseInt(m.group(3));
			return columns * (!m.group(1).isEmpty() && rows >= 3 ? 2 : 1);
		}
		return type.equals("dvec3") || type.equals("dvec4") ? 2 : 1;
	}

	// std140: scalars 4, vec2 8, vec3/vec4 16; matrices are arrays of column vec4s; array elements rounded up to 16.

	public static int std140Alignment(String type) {
		return switch (type) {
			case "float", "int", "uint", "bool" -> 4;
			case "vec2", "ivec2", "uvec2", "bvec2" -> 8;
			default -> 16;
		};
	}

	public static int std140Size(String type) {
		return switch (type) {
			case "float", "int", "uint", "bool" -> 4;
			case "vec2", "ivec2", "uvec2", "bvec2" -> 8;
			case "vec3", "ivec3", "uvec3", "bvec3" -> 12;
			case "vec4", "ivec4", "uvec4", "bvec4" -> 16;
			case "mat2" -> 32;
			case "mat3" -> 48;
			case "mat4" -> 64;
			default -> throw new IllegalArgumentException(type);
		};
	}

	public static int std140ArrayStride(String type) {
		return Math.max(16, (std140Size(type) + 15) / 16 * 16);
	}

	/** The GLSL type a vertex element is read as without conversion (vanilla checks base type and component count). */
	public static String naturalType(GpuFormat format) {
		String base = switch (format.componentType()) {
			case UINT_8, UINT_16, UINT_32 -> "u";
			case SINT_8, SINT_16, SINT_32 -> "i";
			default -> "";
		};
		int n = format.componentCount();
		if (n == 1) {
			return base.equals("u") ? "uint" : base.equals("i") ? "int" : "float";
		}
		return base + "vec" + n;
	}

	public static int components(String type) {
		char last = type.charAt(type.length() - 1);
		return last >= '2' && last <= '4' ? last - '0' : 1;
	}

	public static char baseOf(String type) {
		return type.startsWith("u") ? 'u' : type.startsWith("i") ? 'i' : type.startsWith("b") ? 'b' : 'f';
	}

	/** {@code raw} of type {@code from} as type {@code to}; missing components filled with OpenGL's (0, 0, 0, 1). */
	public static String convert(String raw, String from, String to) {
		int have = components(from);
		int want = components(to);
		String scalar = switch (baseOf(to)) {
			case 'u' -> "uint";
			case 'i' -> "int";
			case 'b' -> "bool";
			default -> "float";
		};
		StringBuilder args = new StringBuilder();
		for (int i = 0; i < want; i++) {
			if (i > 0) {
				args.append(", ");
			}
			String component = i < have ? (have == 1 ? raw : raw + "." + "xyzw".charAt(i)) : (i == 3 ? "1" : "0");
			args.append(scalar).append('(').append(component).append(')');
		}
		return want == 1 ? args.toString() : to + "(" + args + ")";
	}

	/**
	 * OpenGL's value for an attribute the vertex format lacks (0, 0, 0, 1). A missing tangent defaults to +x rather than
	 * zero: packs normalise it, and normalize(0) is NaN.
	 */
	public static String defaultValue(String name, String type) {
		if (name.equals("at_tangent")) {
			if (type.equals("vec4")) {
				return "vec4(1.0, 0.0, 0.0, 1.0)";
			}
			if (type.equals("vec3")) {
				return "vec3(1.0, 0.0, 0.0)";
			}
		}
		return switch (type) {
			case "float" -> "0.0";
			case "int" -> "0";
			case "uint" -> "0u";
			case "bool" -> "false";
			case "vec2", "vec3" -> type + "(0.0)";
			case "vec4" -> "vec4(0.0, 0.0, 0.0, 1.0)";
			case "ivec2", "ivec3" -> type + "(0)";
			case "ivec4" -> "ivec4(0, 0, 0, 1)";
			case "uvec2", "uvec3" -> type + "(0u)";
			case "uvec4" -> "uvec4(0u, 0u, 0u, 1u)";
			default -> type + "(0.0)";
		};
	}
}
