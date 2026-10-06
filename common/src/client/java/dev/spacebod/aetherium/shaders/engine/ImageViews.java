package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import dev.spacebod.aetherium.client.gpu.VulkanAccess;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFormatProperties;

/**
 * Custom images a pack's programs access in another format than shaders.properties gives them: an {@code R32F} image
 * declared {@code layout(r32ui) uniform uimage2D} for integer atomics, an {@code RGBA8} image written as one packed
 * {@code r32ui} word, an image read through a {@code usampler2D}. Each such access gets a view of the same image in the
 * accessed format (the image is created with a mutable format; nothing is copied), at a set-1 binding of its own from
 * {@link #BINDING_BASE} on. Only formats with the same texel size can view one image; any other access is logged and
 * left as the pack wrote it.
 * <p>
 * Found at pack load by scanning every program's declarations (before any program is converted: the set layout and the
 * conversion both need the bindings); the lowering then rebinds each declaration to its view ({@link StorageBindings}).
 */
public final class ImageViews {
	/** First set-1 binding of a view (after the targets bound as images). */
	public static final int BINDING_BASE = 320;
	/** Lowering key of a declaration without a format qualifier whose type is of another kind than the image. */
	public static final String UINT = "kind:uint";
	public static final String INT = "kind:int";
	public static final String FLOAT = "kind:float";

	/**
	 * A view of custom image {@code image} (its index in the pack's image list) in {@code format} at set-1 {@code binding}:
	 * a storage image, or a sampled one (integer formats are sampled nearest). {@code keys}: the declarations it serves
	 * (a format qualifier such as {@code r32ui}, or one of {@link #UINT}, {@link #INT}, {@link #FLOAT}).
	 */
	public record View(String image, int index, GpuFormat format, boolean storage, Set<String> keys, int binding) {
		public View {
			keys = Set.copyOf(keys);
		}

		public String glslFormat() {
			return StorageBindings.glslFormat(format);
		}
	}

	/** GLSL image format qualifier -> format, for every colour format a storage image can be declared with. */
	private static final Map<String, GpuFormat> QUALIFIERS = new HashMap<>();

	static {
		for (GpuFormat format : GpuFormat.values()) {
			if (format.hasColorAspect() && format.componentCount() != 3) {
				QUALIFIERS.put(StorageBindings.glslFormat(format), format);
			}
		}
	}

	private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
	private static final Pattern UNIFORM = Pattern.compile("\\buniform\\b");
	private static final Pattern OPAQUE = Pattern.compile("\\b([iu]?)(image|sampler)(1D|2D|3D|2DRect|1DArray|2DArray|Cube|CubeArray|Buffer|2DMS|2DMSArray)\\b");
	private static final Pattern LAYOUT = Pattern.compile("\\blayout\\s*\\(([^)]*)\\)");
	private static final Pattern NAME = Pattern.compile("^\\s*(\\w+)");

	private ImageViews() {
	}

	/** The format a GLSL image format qualifier names, or null when it names none. */
	public static @Nullable GpuFormat format(String qualifier) {
		return QUALIFIERS.get(qualifier.toLowerCase(Locale.ROOT));
	}

	/** {@link #UINT}, {@link #INT} or {@link #FLOAT}: the kind of value a format reads as. */
	public static String kind(GpuFormat format) {
		return switch (format.componentType()) {
			case UINT_8, UINT_16, UINT_32 -> UINT;
			case SINT_8, SINT_16, SINT_32 -> INT;
			default -> format == GpuFormat.RGB10A2_UINT ? UINT : FLOAT;
		};
	}

	/** The kind a GLSL image or sampler type prefix ({@code u}, {@code i} or none) reads. */
	public static String kind(String typePrefix) {
		return switch (typePrefix) {
			case "u" -> UINT;
			case "i" -> INT;
			default -> FLOAT;
		};
	}

