package dev.spacebod.aetherium.shaders.shaderpack.properties;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.texture.InternalTextureFormat;
import dev.spacebod.aetherium.shaders.helpers.OptionalBoolean;
import dev.spacebod.aetherium.shaders.shaderpack.parsing.DirectiveHolder;
import dev.spacebod.aetherium.shaders.shadows.ShadowMatrices;
import org.joml.Vector4f;

import java.util.Optional;

public class PackShadowDirectives {
	/** Up to 8 shadow colour buffers are supported; the baseline pack format only guarantees 2. */
	public static final int MAX_SHADOW_COLOR_BUFFERS_PACK = 8;

	private final OptionalBoolean shadowEnabled;
	private final OptionalBoolean dhShadowEnabled;
	private final boolean shouldRenderTerrain;
	private final boolean shouldRenderTranslucent;
	private final boolean shouldRenderEntities;
	private final boolean shouldRenderPlayer;
	private final boolean shouldRenderBlockEntities;
	private final boolean shouldRenderLightBlockEntities;
	private final ShadowCullState cullingState;
	private final ImmutableList<DepthSamplingSettings> depthSamplingSettings;
	private final Int2ObjectMap<SamplingSettings> colorSamplingSettings;
	private int resolution;
	// null = no FOV specified (orthographic projection)
	private Float fov;
	private float distance;
	private float nearPlane;
	private float farPlane;
	private float voxelDistance;
	private float distanceRenderMul;
	private float entityShadowDistanceMul;
	private boolean explicitRenderDistance;
	private float intervalSize;

	public PackShadowDirectives(ShaderProperties properties) {
		this.resolution = 1024;

		// Without an FOV the shadow projection is orthographic, sized by shadowDistance. With one it is perspective and
		// shadowDistance only feeds the shadow render distance (when shadowDistanceRenderMul > 0).
		this.fov = null;

		// Orthographic half plane of 160 m (10 chunks). Packs are expected to lower this and set
		// shadowDistanceRenderMul, as a large shadow render distance is expensive.
		this.distance = 160.0f;
		this.nearPlane = ShadowMatrices.NEAR;
		this.farPlane = ShadowMatrices.FAR;
		this.voxelDistance = 0.0f;

		// No distance culling of shadows unless the pack sets shadowDistanceRenderMul. Culling against the shadow
		// matrices is unreliable because packs apply non-linear distortion (more far chunks, more detail up close),
		// so shadow culling instead tests whether a caster can possibly shadow the player's view.
		this.distanceRenderMul = -1.0f;
		this.entityShadowDistanceMul = 1.0f;
		this.explicitRenderDistance = false;

		// The shadow camera snaps to a 2 m grid, moving only when the sun/moon moves or the player enters another cell.
		this.intervalSize = 2.0f;

		this.shouldRenderTerrain = properties.getShadowTerrain().orElse(true);
		this.shouldRenderTranslucent = properties.getShadowTranslucent().orElse(true);
		this.shouldRenderEntities = properties.getShadowEntities().orElse(true);
		this.shouldRenderPlayer = properties.getShadowPlayer().orElse(false);
		this.shouldRenderBlockEntities = properties.getShadowBlockEntities().orElse(true);
		this.shouldRenderLightBlockEntities = properties.getShadowLightBlockEntities().orElse(false);
		this.cullingState = properties.getShadowCulling();
		this.shadowEnabled = properties.getShadowEnabled();
		this.dhShadowEnabled = properties.getDhShadowEnabled();

		this.depthSamplingSettings = ImmutableList.of(new DepthSamplingSettings(), new DepthSamplingSettings());

		ImmutableList.Builder<SamplingSettings> colorSamplingSettings = ImmutableList.builder();

		this.colorSamplingSettings = new Int2ObjectArrayMap<>();
	}

