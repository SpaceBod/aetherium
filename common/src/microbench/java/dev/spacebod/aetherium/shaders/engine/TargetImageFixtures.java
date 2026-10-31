package dev.spacebod.aetherium.shaders.engine;

import com.google.common.collect.ImmutableList;
import com.mojang.renderpearl.api.GpuFormat;
import dev.spacebod.aetherium.shaders.shaderpack.DimensionId;
import dev.spacebod.aetherium.shaders.shaderpack.ShaderPack;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.gl.texture.InternalTextureFormat;
import dev.spacebod.aetherium.shaders.gl.texture.PixelFormat;
import dev.spacebod.aetherium.shaders.gl.texture.PixelType;
import dev.spacebod.aetherium.shaders.shaderpack.texture.CustomTextureData;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureFilteringData;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.lwjgl.vulkan.VK10;

public final class TargetImageFixtures {
	private TargetImageFixtures() {
	}

	public static void main(String[] args) {
		if (args.length > 0 && !Set.of("flags", "samplers").contains(args[0])) {
			throw new IllegalArgumentException("Unknown target image check: " + args[0]);
		}
		if (args.length == 0 || args[0].equals("flags")) {
			creationFlags();
			declarationFormats();
			targetRequirements();
		}
		if (args.length == 0 || args[0].equals("samplers")) {
			customImageViews();
		}
	}

	private static void creationFlags() {
		int original = VK10.VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT;
		TargetStorage.configure(true);
		try {
			if (TargetStorage.imageFlags(original) != original) {
				throw new AssertionError("non-target creation flags changed");
			}
			int target = TargetStorage.create(() -> TargetStorage.imageFlags(original));
			if (target != original) {
				throw new AssertionError("a target without a format alias requested mutable format");
			}
			try {
				TargetStorage.create(() -> { throw new IllegalStateException("fixture"); });
			} catch (IllegalStateException expected) {
				if (!expected.getMessage().equals("fixture")) {
					throw expected;
				}
			}
			if (TargetStorage.imageFlags(original) != original) {
				throw new AssertionError("target creation leaked its flag scope");
			}
		} finally {
			TargetStorage.configure(false);
		}
		if (TargetStorage.create(() -> TargetStorage.imageFlags(original)) != original) {
			throw new AssertionError("an inactive target-storage path requested mutable images");
		}
		System.out.println("target images: unrelated targets retain their creation flags");
	}

	private static void declarationFormats() {
		Map<String, GpuFormat> formats = TargetImageViews.formats("""
				// layout(r32ui) uniform uimage2D colorimg0;
				/* layout(r32ui) uniform uimage2D shadowcolorimg0; */
				layout(binding = 3) layout(rgba8) uniform writeonly image2D colorimg9, colorimg12;
				layout(r32ui) coherent uniform uimage2D shadowcolorimg1;
				layout(r32ui) uniform uimage2D customImage;
				uniform image2D colorimg2;
				layout(rgba8) image2D colorimg3;
				""");
		if (!formats.equals(Map.of("colorimg9", GpuFormat.RGBA8_UNORM, "colorimg12", GpuFormat.RGBA8_UNORM,
				"shadowcolorimg1", GpuFormat.R32_UINT))) {
			throw new AssertionError("target declaration formats differ: " + formats);
		}
		System.out.println("target images: declaration formats retain qualifiers and multiple names, excluding comments and custom images");
	}

