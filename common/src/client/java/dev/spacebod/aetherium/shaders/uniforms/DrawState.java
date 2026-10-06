package dev.spacebod.aetherium.shaders.uniforms;

import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Vector2i;
import org.joml.Vector4i;
import org.jspecify.annotations.Nullable;

/**
 * Per-draw state the pack's {@code gtextureSize}, {@code atlasSize}, {@code gtextureId} and {@code blendFunc} report:
 * the albedo texture of the feature draw being bound (terrain and draws without one report the block atlas), and the
 * bound program's blend. Render thread only.
 */
public final class DrawState {
	private static @Nullable GpuTextureView albedo;
	/** Small stable ids per texture, like the GL texture names packs were written for. */
	private static final Map<GpuTexture, Integer> IDS = new WeakHashMap<>();
	private static int nextId = 1;
	private static final Vector4i BLEND = new Vector4i();
	/** Values handed to the uniforms, reused (they copy what they are given). */
	private static final Vector2i SIZE = new Vector2i();
	private static final Vector2i ATLAS_SIZE = new Vector2i();
	/** The texture {@link #albedoId} and {@link #atlasSize} last looked at, and what they found for it. */
	private static @Nullable GpuTexture lastIdTexture;
	private static int lastId;
	private static @Nullable GpuTexture lastLabelTexture;
	private static boolean lastIsAtlas;

	private DrawState() {
	}

	/** A feature draw starts with this albedo ({@code Sampler0}), or ends (null). */
	public static void setAlbedo(@Nullable GpuTextureView view) {
		albedo = view;
	}

	public static Vector2i albedoSize() {
		GpuTextureView view = current();
		return view == null ? SIZE.zero() : SIZE.set(view.getWidth(0), view.getHeight(0));
	}

	/** The albedo's size when it is a texture atlas, else zero. */
	public static Vector2i atlasSize() {
		GpuTextureView view = current();
		if (view == null || albedo != null && !isAtlas(view.texture())) {
			return ATLAS_SIZE.zero();
		}
		return ATLAS_SIZE.set(view.getWidth(0), view.getHeight(0));
	}

	private static boolean isAtlas(GpuTexture texture) {
		if (texture != lastLabelTexture) {
			lastLabelTexture = texture;
			lastIsAtlas = texture.getLabel().contains("atlas");
		}
		return lastIsAtlas;
	}

	public static int albedoId() {
		GpuTextureView view = current();
		if (view == null) {
			return -1;
		}
		GpuTexture texture = view.texture();
		if (texture != lastIdTexture) {
			lastIdTexture = texture;
			lastId = IDS.computeIfAbsent(texture, t -> nextId++);
		}
		return lastId;
	}

	private static @Nullable GpuTextureView current() {
		if (albedo != null && !albedo.isClosed()) {
			return albedo;
		}
		var atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
		return atlas == null ? null : atlas.getTextureView();
	}

	/** The bound program's blend on its first output, as OpenGL factor enums (all zero without blending). */
	public static void setBlend(Optional<BlendFunction> blend) {
		if (blend.isEmpty()) {
			BLEND.set(0, 0, 0, 0);
			return;
		}
		BlendFunction f = blend.get();
		BLEND.set(gl(f.color().sourceFactor()), gl(f.color().destFactor()), gl(f.alpha().sourceFactor()), gl(f.alpha().destFactor()));
	}

	public static Vector4i blendFunc() {
		return BLEND;
	}

	private static int gl(BlendFactor factor) {
		return switch (factor) {
			case ZERO -> 0;
			case ONE -> 1;
			case SRC_COLOR -> 0x0300;
			case ONE_MINUS_SRC_COLOR -> 0x0301;
			case SRC_ALPHA -> 0x0302;
			case ONE_MINUS_SRC_ALPHA -> 0x0303;
			case DST_ALPHA -> 0x0304;
			case ONE_MINUS_DST_ALPHA -> 0x0305;
			case DST_COLOR -> 0x0306;
			case ONE_MINUS_DST_COLOR -> 0x0307;
			case SRC_ALPHA_SATURATE -> 0x0308;
			case CONSTANT_COLOR -> 0x8001;
			case ONE_MINUS_CONSTANT_COLOR -> 0x8002;
			case CONSTANT_ALPHA -> 0x8003;
			case ONE_MINUS_CONSTANT_ALPHA -> 0x8004;
		};
	}
}
