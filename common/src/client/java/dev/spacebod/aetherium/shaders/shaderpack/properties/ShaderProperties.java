package dev.spacebod.aetherium.shaders.shaderpack.properties;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.ShaderRenderSystem;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTest;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTestFunction;
import dev.spacebod.aetherium.shaders.gl.blending.BlendMode;
import dev.spacebod.aetherium.shaders.gl.blending.BlendModeFunction;
import dev.spacebod.aetherium.shaders.gl.blending.BlendModeOverride;
import dev.spacebod.aetherium.shaders.gl.blending.BufferBlendInformation;
import dev.spacebod.aetherium.shaders.gl.buffer.ShaderStorageInfo;
import dev.spacebod.aetherium.shaders.gl.framebuffer.ViewportData;
import dev.spacebod.aetherium.shaders.gl.texture.InternalTextureFormat;
import dev.spacebod.aetherium.shaders.gl.texture.PixelFormat;
import dev.spacebod.aetherium.shaders.gl.texture.PixelType;
import dev.spacebod.aetherium.shaders.gl.texture.TextureDefinition;
import dev.spacebod.aetherium.shaders.gl.texture.TextureScaleOverride;
import dev.spacebod.aetherium.shaders.gl.texture.TextureType;
import dev.spacebod.aetherium.shaders.helpers.OptionalBoolean;
import dev.spacebod.aetherium.shaders.helpers.StringPair;
import dev.spacebod.aetherium.shaders.helpers.Tri;
import dev.spacebod.aetherium.shaders.gl.shader.PackFacingNames;
import dev.spacebod.aetherium.shaders.shaderpack.ImageInformation;
import dev.spacebod.aetherium.shaders.shaderpack.PackFiles;
import dev.spacebod.aetherium.shaders.shaderpack.option.OrderBackedProperties;
import dev.spacebod.aetherium.shaders.shaderpack.option.ShaderPackOptions;
import dev.spacebod.aetherium.shaders.shaderpack.preprocessor.PropertiesPreprocessor;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import dev.spacebod.aetherium.shaders.uniforms.custom.CustomUniforms;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Parsed shaders.properties. An intermediate step only: combined with directives from shader source to build
 * {@link PackDirectives} and {@link ProgramDirectives}, then discarded.
 */
