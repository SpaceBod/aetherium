package dev.spacebod.aetherium.microbench;

/**
 * Seconds-long correctness tests, {@code gradlew :common:parity}. Exits non-zero on the first failure.
 */
public final class Parity {
	private Parity() {
	}

	public static void main(String[] args) {
		long start = System.nanoTime();
		String only = args.length > 0 ? args[0] : "";
		if (only.isEmpty() || only.equals("memory")) {
			MemoryAudit.dedupParity();
		}
		System.out.printf("parity done in %.1f s%n", (System.nanoTime() - start) / 1e9);
	}
}