	/** Load real preprocessed sources so setup, suffix variants and every compute stage affect allocation. */
	private static void targetRequirements() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		Map<String, String> files = Map.ofEntries(
				Map.entry("final.vsh", "#version 430\nvoid main() { gl_Position = vec4(0); }\n"),
				Map.entry("final.fsh", "#version 430\nconst int colortex9Format = R32UI;\nconst int colortex12Format = R32UI;\n"
						+ "const int shadowcolor1Format = R32UI;\nvoid main() {}\n"),
				Map.entry("gbuffers_terrain.vsh", "#version 430\nvoid main() { gl_Position = vec4(0); }\n"),
				Map.entry("gbuffers_terrain.fsh", "#version 430\nlayout(r32ui) uniform uimage2D colorimg10;\nvoid main() {}\n"),
				Map.entry("setup.csh", compute("layout(rgba8) uniform image2D colorimg9, colorimg12;")),
				Map.entry("setup_a.csh", compute("layout(r32ui) uniform uimage2D colorimg13;")),
				Map.entry("shadow.csh", compute("layout(r32ui) uniform uimage2D colorimg2;")),
				Map.entry("final.csh", compute("layout(r32ui) uniform uimage2D colorimg3;")),
				Map.entry("begin3_a.csh", compute("layout(r32ui) uniform uimage2D colorimg4;")),
				Map.entry("prepare4_a.csh", compute("layout(r32ui) uniform uimage2D colorimg5;")),
				Map.entry("deferred5_a.csh", compute("layout(r32ui) uniform uimage2D colorimg6;")),
				Map.entry("composite6_a.csh", compute("layout(r32ui) uniform uimage2D colorimg7;\n"
						+ "layout(rgba8) uniform image2D colorimg0, colorimg1, shadowcolorimg0;\n"
						+ "layout(rgba16f) uniform image2D colorimg8;\nuniform image2D colorimg11;\n"
						+ "#if 0\nlayout(r32ui) uniform uimage2D colorimg14;\n#endif\n")),
				Map.entry("shadowcomp7_a.csh", compute("layout(rgba8) uniform image2D shadowcolorimg1;")));
		try {
			var directory = Files.createTempDirectory("aetherium-target-images-");
			try {
				for (var file : files.entrySet()) {
					Files.writeString(directory.resolve(file.getKey()), file.getValue());
				}
				ProgramSet programs = new ShaderPack(directory, ImmutableList.of(), false).getProgramSet(DimensionId.OVERWORLD);
				TargetStorage.configure(true, programs);
				Set<Integer> mutable = Set.of(2, 3, 4, 5, 6, 7, 9, 12, 13);
				for (int allocation = 0; allocation < 2; allocation++) {
					for (int index = 0; index < 32; index++) {
						assertFlags(false, index, mutable.contains(index));
					}
					for (int index = 0; index < 8; index++) {
						assertFlags(true, index, index == 1);
					}
				}
				TargetStorage.create(false, 9, () -> {
					TargetStorage.create(false, 0, () -> {
						if (TargetStorage.imageFlags(0) != 0) {
							throw new AssertionError("a nested same-format allocation inherited mutable format");
						}
						return null;
					});
					if (TargetStorage.imageFlags(0) != VK10.VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT) {
						throw new AssertionError("nested allocation lost the outer target's format requirements");
					}
					return null;
				});
				try {
					TargetStorage.create(false, 9, () -> { throw new IllegalStateException("fixture"); });
				} catch (IllegalStateException expected) {
					if (!expected.getMessage().equals("fixture")) {
						throw expected;
					}
				}
				if (TargetStorage.imageFlags(0) != 0) {
					throw new AssertionError("a failed allocation leaked mutable format");
				}
				TargetStorage.configure(false, programs);
				assertFlags(false, 9, false);
				TargetStorage.configure(true, programs);
				assertFlags(false, 9, true);
				for (String file : files.keySet()) {
					if (file.endsWith(".csh")) {
						Files.writeString(directory.resolve(file), compute(""));
					}
				}
				ProgramSet reloaded = new ShaderPack(directory, ImmutableList.of(), false).getProgramSet(DimensionId.OVERWORLD);
				TargetStorage.configure(true, reloaded);
				assertFlags(false, 9, false);
				assertFlags(true, 1, false);
			} finally {
				TargetStorage.configure(false);
				for (String file : files.keySet()) {
					Files.deleteIfExists(directory.resolve(file));
				}
				Files.delete(directory);
			}
		} catch (IOException e) {
			throw new AssertionError("cannot prepare target image fixture", e);
		}
		System.out.println("target images: selective colour/shadow flags across all compute stages, both allocations, disabled paths, nested scopes and reloads pass");
	}

	private static String compute(String declarations) {
		return "#version 430\nlayout(local_size_x = 1) in;\n" + declarations + "\nvoid main() {}\n";
	}

	private static void assertFlags(boolean shadow, int index, boolean mutable) {
		int original = VK10.VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT;
		int flags = TargetStorage.create(shadow, index, () -> TargetStorage.imageFlags(original));
		int expected = mutable ? original | VK10.VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT : original;
		if (flags != expected) {
			throw new AssertionError((shadow ? "shadowcolor" : "colortex") + index + " flags: " + flags + ", expected " + expected);
		}
	}

	private static void customImageViews() {
		Map<String, StorageBindings.Image> sampled = Map.of(
				"depth", new StorageBindings.Image("depth", "depthSampler", 0, GpuFormat.R32_UINT),
				"color", new StorageBindings.Image("color", "colorSampler", 1, GpuFormat.RGBA8_UINT));
		if (!ImageViews.scan(sampled, List.of("uniform sampler2D depthSampler; uniform sampler2D colorSampler;")).isEmpty()) {
			throw new AssertionError("float samplers of integer images were rebound to reinterpreted views");
		}
		List<ImageViews.View> integerViews = ImageViews.scan(
				Map.of("lighting", new StorageBindings.Image("lighting", "lightingSampler", 0, GpuFormat.R32_FLOAT)),
				List.of("layout(r32ui) uniform uimage2D lighting; uniform usampler2D lightingSampler;"));
		if (integerViews.size() != 2 || integerViews.stream().anyMatch(view -> view.format() != GpuFormat.R32_UINT)) {
			throw new AssertionError("integer storage or sampler aliases of a float image were lost");
		}
		Map<String, StorageBindings.Image> images = Map.of("grid", new StorageBindings.Image("grid", null, 0, GpuFormat.R32_UINT));
		List<ImageViews.View> aliases = ImageViews.scan(images, List.of("layout(rgba8) uniform image2D grid;"));
		if (aliases.size() != 1 || aliases.getFirst().format() != GpuFormat.RGBA8_UNORM || !aliases.getFirst().storage()) {
			throw new AssertionError("a compatible storage format alias was lost");
		}
		if (!ImageViews.scan(images, List.of("layout(rgba16f) uniform image2D grid;")).isEmpty()) {
			throw new AssertionError("an alias with a different texel size was accepted");
		}
		TextureFilteringData filtering = new TextureFilteringData(false, false);
		var one = new CustomTextureData.RawData1D(new byte[4], filtering, InternalTextureFormat.RGBA8, PixelFormat.RGBA, PixelType.UNSIGNED_BYTE, 1);
		var two = new CustomTextureData.RawData2D(new byte[4], filtering, InternalTextureFormat.RGBA8, PixelFormat.RGBA, PixelType.UNSIGNED_BYTE, 1, 1);
		if (CustomTextures.vanillaCanHold(one) || !CustomTextures.vanillaCanHold(two)) {
			throw new AssertionError("raw texture dimensionality was lost");
		}
		System.out.println("target images: float samplers unchanged, integer/storage aliases and raw texture dimensionality pass");
	}
}
