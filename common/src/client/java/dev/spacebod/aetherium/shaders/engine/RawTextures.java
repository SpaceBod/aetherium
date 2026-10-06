package dev.spacebod.aetherium.shaders.engine;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.texture.PixelFormat;
import dev.spacebod.aetherium.shaders.gl.texture.PixelType;
import dev.spacebod.aetherium.shaders.shaderpack.ShaderPack;
import dev.spacebod.aetherium.shaders.shaderpack.texture.CustomTextureData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;

/**
 * Raw custom textures vanilla's texture API cannot hold: 3D ones, and 1D/2D ones that are not 8-bit unsigned (16-bit,
 * half and full float, signed, integer). They live in the pack's storage set ({@link PackStorage}, set 1) as Vulkan
 * images, sampled under their pack-wide name ({@code customtexN}: the transformer renames the stage's sampler to it,
 * {@link TexturePatching}). The Vulkan format follows the data the pack supplies (components and type), as OpenGL
 * would convert it on upload: three components become four (alpha one), BGR(A) is swapped to RGB(A).
 */
public final class RawTextures {
	/** Set-1 binding of the first raw texture (custom images take 0..63, storage buffers 64..127). */
	public static final int BINDING_BASE = 128;

	/** One raw texture ready for {@link PackStorage}: Vulkan image parameters and the converted texels. */
	public record Texture(String name, int binding, int imageType, int viewType, int width, int height, int depth, int vkFormat,
			byte[] texels, boolean integer, boolean linear, boolean clamp) {
	}

	private RawTextures() {
	}

	/** The pack's raw textures that go to the storage set, with their bindings. */
	public static List<Texture> collect(ShaderPack pack) {
		List<Texture> out = new ArrayList<>();
		Map<String, CustomTextureData> all = new LinkedHashMap<>(pack.getNamedCustomTextureData());
		pack.getCustomTextureDataMap().values().forEach(all::putAll);
		all.forEach((name, data) -> {
			if (data instanceof CustomTextureData.RawData raw && !CustomTextures.vanillaCanHold(raw)) {
				Texture t = convert(name, raw, BINDING_BASE + out.size());
				if (t != null) {
					out.add(t);
				}
			}
		});
		return out;
	}

	/** Names and set-1 bindings, for {@link StorageBindings#configure} (before any program is built). */
	public static Map<String, Integer> bindings(List<Texture> textures) {
		Map<String, Integer> out = new LinkedHashMap<>();
		textures.forEach(t -> out.put(t.name(), t.binding()));
		return out;
	}

	private static @Nullable Texture convert(String name, CustomTextureData.RawData raw, int binding) {
		PixelFormat pixelFormat = raw.getPixelFormat();
		PixelType type = raw.getPixelType();
		int components = switch (pixelFormat) {
			case RED, RED_INTEGER -> 1;
			case RG, RG_INTEGER -> 2;
			case RGB, BGR, RGB_INTEGER, BGR_INTEGER -> 3;
			default -> 4;
		};
		boolean integer = pixelFormat.isInteger();
		boolean swap = pixelFormat == PixelFormat.BGR || pixelFormat == PixelFormat.BGRA || pixelFormat == PixelFormat.BGR_INTEGER
				|| pixelFormat == PixelFormat.BGRA_INTEGER;
		int size = switch (type) {
			case BYTE, UNSIGNED_BYTE -> 1;
			case SHORT, UNSIGNED_SHORT, HALF_FLOAT -> 2;
			case INT, UNSIGNED_INT, FLOAT -> 4;
			default -> 0; // packed types
		};
		int out = components == 3 ? 4 : components;
		int vkFormat = size == 0 ? 0 : vkFormat(type, integer, out);
		if (vkFormat == 0) {
			AetheriumShaders.logger.warn("raw custom texture {} ({} {}) is not supported yet; built-in texture used", name, pixelFormat, type);
			return null;
		}
		int width, height = 1, depth = 1, imageType, viewType;
		if (raw instanceof CustomTextureData.RawData3D r) {
			width = r.getSizeX();
			height = r.getSizeY();
			depth = r.getSizeZ();
			imageType = VK10.VK_IMAGE_TYPE_3D;
			viewType = VK10.VK_IMAGE_VIEW_TYPE_3D;
		} else if (raw instanceof CustomTextureData.RawData2D r) {
			width = r.getSizeX();
			height = r.getSizeY();
			imageType = VK10.VK_IMAGE_TYPE_2D;
			viewType = VK10.VK_IMAGE_VIEW_TYPE_2D;
		} else if (raw instanceof CustomTextureData.RawData1D r) {
			width = r.getSizeX();
			imageType = VK10.VK_IMAGE_TYPE_1D;
			viewType = VK10.VK_IMAGE_VIEW_TYPE_1D;
		} else {
			return null;
		}
		long texels = (long) width * height * depth;
		byte[] content = raw.getContent();
		if (content.length < texels * components * size) {
			AetheriumShaders.logger.warn("raw custom texture {} has {} bytes, {} expected; built-in texture used", name, content.length,
					texels * components * size);
			return null;
		}
		byte[] converted = new byte[(int) (texels * out * size)];
		ByteBuffer src = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
		ByteBuffer dst = ByteBuffer.wrap(converted).order(ByteOrder.LITTLE_ENDIAN);
		byte[] one = one(type, integer);
		for (long i = 0; i < texels; i++) {
			int base = (int) (i * components * size);
			for (int c = 0; c < out; c++) {
				int from = swap && c < 3 ? 2 - c : c;
				if (from < components) {
					dst.put(src.array(), base + from * size, size);
				} else {
					dst.put(one, 0, size);
				}
			}
		}
		var filtering = raw.getFilteringData();
		return new Texture(name, binding, imageType, viewType, width, height, depth, vkFormat, converted, integer,
				!integer && filtering.shouldBlur(), filtering.shouldClamp());
	}

