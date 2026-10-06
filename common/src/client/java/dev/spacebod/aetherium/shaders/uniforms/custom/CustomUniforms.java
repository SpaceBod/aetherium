package dev.spacebod.aetherium.shaders.uniforms.custom;

import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import kroppeb.stareval.element.ExpressionElement;
import kroppeb.stareval.expression.Expression;
import kroppeb.stareval.expression.VariableExpression;
import kroppeb.stareval.function.FunctionContext;
import kroppeb.stareval.function.FunctionReturn;
import kroppeb.stareval.function.Type;
import kroppeb.stareval.parser.Parser;
import kroppeb.stareval.resolver.ExpressionResolver;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.uniform.LocationalUniformHolder;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;
import dev.spacebod.aetherium.shaders.parsing.ExpressionFunctions;
import dev.spacebod.aetherium.shaders.parsing.ExpressionOperators;
import dev.spacebod.aetherium.shaders.parsing.VectorType;
import dev.spacebod.aetherium.shaders.uniforms.SystemTimeUniforms;
import dev.spacebod.aetherium.shaders.uniforms.custom.cached.CachedUniform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;


/**
 * A pack's custom uniforms and variables ({@code uniform.<type>.<name>} and {@code variable.<type>.<name>} in
 * shaders.properties): expressions over the built-in uniforms and each other, evaluated in dependency order once per
 * frame. Variables are visible to programs like uniforms.
 */
public class CustomUniforms implements FunctionContext {
	private final Map<String, CachedUniform> variables = new Object2ObjectLinkedOpenHashMap<>();
	private final Map<String, Expression> variablesExpressions = new Object2ObjectLinkedOpenHashMap<>();
	private final CustomUniformFixedInputUniformsHolder inputHolder;
	private final List<CachedUniform> uniformOrder;
	private final Map<Object, Object2IntMap<CachedUniform>> locationMap = new Object2ObjectOpenHashMap<>();
	private final Map<CachedUniform, List<CachedUniform>> dependsOn;
	/** The reverse of {@link #dependsOn}: which uniforms read each one. */
	private final Map<CachedUniform, List<CachedUniform>> requiredBy;
	/** Uniforms whose evaluation threw, with everything that depends on them: never evaluated again, value kept. */
	private final Set<CachedUniform> failed = new ObjectOpenHashSet<>();
	/**
	 * Uniforms some program reads, with everything they depend on: only these are evaluated each frame. Grows as programs
	 * are built ({@link #assignTo}); {@link #activeOrder} keeps them in dependency order.
	 */
	private final Set<CachedUniform> active = new ObjectOpenHashSet<>();
	private final List<CachedUniform> activeOrder = new ArrayList<>();
	/**
	 * The frame ({@link SystemTimeUniforms.FrameCounter#frameId}) whose {@link #update} has run, or -1 before the first:
	 * uniforms a program starts reading after it are evaluated at once, before it they wait for it.
	 */
	private long updatedFrame = -1;

