package dev.spacebod.aetherium.shaders.pbr;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/**
 * The material texture format the resource packs declare ({@code optifine/texture.properties}, {@code format=lab-pbr/1.3}).
 * Packs see it as {@code MC_TEXTURE_FORMAT_<NAME>} and {@code MC_TEXTURE_FORMAT_<NAME>_<VERSION>}; it decides whether a
 * map's values may be filtered and how its mip levels are made. LabPBR is the only format known.
 */
public record PbrFormat(String name, @Nullable String version) {
	public static final Identifier LOCATION = Identifier.withDefaultNamespace("optifine/texture.properties");
	private static final String LAB_PBR = "lab-pbr";

	/** The format the resource packs declare, or null (none, or one not known). */
	public static @Nullable PbrFormat load(ResourceManager resources) {
		Optional<Resource> resource = resources.getResource(LOCATION);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream stream = resource.get().open()) {
			Properties properties = new Properties();
			properties.load(stream);
			String format = properties.getProperty("format");
			if (format == null || format.isEmpty()) {
				return null;
			}
			String[] parts = format.split("/");
			if (!parts[0].equals(LAB_PBR)) {
				AetheriumShaders.logger.warn("Invalid texture format '{}' in file '{}'", parts[0], LOCATION);
				return null;
			}
			return new PbrFormat(parts[0], parts.length > 1 ? parts[1] : null);
		} catch (Exception e) {
			AetheriumShaders.logger.error("Failed to load texture format from file '{}'", LOCATION, e);
			return null;
		}
	}

	/** {@code MC_TEXTURE_FORMAT_LAB_PBR}, {@code MC_TEXTURE_FORMAT_LAB_PBR_1_3}. */
	public List<String> defines() {
		List<String> defines = new ArrayList<>();
		String define = "MC_TEXTURE_FORMAT_" + name.toUpperCase(Locale.ROOT).replaceAll("-", "_");
		defines.add(define);
		if (version != null) {
			defines.add(define + "_" + version.replaceAll("[.-]", "_"));
		}
		return defines;
	}

	/** Whether a map's values may be blended (filtered sampling, averaged mip levels). LabPBR specular values are codes. */
	public boolean interpolates(PbrKind kind) {
		return kind != PbrKind.SPECULAR;
	}

	/** How a map's mip levels are made: LabPBR specular keeps its coded channels apart; everything else averages. */
	public PbrImages.ChannelRule[] mipRules(PbrKind kind) {
		if (kind == PbrKind.SPECULAR) {
			return new PbrImages.ChannelRule[]{
					// Red: smoothness, a plain value.
					PbrImages.ChannelRule.LINEAR,
					// Green: reflectance below 230, a metal id from 230 up.
					PbrImages.ChannelRule.discrete(v -> v < 230 ? 0 : v - 229),
					// Blue: porosity below 65, subsurface scattering from 65 up.
					PbrImages.ChannelRule.discrete(v -> v < 65 ? 0 : 1),
					// Alpha: emission below 255, 255 meaning none.
					PbrImages.ChannelRule.discrete(v -> v < 255 ? 0 : 1)};
		}
		return PbrImages.LINEAR;
	}
}
