package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.texture.PixelFormat;
import dev.spacebod.aetherium.shaders.gl.texture.PixelType;
import dev.spacebod.aetherium.shaders.shaderpack.ShaderPack;
import dev.spacebod.aetherium.shaders.shaderpack.texture.CustomTextureData;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureFilteringData;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The pack's custom textures: {@code texture.<stage>.<sampler>=} (per stage), {@code customTexture.<name>=} (every
 * stage) and {@code texture.noise=} (replaces {@code noisetex}). PNGs and 1D/2D raw 8-bit data become GPU textures at
 * load; raw data vanilla's textures cannot hold (3D, 16/32-bit, signed ...) lives in the storage set
 * ({@link RawTextures}). Resource-pack textures are looked up again at every frame start ({@link #beginFrame}, outside
 * any render pass, since a first lookup loads the texture) and sampled nearest with linearly blended mip levels and
 * repeat; the lightmap is vanilla's current one. A colour target's override applies under both of its names
 * ({@code colortex4} and {@code gaux1} ...), as do {@code depthtex0}/{@code gdepthtex} and
 * {@code dhDepthTex0}/{@code dhDepthTex}.
 */
final class CustomTextures implements AutoCloseable {
	/** A bound texture: its view (current at bind time) and sampler. */
	record Binding(Supplier<GpuTextureView> view, GpuSampler sampler) {
	}

	private final Map<TextureStage, Map<String, Binding>> byStage = new EnumMap<>(TextureStage.class);
	private final Map<String, Binding> everyStage = new HashMap<>();
	private final List<AutoCloseable> owned = new ArrayList<>();
	/** Resource-pack textures, re-resolved at every frame start. */
	private final List<ResourceTexture> resources = new ArrayList<>();

	/** A {@code namespace:path} texture: vanilla's view of it as of this frame's start. */
	private static final class ResourceTexture {
		private final Identifier id;
		private @Nullable GpuTextureView view;

		ResourceTexture(Identifier id) {
			this.id = id;
		}

		void refresh() {
			view = Minecraft.getInstance().getTextureManager().getTexture(id).getTextureView();
		}

		GpuTextureView view() {
			if (view == null) {
				refresh();
			}
			return view;
		}
	}

	CustomTextures(ShaderPack pack) {
		pack.getCustomTextureDataMap().forEach((stage, map) -> {
			Map<String, Binding> bindings = new HashMap<>();
			map.forEach((sampler, data) -> {
				Binding b = create(sampler, data);
				if (b != null) {
					bindings.put(sampler, b);
				}
			});
			byStage.put(stage, bindings);
		});
		pack.getNamedCustomTextureData().forEach((sampler, data) -> {
			Binding b = create(sampler, data);
			if (b != null) {
				everyStage.put(sampler, b);
			}
		});
		if (pack.getCustomNoiseTexture() != null) {
			Binding b = create("noisetex", pack.getCustomNoiseTexture());
			if (b != null) {
				everyStage.putIfAbsent("noisetex", b);
			}
		}
		resolveNames();
		int count = everyStage.size() + byStage.values().stream().mapToInt(Map::size).sum();
		if (count > 0) {
			AetheriumShaders.logger.info("{} custom textures", count);
		}
	}

	/** Frame start, outside any render pass: resource-pack textures resolved (and loaded on first use) for this frame. */
	void beginFrame() {
		for (ResourceTexture resource : resources) {
			resource.refresh();
		}
	}

	/**
	 * The custom texture behind {@code sampler} in {@code stage}, or null when the pack defines none. A sampler with a
	 * second name takes an override under either ({@link #names}).
	 */
	@Nullable Binding get(TextureStage stage, String sampler) {
		Map<String, Binding> resolved = this.resolved.get(stage);
		return resolved == null ? null : resolved.get(sampler);
	}

	/** Per stage, every sampler name with an override (under any of its names), resolved once at load. */
	private final Map<TextureStage, Map<String, Binding>> resolved = new EnumMap<>(TextureStage.class);

	private void resolveNames() {
		List<String> defined = new ArrayList<>(everyStage.keySet());
		byStage.values().forEach(map -> defined.addAll(map.keySet()));
		for (TextureStage stage : TextureStage.values()) {
			Map<String, Binding> map = new HashMap<>();
			for (String name : defined) {
				for (String sampler : names(stage, name)) {
					Binding b = find(stage, sampler);
					if (b != null) {
						map.put(sampler, b);
					}
				}
			}
			resolved.put(stage, map);
		}
	}

	private @Nullable Binding find(TextureStage stage, String sampler) {
		for (String name : names(stage, sampler)) {
			Binding b = lookup(stage, name);
			if (b != null) {
				return b;
			}
		}
		return null;
	}

	private @Nullable Binding lookup(TextureStage stage, String sampler) {
		Map<String, Binding> staged = byStage.get(stage);
		Binding b = staged == null ? null : staged.get(sampler);
		return b != null ? b : everyStage.get(sampler);
	}

	/** Legacy names of colortex0..7, in order. */
	private static final String[] LEGACY_TARGETS = {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};

	/**
	 * Every name {@code sampler} is bound under in {@code stage}, in the order overrides are looked up: a colour target as
	 * {@code colortexN} then its legacy name, {@code gdepthtex} then {@code depthtex0} (full-screen and compute programs),
	 * {@code dhDepthTex} then {@code dhDepthTex0}.
	 */
	private static String[] names(TextureStage stage, String sampler) {
		switch (sampler) {
			case "depthtex0", "gdepthtex":
				return stage == TextureStage.GBUFFERS_AND_SHADOW ? new String[]{sampler} : new String[]{"gdepthtex", "depthtex0"};
			case "dhDepthTex", "dhDepthTex0":
				return new String[]{"dhDepthTex", "dhDepthTex0"};
			default:
				break;
		}
		for (int i = 0; i < LEGACY_TARGETS.length; i++) {
			if (sampler.equals(LEGACY_TARGETS[i]) || sampler.equals("colortex" + i)) {
				return new String[]{"colortex" + i, LEGACY_TARGETS[i]};
			}
		}
		return new String[]{sampler};
	}

	private @Nullable Binding create(String sampler, CustomTextureData data) {
		try {
			if (data instanceof CustomTextureData.PngData png) {
				NativeImage image = NativeImage.read(png.getContent());
				try {
					GpuTexture texture = RenderSystem.getDevice().createTexture(() -> "Aetherium Shaders custom " + sampler,
							GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, 1);
					RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image);
					return own(texture, png.getFilteringData());
				} finally {
					image.close();
				}
			}
			if (data instanceof CustomTextureData.RawData raw && !vanillaCanHold(raw)) {
				return null; // in the storage set (RawTextures), which reports what it cannot hold
			}
			if (data instanceof CustomTextureData.RawData2D raw) {
				return raw(sampler, raw, raw.getSizeX(), raw.getSizeY());
			}
			if (data instanceof CustomTextureData.RawData1D raw) {
				return raw(sampler, raw, raw.getSizeX(), 1);
			}
			if (data instanceof CustomTextureData.ResourceData resource) {
				ResourceTexture texture = new ResourceTexture(Identifier.fromNamespaceAndPath(resource.getNamespace(), resource.getLocation()));
				resources.add(texture);
				return new Binding(texture::view, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST, true));
			}
			if (data instanceof CustomTextureData.LightmapMarker) {
				return new Binding(() -> Minecraft.getInstance().gameRenderer.levelLightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			}
			AetheriumShaders.logger.warn("custom texture {} ({}) is not supported yet; built-in texture used", sampler, data.getClass().getSimpleName());
		} catch (IOException | RuntimeException e) {
			DeviceLoss.rethrow(e);
			AetheriumShaders.logger.error("could not create custom texture {}", sampler, e);
		}
		return null;
	}

	/** Whether vanilla's texture API holds this raw data (else it goes to the storage set, {@link RawTextures}). */
	static boolean vanillaCanHold(CustomTextureData.RawData raw) {
		return vanillaFormat(raw) != null;
	}

	/** 8-bit unsigned raw data in R, RG or RGBA layout ({@link #vanillaCanHold}). */
	private @Nullable Binding raw(String sampler, CustomTextureData.RawData raw, int width, int height) {
		GpuFormat format = Objects.requireNonNull(vanillaFormat(raw));
		byte[] content = raw.getContent();
		GpuTexture texture = RenderSystem.getDevice().createTexture(() -> "Aetherium Shaders custom " + sampler,
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, format, width, height, 1, 1);
		ByteBuffer pixels = MemoryUtil.memAlloc(content.length);
		try {
			pixels.put(content).flip();
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, pixels, 0, 0, 0, 0, width, height);
		} finally {
			MemoryUtil.memFree(pixels);
		}
		return own(texture, raw.getFilteringData());
	}

	/** Vanilla's 2D texture format for 1D/2D 8-bit unsigned raw data in R, RG or RGBA layout, or null. */
	private static @Nullable GpuFormat vanillaFormat(CustomTextureData.RawData raw) {
		if (!(raw instanceof CustomTextureData.RawData2D) && !(raw instanceof CustomTextureData.RawData1D)) {
			return null;
		}
		GpuFormat format = switch (raw.getInternalFormat()) {
			case R8 -> GpuFormat.R8_UNORM;
			case RG8 -> GpuFormat.RG8_UNORM;
			case RGBA, RGBA8 -> GpuFormat.RGBA8_UNORM;
			default -> null;
		};
		int components = raw.getPixelFormat() == PixelFormat.RED ? 1 : raw.getPixelFormat() == PixelFormat.RG ? 2
				: raw.getPixelFormat() == PixelFormat.RGBA ? 4 : 0;
		boolean matches = format != null && raw.getPixelType() == PixelType.UNSIGNED_BYTE
				&& components == switch (format) {
					case R8_UNORM -> 1;
					case RG8_UNORM -> 2;
					default -> 4;
				};
		return matches ? format : null;
	}

	private Binding own(GpuTexture texture, TextureFilteringData filtering) {
		GpuTextureView view = RenderSystem.getDevice().createTextureView(texture);
		AddressMode address = filtering.shouldClamp() ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
		FilterMode filter = filtering.shouldBlur() ? FilterMode.LINEAR : FilterMode.NEAREST;
		GpuSampler sampler = RenderSystem.getDevice().createSampler(address, address, filter, filter, 1, OptionalDouble.empty());
		owned.add(sampler);
		owned.add(view);
		owned.add(texture);
		return new Binding(() -> view, sampler);
	}

	@Override
	public void close() {
		for (AutoCloseable c : owned) {
			try {
				c.close();
			} catch (Exception ignored) {
			}
		}
		owned.clear();
		resources.clear();
		resolved.clear();
		byStage.clear();
		everyStage.clear();
	}
}
