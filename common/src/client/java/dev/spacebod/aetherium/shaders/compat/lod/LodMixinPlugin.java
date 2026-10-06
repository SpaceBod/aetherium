package dev.spacebod.aetherium.shaders.compat.lod;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Plugin of the LOD mod's mixin config ({@code aetherium.lod.mixins.json}): its mixins apply only when the mod is
 * installed. Mixin asks the plugin about each declared target before it looks the target class up, so with the mod
 * absent its classes are never searched for (no "Error loading class" warnings). The mod is detected by its API class
 * file, without loading any class.
 */
public final class LodMixinPlugin implements IMixinConfigPlugin {
	private static final String API_CLASS = "com/seibel/distanthorizons/api/DhApi.class";
	private boolean present;

	@Override
	public void onLoad(String mixinPackage) {
		ClassLoader loader = LodMixinPlugin.class.getClassLoader();
		present = loader != null && loader.getResource(API_CLASS) != null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return present;
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
