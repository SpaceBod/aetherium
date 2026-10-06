package dev.spacebod.aetherium.client;

/**
 * Optional instrumentation. Release builds leave every hook a no-op; development runs install a listener that times
 * the shader engine's stages on the GPU.
 */
public final class DevHooks {
	/** GPU-timed stages outside vanilla's frame graph; stages may nest inside frame-graph passes. */
	public interface Stages {
		void begin(String name);

		void end();
	}

	private static final Stages NONE = new Stages() {
		@Override
		public void begin(String name) {
		}

		@Override
		public void end() {
		}
	};

	private static volatile Stages stages = NONE;
	private static volatile boolean installed;

	private DevHooks() {
	}

	/** Called once by the development tooling at client start-up. */
	public static void install(Stages listener) {
		stages = listener;
		installed = true;
	}

	/** Whether the development tooling is loaded (shows its settings). */
	public static boolean installed() {
		return installed;
	}

	public static void stageBegin(String name) {
		stages.begin(name);
	}

	public static void stageEnd() {
		stages.end();
	}
}