	/** The value "one" an absent alpha takes, in the component's encoding (little-endian). */
	private static byte[] one(PixelType type, boolean integer) {
		ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		switch (type) {
			case UNSIGNED_BYTE -> b.put(integer ? (byte) 1 : (byte) 0xFF);
			case BYTE -> b.put(integer ? (byte) 1 : (byte) 0x7F);
			case UNSIGNED_SHORT -> b.putShort(integer ? (short) 1 : (short) 0xFFFF);
			case SHORT -> b.putShort(integer ? (short) 1 : (short) 0x7FFF);
			case HALF_FLOAT -> b.putShort((short) 0x3C00);
			case FLOAT -> b.putFloat(1.0F);
			default -> b.putInt(1);
		}
		return b.array();
	}

	private static int vkFormat(PixelType type, boolean integer, int components) {
		int[] formats = switch (type) {
			case UNSIGNED_BYTE -> integer ? new int[]{VK10.VK_FORMAT_R8_UINT, VK10.VK_FORMAT_R8G8_UINT, VK10.VK_FORMAT_R8G8B8A8_UINT}
					: new int[]{VK10.VK_FORMAT_R8_UNORM, VK10.VK_FORMAT_R8G8_UNORM, VK10.VK_FORMAT_R8G8B8A8_UNORM};
			case BYTE -> integer ? new int[]{VK10.VK_FORMAT_R8_SINT, VK10.VK_FORMAT_R8G8_SINT, VK10.VK_FORMAT_R8G8B8A8_SINT}
					: new int[]{VK10.VK_FORMAT_R8_SNORM, VK10.VK_FORMAT_R8G8_SNORM, VK10.VK_FORMAT_R8G8B8A8_SNORM};
			case UNSIGNED_SHORT -> integer ? new int[]{VK10.VK_FORMAT_R16_UINT, VK10.VK_FORMAT_R16G16_UINT, VK10.VK_FORMAT_R16G16B16A16_UINT}
					: new int[]{VK10.VK_FORMAT_R16_UNORM, VK10.VK_FORMAT_R16G16_UNORM, VK10.VK_FORMAT_R16G16B16A16_UNORM};
			case SHORT -> integer ? new int[]{VK10.VK_FORMAT_R16_SINT, VK10.VK_FORMAT_R16G16_SINT, VK10.VK_FORMAT_R16G16B16A16_SINT}
					: new int[]{VK10.VK_FORMAT_R16_SNORM, VK10.VK_FORMAT_R16G16_SNORM, VK10.VK_FORMAT_R16G16B16A16_SNORM};
			case HALF_FLOAT -> new int[]{VK10.VK_FORMAT_R16_SFLOAT, VK10.VK_FORMAT_R16G16_SFLOAT, VK10.VK_FORMAT_R16G16B16A16_SFLOAT};
			case FLOAT -> new int[]{VK10.VK_FORMAT_R32_SFLOAT, VK10.VK_FORMAT_R32G32_SFLOAT, VK10.VK_FORMAT_R32G32B32A32_SFLOAT};
			case UNSIGNED_INT -> new int[]{VK10.VK_FORMAT_R32_UINT, VK10.VK_FORMAT_R32G32_UINT, VK10.VK_FORMAT_R32G32B32A32_UINT};
			case INT -> new int[]{VK10.VK_FORMAT_R32_SINT, VK10.VK_FORMAT_R32G32_SINT, VK10.VK_FORMAT_R32G32B32A32_SINT};
			default -> null;
		};
		return formats == null ? 0 : formats[components == 1 ? 0 : components == 2 ? 1 : 2];
	}
}
