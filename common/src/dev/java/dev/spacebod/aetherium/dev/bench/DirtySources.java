package dev.spacebod.aetherium.dev.bench;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Attribution of section rebuilds: which code path turned each section from clean to dirty, and, for
 * sections rebuilt less than a second after their previous build, which path caused that rebuild.
 * Tags are set around the vanilla entry points on the client thread (bench mixins); dirty transitions are
 * seen in {@code SectionUpdateTracker.setDirty}; builds are seen on the worker in {@code CompileTask.doTask}.
 */
public final class DirtySources {
	public static final String[] NAMES = {"other", "lightEngine", "lightPacket", "chunkLight", "biomes", "block"};
	public static final int OTHER = 0;
	public static final int LIGHT_ENGINE = 1;
	public static final int LIGHT_PACKET = 2;
	public static final int CHUNK_LIGHT = 3;
	public static final int BIOMES = 4;
	public static final int BLOCK = 5;

	public static final LongAdder[] DIRTIED = adders();
	public static final LongAdder[] REBUILT_WITHIN_1S = adders();

	/** Client thread only. */
	private static int current = OTHER;
	private static final ConcurrentHashMap<Long, Integer> LAST_SOURCE = new ConcurrentHashMap<>();

	private DirtySources() {
	}

	private static LongAdder[] adders() {
		LongAdder[] a = new LongAdder[NAMES.length];
		for (int i = 0; i < a.length; i++) {
			a[i] = new LongAdder();
		}
		return a;
	}

	/** Enters a tagged path unless an outer one is active (the outer path is the cause). */
	public static void enter(int tag) {
		if (current == OTHER) {
			current = tag;
		}
	}

	public static void exit(int tag) {
		if (current == tag) {
			current = OTHER;
		}
	}

	/** A section went from clean to dirty. */
	public static void dirtied(long sectionNode) {
		DIRTIED[current].increment();
		LAST_SOURCE.put(sectionNode, current);
	}

	/** A section build finished less than a second after the previous one (its dirty tag is used up by the build). */
	public static void rebuiltQuickly(long sectionNode) {
		Integer source = LAST_SOURCE.remove(sectionNode);
		REBUILT_WITHIN_1S[source == null ? OTHER : source].increment();
	}

	/** A section build finished that nothing reads the dirty tag of (keeps the map to sections awaiting a build). */
	public static void forget(long sectionNode) {
		LAST_SOURCE.remove(sectionNode);
	}

	/** Client block changes applied from the server, by "old -> new" block id (bench only). */
	public static final ConcurrentHashMap<String, LongAdder> BLOCK_CHANGES = new ConcurrentHashMap<>();

	public static void blockChange(String oldBlock, String newBlock, boolean stateOnly) {
		String key = stateOnly ? oldBlock + " (state change)" : oldBlock + " -> " + newBlock;
		BLOCK_CHANGES.computeIfAbsent(key, k -> new LongAdder()).increment();
	}

	public static long[] snapshot(LongAdder[] adders) {
		long[] v = new long[adders.length];
		for (int i = 0; i < v.length; i++) {
			v[i] = adders[i].sum();
		}
		return v;
	}
}
