package dev.spacebod.aetherium.shaders.engine;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The active pack's storage descriptor set as vanilla's pipeline code sees it: pack pipelines built while a pack has
 * custom images or storage buffers get its layout as their set 1 (registered here by pipeline layout handle), and
 * binding one of them binds the set. Written on the render thread; read by pipeline compilation on worker threads.
 */
public final class StorageSet {
	private static volatile long setLayout;
	private static volatile long descriptorSet;
	/**
	 * The layout of the pack being loaded: its full-screen passes compile on worker threads before its storage exists
	 * ({@link #activate} comes at the end of the load), and need set 1 already. 0 = none.
	 */
	private static volatile long loadingLayout;
	private static final Set<Long> PIPELINE_LAYOUTS = ConcurrentHashMap.newKeySet();

	private StorageSet() {
	}

	static void activate(PackStorage storage) {
		setLayout = storage.setLayout();
		descriptorSet = storage.descriptorSet();
		loadingLayout = 0;
	}

	/** Loader thread: the set layout of the pack being loaded, before its passes compile. */
	static void loading(long layout) {
		loadingLayout = layout;
	}

	/** The load whose layout this is failed or was cancelled: its passes are discarded, and the layout destroyed. */
	static void loadAbandoned(long layout) {
		if (loadingLayout == layout) {
			loadingLayout = 0;
			if (setLayout == 0) {
				PIPELINE_LAYOUTS.clear();
			}
		}
	}

	static void deactivate() {
		setLayout = 0;
		descriptorSet = 0;
		loadingLayout = 0;
		passSet = 0;
		PIPELINE_LAYOUTS.clear();
	}

	/** The layout pack pipelines append as set 1: the active pack's, else the loading pack's; 0 when neither has storage. */
	public static long setLayout() {
		long active = setLayout;
		return active != 0 ? active : loadingLayout;
	}

	public static void registerPipelineLayout(long pipelineLayout) {
		PIPELINE_LAYOUTS.add(pipelineLayout);
	}

	/** Whether pipelines of {@code pipelineLayout} have the storage set as set 1 (while a pack with storage is active). */
	public static boolean has(long pipelineLayout) {
		return descriptorSet != 0 && PIPELINE_LAYOUTS.contains(pipelineLayout);
	}

	/** The full-screen pass's own set ({@link #passSet(long)}), or 0 when none is set. */
	public static long passSet() {
		return passSet;
	}

	/**
	 * The set pack pipelines bind in a render pass without a pass set of its own: in a world or shadow pass whose
	 * programs bind targets as images, a set with the targets' views as those programs read them; else the shared set
	 * (0 = no storage). Asked once per render pass, at its first pack pipeline.
	 */
	public static long renderPassSet() {
		long shared = descriptorSet;
		if (shared == 0) {
			return 0;
		}
		long world = ShaderPackEngine.get().worldTargetImageSet();
		return world != 0 ? world : shared;
	}

	/** Render thread: the set the pass being drawn binds instead of the shared one (its targets as images); 0 = none. */
	private static volatile long passSet;

	/** For the pass drawn next ({@link PackStorage#targetImageSet}); cleared with 0 after it. */
	static void passSet(long set) {
		passSet = set;
	}
}
