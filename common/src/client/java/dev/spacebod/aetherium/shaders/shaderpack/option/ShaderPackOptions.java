package dev.spacebod.aetherium.shaders.shaderpack.option;

import com.google.common.collect.ImmutableMap;
import dev.spacebod.aetherium.shaders.shaderpack.include.AbsolutePackPath;
import dev.spacebod.aetherium.shaders.shaderpack.include.IncludeGraph;
import dev.spacebod.aetherium.shaders.shaderpack.option.values.MutableOptionValues;
import dev.spacebod.aetherium.shaders.shaderpack.option.values.OptionValues;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Discovers, merges and applies shader pack options to an existing {@link IncludeGraph}.
 */
public class ShaderPackOptions {
	private final OptionSet optionSet;
	private final OptionValues optionValues;
	private final IncludeGraph includes;

	public ShaderPackOptions(IncludeGraph graph, Map<String, String> changedConfigs) {
		OptionSet.Builder setBuilder = OptionSet.builder();

		ImmutableMap.Builder<AbsolutePackPath, OptionAnnotatedSource> annotationBuilder = ImmutableMap.builder();
		Set<String> referencedBooleanDefines = new HashSet<>();

		graph.getNodes().forEach((path, node) -> {
			OptionAnnotatedSource annotatedSource = new OptionAnnotatedSource(node.getLines());
			annotationBuilder.put(path, annotatedSource);
			referencedBooleanDefines.addAll(annotatedSource.getBooleanDefineReferences().keySet());
		});

		ImmutableMap<AbsolutePackPath, OptionAnnotatedSource> annotations = annotationBuilder.build();
		Set<String> referencedBooleanDefinesU = Collections.unmodifiableSet(referencedBooleanDefines);

		annotations.forEach((path, annotatedSource) -> setBuilder.addAll(annotatedSource.getOptionSet(path, referencedBooleanDefinesU)));

		this.optionSet = setBuilder.build();
		this.optionValues = new MutableOptionValues(optionSet, changedConfigs);

		this.includes = graph.map(path -> annotations.get(path).asTransform(optionValues));
	}

	public OptionSet getOptionSet() {
		return optionSet;
	}

	public OptionValues getOptionValues() {
		return optionValues;
	}

	public IncludeGraph getIncludes() {
		return includes;
	}
}
