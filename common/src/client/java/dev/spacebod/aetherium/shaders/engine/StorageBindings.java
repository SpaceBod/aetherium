package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;
import dev.spacebod.aetherium.client.gpu.StorageFeatures;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.LoweringParameters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Where a pack's custom images and storage buffers live in its programs: one descriptor set (set 1) shared by every
 * pack program, graphics and compute alike, next to vanilla's per-pipeline set 0. Fixed numbering: custom image i is
 * binding 2i as a storage image (its {@code image.} name) and 2i+1 as a sampled texture (its sampler name); storage
 * buffer N ({@code bufferObject.N}) is binding {@link #BUFFER_BASE} + N.
 * <p>
 * The Vulkan lowering rewrites a program's declarations to those bindings ({@link #snapshot}), giving storage images
 * the format the pack declared in shaders.properties when the GLSL leaves it out (OpenGL allowed that for write-only
 * images; Vulkan needs it). Configured when a pack loads, before its programs are built.
 */
public final class StorageBindings {
	public static final int SET = 1;
	public static final int BUFFER_BASE = 64;
	/** Set-1 bindings of the colour targets bound as images in graphics programs ({@code colorimgN}: base + N). */
	public static final int TARGET_IMAGE_BASE = 256;
	/** Likewise for {@code shadowcolorimgN}. */
	public static final int SHADOW_TARGET_IMAGE_BASE = 288;
	/** A custom image: its two names, binding pair and format. */
	public record Image(String name, @Nullable String samplerName, int index, GpuFormat format) {
		public int storageBinding() {
			return 2 * index;
		}

		public int samplerBinding() {
			return 2 * index + 1;
		}
	}

	private static volatile Map<String, Image> byImageName = Map.of();
	private static volatile Map<String, Image> bySamplerName = Map.of();
	private static volatile boolean buffers;
	/** Raw custom textures in the set ({@link RawTextures}): sampler name -> binding. */
	private static volatile Map<String, Integer> rawSamplers = Map.of();
	/** Graphics programs bind targets as images through the set ({@link TargetStorage}). */
	private static volatile boolean targetImages;
	/** Custom images accessed in another format than their own ({@link ImageViews}). */
	private static volatile List<ImageViews.View> views = List.of();

	private StorageBindings() {
	}

	/** The active pack's images (in shaders.properties order) and whether it has storage buffers; empty = off. */
	public static void configure(Map<String, Image> images, boolean hasBuffers) {
		configure(images, hasBuffers, Map.of());
	}

	/** {@link #configure(Map, boolean)}, plus the raw custom textures the set holds (name -> binding). */
	public static void configure(Map<String, Image> images, boolean hasBuffers, Map<String, Integer> raw) {
		configure(images, hasBuffers, raw, false);
	}

	/** {@link #configure(Map, boolean, Map)}, and whether graphics programs bind targets as images in the set. */
	public static void configure(Map<String, Image> images, boolean hasBuffers, Map<String, Integer> raw, boolean bindsTargetImages) {
		targetImages = bindsTargetImages;
		views = List.of();
		rawSamplers = Map.copyOf(raw);
		Map<String, Image> byName = new LinkedHashMap<>();
		Map<String, Image> bySampler = new LinkedHashMap<>();
		images.values().forEach(i -> {
			byName.put(i.name(), i);
			if (i.samplerName() != null) {
				bySampler.put(i.samplerName(), i);
			}
		});
		byImageName = Map.copyOf(byName);
		bySamplerName = Map.copyOf(bySampler);
		buffers = hasBuffers;
	}

	/** After {@link #configure}: the views programs access custom images through ({@link ImageViews#scan}). */
	public static void configureViews(List<ImageViews.View> imageViews) {
		views = List.copyOf(imageViews);
	}

	/** The configured views, in binding order. */
	public static List<ImageViews.View> views() {
		return views;
	}

	public static void reset() {
		configure(Map.of(), false);
	}

	public static boolean active() {
		return !byImageName.isEmpty() || buffers || !rawSamplers.isEmpty() || targetImages;
	}

	/** The configuration as lowering parameters (a value: part of the transform cache key). */
	public static LoweringParameters.Storage snapshot() {
		Map<String, LoweringParameters.Image> images = new LinkedHashMap<>();
		Map<String, Integer> samplers = new LinkedHashMap<>();
		Map<String, Map<String, Integer>> samplerViews = new LinkedHashMap<>();
		List<ImageViews.View> imageViews = views;
		byImageName.values().forEach(i -> {
			Map<String, LoweringParameters.View> storageViews = new LinkedHashMap<>();
			for (ImageViews.View v : imageViews) {
				if (!v.image().equals(i.name())) {
					continue;
				}
				for (String key : v.keys()) {
					if (v.storage()) {
						storageViews.put(key, new LoweringParameters.View(v.binding(), v.glslFormat()));
					} else if (i.samplerName() != null) {
						samplerViews.computeIfAbsent(i.samplerName(), n -> new LinkedHashMap<>()).put(key, v.binding());
					}
				}
			}
			images.put(i.name(), new LoweringParameters.Image(i.storageBinding(), glslFormat(i.format()), storageViews));
			if (i.samplerName() != null) {
				samplers.put(i.samplerName(), i.samplerBinding());
			}
		});
		samplers.putAll(rawSamplers);
		return new LoweringParameters.Storage(images, samplers, buffers, BUFFER_BASE,
				targetImages, TARGET_IMAGE_BASE, SHADOW_TARGET_IMAGE_BASE, samplerViews,
				StorageFeatures.vertexStores(), StorageFeatures.fragmentStores());
	}

	/** The GLSL image format qualifier for {@code format} ({@code RGBA16_FLOAT} -> {@code rgba16f}). */
	static String glslFormat(GpuFormat format) {
		String name = format.name();
		if (name.equals("RG11B10_FLOAT")) {
			return "r11f_g11f_b10f";
		}
		int underscore = name.lastIndexOf('_');
		String channels = name.substring(0, underscore).toLowerCase(Locale.ROOT).replace("rgb10a2", "rgb10_a2");
		return channels + switch (name.substring(underscore + 1)) {
			case "UINT" -> "ui";
			case "SINT" -> "i";
			case "FLOAT" -> "f";
			case "SNORM" -> "_snorm";
			default -> "";
		};
	}
}
