package dev.spacebod.aetherium.shaders.pbr;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * One material map ({@link PbrKind}) of a texture atlas: the same size and mip levels, each material sprite where its
 * colour sprite is, the default value everywhere else; animated sprites animate with vanilla's sprite blit, ticked with
 * their atlas and started where the colour atlas's animations are (it may have run for a while before the map is
 * built). Uploaded the way vanilla uploads its atlases (each sprite drawn into every mip level).
 */
public final class PbrAtlas extends AbstractTexture {
	private final Identifier location;
	private final PbrKind kind;
	private final List<TextureAtlasSprite> sprites = new ArrayList<>();
	private List<SpriteContents.AnimationState> animations = List.of();
	/**
	 * Per animation: ticks to run before the first draw (to the start of the frame showing at the colour atlas's tick
	 * count), and after it (to that tick). The draw in between shows the right frame.
	 */
	private int[] ticksBeforeDraw = new int[0];
	private int[] ticksAfterDraw = new int[0];
	private int width;
	private int height;
	private int maxMipLevel;
	private int mipLevelCount;
	private GpuTextureView[] mipViews = new GpuTextureView[0];
	private @Nullable GpuBuffer spriteUbos;

	PbrAtlas(Identifier atlas, PbrKind kind) {
		this.kind = kind;
		this.location = Identifier.fromNamespaceAndPath(atlas.getNamespace(), atlas.getPath().replace(".png", "") + kind.suffix() + ".png");
	}

	void addSprite(TextureAtlasSprite sprite) {
		sprites.add(sprite);
	}

	/**
	 * Uploads the sprites; false (logged) when it failed.
	 *
	 * @param animationTicks the animation ticks the colour atlas has run since its upload
	 */
	boolean tryUpload(int atlasWidth, int atlasHeight, int mipLevel, long animationTicks) {
		try {
			upload(atlasWidth, atlasHeight, mipLevel, animationTicks);
			return true;
		} catch (RuntimeException e) {
			AetheriumShaders.logger.error("could not build {}", location, e);
			return false;
		}
	}

	private void upload(int atlasWidth, int atlasHeight, int mipLevel, long animationTicks) {
		GpuDevice device = RenderSystem.getDevice();
		AetheriumShaders.logger.info("Created: {}x{}x{} {}-atlas", atlasWidth, atlasHeight, mipLevel, location);
		texture = device.createTexture(location::toString, 15, GpuFormat.RGBA8_UNORM, atlasWidth, atlasHeight, 1, mipLevel + 1);
		textureView = device.createTextureView(texture);
		width = atlasWidth;
		height = atlasHeight;
		maxMipLevel = mipLevel;
		mipLevelCount = mipLevel + 1;
		mipViews = new GpuTextureView[mipLevelCount];
		for (int level = 0; level <= maxMipLevel; level++) {
			mipViews[level] = device.createTextureView(texture, level, 1);
		}
		sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

		int spriteUboSize = Mth.roundToward(SpriteContents.UBO_SIZE, device.getDeviceInfo().limits().minUniformOffsetAlignment());
		int uboBlockSize = spriteUboSize * mipLevelCount;
		List<TextureAtlasSprite> animated = sprites.stream().filter(TextureAtlasSprite::isAnimated).toList();
		if (!animated.isEmpty()) {
			ByteBuffer buffer = MemoryUtil.memAlloc(animated.size() * uboBlockSize);
			for (int i = 0; i < animated.size(); i++) {
				animated.get(i).uploadSpriteUbo(buffer, i * uboBlockSize, maxMipLevel, width, height, spriteUboSize);
			}
			spriteUbos = device.createBuffer(() -> location + " sprite UBOs", 128, buffer);
			MemoryUtil.memFree(buffer);
			List<SpriteContents.AnimationState> states = new ArrayList<>();
			int[] before = new int[animated.size()];
			int[] after = new int[animated.size()];
			for (int i = 0; i < animated.size(); i++) {
				TextureAtlasSprite sprite = animated.get(i);
				SpriteContents.AnimationState state = sprite.createAnimationState(spriteUbos.slice(i * uboBlockSize, uboBlockSize), spriteUboSize);
				if (state != null) {
					int[][] timeline = sprite.contents() instanceof PbrLoader.PbrSpriteContents contents ? contents.timeline : null;
					if (timeline != null) {
						catchUp(timeline, animationTicks, before, after, states.size());
					}
					states.add(state);
				}
			}
			animations = List.copyOf(states);
			ticksBeforeDraw = Arrays.copyOf(before, states.size());
			ticksAfterDraw = Arrays.copyOf(after, states.size());
		}
		uploadInitialContents(spriteUboSize, uboBlockSize);
	}

