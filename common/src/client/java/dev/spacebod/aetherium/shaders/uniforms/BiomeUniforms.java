package dev.spacebod.aetherium.shaders.uniforms;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import dev.spacebod.aetherium.shaders.engine.VanillaWorldState;
import dev.spacebod.aetherium.shaders.gl.uniform.FloatSupplier;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;
import dev.spacebod.aetherium.shaders.parsing.BiomeCategories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.PER_TICK;

public class BiomeUniforms {
	private static final Object2IntMap<ResourceKey<Biome>> biomeMap = new Object2IntOpenHashMap<>();

	/**
	 * The biome at the player, looked up at most once per tick and block position (the five biome uniforms share it),
	 * with its category, which takes a dozen tag checks.
	 */
	private static @Nullable Holder<Biome> biome;
	private static @Nullable BiomeCategories category;
	/** Weak: the cache must not keep a world alive after leaving it. */
	private static WeakReference<Level> biomeLevel = new WeakReference<>(null);
	private static long biomeTick;
	private static long biomePos;

	public static Object2IntMap<ResourceKey<Biome>> getBiomeMap() {
		return biomeMap;
	}

	public static void addBiomeUniforms(UniformHolder uniforms) {
		uniforms
			.uniform1i(PER_TICK, "biome", playerI(player -> biomeMap.getInt(biomeAt(player).unwrapKey().orElse(null))))
			.uniform1i(PER_TICK, "biome_category", playerI(player -> {
				biomeAt(player);
				return Objects.requireNonNull(category).ordinal();
			}))
			.uniform1i(PER_TICK, "biome_precipitation", playerI(player -> {
				Biome.Precipitation precipitation = biomeAt(player).value().getPrecipitationAt(player.blockPosition(), player.level().getSeaLevel());
				return switch (precipitation) {
					case NONE -> 0;
					case RAIN -> 1;
					case SNOW -> 2;
				};
			}))
			.uniform1f(PER_TICK, "rainfall", playerF(player -> VanillaWorldState.downfall(biomeAt(player).value())))
			.uniform1f(PER_TICK, "temperature", playerF(player -> biomeAt(player).value().getBaseTemperature()));
	}

	private static Holder<Biome> biomeAt(LocalPlayer player) {
		Level level = player.level();
		BlockPos pos = player.blockPosition();
		long tick = level.getGameTime();
		if (biome == null || level != biomeLevel.get() || tick != biomeTick || pos.asLong() != biomePos) {
			Holder<Biome> found = level.getBiome(pos);
			if (found != biome) {
				category = getBiomeCategory(found);
			}
			biome = found;
			if (level != biomeLevel.get()) {
				biomeLevel = new WeakReference<>(level);
			}
			biomeTick = tick;
			biomePos = pos.asLong();
		}
		return biome;
	}

	private static BiomeCategories getBiomeCategory(Holder<Biome> holder) {
		if (holder.is(BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS)) {
			// Only the void biome carries this tag.
			return BiomeCategories.NONE;
		} else if (holder.is(BiomeTags.HAS_VILLAGE_SNOWY)) {
			return BiomeCategories.ICY;
		} else if (holder.is(BiomeTags.IS_HILL)) {
			return BiomeCategories.EXTREME_HILLS;
		} else if (holder.is(BiomeTags.IS_TAIGA)) {
			return BiomeCategories.TAIGA;
		} else if (holder.is(BiomeTags.IS_OCEAN)) {
			return BiomeCategories.OCEAN;
		} else if (holder.is(BiomeTags.IS_JUNGLE)) {
			return BiomeCategories.JUNGLE;
		} else if (holder.is(BiomeTags.IS_FOREST)) {
			return BiomeCategories.FOREST;
		} else if (holder.is(BiomeTags.IS_BADLANDS)) {
			return BiomeCategories.MESA;
		} else if (holder.is(BiomeTags.IS_NETHER)) {
			return BiomeCategories.NETHER;
		} else if (holder.is(BiomeTags.IS_END)) {
			return BiomeCategories.THE_END;
		} else if (holder.is(BiomeTags.IS_BEACH)) {
			return BiomeCategories.BEACH;
		} else if (holder.is(BiomeTags.HAS_DESERT_PYRAMID)) {
			return BiomeCategories.DESERT;
		} else if (holder.is(BiomeTags.IS_RIVER)) {
			return BiomeCategories.RIVER;
		} else if (holder.is(BiomeTags.ALLOWS_SURFACE_SLIME_SPAWNS)) {
			return BiomeCategories.SWAMP;
		// UNDERGROUND is never returned: no tag identifies those biomes.
		} else if (holder.is(BiomeTags.WITHOUT_ZOMBIE_SIEGES)) {
			return BiomeCategories.MUSHROOM;
		} else if (holder.is(BiomeTags.IS_MOUNTAIN)) {
			return BiomeCategories.MOUNTAIN;
		} else {
			return BiomeCategories.PLAINS;
		}
	}

	static IntSupplier playerI(ToIntFunction<LocalPlayer> function) {
		return () -> {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player == null) {
				return 0;
			} else {
				return function.applyAsInt(player);
			}
		};
	}

	static FloatSupplier playerF(ToFloatFunction<LocalPlayer> function) {
		return () -> {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player == null) {
				return 0.0f;
			} else {
				return function.applyAsFloat(player);
			}
		};
	}

	@FunctionalInterface
	public interface ToFloatFunction<T> {
		float applyAsFloat(T value);
	}
}
