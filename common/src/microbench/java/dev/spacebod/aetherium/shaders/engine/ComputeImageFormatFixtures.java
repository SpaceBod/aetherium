package dev.spacebod.aetherium.shaders.engine;

import com.google.common.collect.ImmutableList;
import dev.spacebod.aetherium.client.gpu.DeviceTraits;
import dev.spacebod.aetherium.client.gpu.GeometryStages;
import dev.spacebod.aetherium.shaders.shaderpack.DimensionId;
import dev.spacebod.aetherium.shaders.shaderpack.ShaderPack;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramArrayId;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public final class ComputeImageFormatFixtures {
	private ComputeImageFormatFixtures() {
	}

	public static void main(String[] args) {
		DeviceTraits.assumeForCompileCheck(false);
		GeometryStages.assumeForCompileCheck(true);
		run();
	}

	public static void run() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		try {
			Path directory = Files.createTempDirectory("aetherium-compute-formats-");
			Map<String, String> files = Map.of(
					"final.vsh", "#version 430\nvoid main() { gl_Position = vec4(0); }\n",
					"final.fsh", "#version 430\nconst int colortex2Format = RGBA16F;\nconst int colortex5Format = RGBA16;\n"
							+ "const int colortex9Format = R32UI;\nconst int shadowcolor0Format = RGBA16F;\nvoid main() {}\n",
					"composite20.csh", "#version 430\nlayout(local_size_x=1) in;\n" + stores("rgba16", "colorimg2"));
			try {
				for (var file : files.entrySet()) {
					Files.writeString(directory.resolve(file.getKey()), file.getValue());
				}
				var programs = new ShaderPack(directory, ImmutableList.of(), false).getProgramSet(DimensionId.OVERWORLD);
				PackDirectives directives = programs.getPackDirectives();
				expect(stores("rgba16", "colorimg2"), directives, "rgba16f");
				expect(stores("rgba16_snorm", "shadowcolorimg0"), directives, "rgba16f");
				unchanged(stores("rgba16f", "colorimg2"), directives);
				unchanged(stores("rgba16", "colorimg5"), directives);
				unchanged(stores("rgba8", "colorimg9"), directives);
				unchanged(stores("rg16", "colorimg2"), directives);
				unchanged(stores("rgba16", "customImage"), directives);
				unchanged("/* " + stores("rgba16", "colorimg2") + " */", directives);
				unchanged(stores("rgba16", "colorimg2") + "\nvec4 readback() { return imageLoad(colorimg2, ivec2(0)); }", directives);
				unchanged("layout(rgba16) uniform image2D colorimg2, colorimg5;\n"
						+ "void main() { imageStore(colorimg2, ivec2(0), vec4(0.25)); }", directives);
				String commented = "/* layout(rgba16) uniform image2D colorimg2; */\n" + stores("rgba16", "colorimg2");
				if (!ComputeImageFormats.normalize(commented, directives).startsWith("/* layout(rgba16)")) {
					throw new AssertionError("a commented declaration changed");
				}
				String layoutComment = stores("/* rgba16 */ rgba16, binding=2", "colorimg2");
				if (!ComputeImageFormats.normalize(layoutComment, directives).startsWith("layout(/* rgba16 */ rgba16f, binding=2)")) {
					throw new AssertionError("the layout comment or binding changed");
				}
				StorageBindings.configure(Map.of(), false, Map.of(), true);
				var source = programs.getCompute(ProgramArrayId.Composite)[20][0];
				var lowered = ComputePass.lower(source, TextureStage.COMPOSITE_AND_FINAL);
				if (lowered == null || !"rgba16f".equals(ImageViews.declaredFormat(lowered.glsl()))) {
					throw new AssertionError("the loaded compute program retained normalized image storage on a float target");
				}
			} finally {
				StorageBindings.reset();
				for (String name : files.keySet()) {
					Files.deleteIfExists(directory.resolve(name));
				}
				Files.delete(directory);
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		System.out.println("compute image formats: numeric float stores, shadow targets and loaded programs pass; integer aliases, reads, custom images and comments preserved");
	}

	private static String stores(String format, String name) {
		return "layout(" + format + ") uniform image2D " + name + ";\n"
				+ "void main() { imageStore(" + name + ", ivec2(0), vec4(0.25)); }\n";
	}

	private static void expect(String source, PackDirectives directives, String format) {
		if (!ComputeImageFormats.normalize(source, directives).contains("layout(" + format + ")")) {
			throw new AssertionError("the store format was not converted to " + format);
		}
	}

	private static void unchanged(String source, PackDirectives directives) {
		if (!ComputeImageFormats.normalize(source, directives).equals(source)) {
			throw new AssertionError("an unrelated image declaration changed: " + source);
		}
	}
}