	/** Every mip level cleared to the default value, then each static sprite drawn into it (animated ones follow). */
	private void uploadInitialContents(int spriteUboSize, int uboBlockSize) {
		GpuDevice device = RenderSystem.getDevice();
		GpuSampler spriteSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true);
		List<TextureAtlasSprite> statics = sprites.stream().filter(s -> !s.isAnimated()).toList();
		List<GpuTextureView[]> scratch = new ArrayList<>();
		ByteBuffer buffer = MemoryUtil.memAlloc(Math.max(1, statics.size()) * uboBlockSize);
		for (int i = 0; i < statics.size(); i++) {
			TextureAtlasSprite sprite = statics.get(i);
			sprite.uploadSpriteUbo(buffer, i * uboBlockSize, maxMipLevel, width, height, spriteUboSize);
			GpuTexture spriteTexture = device.createTexture(() -> sprite.contents().name().toString(), 5, GpuFormat.RGBA8_UNORM,
					sprite.contents().width(), sprite.contents().height(), 1, mipLevelCount);
			GpuTextureView[] views = new GpuTextureView[mipLevelCount];
			for (int level = 0; level <= maxMipLevel; level++) {
				sprite.uploadFirstFrame(spriteTexture, level);
				views[level] = device.createTextureView(spriteTexture);
			}
			scratch.add(views);
		}
		try (GpuBuffer ubo = device.createBuffer(() -> "SpriteAnimationInfo", 128, buffer)) {
			for (int level = 0; level < mipLevelCount; level++) {
				try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Animate " + location, mipViews[level],
						Optional.of(kind.defaultColour()))) {
					RenderSystem.bindDefaultUniforms(pass);
					pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.ANIMATE_SPRITE_BLIT));
					for (int i = 0; i < statics.size(); i++) {
						pass.setUniform("Sprite", scratch.get(i)[level], spriteSampler);
						pass.setUniform("SpriteAnimationInfo", ubo.slice(i * uboBlockSize + level * spriteUboSize, SpriteContents.UBO_SIZE));
						pass.draw(6, 1, 0, 0);
					}
				}
			}
		}
		for (GpuTextureView[] views : scratch) {
			for (GpuTextureView view : views) {
				view.close();
				view.texture().close();
			}
		}
		MemoryUtil.memFree(buffer);
		for (int i = 0; i < animations.size(); i++) {
			tick(animations.get(i), ticksBeforeDraw[i]);
		}
		drawAnimationFrames();
		for (int i = 0; i < animations.size(); i++) {
			tick(animations.get(i), ticksAfterDraw[i]);
		}
	}

	/**
	 * Where an animation played from its start stands after {@code ticks}: {@code before[slot]} ticks to the start of
	 * the run of frames showing the same image as the current one (the tick that marks it to be drawn), then
	 * {@code after[slot]} ticks within it.
	 *
	 * @param timeline {frame indices, frame times}, as {@link PbrLoader} reads them
	 */
	private static void catchUp(int[][] timeline, long ticks, int[] before, int[] after, int slot) {
		int[] indices = timeline[0];
		int[] times = timeline[1];
		long cycle = 0;
		for (int time : times) {
			cycle += time;
		}
		int position = (int) (ticks % cycle);
		int frame = 0;
		int start = 0;
		while (position >= start + times[frame]) {
			start += times[frame];
			frame++;
		}
		while (frame > 0 && indices[frame - 1] == indices[frame]) {
			frame--;
			start -= times[frame];
		}
		before[slot] = start;
		after[slot] = position - start;
	}

	private static void tick(SpriteContents.AnimationState state, int ticks) {
		for (int i = 0; i < ticks; i++) {
			state.tick();
		}
	}

	/** With the colour atlas's own tick: advances the animated sprites and draws the frames that changed. */
	void cycleAnimationFrames() {
		if (texture == null) {
			return;
		}
		for (SpriteContents.AnimationState state : animations) {
			state.tick();
		}
		drawAnimationFrames();
	}

	private void drawAnimationFrames() {
		if (animations.stream().noneMatch(SpriteContents.AnimationState::needsToDraw)) {
			return;
		}
		for (int level = 0; level <= maxMipLevel; level++) {
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Animate " + location, mipViews[level],
					Optional.empty())) {
				RenderSystem.bindDefaultUniforms(pass);
				for (SpriteContents.AnimationState state : animations) {
					if (state.needsToDraw()) {
						state.drawToAtlas(pass, state.getDrawUbo(level));
					}
				}
			}
		}
	}

	@Override
	public void close() {
		super.close();
		for (GpuTextureView view : mipViews) {
			view.close();
		}
		mipViews = new GpuTextureView[0];
		animations.forEach(SpriteContents.AnimationState::close);
		animations = List.of();
		sprites.forEach(TextureAtlasSprite::close);
		sprites.clear();
		if (spriteUbos != null) {
			spriteUbos.close();
			spriteUbos = null;
		}
	}
}
