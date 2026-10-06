package dev.spacebod.aetherium.shaders.shaderpack.include;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flattens a file of an {@link IncludeGraph}: every {@code #include} line replaced by the flattened included file.
 * Results are cached per file. Safe to use from several threads: the graph is immutable and a file flattened twice at
 * the same time yields the same lines, so the cache is filled with plain gets and puts (no recursive
 * {@code computeIfAbsent}).
 */
public class IncludeProcessor {
	/** Largest flattened file, in lines: a pack that includes the same files many times over is refused, not run out of memory. */
	private static final int MAX_LINES = 4_000_000;

	private final IncludeGraph graph;
	private final Map<AbsolutePackPath, ImmutableList<String>> cache = new ConcurrentHashMap<>();

	public IncludeProcessor(IncludeGraph graph) {
		this.graph = graph;
	}

	/** The flattened lines of {@code path}, or null when the graph has no such file. */
	public ImmutableList<String> getIncludedFile(AbsolutePackPath path) {
		ImmutableList<String> lines = cache.get(path);

		if (lines == null) {
			lines = process(path);
			if (lines != null) {
				cache.put(path, lines);
			}
		}

		return lines;
	}

	private ImmutableList<String> process(AbsolutePackPath path) {
		FileNode fileNode = graph.getNodes().get(path);

		if (fileNode == null) {
			return null;
		}

		ImmutableList.Builder<String> builder = ImmutableList.builder();
		int count = 0;

		ImmutableList<String> lines = fileNode.getLines();
		ImmutableMap<Integer, AbsolutePackPath> includes = fileNode.getIncludes();

		for (int i = 0; i < lines.size(); i++) {
			AbsolutePackPath include = includes.get(i);

			if (include != null) {
				// Cycles were already rejected by IncludeGraph, so the recursion terminates.
				ImmutableList<String> included = Objects.requireNonNull(getIncludedFile(include));
				builder.addAll(included);
				count += included.size();
			} else {
				builder.add(lines.get(i));
				count++;
			}

			if (count > MAX_LINES) {
				throw new IllegalStateException("including the files of " + path.getPathString() + " gives more than " + MAX_LINES + " lines");
			}
		}

		return builder.build();
	}
}
