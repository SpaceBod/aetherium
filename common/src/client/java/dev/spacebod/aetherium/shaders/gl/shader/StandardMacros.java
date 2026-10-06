package dev.spacebod.aetherium.shaders.gl.shader;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import dev.spacebod.aetherium.client.gpu.StorageFeatures;
import dev.spacebod.aetherium.shaders.compat.lod.LodCompat;
import dev.spacebod.aetherium.shaders.gl.ShaderLimits;
import dev.spacebod.aetherium.shaders.helpers.StringPair;
import dev.spacebod.aetherium.shaders.pbr.PbrTextures;
import dev.spacebod.aetherium.shaders.pipeline.WorldRenderingPhase;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The macros every pack program is preprocessed with: game and device, extensions, materials, render stages.
 * <p>
 * These are the only names a pack's conditionals see: every {@code #if} is resolved here, before the compiler gets
 * the text (which then holds no conditionals). The compiler's own predefined extension macros ({@code GL_AMD_*},
 * {@code GL_NV_*}, {@code GL_KHR_shader_subgroup_*} ..., defined whether the device has the extension or not) therefore
 * never pick a pack's branch: a pack testing {@code #ifdef GL_AMD_shader_trinary_minmax} takes its fallback on every
 * device, and a module never asks for a vendor instruction the driver lacks.
 */
public class StandardMacros {
	/** Reported to packs as MC_GL_VERSION / MC_GLSL_VERSION: GLSL 4.60 is what the transformer + shaderc path accepts. */
	private static final String GL_VERSION = "460";
	/** Pack-facing compatibility value (packs compare it to pick feature levels). */
	private static final String COMPAT_VERSION = "11104";
	/**
	 * GL extensions the Vulkan path always supports, reported as MC_&lt;ext&gt;. Compute, storage buffers and image
	 * load/store follow the device ({@link #STORAGE_EXTENSIONS}).
	 */
	private static final List<String> EXTENSIONS = List.of(
			"GL_ARB_shader_texture_lod", "GL_EXT_gpu_shader4", "GL_ARB_gpu_shader5", "GL_ARB_texture_gather",
			"GL_ARB_texture_rectangle", "GL_ARB_explicit_attrib_location", "GL_ARB_separate_shader_objects",
			"GL_ARB_shading_language_packing", "GL_ARB_texture_query_lod", "GL_ARB_derivative_control");
	/** Reported when the device gives packs storage images and buffers (and with them compute). */
	private static final List<String> STORAGE_EXTENSIONS = List.of(
			"GL_ARB_compute_shader", "GL_ARB_shader_image_load_store", "GL_ARB_shader_storage_buffer_object", "GL_ARB_shader_image_size",
			"GL_ARB_shading_language_420pack", "GL_ARB_enhanced_layouts");
	private static final float HAND_DEPTH = 0.125F;

	private static void define(List<StringPair> defines, String key) {
		defines.add(new StringPair(key, ""));
	}

	private static void define(List<StringPair> defines, String key, String value) {
		defines.add(new StringPair(key, value));
	}

	public static ImmutableList<StringPair> createStandardEnvironmentDefines() {
		ArrayList<StringPair> standardDefines = new ArrayList<>();
		define(standardDefines, "MC_VERSION", getMcVersion());
		// Without a running game (the compile harness): vanilla's default mipmap level and an NVIDIA device.
		Minecraft mc = Minecraft.getInstance();
		define(standardDefines, "MC_MIPMAP_LEVEL", String.valueOf(mc == null ? 4 : mc.options.mipmapLevels().get()));
		define(standardDefines, PackFacingNames.VERSION_MACRO, COMPAT_VERSION);
		define(standardDefines, "MC_GL_VERSION", GL_VERSION);
		define(standardDefines, "MC_GLSL_VERSION", GL_VERSION);
		String os = getOsString();
		if (os != null) {
			define(standardDefines, os);
		}
		DeviceInfo device = mc == null ? null : RenderSystem.getDevice().getDeviceInfo();
		define(standardDefines, getVendor(device == null ? "NVIDIA" : device.vendorName()));
		define(standardDefines, getRenderer(device == null ? "GeForce" : device.name()));
		define(standardDefines, PackFacingNames.ENGINE_MACRO);
		define(standardDefines, PackFacingNames.SEPARATE_ENTITY_DRAWS_MACRO);
		define(standardDefines, "MAX_COLOR_BUFFERS", String.valueOf(ShaderLimits.MAX_COLOR_BUFFERS));
		define(standardDefines, PackFacingNames.TRANSLUCENCY_SORTING_MACRO);
		define(standardDefines, PackFacingNames.TAG_SUPPORT_MACRO, "2");
		for (String extension : EXTENSIONS) {
			define(standardDefines, "MC_" + extension);
		}
		if (StorageFeatures.imageLoadStore()) {
			for (String extension : STORAGE_EXTENSIONS) {
				define(standardDefines, "MC_" + extension);
			}
		}
		define(standardDefines, "MC_NORMAL_MAP");
		define(standardDefines, "MC_SPECULAR_MAP");
		// The resource packs' material format (MC_TEXTURE_FORMAT_LAB_PBR ...); a change reloads the pack.
		var materialFormat = mc == null ? null : PbrTextures.format();
		if (materialFormat != null) {
			materialFormat.defines().forEach(define -> define(standardDefines, define));
		}
		define(standardDefines, "MC_RENDER_QUALITY", "1.0");
		define(standardDefines, "MC_SHADOW_QUALITY", "1.0");
		define(standardDefines, "MC_HAND_DEPTH", Float.toString(HAND_DEPTH));
		// Level-of-detail terrain renders (packs then read dhDepthTex0/1 and draw dh_* programs); the pack is reloaded when
		// this changes.
		if (LodCompat.hasRenderingEnabled() || Boolean.getBoolean("aetherium.shaders.lod")) {
			define(standardDefines, "DISTANT_HORIZONS");
			define(standardDefines, "DISTANT_HORIZONS_TEXTURES");
		}
		// LOD material ids (dhMaterialId in LOD programs), defined whether or not LODs render.
		for (int i = 0; i < LOD_MATERIALS.length; i++) {
			define(standardDefines, "DH_BLOCK_" + LOD_MATERIALS[i], String.valueOf(i));
		}
		getRenderStages().forEach((stage, index) -> define(standardDefines, stage, index));
		return ImmutableList.copyOf(standardDefines);
	}