	private CustomUniforms(CustomUniformFixedInputUniformsHolder inputHolder, Map<String, Builder.Variable> variables) {
		this.inputHolder = inputHolder;
		ExpressionResolver resolver = new ExpressionResolver(
			ExpressionFunctions.functions,
			(name) -> {
				Type type = this.inputHolder.getType(name);
				if (type != null)
					return type;
				Builder.Variable variable = variables.get(name);
				if (variable != null)
					return variable.type;
				return null;
			},
			true);

		for (Builder.Variable variable : variables.values()) {
			try {
				Expression expression = resolver.resolveExpression(variable.type, variable.expression);
				CachedUniform cachedUniform = CachedUniform
					.forExpression(variable.name, variable.type, expression, this);
				this.addVariable(expression, cachedUniform);
			} catch (Exception e) {
				AetheriumShaders.logger
					.warn("Failed to resolve uniform " + variable.name + ", reason: " + e
						.getMessage() + " ( = " + variable.expression + ")", e);
			}
		}

		{
			// Topologically sort so each uniform updates after its dependencies.

			this.dependsOn = new Object2ObjectOpenHashMap<>();
			this.requiredBy = new Object2ObjectOpenHashMap<>();
			Object2IntMap<CachedUniform> dependsOnCount = new Object2IntOpenHashMap<>();

			for (CachedUniform input : this.inputHolder.getAll()) {
				requiredBy.put(input, new ObjectArrayList<>());
			}

			for (CachedUniform input : this.variables.values()) {
				requiredBy.put(input, new ObjectArrayList<>());
			}

			FunctionReturn functionReturn = new FunctionReturn();
			Set<VariableExpression> requires = new ObjectOpenHashSet<>();
			Set<CachedUniform> brokenUniforms = new ObjectOpenHashSet<>();

			for (Map.Entry<String, Expression> entry : this.variablesExpressions.entrySet()) {
				requires.clear();

				entry.getValue().listVariables(requires);
				if (requires.isEmpty()) {
					continue;
				}

				CachedUniform uniform = this.variables.get(entry.getKey());

				List<CachedUniform> dependencies = new ArrayList<>();
				for (VariableExpression v : requires) {
					Expression evaluated = v.partialEval(this, functionReturn);
					if (evaluated instanceof CachedUniform) {
						dependencies.add((CachedUniform) evaluated);
					} else {
						// we are depending on a broken uniform
						brokenUniforms.add(uniform);
					}
				}

				if (dependencies.isEmpty()) {
					// can be empty if we rely on broken uniforms
					continue;
				}

				dependsOn.put(uniform, dependencies);
				dependsOnCount.put(uniform, dependencies.size());

				for (CachedUniform dependency : dependencies) {
					requiredBy.get(dependency).add(uniform);
				}
			}

			// actual toposort:
			List<CachedUniform> ordered = new ObjectArrayList<>();
			List<CachedUniform> free = new ObjectArrayList<>();

			for (CachedUniform entry : requiredBy.keySet()) {
				if (!dependsOnCount.containsKey(entry)) {
					free.add(entry);
				}
			}

			while (!free.isEmpty()) {
				CachedUniform pop = free.removeLast();
				if (!brokenUniforms.contains(pop)) {
					// only add those that aren't broken
					ordered.add(pop);
				} else {
					// everything relying on it is broken too
					brokenUniforms.addAll(requiredBy.get(pop));
				}
				for (CachedUniform dependent : requiredBy.get(pop)) {
					int count = dependsOnCount.mergeInt(dependent, -1, Integer::sum);
					assert count >= 0;
					if (count == 0) {
						free.add(dependent);
						dependsOnCount.removeInt(dependent);
					}
				}
			}

			if (!brokenUniforms.isEmpty()) {
				AetheriumShaders.logger.warn(
					"The following uniforms won't work, either because they are broken, or reference a broken uniform: \n" +
						brokenUniforms.stream().map(CachedUniform::getName).collect(Collectors.joining(", ")));
			}

			if (!dependsOnCount.isEmpty()) {
				// What is left never became free: uniforms in a dependency cycle and the ones that depend on them. They
				// are dropped (programs reading them see 0); the rest of the pack's uniforms work.
				AetheriumShaders.logger.error("these custom uniforms depend on each other in a cycle (or on such a uniform) and are ignored: "
					+ dependsOnCount.keySet().stream().map(CachedUniform::getName).sorted().collect(Collectors.joining(", ")));
			}

			this.uniformOrder = ordered;
		}
	}

	private void addVariable(Expression expression, CachedUniform uniform) throws Exception {
		String name = uniform.getName();
		if (this.variables.containsKey(name))
			throw new Exception("Duplicated variable: " + name);
		if (this.inputHolder.containsKey(name))
			throw new Exception("Variable shadows build in uniform: " + name);

		this.variables.put(name, uniform);
		this.variablesExpressions.put(name, expression);
	}

	public void assignTo(LocationalUniformHolder targetHolder) {
		Object2IntMap<CachedUniform> locations = new Object2IntOpenHashMap<>();
		for (CachedUniform uniform : this.uniformOrder) {
			try {
				OptionalInt location = targetHolder.location(uniform.getName(), Type.convert(uniform.getType()));
				if (location.isPresent()) {
					locations.put(uniform, location.getAsInt());
				}
			} catch (RuntimeException e) {
				AetheriumShaders.logger.error("the custom uniform {} cannot be bound: {}", uniform.getName(), e.getMessage());
			}
		}
		this.locationMap.put(targetHolder, locations);
		activate(locations.keySet());
	}

	/**
	 * Adds {@code used} and their dependencies to the per-frame evaluation. When this frame's {@link #update} has already
	 * run, the new ones are evaluated right away (in dependency order), so a program built mid-frame never pushes a value
	 * skipped until now; before it, they wait for it, so nothing is evaluated (and no {@code smooth()} stepped) twice in a
	 * frame.
	 */
	private void activate(Set<CachedUniform> used) {
		List<CachedUniform> pending = new ArrayList<>(used);
		Set<CachedUniform> added = new ObjectOpenHashSet<>();
		while (!pending.isEmpty()) {
			CachedUniform uniform = pending.removeLast();
			if (!failed.contains(uniform) && active.add(uniform)) {
				added.add(uniform);
				List<CachedUniform> dependencies = this.dependsOn.get(uniform);
				if (dependencies != null) {
					pending.addAll(dependencies);
				}
			}
		}
		if (added.isEmpty()) {
			return;
		}
		boolean updatedThisFrame = updatedFrame == SystemTimeUniforms.COUNTER.frameId();
		activeOrder.clear();
		for (CachedUniform uniform : this.uniformOrder) {
			if (active.contains(uniform)) {
				activeOrder.add(uniform);
				// Programs built after this frame's update: evaluated now, so their first push is current.
				if (updatedThisFrame && added.contains(uniform)) {
					evaluate(uniform);
				}
			}
		}
		if (!failed.isEmpty()) {
			activeOrder.removeIf(failed::contains);
		}
	}

