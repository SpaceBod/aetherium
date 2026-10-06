package dev.spacebod.aetherium.client.render;

import java.util.concurrent.atomic.LongAdder;

/** Count of finished (not cancelled) section mesh builds, bumped from the section-compile worker threads. */
public final class SectionBuilds {
	public static final LongAdder COMPLETED = new LongAdder();

	private SectionBuilds() {
	}
}