	public PackShadowDirectives(PackShadowDirectives shadowDirectives) {
		this.resolution = shadowDirectives.resolution;
		this.fov = shadowDirectives.fov;
		this.distance = shadowDirectives.distance;
		this.nearPlane = shadowDirectives.nearPlane;
		this.farPlane = shadowDirectives.farPlane;
		this.voxelDistance = shadowDirectives.voxelDistance;
		this.distanceRenderMul = shadowDirectives.distanceRenderMul;
		this.entityShadowDistanceMul = shadowDirectives.entityShadowDistanceMul;
		this.explicitRenderDistance = shadowDirectives.explicitRenderDistance;
		this.intervalSize = shadowDirectives.intervalSize;
		this.shouldRenderTerrain = shadowDirectives.shouldRenderTerrain;
		this.shouldRenderTranslucent = shadowDirectives.shouldRenderTranslucent;
		this.shouldRenderEntities = shadowDirectives.shouldRenderEntities;
		this.shouldRenderPlayer = shadowDirectives.shouldRenderPlayer;
		this.shouldRenderBlockEntities = shadowDirectives.shouldRenderBlockEntities;
		this.shouldRenderLightBlockEntities = shadowDirectives.shouldRenderLightBlockEntities;
		this.cullingState = shadowDirectives.cullingState;
		this.depthSamplingSettings = shadowDirectives.depthSamplingSettings;
		this.colorSamplingSettings = shadowDirectives.colorSamplingSettings;
		this.shadowEnabled = shadowDirectives.shadowEnabled;
		this.dhShadowEnabled = shadowDirectives.dhShadowEnabled;
	}

	private static void acceptHardwareFilteringSettings(DirectiveHolder directives, ImmutableList<DepthSamplingSettings> samplers) {
		// Base value, then per-sampler overrides
		directives.acceptConstBooleanDirective("shadowHardwareFiltering", hardwareFiltering -> {
			for (DepthSamplingSettings samplerSettings : samplers) {
				samplerSettings.setHardwareFiltering(hardwareFiltering);
			}
		});

		for (int i = 0; i < samplers.size(); i++) {
			String name = "shadowHardwareFiltering" + i;

			directives.acceptConstBooleanDirective(name, samplers.get(i)::setHardwareFiltering);
		}
	}

	private static void acceptDepthMipmapSettings(DirectiveHolder directives, ImmutableList<DepthSamplingSettings> samplers) {
		// Base value, then per-sampler overrides
		directives.acceptConstBooleanDirective("generateShadowMipmap", mipmap -> {
			for (SamplingSettings samplerSettings : samplers) {
				samplerSettings.setMipmap(mipmap);
			}
		});

		// Legacy alias for shadowtex0Mipmap
		if (!samplers.isEmpty()) {
			directives.acceptConstBooleanDirective("shadowtexMipmap", samplers.getFirst()::setMipmap);
		}

		for (int i = 0; i < samplers.size(); i++) {
			String name = "shadowtex" + i + "Mipmap";

			directives.acceptConstBooleanDirective(name, samplers.get(i)::setMipmap);
		}
	}

	private static void acceptColorMipmapSettings(DirectiveHolder directives, Int2ObjectMap<SamplingSettings> samplers) {
		// Base value, then per-buffer overrides
		directives.acceptConstBooleanDirective("generateShadowColorMipmap", mipmap -> samplers.forEach((i, sampler) -> sampler.setMipmap(mipmap)));

		for (int i = 0; i < PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_PACK; i++) {
			String name = "shadowcolor" + i + "Mipmap";
			directives.acceptConstBooleanDirective(name, samplers.computeIfAbsent(i, sa -> new SamplingSettings())::setMipmap);

			name = "shadowColor" + i + "Mipmap";
			directives.acceptConstBooleanDirective(name, samplers.computeIfAbsent(i, sa -> new SamplingSettings())::setMipmap);
		}
	}

	private static void acceptDepthFilteringSettings(DirectiveHolder directives, ImmutableList<DepthSamplingSettings> samplers) {
		if (!samplers.isEmpty()) {
			directives.acceptConstBooleanDirective("shadowtexNearest", samplers.getFirst()::setNearest);
		}

		for (int i = 0; i < samplers.size(); i++) {
			String name = "shadowtex" + i + "Nearest";

			directives.acceptConstBooleanDirective(name, samplers.get(i)::setNearest);

			name = "shadow" + i + "MinMagNearest";

			directives.acceptConstBooleanDirective(name, samplers.get(i)::setNearest);
		}
	}