public class ShaderProperties {
	final CustomUniforms.Builder customUniforms = new CustomUniforms.Builder();
	private final Map<String, List<String>> profiles = new LinkedHashMap<>();
	private final Map<String, List<String>> subScreenOptions = new LinkedHashMap<>();
	private final Map<String, Integer> subScreenColumnCount = new HashMap<>();
	private final Object2ObjectMap<String, AlphaTest> alphaTestOverrides = new Object2ObjectOpenHashMap<>();
	private final Object2ObjectMap<String, ViewportData> viewportScaleOverrides = new Object2ObjectOpenHashMap<>();
	private final Object2ObjectMap<String, TextureScaleOverride> textureScaleOverrides = new Object2ObjectOpenHashMap<>();
	private final Object2ObjectMap<String, BlendModeOverride> blendModeOverrides = new Object2ObjectOpenHashMap<>();
	private final Object2ObjectMap<String, IndirectPointer> indirectPointers = new Object2ObjectOpenHashMap<>();
	private final Object2ObjectMap<String, ArrayList<BufferBlendInformation>> bufferBlendOverrides = new Object2ObjectOpenHashMap<>();
	private final EnumMap<TextureStage, Object2ObjectMap<String, TextureDefinition>> customTextures = new EnumMap<>(TextureStage.class);
	private final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> customTexturePatching = new Object2ObjectOpenHashMap<>();
	private final Object2ObjectMap<String, TextureDefinition> packCustomTextures = new Object2ObjectOpenHashMap<>();
	private final List<ImageInformation> packCustomImages = new ArrayList<>();
	private final Int2ObjectArrayMap<ShaderStorageInfo> bufferObjects = new Int2ObjectArrayMap<>();
	private final Object2ObjectMap<String, Object2BooleanMap<String>> explicitFlips = new Object2ObjectOpenHashMap<>();
	private final Object2ObjectMap<String, String> conditionallyEnabledPrograms = new Object2ObjectOpenHashMap<>();
	private int customTexAmount;
	private CloudSetting cloudSetting = CloudSetting.DEFAULT;
	private CloudSetting dhCloudSetting = CloudSetting.DEFAULT;
	private OptionalBoolean weather = OptionalBoolean.DEFAULT;
	private OptionalBoolean weatherParticles = OptionalBoolean.DEFAULT;
	private OptionalBoolean oldHandLight = OptionalBoolean.DEFAULT;
	private OptionalBoolean dynamicHandLight = OptionalBoolean.DEFAULT;
	private OptionalBoolean supportsColorCorrection = OptionalBoolean.DEFAULT;
	private OptionalBoolean oldLighting = OptionalBoolean.DEFAULT;
	private OptionalBoolean shadowTerrain = OptionalBoolean.DEFAULT;
	private OptionalBoolean shadowTranslucent = OptionalBoolean.DEFAULT;
	private OptionalBoolean shadowEntities = OptionalBoolean.DEFAULT;
	private OptionalBoolean shadowPlayer = OptionalBoolean.DEFAULT;
	private OptionalBoolean shadowBlockEntities = OptionalBoolean.DEFAULT;
	private OptionalBoolean shadowLightBlockEntities = OptionalBoolean.DEFAULT;
	private OptionalBoolean underwaterOverlay = OptionalBoolean.DEFAULT;
	private OptionalBoolean sun = OptionalBoolean.DEFAULT;
	private OptionalBoolean moon = OptionalBoolean.DEFAULT;
	private OptionalBoolean stars = OptionalBoolean.DEFAULT;
	private OptionalBoolean sky = OptionalBoolean.DEFAULT;
	private OptionalBoolean vignette = OptionalBoolean.DEFAULT;
	private OptionalBoolean backFaceSolid = OptionalBoolean.DEFAULT;
	private OptionalBoolean backFaceCutout = OptionalBoolean.DEFAULT;
	private OptionalBoolean backFaceCutoutMipped = OptionalBoolean.DEFAULT;
	private OptionalBoolean backFaceTranslucent = OptionalBoolean.DEFAULT;
	private OptionalBoolean rainDepth = OptionalBoolean.DEFAULT;
	private OptionalBoolean concurrentCompute = OptionalBoolean.DEFAULT;
	private OptionalBoolean beaconBeamDepth = OptionalBoolean.DEFAULT;
	private OptionalBoolean separateAo = OptionalBoolean.DEFAULT;
	private OptionalBoolean breaksAnisotropy = OptionalBoolean.DEFAULT;
	private OptionalBoolean voxelizeLightBlocks = OptionalBoolean.DEFAULT;
	private OptionalBoolean separateEntityDraws = OptionalBoolean.DEFAULT;
	private OptionalBoolean skipAllRendering = OptionalBoolean.DEFAULT;
	private OptionalBoolean frustumCulling = OptionalBoolean.DEFAULT;
	private OptionalBoolean supportsEndFlash = OptionalBoolean.DEFAULT;
	private OptionalBoolean occlusionCulling = OptionalBoolean.DEFAULT;
	private ShadowCullState shadowCulling = ShadowCullState.DEFAULT;
	private OptionalBoolean shadowEnabled = OptionalBoolean.DEFAULT;
	private OptionalBoolean dhShadowEnabled = OptionalBoolean.DEFAULT;
	private ParticleRenderingSettings particleRenderingSettings = ParticleRenderingSettings.UNSET;
	private OptionalBoolean prepareBeforeShadow = OptionalBoolean.DEFAULT;
	private List<String> sliderOptions = new ArrayList<>();
	private List<String> mainScreenOptions = null;
	private Integer mainScreenColumnCount = null;
	private int fallbackTex = 0;
	private String noiseTexturePath = null;
	private List<String> requiredFeatureFlags = new ArrayList<>();
	private List<String> optionalFeatureFlags = new ArrayList<>();

	private ShaderProperties() {
	}

	public ShaderProperties(String contents, ShaderPackOptions shaderPackOptions, Iterable<StringPair> environmentDefines) {
		String preprocessedContents = PropertiesPreprocessor.preprocessSource(contents, shaderPackOptions, environmentDefines);

		if (AetheriumShaders.getShaderConfig().areDebugOptionsEnabled()) {
			PackFiles.dump("properties/shaders.preprocessed.properties", preprocessedContents);
			PackFiles.dump("properties/shaders.original.properties", contents);
		}

		Properties preprocessed = load(preprocessedContents);
		Properties original = load(contents);

		preprocessed.forEach((keyObject, valueObject) -> {
			String key = (String) keyObject;
			String value = (String) valueObject;
			guarded(key, value, () -> parseDirective(key, value));
		});

		// Menu layout keys are read from the unpreprocessed file so preprocessing can't alter the screen layout.
		original.forEach((keyObject, valueObject) -> {
			String key = (String) keyObject;
			String value = (String) valueObject;
			guarded(key, value, () -> parseMenuDirective(key, value));
		});
	}

	/** The keys of a properties text; a malformed escape stops the read there (the keys before it are kept). */
	private static Properties load(String text) {
		Properties properties = new OrderBackedProperties();
		try {
			properties.load(new StringReader(text));
		} catch (IOException | IllegalArgumentException e) {
			AetheriumShaders.logger.error("shaders.properties is only partly readable: {}", e.getMessage());
		}
		return properties;
	}

	/**
	 * Runs one key's handlers. A malformed line is logged ({@code key = value: reason}) and skipped; it never refuses the
	 * rest of the file.
	 */
	private static void guarded(String key, String value, Runnable handler) {
		try {
			handler.run();
		} catch (RuntimeException e) {
			String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			AetheriumShaders.logger.error("ignoring the shaders.properties line {} = {}: {}", key, value, reason);
		}
	}

