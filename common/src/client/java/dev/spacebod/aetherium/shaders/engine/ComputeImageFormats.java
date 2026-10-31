package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;
import dev.spacebod.aetherium.shaders.gl.texture.InternalTextureFormat;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.TargetImages;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Numeric storage formats for store-only programs whose targets are floating-point textures. */
final class ComputeImageFormats {
	private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
	private static final Pattern READS = Pattern.compile("\\b(?:imageLoad|imageAtomic\\w*)\\s*\\(");
	private static final Pattern DECLARATION = Pattern.compile(
			"\\blayout\\s*\\((?<format>[^)]*)\\)(?<qualifiers>(?:\\s*\\b(?:uniform|readonly|writeonly|coherent|volatile|restrict)\\b)*)"
					+ "\\s*image2D\\s+(?<name>(?:shadow)?colorimg\\d+)\\s*;");
	private static final Pattern UNIFORM = Pattern.compile("\\buniform\\b");

	private ComputeImageFormats() {
	}

	static String normalize(String source, PackDirectives directives) {
		String code = COMMENTS.matcher(source).replaceAll(match -> " ".repeat(match.group().length()));
		if (READS.matcher(code).find()) {
			return source;
		}
		Matcher declaration = DECLARATION.matcher(code);
		StringBuilder result = new StringBuilder();
		int end = 0;
		while (declaration.find()) {
			String name = declaration.group("name");
			if (!UNIFORM.matcher(declaration.group("qualifiers")).find()
					|| !Pattern.compile("\\bimageStore\\s*\\(\\s*" + name + "\\b").matcher(code).find()) {
				continue;
			}
			String qualifier = ImageViews.declaredFormat(declaration.group());
			GpuFormat declared = qualifier == null ? null : ImageViews.format(qualifier);
			GpuFormat actual = targetFormat(name, directives);
			if (declared == null || !(declared.name().endsWith("_UNORM") || declared.name().endsWith("_SNORM"))
					|| !actual.name().endsWith("_FLOAT") || declared.blockSize() != actual.blockSize()
					|| declared.componentCount() != actual.componentCount()) {
				continue;
			}
			// Keep numeric stores in the target's format instead of reinterpreting normalized bits as floats.
			Matcher format = Pattern.compile("\\b" + Pattern.quote(qualifier) + "\\b").matcher(code);
			format.region(declaration.start("format"), declaration.end("format"));
			if (format.find()) {
				result.append(source, end, format.start()).append(StorageBindings.glslFormat(actual));
				end = format.end();
			}
		}
		return end == 0 ? source : result.append(source, end, source.length()).toString();
	}

	private static GpuFormat targetFormat(String name, PackDirectives directives) {
		Matcher target = TargetImages.matcher(name);
		if (!target.matches()) {
			throw new IllegalArgumentException("Not a target image: " + name);
		}
		InternalTextureFormat format;
		if (TargetImages.shadow(target)) {
			var setting = directives.getShadowDirectives().getColorSamplingSettings().get(TargetImages.index(target));
			format = setting == null ? InternalTextureFormat.RGBA : setting.getFormat();
		} else {
			var setting = directives.getRenderTargetDirectives().getRenderTargetSettings().get(TargetImages.index(target));
			format = setting == null ? InternalTextureFormat.RGBA : setting.getInternalFormat();
		}
		return PackTargets.gpuFormat(format);
	}
}
