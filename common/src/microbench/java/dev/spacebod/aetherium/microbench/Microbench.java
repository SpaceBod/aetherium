package dev.spacebod.aetherium.microbench;

/**
 * Seconds-long measurements on synthetic or bootstrapped data, run with {@code gradlew :common:microbench}
 * (optionally {@code -Ponly=memory|lowering|shaders}). Not a substitute for in-game measurement of frame time.
 */
public final class Microbench {
	private Microbench() {
	}

	public static void main(String[] args) {
		String only = args.length > 0 ? args[0] : "";
		if (only.isEmpty() || only.equals("memory")) {
			MemoryAudit.run();
		}
		if (only.equals("lowering")) {
			LoweringFixtures.run();
		}
		if (only.equals("shaders")) {
			ShaderPackCompile.run(args.length > 1 ? args[1] : System.getenv("AETHERIUM_PACKS"));
		}
	}
}
