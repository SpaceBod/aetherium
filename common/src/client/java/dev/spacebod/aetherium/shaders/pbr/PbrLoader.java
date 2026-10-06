package dev.spacebod.aetherium.shaders.pbr;

import com.mojang.blaze3d.platform.NativeImage;
import dev.spacebod.aetherium.client.mixin.access.TextureAtlasAccessor;
import dev.spacebod.aetherium.client.mixin.access.TextureAtlasSpriteAccessor;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Finds and loads the material maps of one colour texture: for an atlas, every sprite's {@code <sprite>_n.png} /
 * {@code <sprite>_s.png} (scaled to the sprite's size, animated like it) laid out in a {@link PbrAtlas}; for a single
 * texture (entities, armour), its {@code <texture>_n.png} / {@code _s.png}.
 */
public final class PbrLoader {
	private PbrLoader() {
	}

	/**
	 * The maps found for {@code texture}, by kind (empty: none).
	 *
	 * @param animationTicks for an atlas, the animation ticks it has run since its upload: animated maps start at the
	 *                       same point of their animation as its sprites are now
	 */
	static Map<PbrKind, AbstractTexture> load(AbstractTexture texture, ResourceManager resources, long animationTicks) {
		Map<PbrKind, AbstractTexture> out = new EnumMap<>(PbrKind.class);
		if (texture instanceof TextureAtlas atlas) {
			loadAtlas(atlas, resources, animationTicks, out);
		} else if (texture instanceof SimpleTexture simple) {
			for (PbrKind kind : PbrKind.values()) {
				AbstractTexture map = loadSimple(simple, resources, kind);
				if (map != null) {
					out.put(kind, map);
				}
			}
		}
		return out;
	}

	private static @Nullable AbstractTexture loadSimple(ReloadableTexture texture, ResourceManager resources, PbrKind kind) {
		Identifier location = texture.resourceId().withPath(kind::withSuffix);
		if (resources.getResource(location).isEmpty()) {
			return null;
		}
		SimpleTexture map = new SimpleTexture(location);
		try {
			TextureContents contents = map.loadContents(resources);
			map.apply(contents);
			return map;
		} catch (IOException | RuntimeException e) {
			AetheriumShaders.logger.warn("could not load {}", location, e);
			map.close();
			return null;
		}
	}

	private static void loadAtlas(TextureAtlas atlas, ResourceManager resources, long animationTicks, Map<PbrKind, AbstractTexture> out) {
		TextureAtlasAccessor access = (TextureAtlasAccessor) atlas;
		int width = access.aetherium$width();
		int height = access.aetherium$height();
		int maxMipLevel = access.aetherium$maxMipLevel();
		Map<PbrKind, PbrAtlas> maps = new EnumMap<>(PbrKind.class);
		for (TextureAtlasSprite sprite : access.aetherium$sprites().values()) {
			for (PbrKind kind : PbrKind.values()) {
				TextureAtlasSprite map = loadSprite(sprite, resources, width, height, maxMipLevel, kind);
				if (map != null) {
					maps.computeIfAbsent(kind, k -> new PbrAtlas(atlas.location(), k)).addSprite(map);
				}
			}
		}
		maps.forEach((kind, map) -> {
			if (map.tryUpload(width, height, maxMipLevel, animationTicks)) {
				out.put(kind, map);
			} else {
				map.close();
			}
		});
	}

	/** {@code minecraft:block/stone} -> {@code minecraft:textures/block/stone_n.png}. */
	private static Identifier imageOf(Identifier sprite, PbrKind kind) {
		String path = kind.withSuffix(sprite.getPath());
		// Sprites some resource-pack features place outside textures/.
		if (path.startsWith("optifine/cit/")) {
			return Identifier.fromNamespaceAndPath(sprite.getNamespace(), path + ".png");
		}
		return Identifier.fromNamespaceAndPath(sprite.getNamespace(), "textures/" + path + ".png");
	}

	/** The material sprite for {@code sprite}, at its place in the atlas; null when the resource packs have none. */
	private static @Nullable TextureAtlasSprite loadSprite(TextureAtlasSprite sprite, ResourceManager resources, int atlasWidth, int atlasHeight,
			int maxMipLevel, PbrKind kind) {
		Identifier name = sprite.contents().name();
		Identifier location = imageOf(name, kind);
		Optional<Resource> resource = resources.getResource(location);
		if (resource.isEmpty()) {
			return null;
		}
		ResourceMetadata metadata;
		try {
			metadata = resource.get().metadata();
		} catch (Exception e) {
			AetheriumShaders.logger.error("Unable to parse metadata from {}", location, e);
			return null;
		}
		NativeImage image;
		try (InputStream stream = resource.get().open()) {
			image = NativeImage.read(stream);
		} catch (IOException e) {
			AetheriumShaders.logger.error("Using missing texture, unable to load {}", location, e);
			return null;
		}
		int imageWidth = image.getWidth();
		int imageHeight = image.getHeight();
		Optional<AnimationMetadataSection> animation = metadata.getSection(AnimationMetadataSection.TYPE);
		FrameSize frame = animation.map(a -> a.calculateFrameSize(imageWidth, imageHeight)).orElse(new FrameSize(imageWidth, imageHeight));
		if (!Mth.isMultipleOf(imageWidth, frame.width()) || !Mth.isMultipleOf(imageHeight, frame.height())) {
			AetheriumShaders.logger.error("Image {} size {},{} is not multiple of frame size {},{}", location, imageWidth, imageHeight, frame.width(), frame.height());
			image.close();
			return null;
		}
		int frameCount = imageWidth / frame.width() * (imageHeight / frame.height());
		int[][] timeline = animation.map(a -> timeline(a, frameCount)).orElse(null);
		// A map of another resolution than its colour texture is scaled to it (exact multiples by nearest texel).
		int targetWidth = sprite.contents().width();
		int targetHeight = sprite.contents().height();
		if (frame.width() != targetWidth || frame.height() != targetHeight) {
			int scaledWidth = imageWidth / frame.width() * targetWidth;
			int scaledHeight = imageHeight / frame.height() * targetHeight;
			NativeImage scaled = scaledWidth % imageWidth == 0 && scaledHeight % imageHeight == 0
					? PbrImages.scaleNearest(image, scaledWidth, scaledHeight)
					: PbrImages.scaleBilinear(image, scaledWidth, scaledHeight);
			image.close();
			image = scaled;
			frame = new FrameSize(targetWidth, targetHeight);
			FrameSize size = frame;
			animation = animation.map(a -> new AnimationMetadataSection(a.frames(), a.frameWidth().map(w -> size.width()),
					a.frameHeight().map(h -> size.height()), a.defaultFrameTime(), a.interpolatedFrames()));
		}
		Identifier mapName = Identifier.fromNamespaceAndPath(name.getNamespace(), name.getPath() + kind.suffix());
		PbrSpriteContents contents = new PbrSpriteContents(mapName, frame, image, animation, metadata.getSection(TextureMetadataSection.TYPE), kind, timeline);
		contents.increaseMipLevel(maxMipLevel);
		return new Sprite(mapName, contents, atlasWidth, atlasHeight, sprite.getX(), sprite.getY(), ((TextureAtlasSpriteAccessor) sprite).aetherium$padding());
	}

	/**
	 * The animation's frames as vanilla plays them: {@code {frame indices, frame times in ticks}}, or null when vanilla
	 * would not animate it (fewer than two frames, or a frame it rejects).
	 */
	private static int @Nullable [][] timeline(AnimationMetadataSection animation, int frameCount) {
		int[] indices;
		int[] times;
		if (animation.frames().isEmpty()) {
			indices = new int[frameCount];
			times = new int[frameCount];
			for (int i = 0; i < frameCount; i++) {
				indices[i] = i;
				times[i] = animation.defaultFrameTime();
			}
		} else {
			List<AnimationFrame> frames = animation.frames().get();
			indices = new int[frames.size()];
			times = new int[frames.size()];
			for (int i = 0; i < frames.size(); i++) {
				indices[i] = frames.get(i).index();
				times[i] = frames.get(i).timeOr(animation.defaultFrameTime());
			}
		}
		if (indices.length < 2) {
			return null;
		}
		for (int i = 0; i < indices.length; i++) {
			if (times[i] <= 0 || indices[i] < 0 || indices[i] >= frameCount) {
				return null;
			}
		}
		return new int[][]{indices, times};
	}

	/** A material sprite's image: its mip levels follow the material format's rules ({@code SpriteContentsMixin}). */
	public static final class PbrSpriteContents extends SpriteContents {
		final PbrKind kind;
		/** {@link #timeline}: the frames its animation plays, or null when it is not animated. */
		final int @Nullable [][] timeline;

		PbrSpriteContents(Identifier name, FrameSize size, NativeImage image, Optional<AnimationMetadataSection> animation,
				Optional<TextureMetadataSection> texture, PbrKind kind, int @Nullable [][] timeline) {
			super(name, size, image, animation, List.of(), texture);
			this.kind = kind;
			this.timeline = timeline;
		}

		/** Mip levels 1..{@code maxLevel} by the format's rules for this kind (plain averages without a format). */
		public NativeImage[] mipLevels(NativeImage[] levels, int maxLevel) {
			PbrFormat format = PbrTextures.format();
			return PbrImages.mipLevels(levels, maxLevel, format == null ? PbrImages.LINEAR : format.mipRules(kind));
		}
	}

	/** A material sprite placed where its colour sprite is. */
	private static final class Sprite extends TextureAtlasSprite {
		Sprite(Identifier location, SpriteContents contents, int atlasWidth, int atlasHeight, int x, int y, int padding) {
			super(location, contents, atlasWidth, atlasHeight, x, y, padding);
		}
	}
}