	/** Directives read from the preprocessed file. */
	private void parseDirective(String key, String value) {

		if ("texture.noise".equals(key)) {
			noiseTexturePath = value;
			return;
		}

		if ("clouds".equals(key)) {
			switch (value) {
				case "off" -> cloudSetting = CloudSetting.OFF;
				case "fast" -> cloudSetting = CloudSetting.FAST;
				case "fancy" -> cloudSetting = CloudSetting.FANCY;
				// Not a setting the format defines; packs that write it mean the player's own choice.
				case "on", "default" -> cloudSetting = CloudSetting.DEFAULT;
				case null, default -> AetheriumShaders.logger.error("Unrecognized clouds setting: " + value);
			}
		}

		if ("dhClouds".equals(key)) {
			if ("off".equals(value)) {
				dhCloudSetting = CloudSetting.OFF;
			} else if ("on".equals(value) || "fancy".equals(value)) {
				dhCloudSetting = CloudSetting.FANCY;
			} else {
				AetheriumShaders.logger.error("Unrecognized DH clouds setting (need off, on): " + value);
			}
		}

		if ("shadow.culling".equals(key)) {
			switch (value) {
				case "false" -> shadowCulling = ShadowCullState.DISTANCE;
				case "true" -> shadowCulling = ShadowCullState.ADVANCED;
				case "reversed", "safe_zone" -> shadowCulling = ShadowCullState.SAFE_ZONE;
				case null, default -> AetheriumShaders.logger.error("Unrecognized shadow culling setting: " + value);
			}
		}

		handleBooleanDirective(key, value, "oldHandLight", bool -> oldHandLight = bool);
		handleBooleanDirective(key, value, "dynamicHandLight", bool -> dynamicHandLight = bool);
		handleBooleanDirective(key, value, "oldLighting", bool -> oldLighting = bool);
		handleBooleanDirective(key, value, "shadowTerrain", bool -> shadowTerrain = bool);
		handleBooleanDirective(key, value, "shadowTranslucent", bool -> shadowTranslucent = bool);
		handleBooleanDirective(key, value, "shadowEntities", bool -> shadowEntities = bool);
		handleBooleanDirective(key, value, "shadowPlayer", bool -> shadowPlayer = bool);
		handleBooleanDirective(key, value, "shadowBlockEntities", bool -> shadowBlockEntities = bool);
		handleBooleanDirective(key, value, "shadowLightBlockEntities", bool -> shadowLightBlockEntities = bool);
		handleBooleanDirective(key, value, "underwaterOverlay", bool -> underwaterOverlay = bool);
		handleBooleanDirective(key, value, "sun", bool -> sun = bool);
		handleBooleanDirective(key, value, "moon", bool -> moon = bool);
		handleBooleanDirective(key, value, "stars", bool -> stars = bool);
		handleBooleanDirective(key, value, "sky", bool -> sky = bool);
		handleBooleanDirective(key, value, "vignette", bool -> vignette = bool);
		handleBooleanDirective(key, value, "backFace.solid", bool -> backFaceSolid = bool);
		handleBooleanDirective(key, value, "backFace.cutout", bool -> backFaceCutout = bool);
		handleBooleanDirective(key, value, "backFace.cutoutMipped", bool -> backFaceCutoutMipped = bool);
		handleBooleanDirective(key, value, "backFace.translucent", bool -> backFaceTranslucent = bool);
		handleBooleanDirective(key, value, "rain.depth", bool -> rainDepth = bool);
		handleBooleanDirective(key, value, "allowConcurrentCompute", bool -> concurrentCompute = bool);
		handleBooleanDirective(key, value, "beacon.beam.depth", bool -> beaconBeamDepth = bool);
		handleBooleanDirective(key, value, "separateAo", bool -> separateAo = bool);
		handleBooleanDirective(key, value, "breaksAnisotropy", bool -> breaksAnisotropy = bool);
		handleBooleanDirective(key, value, "voxelizeLightBlocks", bool -> voxelizeLightBlocks = bool);
		handleBooleanDirective(key, value, "separateEntityDraws", bool -> {
			separateEntityDraws = bool;
			particleRenderingSettings = ParticleRenderingSettings.MIXED;
		});
		handleBooleanDirective(key, value, "frustum.culling", bool -> frustumCulling = bool);
		handleBooleanDirective(key, value, "endFlashShadows", bool -> supportsEndFlash = bool);
		handleBooleanDirective(key, value, "occlusion.culling", bool -> occlusionCulling = bool);
		handleBooleanDirective(key, value, "shadow.enabled", bool -> shadowEnabled = bool);
		handleBooleanDirective(key, value, "skipAllRendering", bool -> skipAllRendering = bool);
		handleBooleanDirective(key, value, "dhShadow.enabled", bool -> dhShadowEnabled = bool);
		handleBooleanDirective(key, value, "particles.before.deferred", bool -> {
			if (bool.orElse(false) && particleRenderingSettings == ParticleRenderingSettings.UNSET) {
				particleRenderingSettings = ParticleRenderingSettings.BEFORE;
			}
		});
		handleBooleanDirective(key, value, "prepareBeforeShadow", bool -> prepareBeforeShadow = bool);
		handleBooleanDirective(key, value, "supportsColorCorrection", bool -> supportsColorCorrection = bool);
		handleIntDirective(key, value, "fallbackTex", bool -> fallbackTex = bool);

		if (key.startsWith("particles.ordering")) {
			particleRenderingSettings = ParticleRenderingSettings.fromString(value.trim().toUpperCase(Locale.US));
		}

		handlePassDirective("scale.", key, value, pass -> {
			float scale, offsetX = 0.0f, offsetY = 0.0f;
			String[] parts = value.split(" ");

			try {
				scale = Float.parseFloat(parts[0]);

				if (parts.length > 1) {
					offsetX = Float.parseFloat(parts[1]);
					offsetY = Float.parseFloat(parts[2]);
				}
			} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
				AetheriumShaders.logger.error("Unable to parse scale directive for " + pass + ": " + value, e);
				return;
			}

			viewportScaleOverrides.put(pass, new ViewportData(scale, offsetX, offsetY));
		});