	private static void acceptColorFilteringSettings(DirectiveHolder directives, Int2ObjectMap<SamplingSettings> samplers) {
		for (int i = 0; i < PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_PACK; i++) {
			String name = "shadowcolor" + i + "Nearest";

			directives.acceptConstBooleanDirective(name, samplers.computeIfAbsent(i, sa -> new SamplingSettings())::setNearest);

			name = "shadowColor" + i + "Nearest";

			directives.acceptConstBooleanDirective(name, samplers.computeIfAbsent(i, sa -> new SamplingSettings())::setNearest);

			name = "shadowColor" + i + "MinMagNearest";

			directives.acceptConstBooleanDirective(name, samplers.computeIfAbsent(i, sa -> new SamplingSettings())::setNearest);
		}
	}

	public int getResolution() {
		return resolution;
	}

	public Float getFov() {
		return fov;
	}

	public float getDistance() {
		return distance;
	}

	public float getNearPlane() {
		return nearPlane;
	}

	public float getFarPlane() {
		return farPlane;
	}

	public float getVoxelDistance() {
		return voxelDistance;
	}

	public float getDistanceRenderMul() {
		return distanceRenderMul;
	}

	public float getEntityShadowDistanceMul() {
		return entityShadowDistanceMul;
	}

	public boolean isDistanceRenderMulExplicit() {
		return explicitRenderDistance;
	}

	public float getIntervalSize() {
		return intervalSize;
	}

	public boolean shouldRenderTerrain() {
		return shouldRenderTerrain;
	}

	public boolean shouldRenderTranslucent() {
		return shouldRenderTranslucent;
	}

	public boolean shouldRenderEntities() {
		return shouldRenderEntities;
	}

	public boolean shouldRenderPlayer() {
		return shouldRenderPlayer;
	}

	public boolean shouldRenderBlockEntities() {
		return shouldRenderBlockEntities;
	}

	public boolean shouldRenderLightBlockEntities() {
		return shouldRenderLightBlockEntities;
	}

	public ShadowCullState getCullingState() {
		return cullingState;
	}

	public OptionalBoolean isShadowEnabled() {
		return shadowEnabled;
	}

	public OptionalBoolean isDhShadowEnabled() {
		return dhShadowEnabled;
	}

	public ImmutableList<DepthSamplingSettings> getDepthSamplingSettings() {
		return depthSamplingSettings;
	}

	public Int2ObjectMap<SamplingSettings> getColorSamplingSettings() {
		return colorSamplingSettings;
	}

	public void acceptDirectives(DirectiveHolder directives) {
		directives.acceptCommentIntDirective("SHADOWRES", resolution -> this.resolution = resolution);
		directives.acceptConstIntDirective("shadowMapResolution", resolution -> this.resolution = resolution);

		directives.acceptCommentFloatDirective("SHADOWFOV", fov -> this.fov = fov);
		directives.acceptConstFloatDirective("shadowMapFov", fov -> this.fov = fov);

		directives.acceptCommentFloatDirective("SHADOWHPL", distance -> this.distance = distance);
		directives.acceptConstFloatDirective("shadowDistance", distance -> this.distance = distance);
		directives.acceptConstFloatDirective("shadowNearPlane", nearPlane -> this.nearPlane = nearPlane);
		directives.acceptConstFloatDirective("shadowFarPlane", farPlane -> this.farPlane = farPlane);
		directives.acceptConstFloatDirective("voxelDistance", distance -> this.voxelDistance = distance);

		directives.acceptConstFloatDirective("entityShadowDistanceMul", distance -> this.entityShadowDistanceMul = distance);

		directives.acceptConstFloatDirective("shadowDistanceRenderMul", distanceRenderMul -> {
			this.distanceRenderMul = distanceRenderMul;
			this.explicitRenderDistance = true;
		});

		directives.acceptConstFloatDirective("shadowIntervalSize",
			intervalSize -> this.intervalSize = intervalSize);

		acceptHardwareFilteringSettings(directives, depthSamplingSettings);
		acceptDepthMipmapSettings(directives, depthSamplingSettings);
		acceptColorMipmapSettings(directives, colorSamplingSettings);
		acceptDepthFilteringSettings(directives, depthSamplingSettings);
		acceptColorFilteringSettings(directives, colorSamplingSettings);
		acceptBufferDirectives(directives, colorSamplingSettings);
	}

