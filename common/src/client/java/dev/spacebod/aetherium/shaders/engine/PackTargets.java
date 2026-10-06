package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.BooleanSupplier;
import dev.spacebod.aetherium.shaders.gl.texture.InternalTextureFormat;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackRenderTargetDirectives;
import org.joml.Vector2i;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The pack's colour targets ({@code colortex0..N}), each a texture pair that flips: a pass samples the current side and
 * writes the other, after which the written targets flip. Only targets the pack references are created, and only
 * targets some full-screen pass writes or flips get the second texture ({@link EngineSwitches#FLIP_ONLY_DOUBLES}); the
 * others alias their one texture on both sides and are never flipped. At the end of a frame the side holding each
 * target's latest contents becomes its base side for the next frame (swapped, not copied), which is what buffers kept
 * across frames ({@code clear = false}) rely on. A kept target whose last flip this frame did not come from a pass
 * covering all of it (scaled viewport, blending, {@code discard}, or a flip with no write) is copied back to its base side
 * instead: its other side keeps texels from an older frame, and swapping would make it alternate between two images.
 * Also the noise texture and 1x1 placeholders.
 */
public final class PackTargets implements AutoCloseable {
	private static final int USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
			| GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC;
	/** {@link #chainRead}: reads sample level 0 only, the chain drawn level by level, or the blitted full chain. */
	private static final byte NO_CHAIN = 0;
	private static final byte SHORT_CHAIN = 1;
	private static final byte FULL_CHAIN = 2;
	private final Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> settings;
	private final int count;
	private final GpuTexture[][] textures;
	private final GpuTextureView[][] views;
	private final boolean[] used;
	/** Targets some pass reads mipmapped ({@code colortexNMipmapEnabled}): created with a full mip chain (to 1x1). */
	private final boolean[] mipmapped = new boolean[64];
	/** Per mipmapped target: its full chain's level count ({@link MipChains#fullLevels}). */
	private final int[] chainLevels;
	/** Per mipmapped target: the levels down to where the shorter side reaches 1 ({@link #mipLevels}). */
	private final int[] shortLevels;
	/** Full-chain views of mipmapped targets (sampling after a blitted chain). */
	private final GpuTextureView[][] chainViews;
	/** Views of the first {@link #shortLevels} levels (sampling after a chain drawn level by level). */
	private final GpuTextureView[][] shortChainViews;
	/** Per-level views of mipmapped targets down to {@link #shortLevels}, for drawing the chain: [target][side][level]. */
	private final GpuTextureView[][][] levelViews;
	/** Per target and side: which chain reads sample this frame ({@link #NO_CHAIN} until one was generated). */
	private final byte[][] chainRead;
	/** Per target and side: the blitted chain still matches level 0 (nothing wrote the target since it was generated). */
	private final boolean[][] chainCurrent;
	/** {@code size.buffer.colortexN}: per-target sizes (screen size when null or for targets world passes draw into). */
	private @Nullable PackDirectives sizes;
	private final boolean[] fullSize = new boolean[64];
	private final int[] targetWidth;
	private final int[] targetHeight;
	/** Per target: the side (0/1) that holds its contents at frame start. */
	private final int[] base;
	private final boolean[] flipped;
	/** Per target: the last flip this frame left the read side only partly written (see the class comment). */
	private final boolean[] partial;
	/** Per target: a full-screen pass writes or flips it, so it needs a second texture (null: every target). */
	private boolean @Nullable [] needsSecond;
	/** Per target: side 1 is a texture of its own (else side 1 aliases side 0). */
	private final boolean[] doubled;
	/**
	 * Per target: the frame-start clear of its base side, not recorded yet ({@link EngineSwitches#LOADOP_CLEARS}): it rides
	 * the load operation of the first render pass that attaches that side, or is paid before anything else touches it.
	 */
	private final Vector4f[] pendingClear;
	/** True while a render pass is open (a pending clear cannot be recorded then). */
	private BooleanSupplier passOpen = () -> false;
	private boolean warnedInPass;
	private final GpuFormat[] formats;
	private final GpuTexture[] snapshots;
	private final GpuTextureView[] snapshotViews;
	private boolean fullClearRequired = true;
	private int width;
	private int height;
	private @Nullable GpuTexture noise;
	private @Nullable GpuTextureView noiseView;
	private @Nullable GpuTexture white;
	private @Nullable GpuTextureView whiteView;
	private @Nullable GpuTexture overlayWhite;
	private @Nullable GpuTextureView overlayWhiteView;
	private @Nullable GpuTexture flatNormal;
	private @Nullable GpuTextureView flatNormalView;
	private @Nullable GpuTexture black;
	private @Nullable GpuTextureView blackView;

	/** @param used which targets the pack references (the others are never created) */
	public PackTargets(Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> settings, int count, boolean[] used) {
		this.settings = settings;
		this.count = count;
		this.used = used;
		this.base = new int[count];
		this.textures = new GpuTexture[count][2];
		this.views = new GpuTextureView[count][2];
		this.chainLevels = new int[count];
		this.shortLevels = new int[count];
		this.chainViews = new GpuTextureView[count][2];
		this.shortChainViews = new GpuTextureView[count][2];
		this.levelViews = new GpuTextureView[count][2][];
		this.chainRead = new byte[count][2];
		this.chainCurrent = new boolean[count][2];
		this.targetWidth = new int[count];
		this.targetHeight = new int[count];
		this.flipped = new boolean[count];
		this.partial = new boolean[count];
		this.doubled = new boolean[count];
		this.pendingClear = new Vector4f[count];
		this.formats = new GpuFormat[count];
		this.snapshots = new GpuTexture[count];
		this.snapshotViews = new GpuTextureView[count];
		for (int i = 0; i < count; i++) {
			PackRenderTargetDirectives.RenderTargetSettings s = settings.get(i);
			formats[i] = gpuFormat(s == null ? InternalTextureFormat.RGBA : s.getInternalFormat());
		}
	}

	public int count() {
		return count;
	}

	public GpuFormat format(int target) {
		return formats[target];
	}

	/**
	 * The pack's {@code size.buffer.colortexN} directives, before the first {@link #resize}. Targets in
	 * {@code worldAttachments} stay screen-sized: a world render pass needs every attachment the same size.
	 */
	public void setSizes(PackDirectives directives, int[] worldAttachments) {
		this.sizes = directives;
		for (int target : worldAttachments) {
			if (target >= 0 && target < fullSize.length) {
				fullSize[target] = true;
			}
		}
	}

	/** The targets some full-screen pass writes or flips ({@link FramePlan#writtenOrFlipped}); call before the first {@link #resize}. */
	public void setNeedsSecond(boolean[] targets) {
		this.needsSecond = targets;
	}

	/** Tells whether a render pass is open; a clear still pending then is reported instead of recorded. */
	void setPassOpen(BooleanSupplier open) {
		this.passOpen = open;
	}

	/** The size of {@code target}'s textures (its {@code size.buffer} override, else the screen size). */
	public int width(int target) {
		return targetWidth[target];
	}

	public int height(int target) {
		return targetHeight[target];
	}

	/** Marks {@code target} as read mipmapped by some pass; call before the first {@link #resize}. */
	public void markMipmapped(int target) {
		if (target >= 0 && target < count) {
			mipmapped[target] = true;
		}
	}

	/** Whether the pack references {@code target} (unreferenced targets have no textures). */
	public boolean used(int target) {
		return target >= 0 && target < count && used[target];
	}

	/** (Re)creates the textures when the screen size changed; true when it did. */
	public boolean resize(int width, int height) {
		if (width == this.width && height == this.height && this.width > 0) {
			return false;
		}
		closeTargets();
		this.width = width;
		this.height = height;
		this.fullClearRequired = true;
		GpuDevice device = RenderSystem.getDevice();
		boolean onlyFlipped = needsSecond != null && EngineSwitches.enabled(EngineSwitches.FLIP_ONLY_DOUBLES);
		long saved = 0;
		for (int i = 0; i < count; i++) {
			base[i] = 0;
			pendingClear[i] = null;
			if (!used[i]) {
				continue;
			}
			int w = width, h = height;
			if (sizes != null && !fullSize[i]) {
				Vector2i size = sizes.getTextureScaleOverride(i, width, height);
				w = Math.max(1, size.x);
				h = Math.max(1, size.y);
			}
			targetWidth[i] = w;
			targetHeight[i] = h;
			int levels = mipmapped[i] ? MipChains.fullLevels(w, h) : 1;
			chainLevels[i] = levels;
			shortLevels[i] = mipmapped[i] ? mipLevels(w, h) : 1;
			doubled[i] = !onlyFlipped || needsSecond[i];
			for (int side = 0; side < 2; side++) {
				if (side == 1 && !doubled[i]) {
					// Never written by a full-screen pass, never flipped: side 1 is side 0.
					textures[i][1] = textures[i][0];
					views[i][1] = views[i][0];
					chainViews[i][1] = chainViews[i][0];
					shortChainViews[i][1] = shortChainViews[i][0];
					levelViews[i][1] = levelViews[i][0];
					saved += bytes(formats[i], w, h, levels);
					continue;
				}
				int target = i;
				int s = side;
				GpuFormat format = formats[i];
				int fw = w, fh = h;
				textures[i][side] = TargetStorage.create(() -> device.createTexture(() -> "Aetherium Shaders colortex" + target + (s == 0 ? "" : " alt"), USAGE, format, fw, fh, 1, levels));
				// Level 0 alone: render attachments must be one level; reads before the chain exists see level 0 only.
				views[i][side] = device.createTextureView(textures[i][side], 0, 1);
				if (levels > 1) {
					chainViews[i][side] = device.createTextureView(textures[i][side]);
					shortChainViews[i][side] = shortLevels[i] == levels ? chainViews[i][side] : device.createTextureView(textures[i][side], 0, shortLevels[i]);
					levelViews[i][side] = new GpuTextureView[shortLevels[i]];
					levelViews[i][side][0] = views[i][side];
					for (int level = 1; level < shortLevels[i]; level++) {
						levelViews[i][side][level] = device.createTextureView(textures[i][side], level, 1);
					}
				}
			}
		}
		if (saved > 0) {
			AetheriumShaders.logger.info("colour targets: {} MiB saved by giving a second texture only to targets passes write or flip",
					String.format(Locale.ROOT, "%.1f", saved / (1024.0 * 1024.0)));
		}
		return true;
	}

	/** Bytes of one texture of {@code format}, {@code w} x {@code h} with {@code levels} mip levels. */
	private static long bytes(GpuFormat format, int w, int h, int levels) {
		long total = 0;
		for (int level = 0; level < levels; level++) {
			total += (long) Math.max(1, w >> level) * Math.max(1, h >> level) * format.blockSize();
		}
		return total;
	}

	/**
	 * Mip levels of a {@code w} x {@code h} chain drawn level by level: down to the level where the shorter side
	 * reaches 1. A level drawn by a render pass is never zero texels on either side, so such a chain ends at 2x1 or 1x2
	 * and longer; a blitted chain floors the sides at 1 and runs on to 1x1 ({@link MipChains#fullLevels}).
	 */
	static int mipLevels(int w, int h) {
		return 1 + (31 - Integer.numberOfLeadingZeros(Math.max(1, Math.min(w, h))));
	}

	private final Vector4f fallbackClear = new Vector4f();

	/**
	 * Start of a frame: nothing flipped; targets with clear=true (both sides of all of them after a resize) cleared to
	 * their clear colour. Only the base side is cleared: world passes draw into the current side, and full-screen passes
	 * read it and write the other. Defaults: colortex0 the fog colour with alpha 1, colortex1 opaque
	 * white, every other target transparent black. {@code loadOps}: the base-side clears are left pending
	 * ({@link #loadClear}, {@link #flushClears}); the clears of both sides after a resize are recorded now.
	 */
	public void beginFrame(Vector4f fogColor, boolean @Nullable [] overwritten, boolean loadOps) {
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		boolean full = fullClearRequired;
		fullClearRequired = false;
		for (int i = 0; i < count; i++) {
			flipped[i] = false;
			partial[i] = false;
			pendingClear[i] = null;
			chainRead[i][0] = NO_CHAIN;
			chainRead[i][1] = NO_CHAIN;
			chainCurrent[i][0] = false;
			chainCurrent[i][1] = false;
			if (!used[i]) {
				continue;
			}
			PackRenderTargetDirectives.RenderTargetSettings s = settings.get(i);
			if (!full && overwritten != null && overwritten[i]) {
				// The first pass to touch it this frame writes every texel without reading it.
				continue;
			}
			if (full || s == null || s.shouldClear()) {
				Vector4f fallback = i == 0 ? fallbackClear.set(fogColor.x, fogColor.y, fogColor.z, 1.0f) : i == 1 ? fallbackClear.set(1.0f) : fallbackClear.set(0.0f);
				Vector4f clear = s == null ? fallback : s.getClearColor().orElse(fallback);
				if (full) {
					encoder.clearColorTexture(textures[i][0], clear);
					if (doubled[i]) {
						encoder.clearColorTexture(textures[i][1], clear);
					}
				} else if (loadOps) {
					pendingClear[i] = new Vector4f(clear);
				} else {
					encoder.clearColorTexture(textures[i][base[i]], clear);
				}
			}
		}
	}

	/**
	 * The clear a render pass attaching {@code view} (level 0 of one side of {@code target}) carries as its load
	 * operation: the target's pending frame-start clear when {@code view} is its base side and the pass covers the whole
	 * target ({@code whole}); taken, so it is recorded once. A pending clear the pass cannot carry is recorded first.
	 */
	public Optional<Vector4fc> loadClear(int target, GpuTextureView view, boolean whole) {
		Vector4f clear = target >= 0 && target < count ? pendingClear[target] : null;
		if (clear == null || view != views[target][base[target]]) {
			// Nothing owed, or the pass draws the other side: the base side's clear stays pending.
			return Optional.empty();
		}
		if (whole) {
			pendingClear[target] = null;
			return Optional.of(clear);
		}
		flushClears();
		return Optional.empty();
	}

	/** Adds every pending clear to {@code into} (base side, level 0) and forgets them: the caller records them. */
	void drainClears(List<LoadOpClears.Pending> into) {
		for (int i = 0; i < count; i++) {
			Vector4f clear = pendingClear[i];
			if (clear != null) {
				pendingClear[i] = null;
				into.add(new LoadOpClears.Pending(views[i][base[i]], clear));
			}
		}
	}

	/** Records every pending clear now (one empty render pass per size). Not inside a render pass. */
	public void flushClears() {
		if (passOpen.getAsBoolean()) {
			if (!warnedInPass) {
				warnedInPass = true;
				AetheriumShaders.logger.warn("a colour target's clear was still pending inside a render pass; it is recorded after the pass");
			}
			return;
		}
		List<LoadOpClears.Pending> pending = new ArrayList<>();
		drainClears(pending);
		LoadOpClears.flush(pending);
	}

	/** Before the side of {@code target} passes read is read: when that is its base side still owed its clear, every pending clear is recorded. */
	private void beforeRead(int target) {
		if (pendingClear[target] != null && readSide(target) == base[target]) {
			flushClears();
		}
	}

	/**
	 * A copy of the current side of {@code target}, taken now: what a program samples while the same target is a colour
	 * attachment of its render pass (Vulkan cannot sample an image the pass is rendering into).
	 */
	public GpuTextureView snapshot(int target) {
		snapshotView(target);
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(read(target), snapshots[target], 0, 0, 0, 0, 0, targetWidth[target], targetHeight[target]);
		return snapshotViews[target];
	}

	/** The snapshot texture of {@code target} (created on first use), without copying into it: a caller clears it instead. */
	GpuTextureView snapshotView(int target) {
		if (snapshots[target] == null) {
			snapshots[target] = RenderSystem.getDevice().createTexture(() -> "Aetherium Shaders colortex" + target + " snapshot",
					GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT, formats[target],
					targetWidth[target], targetHeight[target], 1, 1);
			snapshotViews[target] = RenderSystem.getDevice().createTextureView(snapshots[target]);
		}
		return snapshotViews[target];
	}

	private int readSide(int target) {
		return flipped[target] ? 1 - base[target] : base[target];
	}

	/** The side passes read (the base side until the target has been written an odd number of times this frame). */
	public GpuTexture read(int target) {
		beforeRead(target);
		return textures[target][readSide(target)];
	}

	public GpuTextureView readView(int target) {
		beforeRead(target);
		int side = readSide(target);
		return switch (chainRead[target][side]) {
			case FULL_CHAIN -> chainViews[target][side];
			case SHORT_CHAIN -> shortChainViews[target][side];
			default -> views[target][side];
		};
	}

	/**
	 * The current side's level 0, for world passes that draw into the current side. Never the mip chain: a render
	 * attachment must be a single level, and the chain view is what {@link #readView} returns once mips were built.
	 */
	public GpuTextureView attachmentView(int target) {
		return views[target][readSide(target)];
	}

	/** Whether reads of {@code target} sample its mip chain now (generated earlier this frame for a pass). */
	public boolean readsMipmapped(int target) {
		return used[target] && chainRead[target][readSide(target)] != NO_CHAIN;
	}

	/**
	 * Generates the mip chain of the side passes read, then reads of that side sample mipmapped for the rest of the
	 * frame. {@code blitted} ({@link EngineSwitches#BLIT_MIPS}): the full chain by {@link MipChains}, skipped when nothing
	 * wrote the target since its chain was generated; else (or when the device cannot blit the format) a render pass per
	 * level, each a linear 2x2 downsample of the one above (the box filter OpenGL's mipmap generation uses), down to the
	 * level where the shorter side reaches 1.
	 */
	public void generateMipmaps(int target, BlitPass blit, GpuBuffer quad, boolean blitted) {
		if (target < 0 || target >= count || !used[target]) {
			return;
		}
		int side = readSide(target);
		if (chainLevels[target] <= 1) {
			return;
		}
		beforeRead(target);
		if (blitted) {
			if (chainCurrent[target][side] && chainRead[target][side] == FULL_CHAIN) {
				return;
			}
			if (MipChains.generate(textures[target][side], chainLevels[target])) {
				chainRead[target][side] = FULL_CHAIN;
				chainCurrent[target][side] = true;
				return;
			}
		}
		GpuTextureView[] levels = levelViews[target][side];
		for (int level = 1; level < levels.length; level++) {
			blit.blitLinear(levels[level - 1], levels[level], formats[target], quad);
		}
		chainRead[target][side] = SHORT_CHAIN;
		chainCurrent[target][side] = false;
	}

	/** Something wrote {@code target} (either side): a blitted chain of it is generated again before its next mipmapped read. */
	public void markWritten(int target) {
		if (target >= 0 && target < count) {
			chainCurrent[target][0] = false;
			chainCurrent[target][1] = false;
		}
	}

	/** Something may have written any target (compute programs, image stores). */
	public void markAllWritten() {
		for (int i = 0; i < count; i++) {
			chainCurrent[i][0] = false;
			chainCurrent[i][1] = false;
		}
	}

	/** The side a pass writing this target renders into. */
	public GpuTextureView writeView(int target) {
		return views[target][1 - readSide(target)];
	}

	/**
	 * End of the frame: the side each target was left on becomes its base side, so the next frame reads this frame's
	 * result. A kept target whose last flip left that side partly written is copied back to its base side instead.
	 * A clear still pending on a side that becomes the other side (the target flipped) is recorded first, so that side
	 * holds what a standalone clear left in it; one on a side that stays the base side is dropped: nothing read or wrote
	 * that side this frame, and the next frame owes it its clear again (or overwrites it whole first).
	 */
	public void endFrame() {
		boolean flush = false;
		for (int i = 0; i < count; i++) {
			if (pendingClear[i] != null && readSide(i) == base[i]) {
				pendingClear[i] = null;
			}
			flush |= pendingClear[i] != null;
		}
		if (flush) {
			flushClears();
		}
		CommandEncoder encoder = null;
		for (int i = 0; i < count; i++) {
			int side = readSide(i);
			if (side != base[i] && partial[i] && used[i] && kept(i)) {
				if (encoder == null) {
					encoder = RenderSystem.getDevice().createCommandEncoder();
				}
				encoder.copyTextureToTexture(textures[i][side], textures[i][base[i]], 0, 0, 0, 0, 0, targetWidth[i], targetHeight[i]);
			} else {
				base[i] = side;
			}
			flipped[i] = false;
			partial[i] = false;
		}
	}

	/** Whether {@code target} keeps its contents across frames ({@code colortexNClear = false}). */
	private boolean kept(int target) {
		PackRenderTargetDirectives.RenderTargetSettings s = settings.get(target);
		return s != null && !s.shouldClear();
	}

	/**
	 * Flips {@code target}; {@code whole}: the pass that just wrote it covered every texel of the side it wrote (false for
	 * a flip with no write).
	 */
	public void flip(int target, boolean whole) {
		if (!doubled[target]) {
			// Not expected: the frame plan names every target a pass writes or flips. Reading and writing one texture
			// would be wrong, so the target keeps reading its only side.
			if (!warnedFlip) {
				warnedFlip = true;
				AetheriumShaders.logger.warn("colortex{} flipped without a second texture; kept on its only side", target);
			}
			return;
		}
		flipped[target] = !flipped[target];
		partial[target] = !whole;
	}

	private boolean warnedFlip;

	/**
	 * The pack's noisetex: {@code size}x{@code size} RGBA8 ({@code noiseTextureResolution}). The pixel at (x, y) takes the
	 * (x * size + y)th {@code nextInt()} of {@code new Random(0)} as ARGB with alpha forced to 255, stored as R, G, B, A
	 * bytes: the pattern packs index into for dithering and noise.
	 */
	public GpuTextureView noise(int size) {
		if (noise == null || noise.getWidth(0) != size) {
			if (noise != null) {
				noiseView.close();
				noise.close();
			}
			noise = RenderSystem.getDevice().createTexture(() -> "Aetherium Shaders noisetex", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
					GpuFormat.RGBA8_UNORM, size, size, 1, 1);
			noiseView = RenderSystem.getDevice().createTextureView(noise);
			ByteBuffer pixels = MemoryUtil.memAlloc(size * size * 4);
			try {
				Random random = new Random(0);
				for (int x = 0; x < size; x++) {
					for (int y = 0; y < size; y++) {
						int argb = random.nextInt() | 0xFF000000;
						int offset = (x + y * size) * 4;
						pixels.put(offset, (byte) (argb >> 16));
						pixels.put(offset + 1, (byte) (argb >> 8));
						pixels.put(offset + 2, (byte) argb);
						pixels.put(offset + 3, (byte) 0xFF);
					}
				}
				RenderSystem.getDevice().createCommandEncoder().writeToTexture(noise, pixels, 0, 0, 0, 0, size, size);
			} finally {
				MemoryUtil.memFree(pixels);
			}
		}
		return noiseView;
	}

	/** 1x1 opaque white: the default for samplers the engine has no data for. */
	public GpuTextureView white() {
		if (whiteView == null) {
			white = solid("white", 0xFFFFFFFF);
			whiteView = RenderSystem.getDevice().createTextureView(white);
		}
		return whiteView;
	}

	/**
	 * 32x32 opaque white: the overlay ({@code Sampler1}) for draws whose render type binds none (eyes, for one). Packs
	 * fetch the overlay texel at the vertex's overlay coordinates (up to 15, 15 with no overlay: 0, 10), which must land
	 * inside the texture: out of range a fetch reads zero, and {@code entityColor} becomes opaque black.
	 */
	public GpuTextureView overlayWhite() {
		if (overlayWhiteView == null) {
			overlayWhite = solid("overlay white", 0xFFFFFFFF, 32);
			overlayWhiteView = RenderSystem.getDevice().createTextureView(overlayWhite);
		}
		return overlayWhiteView;
	}

	/** 1x1 flat tangent-space normal (0.5, 0.5, 1, 1): the {@code normals} default when the pack has no normal atlas. */
	public GpuTextureView flatNormal() {
		if (flatNormalView == null) {
			flatNormal = solid("flat normal", 0xFFFF8080);
			flatNormalView = RenderSystem.getDevice().createTextureView(flatNormal);
		}
		return flatNormalView;
	}

	/** 1x1 transparent black: the {@code specular} default (no reflectance, no emission). */
	public GpuTextureView transparentBlack() {
		if (blackView == null) {
			black = solid("transparent black", 0);
			blackView = RenderSystem.getDevice().createTextureView(black);
		}
		return blackView;
	}

	/** A 1x1 RGBA8 texture holding {@code abgr} (R in the low byte). */
	private static GpuTexture solid(String name, int abgr) {
		return solid(name, abgr, 1);
	}

	/** A size x size RGBA8 texture filled with {@code abgr} (R in the low byte). */
	private static GpuTexture solid(String name, int abgr, int size) {
		GpuTexture texture = RenderSystem.getDevice().createTexture(() -> "Aetherium Shaders " + name,
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, size, size, 1, 1);
		ByteBuffer pixels = MemoryUtil.memAlloc(4 * size * size);
		try {
			for (int i = 0; i < size * size; i++) {
				pixels.putInt(i * 4, abgr);
			}
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, pixels, 0, 0, 0, 0, size, size);
		} finally {
			MemoryUtil.memFree(pixels);
		}
		return texture;
	}

	/**
	 * Pack colortex formats as vanilla GpuFormats. Three-channel formats are promoted to four channels: most Vulkan
	 * devices cannot render to RGB8/RGB16F/RGB32F, and a pack cannot observe the extra channel.
	 */
	public static GpuFormat gpuFormat(InternalTextureFormat format) {
		return switch (format) {
			case R8 -> GpuFormat.R8_UNORM;
			case RG8 -> GpuFormat.RG8_UNORM;
			case R8_SNORM -> GpuFormat.R8_SNORM;
			case RG8_SNORM -> GpuFormat.RG8_SNORM;
			case RGB8_SNORM, RGBA8_SNORM -> GpuFormat.RGBA8_SNORM;
			case R16 -> GpuFormat.R16_UNORM;
			case RG16 -> GpuFormat.RG16_UNORM;
			case RGB16, RGBA16 -> GpuFormat.RGBA16_UNORM;
			case R16_SNORM -> GpuFormat.R16_SNORM;
			case RG16_SNORM -> GpuFormat.RG16_SNORM;
			case RGB16_SNORM, RGBA16_SNORM -> GpuFormat.RGBA16_SNORM;
			case R16F -> GpuFormat.R16_FLOAT;
			case RG16F -> GpuFormat.RG16_FLOAT;
			case RGB16F, RGBA16F, RGB9_E5 -> GpuFormat.RGBA16_FLOAT;
			case R32F -> GpuFormat.R32_FLOAT;
			case RG32F -> GpuFormat.RG32_FLOAT;
			case RGB32F, RGBA32F -> GpuFormat.RGBA32_FLOAT;
			case R8I -> GpuFormat.R8_SINT;
			case RG8I -> GpuFormat.RG8_SINT;
			case RGB8I, RGBA8I -> GpuFormat.RGBA8_SINT;
			case R8UI -> GpuFormat.R8_UINT;
			case RG8UI -> GpuFormat.RG8_UINT;
			case RGB8UI, RGBA8UI -> GpuFormat.RGBA8_UINT;
			case R16I -> GpuFormat.R16_SINT;
			case RG16I -> GpuFormat.RG16_SINT;
			case RGB16I, RGBA16I -> GpuFormat.RGBA16_SINT;
			case R16UI -> GpuFormat.R16_UINT;
			case RG16UI -> GpuFormat.RG16_UINT;
			case RGB16UI, RGBA16UI -> GpuFormat.RGBA16_UINT;
			case R32I -> GpuFormat.R32_SINT;
			case RG32I -> GpuFormat.RG32_SINT;
			case RGB32I, RGBA32I -> GpuFormat.RGBA32_SINT;
			case R32UI -> GpuFormat.R32_UINT;
			case RG32UI -> GpuFormat.RG32_UINT;
			case RGB32UI, RGBA32UI -> GpuFormat.RGBA32_UINT;
			case RGB10_A2 -> GpuFormat.RGB10A2_UNORM;
			case RGB10_A2UI -> GpuFormat.RGB10A2_UINT;
			case R11F_G11F_B10F -> GpuFormat.RG11B10_FLOAT;
			default -> GpuFormat.RGBA8_UNORM; // RGBA, RGBA8, RGB8 and the legacy packed 8-bit formats
		};
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	private void closeTargets() {
		for (int i = 0; i < count; i++) {
			if (snapshotViews[i] != null) {
				snapshotViews[i].close();
				snapshots[i].close();
				snapshotViews[i] = null;
				snapshots[i] = null;
			}
			for (int side = 0; side < 2; side++) {
				// Side 1 of a target with one texture aliases side 0: closed once, with side 0.
				boolean own = side == 0 || textures[i][1] != textures[i][0];
				if (own && levelViews[i][side] != null) {
					for (int level = 1; level < levelViews[i][side].length; level++) {
						levelViews[i][side][level].close();
					}
				}
				levelViews[i][side] = null;
				if (own && shortChainViews[i][side] != null && shortChainViews[i][side] != chainViews[i][side]) {
					shortChainViews[i][side].close();
				}
				shortChainViews[i][side] = null;
				if (own && chainViews[i][side] != null) {
					chainViews[i][side].close();
				}
				chainViews[i][side] = null;
				chainRead[i][side] = NO_CHAIN;
				chainCurrent[i][side] = false;
				if (own && views[i][side] != null) {
					views[i][side].close();
					textures[i][side].close();
				}
			}
			views[i][0] = views[i][1] = null;
			textures[i][0] = textures[i][1] = null;
			pendingClear[i] = null;
		}
	}

	@Override
	public void close() {
		closeTargets();
		if (noise != null) {
			noiseView.close();
			noise.close();
		}
		for (AutoCloseable c : new AutoCloseable[]{whiteView, white, overlayWhiteView, overlayWhite, flatNormalView, flatNormal, blackView, black}) {
			if (c != null) {
				try {
					c.close();
				} catch (Exception ignored) {
				}
			}
		}
	}
}
