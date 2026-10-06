package dev.spacebod.aetherium.shaders.shaderpack.include;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.shaderpack.PackFiles;
import dev.spacebod.aetherium.shaders.shaderpack.error.RusticError;
import dev.spacebod.aetherium.shaders.shaderpack.transform.line.LineTransform;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Directed graph of a pack's source files: one node per file, one edge per {@code #include} line.
 *
 * <ul>
 *     <li>Each file is read and scanned for {@code #include} once.</li>
 *     <li>Line transforms (e.g. option application) run once per file rather than once per inclusion, which matters
 *         for the common single settings file included by every program.</li>
 *     <li>Cycles are detected up front, so there is no include depth limit and no unbounded recursion.</li>
 * </ul>
 */
public class IncludeGraph {
	private final ImmutableMap<AbsolutePackPath, FileNode> nodes;
	private final ImmutableMap<AbsolutePackPath, RusticError> failures;

	private IncludeGraph(ImmutableMap<AbsolutePackPath, FileNode> nodes,
						 ImmutableMap<AbsolutePackPath, RusticError> failures) {
		this.nodes = nodes;
		this.failures = failures;
	}

	public IncludeGraph(Path root, ImmutableList<AbsolutePackPath> startingPaths, boolean isZip) {
		Map<AbsolutePackPath, AbsolutePackPath> cameFrom = new HashMap<>();
		Map<AbsolutePackPath, Integer> lineNumberInclude = new HashMap<>();

		Map<AbsolutePackPath, FileNode> nodes = new HashMap<>();
		Map<AbsolutePackPath, RusticError> failures = new HashMap<>();

		List<AbsolutePackPath> queue = new ArrayList<>(startingPaths);
		Set<AbsolutePackPath> seen = new HashSet<>(startingPaths);

		while (!queue.isEmpty()) {
			AbsolutePackPath next = queue.removeLast();

			String source;

			try {
				Path p = next.resolved(root);
				if (AetheriumShaders.getShaderConfig().areDebugOptionsEnabled() && !isZip) {
					String absolute = p.toAbsolutePath().toString().replace("\\", "/");
					absolute = absolute.substring(absolute.lastIndexOf("shaders/") + 8);

					String canonical = p.toFile().getCanonicalPath().replace("\\", "/");
					canonical = canonical.substring(canonical.lastIndexOf("shaders/") + 8);
					if (!absolute.equals(canonical)) {
						throw new FileIncludeException("'" + next.getPathString() + "' doesn't exist, did you mean '" + canonical + "'?");
					}
				}
				source = readFile(p);
			} catch (IOException e) {
				AbsolutePackPath src = cameFrom.get(next);

				if (src == null) {
					throw new RuntimeException("unexpected error: failed to read " + next.getPathString(), e);
				}

				String topLevelMessage;
				String detailMessage;
				if (e instanceof FileIncludeException) {
					topLevelMessage = "failed to resolve #include directive\n" + e.getMessage();
					detailMessage = "file not found";
				} else if (e instanceof NoSuchFileException) {
					topLevelMessage = "failed to resolve #include directive";
					detailMessage = "file not found";
				} else {
					topLevelMessage = "unexpected I/O error while resolving #include directive: " + e;
					detailMessage = "IO error";
				}

				String badLine = nodes.get(src).getLines().get(lineNumberInclude.get(next)).trim();

				RusticError topLevelError = new RusticError("error", topLevelMessage, detailMessage, src.getPathString(),
					lineNumberInclude.get(next) + 1, badLine);

				failures.put(next, topLevelError);

				continue;
			}

			ImmutableList<String> lines = ImmutableList.copyOf(source.split("\\R"));

			FileNode node = new FileNode(next, lines);
			boolean selfInclude = false;

			for (Map.Entry<Integer, AbsolutePackPath> include : node.getIncludes().entrySet()) {
				int line = include.getKey();
				AbsolutePackPath included = include.getValue();

				if (next.equals(included)) {
					selfInclude = true;
					failures.put(next, new RusticError("error", "trivial #include cycle detected",
						"file includes itself", next.getPathString(), line + 1, lines.get(line)));

					break;
				} else if (!seen.contains(included)) {
					queue.add(included);
					seen.add(included);
					cameFrom.put(included, next);
					lineNumberInclude.put(included, line);
				}
			}

			if (!selfInclude) {
				nodes.put(next, node);
			}
		}

		this.nodes = ImmutableMap.copyOf(nodes);
		this.failures = ImmutableMap.copyOf(failures);

		detectCycle();
	}

	private static String readFile(Path path) throws IOException {
		return PackFiles.readText(path);
	}

	private void detectCycle() {
		List<AbsolutePackPath> cycle = new ArrayList<>();
		Set<AbsolutePackPath> visited = new HashSet<>();

		for (AbsolutePackPath start : nodes.keySet()) {
			if (exploreForCycles(start, cycle, visited)) {
				AbsolutePackPath lastFilePath = null;

				StringBuilder error = new StringBuilder();

				for (AbsolutePackPath node : cycle) {
					if (lastFilePath == null) {
						lastFilePath = node;
						continue;
					}

					FileNode lastFile = nodes.get(lastFilePath);
					int lineNumber = -1;

					for (Map.Entry<Integer, AbsolutePackPath> include : lastFile.getIncludes().entrySet()) {
						if (include.getValue() == node) {
							lineNumber = include.getKey() + 1;
						}
					}

					String badLine = lastFile.getLines().get(lineNumber - 1);

					String detailMessage = node.equals(start) ? "final #include in cycle" : "#include involved in cycle";

					if (lastFilePath.equals(start)) {
						// first node in cycle
						error.append(new RusticError("error", "#include cycle detected",
							detailMessage, lastFilePath.getPathString(), lineNumber, badLine));
					} else {
						error.append("\n  = ").append(new RusticError("note", "cycle involves another file",
							detailMessage, lastFilePath.getPathString(), lineNumber, badLine));
					}

					lastFilePath = node;
				}

				error.append(
					"""
						  note: #include directives are resolved before any other preprocessor directives, any form of #include guard will not work

						  note: other cycles may still exist, only the first detected non-trivial cycle will be reported
						""");

				AetheriumShaders.logger.error(error.toString());

				throw new IllegalStateException("Cycle detected in #include graph, see previous messages for details");
			}
		}
	}

	private boolean exploreForCycles(AbsolutePackPath frontier, List<AbsolutePackPath> path, Set<AbsolutePackPath> visited) {
		if (visited.contains(frontier)) {
			path.add(frontier);
			return true;
		}

		path.add(frontier);
		visited.add(frontier);

		for (AbsolutePackPath included : nodes.get(frontier).getIncludes().values()) {
			if (!nodes.containsKey(included)) {
				// file that failed to load for another reason, error should already be reported
				continue;
			}

			if (exploreForCycles(included, path, visited)) {
				return true;
			}
		}

		path.removeLast();
		visited.remove(frontier);

		return false;
	}

	public ImmutableMap<AbsolutePackPath, FileNode> getNodes() {
		return nodes;
	}

	public IncludeGraph map(Function<AbsolutePackPath, LineTransform> transformProvider) {
		ImmutableMap.Builder<AbsolutePackPath, FileNode> mappedNodes = ImmutableMap.builder();

		nodes.forEach((path, node) -> mappedNodes.put(path, node.map(transformProvider.apply(path))));

		return new IncludeGraph(mappedNodes.build(), failures);
	}

	public ImmutableMap<AbsolutePackPath, RusticError> getFailures() {
		return failures;
	}
}
