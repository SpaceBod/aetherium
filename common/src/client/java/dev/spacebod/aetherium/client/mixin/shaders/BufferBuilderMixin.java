package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.spacebod.aetherium.shaders.engine.EntityVertexFormats;
import dev.spacebod.aetherium.shaders.engine.TerrainVertexFormat;
import net.minecraft.util.ARGB;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Aetherium Shaders:
 * <ul>
 *   <li>vertices in the extended terrain format (normal, block ID, mid-texture, tangent, mid-block) are written in one go,
 *       like vanilla's own fast path for its block format ({@link TerrainVertexFormat#EXTENDED}, only when a shader pack
 *       is configured);</li>
 *   <li>vertices in the extended entity and world-text formats ({@link EntityVertexFormats}, only while a pack draws the
 *       world) get the ids of what is being drawn at each vertex and the mid-texture coordinate, tangent (and, for text,
 *       normal) once their quad is complete. Text never sets a normal, so it is not required of its vertices.</li>
 * </ul>
 * The format is fixed per builder, so each hook is one field test on vanilla's own formats.
 */
@Mixin(BufferBuilder.class)
abstract class BufferBuilderMixin {
	@Shadow
	@Final
	private VertexFormat format;

	@Shadow
	private int vertices;

	@Shadow
	private long vertexPointer;

	@Shadow
	@Final
	private int vertexSize;

	@Shadow
	@Final
	private PrimitiveTopology primitiveTopology;

	@Shadow
	@Final
	@Mutable
	private int initialElementsToFill;

	@Shadow
	private long beginVertex() {
		throw new AssertionError();
	}

	/** The builder's format is an extended entity or text format. */
	@Unique
	private boolean aetherium$entityFormat;
	/** The builder's format is one of the extended terrain, entity or text formats. */
	@Unique
	private boolean aetherium$packFormat;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void aetherium$classifyFormat(CallbackInfo ci) {
		aetherium$entityFormat = EntityVertexFormats.isExtended(format);
		aetherium$packFormat = aetherium$entityFormat || format == TerrainVertexFormat.EXTENDED;
		if (format == EntityVertexFormats.EXTENDED_TEXT) {
			// Bit 6 = Normal (BufferBuilder's semantic ids): filled in per quad instead.
			initialElementsToFill &= ~(1 << 6);
		}
	}

	@Inject(method = "beginVertex", at = @At("HEAD"))
	private void aetherium$finishQuad(CallbackInfoReturnable<Long> cir) {
		if (aetherium$entityFormat) {
			finishLastQuad();
		}
	}

	@Inject(method = "beginVertex", at = @At("RETURN"))
	private void aetherium$entityIds(CallbackInfoReturnable<Long> cir) {
		if (aetherium$entityFormat) {
			EntityVertexFormats.writeIds(format, cir.getReturnValueJ());
		}
	}

	@Inject(method = "build", at = @At("HEAD"))
	private void aetherium$finishLastQuad(CallbackInfoReturnable<?> cir) {
		if (aetherium$entityFormat) {
			finishLastQuad();
		}
	}

	/** When the last vertex written completed a quad of an extended entity/text format (before more vertices move the buffer). */
	private void finishLastQuad() {
		if (vertices > 0 && (vertices & 3) == 0 && primitiveTopology == PrimitiveTopology.QUADS && vertexPointer != -1L) {
			EntityVertexFormats.finishQuad(format, vertexPointer - 3L * vertexSize);
		}
	}

	@Inject(method = "addVertex(FFFIFFIIFFF)V", at = @At("HEAD"), cancellable = true)
	private void aetherium$extendedTerrain(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords,
			float nx, float ny, float nz, CallbackInfo ci) {
		if (!aetherium$packFormat) {
			return;
		}
		if (format == TerrainVertexFormat.EXTENDED) {
			long pointer = beginVertex();
			TerrainVertexFormat.write(pointer, vertices - 1, x, y, z, ARGB.toABGR(color), u, v, lightCoords, nx, ny, nz);
			ci.cancel();
		} else if (format == EntityVertexFormats.EXTENDED_ENTITY) {
			// Vanilla's entity fast path, at the same offsets (the ids are written by beginVertex).
			long pointer = beginVertex();
			MemoryUtil.memPutFloat(pointer, x);
			MemoryUtil.memPutFloat(pointer + 4, y);
			MemoryUtil.memPutFloat(pointer + 8, z);
			MemoryUtil.memPutInt(pointer + 12, ARGB.toABGR(color));
			MemoryUtil.memPutFloat(pointer + 16, u);
			MemoryUtil.memPutFloat(pointer + 20, v);
			MemoryUtil.memPutInt(pointer + 24, overlayCoords);
			MemoryUtil.memPutInt(pointer + 28, lightCoords);
			MemoryUtil.memPutByte(pointer + 32, normal(nx));
			MemoryUtil.memPutByte(pointer + 33, normal(ny));
			MemoryUtil.memPutByte(pointer + 34, normal(nz));
			ci.cancel();
		}
	}

	private static byte normal(float c) {
		return (byte) ((int) (Math.max(-1.0F, Math.min(1.0F, c)) * 127.0F) & 0xFF);
	}
}