	private void acceptBufferDirectives(DirectiveHolder directives, Int2ObjectMap<SamplingSettings> settings) {
		for (int i = 0; i < PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_PACK; i++) {
			String bufferName = "shadowcolor" + i;
			int finalI = i;
			directives.acceptConstStringDirective(bufferName + "Format", format -> {
				Optional<InternalTextureFormat> internalFormat = InternalTextureFormat.fromString(format);

				if (internalFormat.isPresent()) {
					settings.computeIfAbsent(finalI, sa -> new SamplingSettings()).setFormat(internalFormat.get());
				} else {
					AetheriumShaders.logger.warn("Unrecognized internal texture format " + format + " specified for " + bufferName + "Format, ignoring.");
				}
			});

			// Read from every program, though only composite and deferred passes clear.
			directives.acceptConstBooleanDirective(bufferName + "Clear",
				shouldClear -> settings.computeIfAbsent(finalI, sa -> new SamplingSettings()).setClear(shouldClear));

			// Relevant even when clear is false: it is the buffer's initial colour.
			directives.acceptConstVec4Directive(bufferName + "ClearColor",
				clearColor -> settings.computeIfAbsent(finalI, sa -> new SamplingSettings()).setClearColor(clearColor));
		}
	}

	@Override
	public String toString() {
		return "PackShadowDirectives{" +
			"resolution=" + resolution +
			", fov=" + fov +
			", distance=" + distance +
			", distanceRenderMul=" + distanceRenderMul +
			", entityDistanceRenderMul=" + entityShadowDistanceMul +
			", intervalSize=" + intervalSize +
			", depthSamplingSettings=" + depthSamplingSettings +
			", colorSamplingSettings=" + colorSamplingSettings +
			'}';
	}

	public static class SamplingSettings {
		/**
		 * Generate mipmaps before sampling. Off by default.
		 */
		private boolean mipmap;

		/**
		 * Nearest instead of linear filtering. Integer formats always sample nearest.
		 */
		private boolean nearest;

		/**
		 * Clear the buffer every frame.
		 */
		private boolean clear;

		/**
		 * The color to clear the buffer to. If {@code clear} is false, this has no effect.
		 */
		private Vector4f clearColor;

		private InternalTextureFormat format;

		public SamplingSettings() {
			mipmap = false;
			nearest = false;
			clear = true;
			clearColor = new Vector4f(1.0F);
			format = InternalTextureFormat.RGBA;
		}

		public boolean getMipmap() {
			return this.mipmap;
		}

		protected void setMipmap(boolean mipmap) {
			this.mipmap = mipmap;
		}

		public boolean getNearest() {
			return this.nearest || this.format.getPixelFormat().isInteger();
		}

		protected void setNearest(boolean nearest) {
			this.nearest = nearest;
		}

		public boolean getClear() {
			return clear;
		}

		protected void setClear(boolean clear) {
			this.clear = clear;
		}

		public Vector4f getClearColor() {
			return clearColor;
		}

		protected void setClearColor(Vector4f clearColor) {
			this.clearColor = clearColor;
		}

		public InternalTextureFormat getFormat() {
			return this.format;
		}

		protected void setFormat(InternalTextureFormat format) {
			this.format = format;
		}

		@Override
		public String toString() {
			return "SamplingSettings{" +
				"mipmap=" + mipmap +
				", nearest=" + nearest +
				", clear=" + clear +
				", clearColor=" + clearColor +
				", format=" + format.name() +
				'}';
		}
	}

	public static class DepthSamplingSettings extends SamplingSettings {
		private boolean hardwareFiltering;

		public DepthSamplingSettings() {
			hardwareFiltering = false;
		}

		public boolean getHardwareFiltering() {
			return hardwareFiltering;
		}

		private void setHardwareFiltering(boolean hardwareFiltering) {
			this.hardwareFiltering = hardwareFiltering;
		}

		@Override
		public String toString() {
			return "DepthSamplingSettings{" +
				"mipmap=" + getMipmap() +
				", nearest=" + getNearest() +
				", hardwareFiltering=" + hardwareFiltering +
				'}';
		}
	}
}
