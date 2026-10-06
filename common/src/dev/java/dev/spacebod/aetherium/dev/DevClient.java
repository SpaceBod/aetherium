package dev.spacebod.aetherium.dev;

import dev.spacebod.aetherium.client.DevHooks;
import dev.spacebod.aetherium.dev.bench.BenchDriver;

/** Development tooling start-up (benchmark driver, GPU stage timers). Never part of a release jar. */
public final class DevClient {
	private DevClient() {
	}

	public static void init() {
		DevHooks.install(new DevHooks.Stages() {
			@Override
			public void begin(String name) {
				BenchDriver.stageBegin(name);
			}

			@Override
			public void end() {
				BenchDriver.stageEnd();
			}
		});
		BenchDriver.bootstrap();
	}
}
