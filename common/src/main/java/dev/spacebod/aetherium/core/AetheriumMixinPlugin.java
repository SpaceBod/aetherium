package dev.spacebod.aetherium.core;

import dev.spacebod.aetherium.Aetherium;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies a mixin under {@code .mixin.opt.<id>.} only when optimisation {@code <id>} is enabled.
 * Everything else (core and shader-engine hooks) is always applied.
 */
public final class AetheriumMixinPlugin implements IMixinConfigPlugin {
	private static final String OPT = ".mixin.opt.";
	private static volatile boolean logged;

	@Override
	public void onLoad(String mixinPackage) {
		if (!logged) {
			logged = true;
			AetheriumConfig config = AetheriumConfig.get();
			Aetherium.LOG.info("Config profile '{}', optimisations {}", config.profile, Optimisations.snapshot());
		}
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		int at = mixinClassName.indexOf(OPT);
		if (at < 0) {
			return true;
		}
		String rest = mixinClassName.substring(at + OPT.length());
		int lastDot = rest.lastIndexOf('.');
		String id = lastDot < 0 ? rest : rest.substring(0, lastDot);
		return Optimisations.isEnabled(id);
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
