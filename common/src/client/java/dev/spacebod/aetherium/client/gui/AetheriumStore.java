package dev.spacebod.aetherium.client.gui;

import dev.spacebod.aetherium.core.AetheriumConfig;
import dev.spacebod.aetherium.core.Optimisations;
import dev.spacebod.aetherium.shaders.engine.EngineSwitches;
import java.util.HashMap;
import java.util.Map;

/**
 * Aetherium's own settings as the settings screen edits them: read from {@code config/aetherium.json} when the screen
 * opens, saved once per Apply. Optimisations that have a runtime switch also change at once; the rest take effect on
 * the next start (their mixins are applied or skipped while the game loads).
 */
final class AetheriumStore {
	private String profile;
	private final Map<String, Boolean> enabled = new HashMap<>();
	private boolean gpuTimers;
	private boolean passTimers;
	private boolean dirty;

	AetheriumStore() {
		AetheriumConfig saved = AetheriumConfig.readSaved();
		profile = saved.profile;
		for (Optimisations.Optimisation o : Optimisations.ALL) {
			enabled.put(o.id(), Optimisations.isEnabled(saved, o.id()));
		}
		gpuTimers = saved.gpuTimers;
		passTimers = saved.passTimers;
	}

	/** On in this session (what the game started with, plus runtime switches flipped since). */
	static boolean running(String id) {
		return EngineSwitches.isRuntime(id) ? EngineSwitches.enabled(id) : Optimisations.isEnabled(id);
	}

	/** Whether flipping {@code id} takes effect without a restart in this session. */
	static boolean live(String id) {
		if (!EngineSwitches.isRuntime(id)) {
			return false;
		}
		// Its mixins exist only if it was on at start-up.
		return !Optimisations.mixinBacked().contains(id) || Optimisations.isEnabled(id);
	}

	boolean enabled(String id) {
		return enabled.getOrDefault(id, false);
	}

	void setEnabled(String id, boolean on) {
		enabled.put(id, on);
		dirty = true;
		if (live(id)) {
			EngineSwitches.set(id, on);
		}
	}

	/** The saved choice differs from what is running and cannot change until the next start. */
	boolean restartPending(String id) {
		return enabled(id) != running(id) && !live(id);
	}

	String profile() {
		return profile;
	}

	/** Switching profile resets every optimisation to that profile's value. */
	void setProfile(String profile) {
		this.profile = profile;
		for (Optimisations.Optimisation o : Optimisations.ALL) {
			setEnabled(o.id(), Optimisations.profileDefault(profile, o.id()));
		}
		dirty = true;
	}

	boolean profileRestartPending() {
		return !profile.equals(AetheriumConfig.get().profile);
	}

	boolean gpuTimers() {
		return gpuTimers;
	}

	void setGpuTimers(boolean on) {
		gpuTimers = on;
		dirty = true;
	}

	boolean passTimers() {
		return passTimers;
	}

	void setPassTimers(boolean on) {
		passTimers = on;
		dirty = true;
	}

	boolean debugRestartPending() {
		AetheriumConfig boot = AetheriumConfig.get();
		return gpuTimers != boot.gpuTimers || passTimers != boot.passTimers;
	}

	void saveIfDirty() {
		if (dirty) {
			AetheriumConfig.save(profile, enabled, gpuTimers, passTimers);
			dirty = false;
		}
	}
}
