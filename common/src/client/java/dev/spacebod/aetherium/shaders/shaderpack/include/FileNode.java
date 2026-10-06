package dev.spacebod.aetherium.shaders.shaderpack.include;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import dev.spacebod.aetherium.shaders.shaderpack.transform.line.LineTransform;

import java.util.Objects;

public class FileNode {
	private final AbsolutePackPath path;
	private final ImmutableList<String> lines;
	private final ImmutableMap<Integer, AbsolutePackPath> includes;

	// The caller must keep includes in sync with lines.
	private FileNode(AbsolutePackPath path, ImmutableList<String> lines,
					 ImmutableMap<Integer, AbsolutePackPath> includes) {
		this.path = path;
		this.lines = lines;
		this.includes = includes;
	}

	public FileNode(AbsolutePackPath path, ImmutableList<String> lines) {
		this.path = path;
		this.lines = lines;

		AbsolutePackPath currentDirectory = path.parent().orElseThrow(
			() -> new IllegalArgumentException("Not a valid shader file name: " + path));

		this.includes = findIncludes(currentDirectory, lines);
	}

	private static ImmutableMap<Integer, AbsolutePackPath> findIncludes(AbsolutePackPath currentDirectory,
																		ImmutableList<String> lines) {
		ImmutableMap.Builder<Integer, AbsolutePackPath> foundIncludes = ImmutableMap.builder();

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i).trim();

			if (!line.startsWith("#include")) {
				continue;
			}

			String target = line.substring("#include ".length()).trim();

			// Quotes are optional; a quote at only one end is dropped as well.
			if (target.startsWith("\"")) {
				target = target.substring(1);
			}

			if (target.endsWith("\"")) {
				target = target.substring(0, target.length() - 1);
			}

			foundIncludes.put(i, currentDirectory.resolve(target));
		}

		return foundIncludes.build();
	}

	public AbsolutePackPath getPath() {
		return path;
	}

	public ImmutableList<String> getLines() {
		return lines;
	}

	public ImmutableMap<Integer, AbsolutePackPath> getIncludes() {
		return includes;
	}

	public FileNode map(LineTransform transform) {
		ImmutableList.Builder<String> newLines = ImmutableList.builder();
		int index = 0;

		for (String line : lines) {
			String transformedLine = transform.transform(index, line);

			if (includes.containsKey(index)) {
				if (!Objects.equals(line, transformedLine)) {
					throw new IllegalStateException("Attempted to modify an #include line in LineTransform.");
				}
			}

			newLines.add(transformedLine);
			index += 1;
		}

		return new FileNode(path, newLines.build(), includes);
	}
}