	public void mapholderToPass(LocationalUniformHolder holder, Object pass) {
		locationMap.put(pass, locationMap.remove(holder));
	}


	/**
	 * Evaluates, once per frame, every uniform a program reads (and what they depend on); the rest are skipped. A uniform
	 * that throws is switched off with everything depending on it (logged once); the others keep updating.
	 */
	public void update() {
		boolean anyFailed = false;
		for (int i = 0; i < this.activeOrder.size(); i++) {
			CachedUniform value = this.activeOrder.get(i);
			if (!failed.contains(value) && !evaluate(value)) {
				anyFailed = true;
			}
		}
		if (anyFailed) {
			activeOrder.removeIf(failed::contains);
		}
		updatedFrame = SystemTimeUniforms.COUNTER.frameId();
	}

	/** Updates {@code uniform}; false (and it and its dependents switched off) when its expression threw. */
	private boolean evaluate(CachedUniform uniform) {
		try {
			uniform.update();
			return true;
		} catch (RuntimeException e) {
			List<String> dropped = new ArrayList<>();
			List<CachedUniform> pending = new ArrayList<>();
			pending.add(uniform);
			while (!pending.isEmpty()) {
				CachedUniform next = pending.removeLast();
				if (failed.add(next)) {
					dropped.add(next.getName());
					List<CachedUniform> dependents = requiredBy.get(next);
					if (dependents != null) {
						pending.addAll(dependents);
					}
				}
			}
			AetheriumShaders.logger.error("the custom uniform {} failed to evaluate ({}); it keeps its last value, as do {}",
				uniform.getName(), e.toString(), String.join(", ", dropped));
			return false;
		}
	}

	public void push(Object pass) {
		Object2IntMap<CachedUniform> uniforms = this.locationMap.get(pass);
		if (uniforms != null) {
			// Primitive iteration: forEach(BiConsumer) boxes every location.
			for (Object2IntMap.Entry<CachedUniform> e : Object2IntMaps.fastIterable(uniforms)) {
				e.getKey().push(e.getIntValue());
			}
		}
	}

	@Override
	public boolean hasVariable(String name) {
		return this.inputHolder.containsKey(name) || this.variables.containsKey(name);
	}

	@Override
	public Expression getVariable(String name) {
		final CachedUniform inputUniform = this.inputHolder.getUniform(name);
		if (inputUniform != null)
			return inputUniform;
		final CachedUniform customUniform = this.variables.get(name);
		if (customUniform != null)
			return customUniform;
		throw new RuntimeException("Unknown variable: " + name);
	}

	public static class Builder {
		final private static Map<String, Type> types = new ImmutableMap.Builder<String, Type>()
			.put("bool", Type.Boolean)
			.put("float", Type.Float)
			.put("int", Type.Int)
			.put("vec2", VectorType.VEC2)
			.put("vec3", VectorType.VEC3)
			.put("vec4", VectorType.VEC4)
			.build();
		final Map<String, Variable> variables = new Object2ObjectLinkedOpenHashMap<>();

		/** The names of every custom uniform and variable the pack defines. */
		public Set<String> names() {
			return Collections.unmodifiableSet(variables.keySet());
		}

		public void addVariable(String type, String name, String expression, boolean isUniform) {
			if (variables.containsKey(name)) {
				AetheriumShaders.logger.warn("Ignoring duplicated custom uniform name: " + name);
				return;
			}

			Type parsedType = types.get(type);
			if (parsedType == null) {
				AetheriumShaders.logger.warn("Ignoring invalid uniform type: " + type + " of " + name);
				return;
			}

			try {
				ExpressionElement ast = Parser.parse(expression, ExpressionOperators.options);
				variables.put(name, new Variable(parsedType, name, ast, isUniform));
			} catch (Exception e) {
				AetheriumShaders.logger.warn("Failed to parse custom variable/uniform " + name + " with expression " + expression, e);
			}
		}

		public CustomUniforms build(
			CustomUniformFixedInputUniformsHolder inputHolder
		) {
			return new CustomUniforms(inputHolder, this.variables);
		}

		@SafeVarargs
		public final CustomUniforms build(
			Consumer<UniformHolder>... uniforms
		) {
			CustomUniformFixedInputUniformsHolder.Builder inputs = new CustomUniformFixedInputUniformsHolder.Builder();
			for (Consumer<UniformHolder> uniform : uniforms) {
				uniform.accept(inputs);
			}
			return this.build(inputs.build());
		}

		private record Variable(Type type, String name, ExpressionElement expression, boolean uniform) {
		}


	}
}
