package dev.spacebod.aetherium.shaders.chunks;

import dev.spacebod.aetherium.shaders.shaderpack.materialmap.BlockRenderType;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/** The terrain layer a pack's {@code block.properties} {@code layer.*} entries give a block, or null to keep the game's. */
public final class PackLayers {
	private PackLayers() {
	}

	public static @Nullable ChunkSectionLayer of(Block block) {
		Map<Block, BlockRenderType> types = WorldRenderingSettings.INSTANCE.getBlockTypeIds();
		BlockRenderType type = types == null ? null : types.get(block);
		if (type == null) {
			return null;
		}
		return switch (type) {
			case SOLID -> ChunkSectionLayer.SOLID;
			case CUTOUT, CUTOUT_MIPPED -> ChunkSectionLayer.CUTOUT;
			case TRANSLUCENT -> ChunkSectionLayer.TRANSLUCENT;
		};
	}
}
