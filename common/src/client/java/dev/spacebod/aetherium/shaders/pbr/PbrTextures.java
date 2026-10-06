package dev.spacebod.aetherium.shaders.pbr;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.uniforms.CapturedRenderingState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/**
 * Material maps (normal, specular) for the colour textures draws bind, from the resource packs: looked up by the colour
 * texture a draw binds as its albedo, loaded at the next frame start the first time one is asked for (the default
 * until then), dropped when that texture goes. Render thread only.
 */
public final class PbrTextures {
	/** Every colour texture that may have maps (atlases, single textures), by its GPU texture. */
	private static final Map<GpuTexture, AbstractTexture> COLOUR = new WeakHashMap<>();
	/** The maps loaded for a colour texture (an empty map: it has none). */
	private static final Map<GpuTexture, Map<PbrKind, AbstractTexture>> MAPS = new IdentityHashMap<>();
	private static final Set<GpuTexture> PENDING = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Per colour atlas: the animation ticks it has run since its upload (its maps' animations start there). */
	private static final Map<GpuTexture, long[]> ANIMATION_TICKS = new WeakHashMap<>();
	private static final Map<PbrKind, GpuTextureView> DEFAULTS = new EnumMap<>(PbrKind.class);
	/** Nearest-sampled maps with nearest mip selection (by albedo address modes): values that must not be blended. */
	private static final Map<AddressMode, Map<AddressMode, GpuSampler>> NEAREST_MIPS = new EnumMap<>(AddressMode.class);
	/** A sampler created with this max LOD selects mip levels by nearest ({@code VulkanGpuSamplerMixin}). */
	public static final double NEAREST_MIPS_LOD = 1000.5;

	private static @Nullable PbrFormat format;
	private static boolean formatRead;
	private static boolean formatChanged;

	private PbrTextures() {
	}

	/** A colour texture was (re)uploaded: it may get maps. */
	public static void track(AbstractTexture texture) {
		COLOUR.put(texture.getTexture(), texture);
		if (texture instanceof TextureAtlas) {
			ANIMATION_TICKS.put(texture.getTexture(), new long[1]);
		}
	}

	/** A GPU texture goes away: its maps go too (they belong to that upload of it). */
	public static void forget(@Nullable GpuTexture texture) {
		if (texture == null) {
			return;
		}
		COLOUR.remove(texture);
		ANIMATION_TICKS.remove(texture);
		PENDING.remove(texture);
		Map<PbrKind, AbstractTexture> maps = MAPS.remove(texture);
		if (maps != null) {
			maps.values().forEach(AbstractTexture::close);
		}
	}

	/** Every map loaded so far is dropped (shaders off). */
	public static void clear() {
		List<Map<PbrKind, AbstractTexture>> all = new ArrayList<>(MAPS.values());
		MAPS.clear();
		PENDING.clear();
		all.forEach(maps -> maps.values().forEach(AbstractTexture::close));
	}

	/**
	 * The {@code kind} map of the colour texture {@code colour} (null: no albedo bound): the default value until it is
	 * loaded, which the first request schedules for the next frame start.
	 */
	public static GpuTextureView view(PbrKind kind, @Nullable GpuTexture colour) {
		if (colour != null) {
			Map<PbrKind, AbstractTexture> maps = MAPS.get(colour);
			if (maps == null) {
				if (COLOUR.containsKey(colour)) {
					PENDING.add(colour);
				}
			} else {
				AbstractTexture map = maps.get(kind);
				if (map != null) {
					return map.getTextureView();
				}
			}
		}
		return defaultView(kind);
	}

	/** Frame start (no render pass open): loads the maps asked for since the last one. */
	public static void loadPending() {
		if (PENDING.isEmpty()) {
			return;
		}
		ResourceManager resources = Minecraft.getInstance().getResourceManager();
		for (GpuTexture colour : new ArrayList<>(PENDING)) {
			AbstractTexture texture = COLOUR.get(colour);
			Map<PbrKind, AbstractTexture> maps = Map.of();
			if (texture != null) {
				try {
					long[] ticks = ANIMATION_TICKS.get(colour);
					maps = PbrLoader.load(texture, resources, ticks == null ? 0 : ticks[0]);
				} catch (RuntimeException e) {
					AetheriumShaders.logger.warn("failed to load the material maps of {}", colour.getLabel(), e);
				}
			}
			MAPS.put(colour, maps);
		}
		PENDING.clear();
	}

	/** With an atlas's animation tick: its material atlases animate too. */
	public static void cycleAnimations(TextureAtlas atlas) {
		long[] ticks = ANIMATION_TICKS.get(atlas.getTexture());
		if (ticks != null) {
			ticks[0]++;
		}
		Map<PbrKind, AbstractTexture> maps = MAPS.get(atlas.getTexture());
		if (maps != null) {
			for (AbstractTexture map : maps.values()) {
				if (map instanceof PbrAtlas pbr) {
					pbr.cycleAnimationFrames();
				}
			}
		}
	}

	/** The sampler for a {@code kind} map read beside an albedo sampled with {@code albedo}. */
	public static GpuSampler sampler(PbrKind kind, @Nullable GpuSampler albedo) {
		PbrFormat f = format();
		if (albedo == null) {
			return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true);
		}
		if (f == null || f.interpolates(kind)) {
			return albedo;
		}
		return NEAREST_MIPS.computeIfAbsent(albedo.getAddressModeU(), u -> new EnumMap<>(AddressMode.class)).computeIfAbsent(albedo.getAddressModeV(),
				v -> RenderSystem.getDevice().createSampler(albedo.getAddressModeU(), v, FilterMode.NEAREST, FilterMode.NEAREST, 1,
						OptionalDouble.of(NEAREST_MIPS_LOD)));
	}

	/** One texel of {@code kind}'s default value (a flat normal; no specular). */
	public static GpuTextureView defaultView(PbrKind kind) {
		return DEFAULTS.computeIfAbsent(kind, k -> {
			GpuTexture texture = RenderSystem.getDevice().createTexture(() -> "Aetherium Shaders default " + k.sampler(), 5, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
			try (NativeImage image = new NativeImage(1, 1, false)) {
				image.setPixel(0, 0, k.defaultArgb());
				RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image);
			}
			return RenderSystem.getDevice().createTextureView(texture);
		});
	}

	/** The material format the resource packs declare (read once, then at each resource reload). */
	public static @Nullable PbrFormat format() {
		if (!formatRead) {
			formatRead = true;
			format = PbrFormat.load(Minecraft.getInstance().getResourceManager());
		}
		return format;
	}

	/** Resource reload: the format is read again; a change reloads the pack (it is in the pack's macros). */
	public static void onResourceReload(ResourceManager resources) {
		// The same hook counts the pack-facing textureReloadCount.
		CapturedRenderingState.INSTANCE.incrementTextureReloadCount();
		PbrFormat previous = format();
		format = PbrFormat.load(resources);
		formatChanged |= !Objects.equals(previous, format);
	}

	/** Whether the format changed since the last call. */
	public static boolean consumeFormatChange() {
		boolean changed = formatChanged;
		formatChanged = false;
		return changed;
	}
}
