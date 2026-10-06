package dev.spacebod.aetherium.shaders.shaderpack.parsing;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.shaderpack.option.values.OptionValues;

import java.util.EmptyStackException;
import java.util.Stack;

public class BooleanParser {
	/**
	 * Evaluates a boolean expression over pack option names ({@code !}, {@code &&}, {@code ||}, brackets).
	 *
	 * @param valueLookup option values used to resolve names; null treats every name as false
	 * @return result of the expression, or true if it failed to parse
	 */
	public static boolean parse(String expression, OptionValues valueLookup) {
		try {
			StringBuilder option = new StringBuilder();
			Stack<Operation> operationStack = new Stack<>();
			Stack<Boolean> valueStack = new Stack<>();
			for (int i = 0; i < expression.length(); i++) {
				char c = expression.charAt(i);
				switch (c) {
					case '!' -> operationStack.push(Operation.NOT);
					case '&' -> {
						// add value first, because this checks for preceding NOTs
						if (!option.isEmpty()) {
							valueStack.push(processValue(option.toString(), valueLookup, operationStack));
							option = new StringBuilder();
						}
						// AND operators have priority, so add a bracket if it's the first AND
						if (operationStack.isEmpty() || !operationStack.peek().equals(Operation.AND)) {
							operationStack.push(Operation.OPEN);
						}
						i++;
						operationStack.push(Operation.AND);
					}
					case '|' -> {
						// add value first, because this checks for preceding NOTs
						if (!option.isEmpty()) {
							valueStack.push(processValue(option.toString(), valueLookup, operationStack));
							option = new StringBuilder();
						}
						// if there was an AND before, that needs to be evaluated because it takes priority
						if (!operationStack.isEmpty() && operationStack.peek().equals(Operation.AND)) {
							evaluate(operationStack, valueStack, true);
						}
						i++;
						operationStack.push(Operation.OR);
					}
					case '(' -> operationStack.push(Operation.OPEN);
					case ')' -> {
						// add value first, because this checks for preceding NOTs
						if (!option.isEmpty()) {
							valueStack.push(processValue(option.toString(), valueLookup, operationStack));
							option = new StringBuilder();
						}
						// if there was an AND before, that needs to be evaluated because it added its own bracket
						if (!operationStack.isEmpty() && operationStack.peek().equals(Operation.AND)) {
							evaluate(operationStack, valueStack, true);
						}
						evaluate(operationStack, valueStack, true);
					}
					case ' ' -> {
					}
					default -> option.append(c);
				}
			}
			if (!option.isEmpty()) {
				valueStack.push(processValue(option.toString(), valueLookup, operationStack));
			}
			evaluate(operationStack, valueStack, false);
			boolean result = valueStack.pop();
			if (!valueStack.isEmpty() || !operationStack.isEmpty()) {
				AetheriumShaders.logger.warn(
					"Failed to parse the following boolean operation correctly, stacks not empty, defaulting to true!: '{}'",
					expression);
				return true;
			}
			return result;
		} catch (EmptyStackException emptyStackException) {
			AetheriumShaders.logger.warn(
				"Failed to parse the following boolean operation correctly, stacks empty when it shouldn't, defaulting to true!: '{}'",
				expression);
			return true;
		}
	}

	/**
	 * Resolves a literal or option name, applying a pending NOT from the top of the operation stack.
	 */
	private static boolean processValue(String value, OptionValues valueLookup, Stack<Operation> operationStack) {
		boolean booleanValue = switch (value) {
			case "true", "1" -> true;
			case "false", "0" -> false;
			default -> valueLookup != null && valueLookup.getBooleanValueOrDefault(value);
		};
		if (!operationStack.isEmpty() && operationStack.peek() == Operation.NOT) {
			// if there is a NOT, that needs to be handled immediately
			operationStack.pop();
			return !booleanValue;
		} else {
			return booleanValue;
		}
	}

	/**
	 * Evaluates the operation stack backwards, either to the next open bracket or all the way.
	 *
	 * @param currentBracket stop at (and consume) the innermost open bracket
	 */
	private static void evaluate(Stack<Operation> operationStack, Stack<Boolean> valueStack, boolean currentBracket) {
		boolean value = valueStack.pop();
		while (!operationStack.isEmpty() && (!currentBracket || operationStack.peek() != Operation.OPEN)) {
			value = operationStack.pop().compute(value, valueStack);
		}

		// if there is a bracket check if the whole bracket should be negated
		if (!operationStack.isEmpty() && operationStack.peek() == Operation.OPEN) {
			operationStack.pop();
			if (!operationStack.isEmpty() && operationStack.peek() == Operation.NOT) {
				value = operationStack.pop().compute(value, valueStack);
			}
		}
		valueStack.push(value);
	}

	private enum Operation {
		AND {
			@Override
			boolean compute(boolean value, Stack<Boolean> valueStack) {
				return valueStack.pop() && value;
			}
		}, OR {
			@Override
			boolean compute(boolean value, Stack<Boolean> valueStack) {
				return valueStack.pop() || value;
			}
		}, NOT {
			@Override
			boolean compute(boolean value, Stack<Boolean> valueStack) {
				return !value;
			}
		}, OPEN;

		boolean compute(boolean value, Stack<Boolean> valueStack) {
			return value;
		}
	}
}