	/** The LOD mod's block materials, in the index order of its API's material enum. */
	public static final String[] LOD_MATERIALS = {"UNKNOWN", "LEAVES", "STONE", "WOOD", "METAL", "DIRT", "LAVA", "DEEPSLATE", "SNOW", "SAND", "TERRACOTTA", "NETHER_STONE", "WATER", "GRASS", "AIR", "ILLUMINATED"};

	/** Minecraft's version in the pack format's 5/6-digit form (26.3 -> 260300). */
	public static String getMcVersion() {
		String formatted = formatVersionString(SharedConstants.getCurrentVersion().name());
		if (formatted == null) {
			throw new IllegalStateException("Could not parse game version " + SharedConstants.getCurrentVersion().name());
		}
		return formatted;
	}

	public static @Nullable String formatVersionString(String version) {
		String[] splitVersion = version.split("[.\\-]");
		if (splitVersion.length < 2) {
			return null;
		}
		String major = splitVersion[0];
		String minor = splitVersion[1].length() == 1 ? "0" + splitVersion[1] : splitVersion[1];
		String bugFix = splitVersion.length < 3 || !splitVersion[2].matches("\\d+") ? "00" : splitVersion[2];
		if (bugFix.length() == 1) {
			bugFix = "0" + bugFix;
		}
		return major + minor + bugFix;
	}

	/**
	 * The OS macro, or null on macOS. Packs read {@code MC_OS_MAC} as Apple's OpenGL driver and switch off what that
	 * driver lacks (compute and image stores, coloured lighting, per-buffer blending, samplers past eight). On a Mac the
	 * engine draws through Vulkan on Metal, which has all of them, so no OS macro is defined there.
	 */
	public static @Nullable String getOsString() {
		return switch (Util.getPlatform()) {
			case OSX -> null;
			case LINUX -> "MC_OS_LINUX";
			case WINDOWS -> "MC_OS_WINDOWS";
			default -> "MC_OS_UNKNOWN";
		};
	}

	public static String getVendor(String vendorName) {
		String vendor = vendorName.toLowerCase(Locale.ROOT);
		if (vendor.startsWith("ati")) {
			return "MC_GL_VENDOR_ATI";
		} else if (vendor.startsWith("intel")) {
			return "MC_GL_VENDOR_INTEL";
		} else if (vendor.startsWith("nvidia")) {
			return "MC_GL_VENDOR_NVIDIA";
		} else if (vendor.startsWith("amd")) {
			return "MC_GL_VENDOR_AMD";
		} else if (vendor.startsWith("x.org")) {
			return "MC_GL_VENDOR_XORG";
		}
		return "MC_GL_VENDOR_OTHER";
	}

	public static String getRenderer(String deviceName) {
		String renderer = deviceName.toLowerCase(Locale.ROOT);
		if (renderer.startsWith("amd") || renderer.startsWith("ati") || renderer.startsWith("radeon")) {
			return "MC_GL_RENDERER_RADEON";
		} else if (renderer.startsWith("gallium")) {
			return "MC_GL_RENDERER_GALLIUM";
		} else if (renderer.startsWith("intel")) {
			return "MC_GL_RENDERER_INTEL";
		} else if (renderer.startsWith("geforce") || renderer.startsWith("nvidia")) {
			return "MC_GL_RENDERER_GEFORCE";
		} else if (renderer.startsWith("quadro") || renderer.startsWith("nvs")) {
			return "MC_GL_RENDERER_QUADRO";
		} else if (renderer.startsWith("mesa")) {
			return "MC_GL_RENDERER_MESA";
		} else if (renderer.startsWith("apple")) {
			return "MC_GL_RENDERER_APPLE";
		}
		return "MC_GL_RENDERER_OTHER";
	}

	public static Map<String, String> getRenderStages() {
		Map<String, String> stages = new HashMap<>();
		for (WorldRenderingPhase phase : WorldRenderingPhase.values()) {
			stages.put("MC_RENDER_STAGE_" + phase.name(), String.valueOf(phase.ordinal()));
		}
		return stages;
	}
}
