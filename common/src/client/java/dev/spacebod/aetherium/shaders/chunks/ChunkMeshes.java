package dev.spacebod.aetherium.shaders.chunks;

import dev.spacebod.aetherium.shaders.config.ShaderPackSettings;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.jspecify.annotations.Nullable;

/**
 * Which chunk mesh format the terrain renderer builds: {@link ChunkMeshFormat} while a shader pack is in use, its own
 * compact format otherwise. Settled each time the renderer is (re)built ({@link #settle}), so every reader of one
 * renderer (mesh builder, region buffers, pipelines) agrees; a pack switched on or off rebuilds the renderer when the
 * format no longer matches ({@link #stale}).
 */
public final class ChunkMeshes {
	private static @Nullable ChunkMeshFormat format;
	private static volatile boolean extended;

	private ChunkMeshes() {
	}

	/** The renderer is being (re)built: meshes carry the pack data from here on if a pack is in use. */
	public static synchronized void settle() {
		extended = wanted();
		if (extended && format == null) {
			format = new ChunkMeshFormat();
		}
	}

	/** The format meshes are built in, or null for the renderer's own. */
	public static @Nullable ChunkVertexType current() {
		return extended ? format : null;
	}

	/** Whether meshes carry the pack data ({@link ChunkMeshFormat}). */
	public static boolean extended() {
		return extended;
	}

	/** Whether the meshes are in the other format than the one a pack being in use (or not) needs. */
	public static boolean stale() {
		return extended != wanted();
	}

	private static boolean wanted() {
		return ShaderPackSettings.activePack().isPresent();
	}
}
