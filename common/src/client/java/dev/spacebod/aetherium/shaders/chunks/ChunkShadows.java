package dev.spacebod.aetherium.shaders.chunks;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.spacebod.aetherium.client.mixin.chunks.ChunkRendererAccessor;
import dev.spacebod.aetherium.client.mixin.chunks.SectionManagerAccessor;
import dev.spacebod.aetherium.client.mixin.chunks.WorldRendererAccessor;
import dev.spacebod.aetherium.shaders.shadows.ShadowCasterCulling;
import dev.spacebod.aetherium.shaders.uniforms.CapturedRenderingState;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Terrain for the shadow map, from the chunk renderer's meshes, in the same frame as the camera's view.
 *
 * <p>The shadow casters are found by walking the renderer's tree of sections with geometry through
 * {@link CasterFrustum}, into draw lists of this class's own, one per region: the renderer's own lists and batches
 * belong to the camera and stay as the camera left them. While this draws ({@link #drawing()}), regions hand out a
 * second set of batches (refilled every shadow frame), block face culling is off, and the renderer's record of which
 * passes have geometry is put back afterwards, so the camera's terrain draws exactly as it was prepared.
 *
 * <p>Use per frame: {@link #collect}, then {@link #prepare}, {@link #draw} per layer group, and {@link #finish}.
 * Render thread only.
 */
public final class ChunkShadows {
	private static boolean drawing;

	/** Lists whose region has not cast for this many frames are dropped. */
	private static final int KEEP_FRAMES = 600;

	private final Map<RenderRegion, ChunkRenderList> lists = new IdentityHashMap<>();
	private final ObjectArrayList<ChunkRenderList> active = new ObjectArrayList<>();
	private final ChunkRenderListIterable drawn = this::iterator;
	private final boolean[] savedShouldDraw = new boolean[DefaultTerrainRenderPasses.ALL.length];
	private @Nullable RenderSectionManager manager;
	private @Nullable SodiumWorldRenderer renderer;
	private @Nullable CameraTransform camera;
	private @Nullable ChunkRenderMatrices matrices;
	private int frame;

	/** Whether the shadow map's terrain is being prepared or drawn now. */
	public static boolean drawing() {
		return drawing;
	}

	/**
	 * Finds the casters around the camera: within {@code radius} blocks horizontally, and passing {@code culling} when
	 * given. Returns how many sections with geometry cast, or -1 when the renderer has no world.
	 */
	public int collect(double cameraX, double cameraY, double cameraZ, @Nullable ShadowCasterCulling culling, double radius) {
		active.clear();
		renderer = SodiumWorldRenderer.instanceNullable();
		RenderSectionManager sections = renderer == null ? null : ((WorldRendererAccessor) renderer).aetherium$sections();
		if (sections == null) {
			manager = null;
			lists.clear();
			return -1;
		}
		if (sections != manager) {
			// A new renderer (world change, reload): its regions are new too.
			manager = sections;
			lists.clear();
		}
		frame++;
		if (frame % KEEP_FRAMES == 0) {
			lists.values().removeIf(list -> frame - list.getLastVisibleFrame() > KEEP_FRAMES);
		}
		camera = new CameraTransform(cameraX, cameraY, cameraZ);
		Viewport viewport = new Viewport(new CasterFrustum(cameraX, cameraY, cameraZ, culling, radius), new Vector3d(cameraX, cameraY, cameraZ));
		var tree = ((SectionManagerAccessor) sections).aetherium$renderable();
		tree.prepareForTraversal();
		tree.traverse(this::visit, viewport, (float) radius);
		int casting = 0;
		for (ChunkRenderList list : active) {
			casting += list.getSectionsWithGeometryCount();
		}
		return casting;
	}

	private void visit(int x, int y, int z) {
		RenderRegion region = manager.regions.getForChunk(x, y, z);
		if (region == null) {
			return;
		}
		ChunkRenderList list = lists.computeIfAbsent(region, ChunkRenderList::new);
		if (list.getLastVisibleFrame() != frame) {
			list.reset(frame);
			active.add(list);
		}
		list.add(LocalSectionIndex.pack(x & 7, y & 3, z & 7));
	}

	private Iterator<ChunkRenderList> iterator(boolean reverse) {
		return reverse ? active.reversed().iterator() : active.iterator();
	}

	/**
	 * Fills the shadow batches for the collected casters. The renderer's terrain uniforms are written for the camera
	 * first if this frame's have not been (the camera's terrain is drawn after the shadow map): the same matrices and
	 * fog its own draw would write.
	 */
	public void prepare() {
		RenderSectionManager sections = manager;
		if (sections == null || renderer == null || camera == null) {
			return;
		}
		WorldRendererAccessor access = (WorldRendererAccessor) renderer;
		ChunkRenderer chunks = sections.getChunkRenderer();
		matrices = new ChunkRenderMatrices(((GameRendererStorage) Minecraft.getInstance().gameRenderer).sodium$getProjectionMatrix(),
				CapturedRenderingState.INSTANCE.getGbufferModelView());
		access.aetherium$uniforms().update(matrices, access.aetherium$fog());
		boolean[] shouldDraw = ((ChunkRendererAccessor) chunks).aetherium$shouldDraw();
		System.arraycopy(shouldDraw, 0, savedShouldDraw, 0, shouldDraw.length);
		drawing = true;
		for (ChunkRenderList list : active) {
			list.getRegion().clearAllCachedBatches();
		}
		chunks.prepare(drawn, camera, access.aetherium$translucencySorting());
	}

	/** Draws one layer group of the casters into {@code pass} (opened on the shadow map), sampling the atlas with {@code sampler}. */
	public void draw(ChunkSectionLayerGroup group, RenderPass pass, GpuSampler sampler) {
		if (!drawing || camera == null || matrices == null) {
			return;
		}
		if (group == ChunkSectionLayerGroup.OPAQUE) {
			draw(DefaultTerrainRenderPasses.SOLID, pass, sampler);
			draw(DefaultTerrainRenderPasses.CUTOUT, pass, sampler);
		} else if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
			draw(DefaultTerrainRenderPasses.TRANSLUCENT, pass, sampler);
		}
	}

	private void draw(TerrainRenderPass terrain, RenderPass pass, GpuSampler sampler) {
		WorldRendererAccessor access = (WorldRendererAccessor) renderer;
		UniformBufferManager uniforms = access.aetherium$uniforms();
		manager.getChunkRenderer().render(matrices, drawn, terrain, camera, access.aetherium$fog(), access.aetherium$translucencySorting(), pass, sampler,
				uniforms.getUniformBuffer(), uniforms.getSectionTimeInfo(), null);
	}

	/** Puts back the renderer's record of the camera's passes and ends the shadow draw. */
	public void finish() {
		if (!drawing) {
			return;
		}
		drawing = false;
		if (manager != null) {
			boolean[] shouldDraw = ((ChunkRendererAccessor) manager.getChunkRenderer()).aetherium$shouldDraw();
			System.arraycopy(savedShouldDraw, 0, shouldDraw, 0, shouldDraw.length);
		}
	}
}