	/**
	 * {@code format} with the same channel widths read as {@code kind} ({@code RGBA8_UNORM} as {@link #UINT} is
	 * {@code RGBA8_UINT}); a packed 32-bit format becomes one 32-bit channel when the kind has no packed variant. Null when
	 * none fits.
	 */
	static @Nullable GpuFormat retype(GpuFormat format, String kind) {
		if (format == GpuFormat.RGB10A2_UNORM || format == GpuFormat.RGB10A2_UINT || format == GpuFormat.RG11B10_FLOAT) {
			return switch (kind) {
				case UINT -> format == GpuFormat.RG11B10_FLOAT ? GpuFormat.R32_UINT : GpuFormat.RGB10A2_UINT;
				case INT -> GpuFormat.R32_SINT;
				default -> format == GpuFormat.RGB10A2_UINT ? GpuFormat.RGB10A2_UNORM : format;
			};
		}
		int width = format.componentType().byteSize();
		boolean signed = switch (format.componentType()) {
			case SNORM_8, SNORM_16, SINT_8, SINT_16, SINT_32 -> true;
			default -> false;
		};
		GpuFormat.ComponentType type = switch (kind) {
			case UINT -> width == 1 ? GpuFormat.ComponentType.UINT_8 : width == 2 ? GpuFormat.ComponentType.UINT_16 : GpuFormat.ComponentType.UINT_32;
			case INT -> width == 1 ? GpuFormat.ComponentType.SINT_8 : width == 2 ? GpuFormat.ComponentType.SINT_16 : GpuFormat.ComponentType.SINT_32;
			default -> width == 1 ? (signed ? GpuFormat.ComponentType.SNORM_8 : GpuFormat.ComponentType.UNORM_8)
					: width == 2 ? GpuFormat.ComponentType.FLOAT_16 : GpuFormat.ComponentType.FLOAT_32;
		};
		for (GpuFormat candidate : GpuFormat.values()) {
			if (candidate.componentType() == type && candidate.componentCount() == format.componentCount() && candidate.hasColorAspect()) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * The views the programs in {@code texts} need of the pack's {@code images} (by image name), bindings assigned from
	 * {@link #BINDING_BASE} on. A view the device cannot create (format not storable or sampleable) is logged and left out.
	 */
	public static List<View> scan(Map<String, StorageBindings.Image> images, List<String> texts) {
		Map<String, StorageBindings.Image> bySampler = new HashMap<>();
		images.values().forEach(i -> {
			if (i.samplerName() != null) {
				bySampler.put(i.samplerName(), i);
			}
		});
		// (image, format, storage) -> keys, in first-seen order.
		record Wanted(StorageBindings.Image image, GpuFormat format, boolean storage) {
		}
		Map<Wanted, Set<String>> wanted = new LinkedHashMap<>();
		Set<String> refused = new LinkedHashSet<>();
		for (String text : texts) {
			if (text.indexOf("image") < 0 && text.indexOf("sampler") < 0) {
				continue;
			}
			for (String statement : COMMENTS.matcher(text).replaceAll(" ").split("[;{}]")) {
				if (!UNIFORM.matcher(statement).find()) {
					continue;
				}
				Matcher type = OPAQUE.matcher(statement);
				if (!type.find()) {
					continue;
				}
				boolean storage = type.group(2).equals("image");
				String kind = kind(type.group(1));
				String declared = storage ? declaredFormat(statement) : null;
				for (String declarator : statement.substring(type.end()).split(",")) {
					Matcher name = NAME.matcher(declarator);
					if (!name.find()) {
						continue;
					}
					StorageBindings.Image image = (storage ? images : bySampler).get(name.group(1));
					if (image == null) {
						continue;
					}
					GpuFormat base = image.format();
					GpuFormat view;
					String key;
					if (storage && declared != null) {
						if (declared.equals(StorageBindings.glslFormat(base))) {
							continue;
						}
						view = format(declared);
						key = declared;
					} else if (kind.equals(kind(base)) || !storage && kind.equals(FLOAT)) {
						// Same kind as the image (a float sampler of an integer image is left as written).
						continue;
					} else {
						view = retype(base, kind);
						key = kind;
					}
					if (view == null || view.blockSize() != base.blockSize()) {
						refused.add(image.name() + " (" + base + ") accessed as " + (view == null ? key : view));
						continue;
					}
					wanted.computeIfAbsent(new Wanted(image, view, storage), w -> new LinkedHashSet<>()).add(key);
				}
			}
		}
		for (String r : refused) {
			AetheriumShaders.logger.warn("custom image {}: texel sizes differ, no view; left as the pack declares it", r);
		}
		List<View> out = new ArrayList<>();
		for (Map.Entry<Wanted, Set<String>> e : wanted.entrySet()) {
			Wanted w = e.getKey();
			if (!supported(w.format(), w.storage())) {
				AetheriumShaders.logger.warn("custom image {} ({}): the graphics card cannot {} it as {}; left as the pack declares it",
						w.image().name(), w.image().format(), w.storage() ? "store to" : "sample", w.format());
				continue;
			}
			out.add(new View(w.image().name(), w.image().index(), w.format(), w.storage(), e.getValue(), BINDING_BASE + out.size()));
		}
		if (!out.isEmpty()) {
			AetheriumShaders.logger.info("custom image views in other formats: {}", out.stream()
					.map(v -> v.image() + " as " + v.format() + (v.storage() ? "" : " (sampled)")).toList());
		}
		return out;
	}

	/** The format qualifier in a declaration's {@code layout(...)}, lower case, or null. */
	private static @Nullable String declaredFormat(String statement) {
		Matcher layout = LAYOUT.matcher(statement);
		while (layout.find()) {
			for (String part : layout.group(1).split(",")) {
				String p = part.trim().toLowerCase(Locale.ROOT);
				if (!p.contains("=") && QUALIFIERS.containsKey(p)) {
					return p;
				}
			}
		}
		return null;
	}

	/** Whether the device can store to ({@code storage}) or sample {@code format} in optimal tiling; true without a device. */
	private static boolean supported(GpuFormat format, boolean storage) {
		var device = RenderSystem.tryGetDevice() == null ? null : VulkanAccess.device();
		if (device == null) {
			return true;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkFormatProperties properties = VkFormatProperties.calloc(stack);
			VK10.vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), VulkanConst.toVk(format), properties);
			int need = storage ? VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT : VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
			return (properties.optimalTilingFeatures() & need) != 0;
		}
	}
}