		if ("weather".equals(key)) {
			String[] parts = value.split(" ");

			weather = parts[0].equals("true") ? OptionalBoolean.TRUE : OptionalBoolean.FALSE;

			if (parts.length > 1) {
				weatherParticles = parts[1].equals("true") ? OptionalBoolean.TRUE : OptionalBoolean.FALSE;
			}
		}

		handlePassDirective("size.buffer.", key, value, pass -> {
			String[] parts = value.split(" ");

			if (parts.length != 2) {
				AetheriumShaders.logger.error("Unable to parse size.buffer directive for " + pass + ": " + value);
				return;
			}

			textureScaleOverrides.put(pass, new TextureScaleOverride(parts[0], parts[1]));
		});

		handlePassDirective("alphaTest.", key, value, pass -> {
			if ("off".equals(value) || "false".equals(value)) {
				alphaTestOverrides.put(pass, AlphaTest.ALWAYS);
				return;
			}

			String[] parts = value.split(" ");

			if (parts.length > 2) {
				AetheriumShaders.logger.warn("Weird alpha test directive for " + pass + " contains more parts than we expected: " + value);
			} else if (parts.length < 2) {
				AetheriumShaders.logger.error("Invalid alpha test directive for " + pass + ": " + value);
				return;
			}

			Optional<AlphaTestFunction> function = AlphaTestFunction.fromString(parts[0]);

			if (function.isEmpty()) {
				AetheriumShaders.logger.error("Unable to parse alpha test directive for " + pass + ", unknown alpha test function " + parts[0] + ": " + value);
				return;
			}

			float reference;

			try {
				reference = Float.parseFloat(parts[1]);
			} catch (NumberFormatException e) {
				AetheriumShaders.logger.error("Unable to parse alpha test directive for " + pass + ": " + value, e);
				return;
			}

			alphaTestOverrides.put(pass, new AlphaTest(function.get(), reference));
		});

		handlePassDirective("blend.", key, value, pass -> {
			if (pass.contains(".")) {

				if (!ShaderRenderSystem.supportsBufferBlending()) {
					throw new IllegalStateException("per-buffer blending is not supported on this device");
				}

				String[] parts = pass.split("\\.");
				int index = PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.indexOf(parts[1]);

				if (index == -1 && parts[1].startsWith("colortex")) {
					String id = parts[1].substring("colortex".length());

					try {
						index = Integer.parseInt(id);
					} catch (NumberFormatException e) {
						throw new IllegalArgumentException("unknown buffer " + parts[1]);
					}
				}

				if (index == -1) {
					throw new IllegalArgumentException("unknown buffer " + parts[1]);
				}

				if ("off".equals(value)) {
					bufferBlendOverrides.computeIfAbsent(parts[0], list -> new ArrayList<>()).add(new BufferBlendInformation(index, null));
					return;
				}

				bufferBlendOverrides.computeIfAbsent(parts[0], list -> new ArrayList<>()).add(new BufferBlendInformation(index, parseBlendMode(value)));

				return;
			}

			if ("off".equals(value)) {
				blendModeOverrides.put(pass, BlendModeOverride.OFF);
				return;
			}

			blendModeOverrides.put(pass, new BlendModeOverride(parseBlendMode(value)));
		});

