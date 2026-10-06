package dev.spacebod.aetherium.shaders.shaderpack.programs;

import dev.spacebod.aetherium.shaders.gl.blending.BlendModeOverride;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** The program that draws a {@link ProgramId}: its own, else its fallback's (recursively), memoised per id. */
public class ProgramFallbackResolver {
	private final ProgramSet programs;
	private final Map<ProgramId, ProgramSource> cache;

	public ProgramFallbackResolver(ProgramSet programs) {
		this.programs = programs;
		this.cache = new HashMap<>();
	}

	public Optional<ProgramSource> resolve(ProgramId id) {
		return Optional.ofNullable(resolveNullable(id));
	}

	public boolean has(ProgramId id) {
		return programs.get(id).isPresent();
	}

	@Nullable
	public ProgramSource resolveNullable(ProgramId id) {
		if (cache.containsKey(id)) {
			return cache.get(id);
		}

		ProgramSource source = programs.get(id).orElse(null);

		if (source == null) {
			ProgramId fallback = id.getFallback().orElse(null);

			if (fallback != null) {
				source = resolveNullable(fallback);
			}

			// A program standing in for another keeps the default blend mode of the one asked for (spider eyes stay
			// additive when drawn with gbuffers_textured) unless the stand-in sets its own.
			BlendModeOverride requestedDefault = id.getBlendModeOverride();
			if (source != null && requestedDefault != null && source.getDirectives().getBlendModeOverride().isEmpty()) {
				source = source.withDirectiveOverride(source.getDirectives().withBlendModeOverride(requestedDefault));
			}
		}

		cache.put(id, source);
		return source;
	}
}
