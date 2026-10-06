package dev.spacebod.aetherium.shaders.engine;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializer;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializerRegistry;
import org.lwjgl.system.MemoryUtil;

/**
 * Copies of vanilla entity and world-text vertices into the extended formats ({@link EntityVertexFormats}), for the
 * terrain renderer's bulk vertex writes (entity cubes, item quads, glyphs). Those write whole quads in vanilla's format
 * and copy them into the builder through a serializer per format pair; a generated one cannot fill elements the source
 * lacks. These copy vanilla's elements, which sit at the same offsets, then add the ids and per-quad data as
 * {@code BufferBuilderMixin} does for vertices written one at a time.
 */
public final class ExtendedVertexSerializers {
	private ExtendedVertexSerializers() {
	}

	public static void register() {
		VertexSerializerRegistry registry = VertexSerializerRegistry.instance();
		registry.registerSerializer(DefaultVertexFormat.ENTITY, EntityVertexFormats.EXTENDED_ENTITY,
				serializer(DefaultVertexFormat.ENTITY, EntityVertexFormats.EXTENDED_ENTITY));
		registry.registerSerializer(DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR, EntityVertexFormats.EXTENDED_TEXT,
				serializer(DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR, EntityVertexFormats.EXTENDED_TEXT));
	}

	private static VertexSerializer serializer(VertexFormat from, VertexFormat to) {
		int fromStride = from.getVertexSize();
		int toStride = to.getVertexSize();
		return (src, dst, count) -> {
			for (int i = 0; i < count; i++) {
				long out = dst + (long) i * toStride;
				MemoryUtil.memCopy(src + (long) i * fromStride, out, fromStride);
				EntityVertexFormats.writeIds(to, out);
			}
			// Bulk writes are whole quads.
			for (int q = 0; q + 4 <= count; q += 4) {
				EntityVertexFormats.finishQuad(to, dst + (long) q * toStride);
			}
		};
	}
}