		handlePassDirective("indirect.", key, value, pass -> {
			try {
				String[] locations = value.split(" ");

				indirectPointers.put(pass, new IndirectPointer(Integer.parseInt(locations[0]), Long.parseLong(locations[1])));
			} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
				AetheriumShaders.logger.error("Failed to parse indirect command for " + pass + "! " + value);
			}
		});

		handleProgramEnabledDirective("program.", key, value, program -> conditionallyEnabledPrograms.put(program, value));

		handlePassDirective("bufferObject.", key, value, index -> {
			int trueIndex;
			long trueSize;
			boolean isRelative;
			float scaleX, scaleY;
			String[] parts = value.split(" ");
			if (parts.length <= 2) {
				try {
					trueIndex = Integer.parseInt(index);
					trueSize = Long.parseLong(parts[0]);
				} catch (NumberFormatException e) {
					AetheriumShaders.logger.error("Number format exception parsing SSBO index/size!", e);
					return;
				}

				String name = null;

				if (parts.length > 1) {
					name = parts[1];
				}

				if (trueIndex > 12) {
					AetheriumShaders.logger.error("SSBO's cannot use buffer numbers higher than 12, they're reserved!");
					return;
				}

				if (trueSize < 1) {
					// A size below 1 disables the buffer
					return;
				}

				bufferObjects.put(trueIndex, new ShaderStorageInfo(trueSize, false, 0, 0, name));
			} else {
				// Long form: <size> <relative> <scaleX> <scaleY>
				try {
					trueIndex = Integer.parseInt(index);
					trueSize = Long.parseLong(parts[0]);
					isRelative = Boolean.parseBoolean(parts[1]);
					scaleX = Float.parseFloat(parts[2]);
					scaleY = Float.parseFloat(parts[3]);
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
					AetheriumShaders.logger.error("Number format exception parsing SSBO index/size, or not correct format!", e);
					return;
				}

				if (trueIndex > 12) {
					AetheriumShaders.logger.error("SSBO's cannot use buffer numbers higher than 12, they're reserved!");
					return;
				}

				if (trueSize < 1) {
					// A size below 1 disables the buffer
					return;
				}

				bufferObjects.put(trueIndex, new ShaderStorageInfo(trueSize, isRelative, scaleX, scaleY, null));
			}
		});

		handleTwoArgDirective("texture.", key, value, (stageName, samplerName) -> {
			String[] parts = value.split(" ");
			// Anything after the sampler name (texture.<stage>.<sampler>.<suffix>) is ignored.
			samplerName = samplerName.split("\\.")[0];

			Optional<TextureStage> optionalTextureStage = TextureStage.parse(stageName);

			if (optionalTextureStage.isEmpty()) {
				AetheriumShaders.logger.warn("Unknown texture stage " + "\"" + stageName + "\"," + " ignoring custom texture directive for " + key);
				return;
			}

			TextureStage stage = optionalTextureStage.get();

			if (parts.length > 1) {
				String newSamplerName = "customtex" + customTexAmount;
				customTexAmount++;
				TextureType type = null;
				// Raw texture
				TextureDefinition.RawDefinition raw = parseRawTexture(parts);
				if (raw == null) {
					AetheriumShaders.logger.warn("Unknown texture directive for " + key + ": " + value);
				} else {
					packCustomTextures.put(newSamplerName, raw);
					// 1D and 3D by their part count; a 2D-sized declaration names its own type (2D or rectangle).
					type = parts.length == 6 ? TextureType.TEXTURE_1D : parts.length == 8 ? TextureType.TEXTURE_3D : raw.getTarget();
				}

				customTexturePatching.put(new Tri<>(samplerName, type, stage), newSamplerName);

				return;
			}

			customTextures.computeIfAbsent(stage, _stage -> new Object2ObjectOpenHashMap<>())
				.put(samplerName, new TextureDefinition.PNGDefinition(value));
		});

		handlePassDirective("customTexture.", key, value, (samplerName) -> {
			String[] parts = value.split(" ");

			if (parts.length > 1) {
				TextureDefinition.RawDefinition raw = parseRawTexture(parts);
				if (raw == null) {
					AetheriumShaders.logger.warn("Unknown texture directive for " + key + ": " + value);
				} else {
					packCustomTextures.put(samplerName, raw);
				}

				return;
			}

			packCustomTextures.put(samplerName, new TextureDefinition.PNGDefinition(value));
		});

		handlePassDirective("image.", key, value, (imageName) -> {
			String[] parts = value.split(" ");
			String key2 = key.substring(6);

			if (packCustomImages.size() > 15) {
				AetheriumShaders.logger.error("Only up to 16 images are allowed, but tried to add another image! " + key);
				return;
			}

			ImageInformation image;

			String samplerName = parts[0];
			if (samplerName.equals("none")) {
				samplerName = null;
			}
			PixelFormat format = PixelFormat.fromString(parts[1]).orElse(null);
			InternalTextureFormat internalFormat = InternalTextureFormat.fromString(parts[2]).orElse(null);
			PixelType pixelType = PixelType.fromString(parts[3]).orElse(null);

			if (format == null || internalFormat == null || pixelType == null) {
				AetheriumShaders.logger.error("Image " + key2 + " is invalid! Format: " + format + " Internal format: " + internalFormat + " Pixel type: " + pixelType);
				return;
			}

			boolean clear = Boolean.parseBoolean(parts[4]);

			boolean relative = Boolean.parseBoolean(parts[5]);

			if (relative) {
				float relativeWidth = Float.parseFloat(parts[6]);
				float relativeHeight = Float.parseFloat(parts[7]);
				image = new ImageInformation(key2, samplerName, TextureType.TEXTURE_2D, format, internalFormat, pixelType, 0, 0, 0, clear, true, relativeWidth, relativeHeight);
			} else {
				TextureType type;
				int width, height, depth;
				if (parts.length == 7) {
					type = TextureType.TEXTURE_1D;
					width = Integer.parseInt(parts[6]);
					height = 0;
					depth = 0;
				} else if (parts.length == 8) {
					type = TextureType.TEXTURE_2D;
					width = Integer.parseInt(parts[6]);
					height = Integer.parseInt(parts[7]);
					depth = 0;
				} else if (parts.length == 9) {
					type = TextureType.TEXTURE_3D;
					width = Integer.parseInt(parts[6]);
					height = Integer.parseInt(parts[7]);
					depth = Integer.parseInt(parts[8]);
				} else {
					AetheriumShaders.logger.error("Unknown image type! " + key2 + " = " + value);
					return;
				}
				image = new ImageInformation(key2, samplerName, type, format, internalFormat, pixelType, width, height, depth, clear, false, 0, 0);
			}

			packCustomImages.add(image);
		});

		handleTwoArgDirective("flip.", key, value, (pass, buffer) -> handleBooleanValue(key, value, shouldFlip -> explicitFlips.computeIfAbsent(pass, _pass -> new Object2BooleanOpenHashMap<>())
			.put(buffer, shouldFlip)));

		handlePassDirective("variable.", key, value, pass -> {
			String[] parts = pass.split("\\.");
			if (parts.length != 2) {
				AetheriumShaders.logger.warn("Custom variables should take the form of `variable.<type>.<name> = <expression>. Ignoring " + key);
				return;
			}

			customUniforms.addVariable(parts[0], parts[1], value, false);
		});

		handlePassDirective("uniform.", key, value, pass -> {
			String[] parts = pass.split("\\.");
			if (parts.length != 2) {
				AetheriumShaders.logger.warn("Custom uniforms should take the form of `uniform.<type>.<name> = <expression>. Ignoring " + key);
				return;
			}

			customUniforms.addVariable(parts[0], parts[1], value, true);
		});
	}

	/** Menu layout and feature keys, read from the unpreprocessed file. */
	private void parseMenuDirective(String key, String value) {

		handleWhitespacedListDirective(key, value, PackFacingNames.REQUIRED_FEATURES_KEY, options -> requiredFeatureFlags = options);
		handleWhitespacedListDirective(key, value, PackFacingNames.OPTIONAL_FEATURES_KEY, options -> optionalFeatureFlags = options);

		// If "sliders" is defined more than once, the last definition wins.
		handleWhitespacedListDirective(key, value, "sliders", sliders -> sliderOptions = sliders);
		handlePrefixedWhitespacedListDirective("profile.", key, value, profiles::put);

		if (handleIntDirective(key, value, "screen.columns", columns -> mainScreenColumnCount = columns)) {
			return;
		}

		if (handleAffixedIntDirective("screen.", ".columns", key, value, subScreenColumnCount::put)) {
			return;
		}

		handleWhitespacedListDirective(key, value, "screen", options -> mainScreenOptions = options);
		handlePrefixedWhitespacedListDirective("screen.", key, value, subScreenOptions::put);
	}

	private static void handleBooleanValue(String key, String value, BooleanConsumer handler) {
		if ("true".equals(value) || "1".equals(value)) {
			handler.accept(true);
		} else if ("false".equals(value) || "0".equals(value)) {
			handler.accept(false);
		} else {
			AetheriumShaders.logger.warn("Unexpected value for boolean key " + key + " in shaders.properties: got " + value + ", but expected either true or false");
		}
	}

		private static void handleBooleanDirective(String key, String value, String expectedKey, Consumer<OptionalBoolean> handler) {
			if (!expectedKey.equals(key)) {
				return;
			}

			if ("true".equals(value) || "1".equals(value)) {
				handler.accept(OptionalBoolean.TRUE);
			} else if ("false".equals(value) || "0".equals(value)) {
				handler.accept(OptionalBoolean.FALSE);
			} else {
				AetheriumShaders.logger.warn("Unexpected value for boolean key " + key + " in shaders.properties: got " + value + ", but expected either true or false");
			}
		}

	private static boolean handleIntDirective(String key, String value, String expectedKey, Consumer<Integer> handler) {
		if (!expectedKey.equals(key)) {
			return false;
		}

		try {
			int result = Integer.parseInt(value);

			handler.accept(result);
		} catch (NumberFormatException nex) {
			AetheriumShaders.logger.warn("Unexpected value for integer key " + key + " in shaders.properties: got " + value + ", but expected an integer");
		}

		return true;
	}

	private static boolean handleAffixedIntDirective(String prefix, String suffix, String key, String value, BiConsumer<String, Integer> handler) {
		if (key.startsWith(prefix) && key.endsWith(suffix)) {
			int substrBegin = prefix.length();
			int substrEnd = key.length() - suffix.length();

			if (substrEnd <= substrBegin) {
				return false;
			}

			String affixStrippedKey = key.substring(substrBegin, substrEnd);

			try {
				int result = Integer.parseInt(value);

				handler.accept(affixStrippedKey, result);
			} catch (NumberFormatException nex) {
				AetheriumShaders.logger.warn("Unexpected value for integer key " + key + " in shaders.properties: got " + value + ", but expected an integer");
			}

			return true;
		}

		return false;
	}

	private static void handlePassDirective(String prefix, String key, String value, Consumer<String> handler) {
		if (key.startsWith(prefix)) {
			String pass = key.substring(prefix.length());

			handler.accept(pass);
		}
	}

	private static void handleProgramEnabledDirective(String prefix, String key, String value, Consumer<String> handler) {
		if (key.startsWith(prefix)) {
			int end = key.indexOf('.', prefix.length());
			if (end < 0) {
				throw new IllegalArgumentException("expected " + prefix + "<program>.enabled");
			}

			handler.accept(key.substring(prefix.length(), end));
		}
	}

	private static void handleWhitespacedListDirective(String key, String value, String expectedKey, Consumer<List<String>> handler) {
		if (!expectedKey.equals(key)) {
			return;
		}

		String[] elements = value.split(" +");

		handler.accept(Arrays.asList(elements));
	}

	private static void handlePrefixedWhitespacedListDirective(String prefix, String key, String value, BiConsumer<String, List<String>> handler) {
		if (key.startsWith(prefix)) {
			String prefixStrippedKey = key.substring(prefix.length());
			String[] elements = value.split(" +");

			handler.accept(prefixStrippedKey, Arrays.asList(elements));
		}
	}

	private static void handleTwoArgDirective(String prefix, String key, String value, BiConsumer<String, String> handler) {
		if (key.startsWith(prefix)) {
			int endOfPassIndex = key.indexOf(".", prefix.length());
			if (endOfPassIndex < 0) {
				throw new IllegalArgumentException("expected " + prefix + "<a>.<b>");
			}
			String stage = key.substring(prefix.length(), endOfPassIndex);
			String sampler = key.substring(endOfPassIndex + 1);

			handler.accept(stage, sampler);
		}
	}

	/** {@code <srcRGB> <dstRGB> <srcAlpha> <dstAlpha>}, each a blend factor name. */
	private static BlendMode parseBlendMode(String value) {
		String[] names = value.trim().split("\\s+");
		if (names.length < 4) {
			throw new IllegalArgumentException("expected 4 blend factors");
		}
		int[] modes = new int[4];
		for (int i = 0; i < 4; i++) {
			String name = names[i];
			modes[i] = BlendModeFunction.fromString(name).orElseThrow(() -> new IllegalArgumentException("unknown blend factor " + name)).getGlId();
		}
		return new BlendMode(modes[0], modes[1], modes[2], modes[3]);
	}

	/**
	 * A raw texture declaration: {@code <path> <type> <internalFormat> <sizeX> [<sizeY> [<sizeZ>]] <pixelFormat> <pixelType>}
	 * (6, 7 or 8 parts); null for any other part count.
	 */
	private static TextureDefinition.RawDefinition parseRawTexture(String[] parts) {
		int sizes = parts.length - 5;
		if (sizes < 1 || sizes > 3) {
			return null;
		}
		TextureType type = TextureType.valueOf(parts[1].toUpperCase(Locale.ROOT));
		InternalTextureFormat internalFormat = InternalTextureFormat.fromString(parts[2])
			.orElseThrow(() -> new IllegalArgumentException("unknown internal format " + parts[2]));
		int sizeX = Integer.parseInt(parts[3]);
		int sizeY = sizes > 1 ? Integer.parseInt(parts[4]) : 0;
		int sizeZ = sizes > 2 ? Integer.parseInt(parts[5]) : 0;
		String formatName = parts[3 + sizes];
		String typeName = parts[4 + sizes];
		PixelFormat format = PixelFormat.fromString(formatName).orElseThrow(() -> new IllegalArgumentException("unknown pixel format " + formatName));
		PixelType pixelType = PixelType.fromString(typeName).orElseThrow(() -> new IllegalArgumentException("unknown pixel type " + typeName));
		return new TextureDefinition.RawDefinition(parts[0], type, internalFormat, sizeX, sizeY, sizeZ, format, pixelType);
	}

	public static ShaderProperties empty() {
		return new ShaderProperties();
	}

	public OptionalBoolean getDhShadowEnabled() {
		return dhShadowEnabled;
	}

	public CloudSetting getCloudSetting() {
		return cloudSetting;
	}

	public CloudSetting getDHCloudSetting() {
		return dhCloudSetting;
	}

	public OptionalBoolean getOldHandLight() {
		return oldHandLight;
	}

	public OptionalBoolean getDynamicHandLight() {
		return dynamicHandLight;
	}

	public OptionalBoolean getOldLighting() {
		return oldLighting;
	}

	public OptionalBoolean getShadowTerrain() {
		return shadowTerrain;
	}

	public OptionalBoolean getShadowTranslucent() {
		return shadowTranslucent;
	}

	public OptionalBoolean getShadowEntities() {
		return shadowEntities;
	}

	public OptionalBoolean getShadowPlayer() {
		return shadowPlayer;
	}

	public OptionalBoolean getShadowBlockEntities() {
		return shadowBlockEntities;
	}

	public OptionalBoolean getShadowLightBlockEntities() {
		return shadowLightBlockEntities;
	}

	public OptionalBoolean getUnderwaterOverlay() {
		return underwaterOverlay;
	}

	public OptionalBoolean getSun() {
		return sun;
	}

	public OptionalBoolean getWeather() {
		return weather;
	}

	public OptionalBoolean getWeatherParticles() {
		return weatherParticles;
	}

	public OptionalBoolean getMoon() {
		return moon;
	}

	public OptionalBoolean getStars() {
		return stars;
	}

	public OptionalBoolean getSky() {
		return sky;
	}

	public OptionalBoolean getVignette() {
		return vignette;
	}

	public OptionalBoolean getBackFaceSolid() {
		return backFaceSolid;
	}

	public OptionalBoolean getBackFaceCutout() {
		return backFaceCutout;
	}

	public OptionalBoolean getBackFaceCutoutMipped() {
		return backFaceCutoutMipped;
	}

	public OptionalBoolean getBackFaceTranslucent() {
		return backFaceTranslucent;
	}

	public OptionalBoolean getRainDepth() {
		return rainDepth;
	}

	public OptionalBoolean getBeaconBeamDepth() {
		return beaconBeamDepth;
	}

	public OptionalBoolean getSeparateAo() {
		return separateAo;
	}

	public OptionalBoolean breaksAnisotropy() {
		return breaksAnisotropy;
	}

	public OptionalBoolean getVoxelizeLightBlocks() {
		return voxelizeLightBlocks;
	}

	public OptionalBoolean getSeparateEntityDraws() {
		return separateEntityDraws;
	}

	public OptionalBoolean skipAllRendering() {
		return skipAllRendering;
	}

	public OptionalBoolean getFrustumCulling() {
		return frustumCulling;
	}

	public OptionalBoolean supportsEndFlash() {
		return supportsEndFlash;
	}

	public OptionalBoolean getOcclusionCulling() {
		return occlusionCulling;
	}

	public ShadowCullState getShadowCulling() {
		return shadowCulling;
	}

	public int getFallbackTex() {
		return fallbackTex;
	}

	public Object2ObjectMap<String, AlphaTest> getAlphaTestOverrides() {
		return alphaTestOverrides;
	}

	public OptionalBoolean getShadowEnabled() {
		return shadowEnabled;
	}

	public ParticleRenderingSettings getParticleRenderingSettings() {
		// Mixed is implied if separateEntityDraws is true.
		return particleRenderingSettings;
	}

	public OptionalBoolean getConcurrentCompute() {
		return concurrentCompute;
	}

	public OptionalBoolean getPrepareBeforeShadow() {
		return prepareBeforeShadow;
	}

	public Object2ObjectMap<String, ViewportData> getViewportScaleOverrides() {
		return viewportScaleOverrides;
	}

	public Object2ObjectMap<String, TextureScaleOverride> getTextureScaleOverrides() {
		return textureScaleOverrides;
	}

	public Object2ObjectMap<String, BlendModeOverride> getBlendModeOverrides() {
		return blendModeOverrides;
	}

	public Object2ObjectMap<String, IndirectPointer> getIndirectPointers() {
		return indirectPointers;
	}

	public Object2ObjectMap<String, ArrayList<BufferBlendInformation>> getBufferBlendOverrides() {
		return bufferBlendOverrides;
	}

	public Int2ObjectArrayMap<ShaderStorageInfo> getBufferObjects() {
		return bufferObjects;
	}

	public EnumMap<TextureStage, Object2ObjectMap<String, TextureDefinition>> getCustomTextures() {
		return customTextures;
	}

	public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getCustomTexturePatching() {

		return customTexturePatching;
	}

	public Object2ObjectMap<String, TextureDefinition> getNamedCustomTextures() {
		return packCustomTextures;
	}

	public List<ImageInformation> getCustomImages() {
		return packCustomImages;
	}

	public Optional<String> getNoiseTexturePath() {
		return Optional.ofNullable(noiseTexturePath);
	}

	public Object2ObjectMap<String, String> getConditionallyEnabledPrograms() {
		return conditionallyEnabledPrograms;
	}

	public List<String> getSliderOptions() {
		return sliderOptions;
	}

	public Map<String, List<String>> getProfiles() {
		return profiles;
	}

	public Optional<List<String>> getMainScreenOptions() {
		return Optional.ofNullable(mainScreenOptions);
	}

	public Map<String, List<String>> getSubScreenOptions() {
		return subScreenOptions;
	}

	public Optional<Integer> getMainScreenColumnCount() {
		return Optional.ofNullable(mainScreenColumnCount);
	}

	public Map<String, Integer> getSubScreenColumnCount() {
		return subScreenColumnCount;
	}

	public Object2ObjectMap<String, Object2BooleanMap<String>> getExplicitFlips() {
		return explicitFlips;
	}

	public List<String> getRequiredFeatureFlags() {
		return requiredFeatureFlags;
	}

	public List<String> getOptionalFeatureFlags() {
		return optionalFeatureFlags;
	}

	public OptionalBoolean supportsColorCorrection() {
		return supportsColorCorrection;
	}

	public CustomUniforms.Builder getCustomUniforms() {
		return customUniforms;
	}
}
