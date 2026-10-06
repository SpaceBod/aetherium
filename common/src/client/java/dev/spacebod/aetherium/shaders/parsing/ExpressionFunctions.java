package dev.spacebod.aetherium.shaders.parsing;

import kroppeb.stareval.expression.Expression;
import kroppeb.stareval.function.AbstractTypedFunction;
import kroppeb.stareval.function.B2BFunction;
import kroppeb.stareval.function.BB2BFunction;
import kroppeb.stareval.function.F2FFunction;
import kroppeb.stareval.function.F2IFunction;
import kroppeb.stareval.function.FF2BFunction;
import kroppeb.stareval.function.FF2FFunction;
import kroppeb.stareval.function.FFF2BFunction;
import kroppeb.stareval.function.FFF2FFunction;
import kroppeb.stareval.function.FunctionContext;
import kroppeb.stareval.function.FunctionResolver;
import kroppeb.stareval.function.FunctionReturn;
import kroppeb.stareval.function.I2IFunction;
import kroppeb.stareval.function.II2BFunction;
import kroppeb.stareval.function.II2IFunction;
import kroppeb.stareval.function.III2BFunction;
import kroppeb.stareval.function.III2IFunction;
import kroppeb.stareval.function.Type;
import kroppeb.stareval.function.TypedFunction;
import kroppeb.stareval.function.TypedFunction.Parameter;
import kroppeb.stareval.function.V2FFunction;
import kroppeb.stareval.function.V2IFunction;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Functions available to expressions in shaders.properties (custom uniforms and variables): the pack-format set
 * (trigonometry, min/max/clamp, fmod, random, if, smooth, between, equals, in, ...) plus GLSL-style builtins,
 * operators, casts, vector constructors and swizzle/column accessors.
 * <p>
 * {@code smooth([id], val, [fadeTime | fadeUpTime, fadeDownTime])} smooths a value over time (fade times
 * default to 1); the optional {@code id} is accepted for compatibility and ignored.
 */
public class ExpressionFunctions {
	public static final FunctionResolver functions;
	static final FunctionResolver.Builder builder = new FunctionResolver.Builder();
	/** Before the static block below, which registers the component-wise functions. */
	private static final List<FloatVector<?>> FLOAT_VECTORS = List.of(
		new FloatVector<>(VectorType.VEC2, 2, Vector2f::get, Vector2f::setComponent),
		new FloatVector<>(VectorType.VEC3, 3, Vector3f::get, Vector3f::setComponent),
		new FloatVector<>(VectorType.VEC4, 4, Vector4f::get, Vector4f::setComponent));

	static {
		{
			// Unary ops
			{
				// negate
				ExpressionFunctions.<I2IFunction>addVectorizable("negate", (a) -> -a);
				ExpressionFunctions.<F2FFunction>add("negate", (a) -> -a);

				ExpressionFunctions.addUnaryOpJOML("negate", VectorType.VEC2, Vector2f::negate);
				ExpressionFunctions.addUnaryOpJOML("negate", VectorType.VEC3, Vector3f::negate);
				ExpressionFunctions.addUnaryOpJOML("negate", VectorType.VEC4, Vector4f::negate);
			}
		}
		{
			// binary ops
			{
				// add
				ExpressionFunctions.<II2IFunction>addVectorizable("add", Integer::sum);
				ExpressionFunctions.<FF2FFunction>add("add", Float::sum);

				ExpressionFunctions.addBinaryOpJOML("add", VectorType.VEC2, Vector2f::add);
				ExpressionFunctions.addBinaryOpJOML("add", VectorType.VEC3, Vector3f::add);
				ExpressionFunctions.addBinaryOpJOML("add", VectorType.VEC4, Vector4f::add);
			}

			{
				// subtract
				ExpressionFunctions.<II2IFunction>addVectorizable("subtract", (a, b) -> a - b);
				ExpressionFunctions.<FF2FFunction>add("subtract", (a, b) -> a - b);

				ExpressionFunctions.addBinaryOpJOML("subtract", VectorType.VEC2, Vector2f::sub);
				ExpressionFunctions.addBinaryOpJOML("subtract", VectorType.VEC3, Vector3f::sub);
				ExpressionFunctions.addBinaryOpJOML("subtract", VectorType.VEC4, Vector4f::sub);
			}

			{
				// multiply
				ExpressionFunctions.<II2IFunction>addVectorizable("multiply", (a, b) -> a * b);
				ExpressionFunctions.<FF2FFunction>add("multiply", (a, b) -> a * b);

				ExpressionFunctions.addBinaryOpJOML("multiply", VectorType.VEC2, Vector2f::mul);
				ExpressionFunctions.addBinaryOpJOML("multiply", VectorType.VEC3, Vector3f::mul);
				ExpressionFunctions.addBinaryOpJOML("multiply", VectorType.VEC4, Vector4f::mul);
			}

			{
				// divide
				ExpressionFunctions.<FF2FFunction>add("divide", (a, b) -> a / b);

				ExpressionFunctions.addBinaryOpJOML("divide", VectorType.VEC2, Vector2f::div);
				ExpressionFunctions.addBinaryOpJOML("divide", VectorType.VEC3, Vector3f::div);
				ExpressionFunctions.addBinaryOpJOML("divide", VectorType.VEC4, Vector4f::div);
			}

			{
				// remainder
				ExpressionFunctions.<II2IFunction>addVectorizable("remainder", (a, b) -> a % b);
				ExpressionFunctions.<FF2FFunction>add("remainder", (a, b) -> a % b);
			}
		}
		{
			ExpressionFunctions.<II2BFunction>addBooleanVectorizable("equals", (a, b) -> a == b);
			ExpressionFunctions.<FF2BFunction>add("equals", (a, b) -> a == b);

			ExpressionFunctions.addBinaryToBooleanOpJOML("equal", VectorType.VEC2, false, Vector2f::equals);
			ExpressionFunctions.addBinaryToBooleanOpJOML("equal", VectorType.VEC3, false, Vector3f::equals);
			ExpressionFunctions.addBinaryToBooleanOpJOML("equal", VectorType.VEC4, false, Vector4f::equals);

			ExpressionFunctions.<II2BFunction>addBooleanVectorizable("notEquals", (a, b) -> a != b);
			ExpressionFunctions.<FF2BFunction>add("notEquals", (a, b) -> a != b);

			ExpressionFunctions.addBinaryToBooleanOpJOML("equal", VectorType.VEC2, true, Vector2f::equals);
			ExpressionFunctions.addBinaryToBooleanOpJOML("equal", VectorType.VEC3, true, Vector3f::equals);
			ExpressionFunctions.addBinaryToBooleanOpJOML("equal", VectorType.VEC4, true, Vector4f::equals);

			ExpressionFunctions.<II2BFunction>add("lessThanOrEquals", (a, b) -> a <= b);
			ExpressionFunctions.<FF2BFunction>add("lessThanOrEquals", (a, b) -> a <= b);

			ExpressionFunctions.<II2BFunction>add("moreThanOrEquals", (a, b) -> a >= b);
			ExpressionFunctions.<FF2BFunction>add("moreThanOrEquals", (a, b) -> a >= b);

			ExpressionFunctions.<II2BFunction>add("lessThan", (a, b) -> a < b);
			ExpressionFunctions.<FF2BFunction>add("lessThan", (a, b) -> a < b);

			ExpressionFunctions.<II2BFunction>add("moreThan", (a, b) -> a > b);
			ExpressionFunctions.<FF2BFunction>add("moreThan", (a, b) -> a > b);
		}
		{

			ExpressionFunctions.<BB2BFunction>addVectorizable("equals", (a, b) -> a == b);
			ExpressionFunctions.<BB2BFunction>addVectorizable("notEquals", (a, b) -> a != b);
			ExpressionFunctions.<BB2BFunction>addVectorizable("and", (a, b) -> a && b);
			ExpressionFunctions.<BB2BFunction>addVectorizable("or", (a, b) -> a || b);
			ExpressionFunctions.<B2BFunction>addVectorizable("not", (a) -> !a);
		}

		{
			// these are also vectorizable in glsl
			// http://learnwebgl.brown37.net/12_shader_language/documents/webgl-reference-card-1_0.pdf
			// page 4

			{
				// Angle & Trigonometry Functions

				// pack format
				ExpressionFunctions.<F2FFunction>add("torad", (a) -> (float) Math.toRadians(a));
				ExpressionFunctions.<F2FFunction>add("todeg", (a) -> (float) Math.toDegrees(a));

				ExpressionFunctions.<F2FFunction>add("radians", (a) -> (float) Math.toRadians(a));
				ExpressionFunctions.<F2FFunction>add("degrees", (a) -> (float) Math.toDegrees(a));


				ExpressionFunctions.<F2FFunction>add("sin", (a) -> (float) Math.sin(a));
				ExpressionFunctions.<F2FFunction>add("cos", (a) -> (float) Math.cos(a));
				ExpressionFunctions.<F2FFunction>add("tan", (a) -> (float) Math.tan(a));
				ExpressionFunctions.<F2FFunction>add("asin", (a) -> (float) Math.asin(a));
				ExpressionFunctions.<F2FFunction>add("acos", (a) -> (float) Math.acos(a));
				ExpressionFunctions.<F2FFunction>add("atan", (a) -> (float) Math.atan(a));
				ExpressionFunctions.<FF2FFunction>add("atan", (y, x) -> (float) Math.atan2(y, x));
				// pack format
				ExpressionFunctions.<FF2FFunction>add("atan2", (y, x) -> (float) Math.atan2(y, x));
			}
			{
				// Exponential Functions
				ExpressionFunctions.<FF2FFunction>add("pow", (a, b) -> (float) Math.pow(a, b));
				ExpressionFunctions.<F2FFunction>add("exp", (a) -> (float) Math.exp(a));
				ExpressionFunctions.<F2FFunction>add("log", (a) -> (float) Math.log(a));
				// Java has no exp2/log2 builtins: https://bugs.java.com/bugdatabase/view_bug.do?bug_id=4851627
				ExpressionFunctions.<F2FFunction>add("exp2", (a) -> (float) Math.pow(2, a));
				ExpressionFunctions.<F2FFunction>add("log2", (a) -> (float) (Math.log(a) / Math.log(2)));

				ExpressionFunctions.<F2FFunction>add("sqrt", (a) -> (float) Math.sqrt(a));

				// pack format
				ExpressionFunctions.<F2FFunction>add("log10", (a) -> (float) Math.log10(a));

				ExpressionFunctions.<FF2FFunction>add("log",
					(base, value) -> (float) (Math.log(value) / Math.log(base)));

				// Counterpart to log10.
				ExpressionFunctions.<F2FFunction>add("exp10", (a) -> (float) Math.pow(10, a));

			}

			{
				// Common Functions
				ExpressionFunctions.<I2IFunction>addVectorizable("abs", Math::abs);
				ExpressionFunctions.<F2FFunction>add("abs", Math::abs);

				ExpressionFunctions.addUnaryOpJOML("abs", VectorType.VEC2, Vector2f::absolute);
				ExpressionFunctions.addUnaryOpJOML("abs", VectorType.VEC3, Vector3f::absolute);
				ExpressionFunctions.addUnaryOpJOML("abs", VectorType.VEC4, Vector4f::absolute);


				ExpressionFunctions.<F2FFunction>add("sign", Math::signum);

				// pack format
				ExpressionFunctions.<F2FFunction>add("signum", Math::signum);

				// Both overloads: the type checker picks (float) -> int where an int is wanted, so no cast is needed,
				// and (float) -> float otherwise, avoiding a lossy float -> int -> float round trip for large values.

				ExpressionFunctions.<F2FFunction>add("floor", (a) -> (float) Math.floor(a));
				ExpressionFunctions.<F2IFunction>add("floor", (a) -> (int) Math.floor(a));

				ExpressionFunctions.addUnaryOpJOML("floor", VectorType.VEC2, Vector2f::floor);
				ExpressionFunctions.addUnaryOpJOML("floor", VectorType.VEC3, Vector3f::floor);
				ExpressionFunctions.addUnaryOpJOML("floor", VectorType.VEC4, Vector4f::floor);

				ExpressionFunctions.<F2FFunction>add("ceil", (a) -> (float) Math.ceil(a));
				ExpressionFunctions.<F2IFunction>add("ceil", (a) -> (int) Math.ceil(a));

				ExpressionFunctions.addUnaryOpJOML("ceil", VectorType.VEC2, Vector2f::ceil);
				ExpressionFunctions.addUnaryOpJOML("ceil", VectorType.VEC3, Vector3f::ceil);
				ExpressionFunctions.addUnaryOpJOML("ceil", VectorType.VEC4, Vector4f::ceil);

				ExpressionFunctions.<F2FFunction>add("frac", (a) -> (float) (a - Math.floor(a)));

				// Halves round up (towards positive infinity).
				ExpressionFunctions.<F2FFunction>add("round", (a) -> (float) Math.floor(a + 0.5f));
				ExpressionFunctions.<F2IFunction>add("round", (a) -> (int) Math.floor(a + 0.5f));
				addComponentWise("round", new boolean[]{false}, args -> (float) Math.floor(args[0] + 0.5f));

				// mod is also already an operator

				ExpressionFunctions.<II2IFunction>addVectorizable("min", Math::min);
				ExpressionFunctions.<FF2FFunction>add("min", Math::min);

				ExpressionFunctions.addBinaryOpJOML("min", VectorType.VEC2, Vector2f::min);
				ExpressionFunctions.addBinaryOpJOML("min", VectorType.VEC3, Vector3f::min);
				ExpressionFunctions.addBinaryOpJOML("min", VectorType.VEC4, Vector4f::min);

				ExpressionFunctions.<II2IFunction>addVectorizable("max", Math::max);
				ExpressionFunctions.<FF2FFunction>add("max", Math::max);

				ExpressionFunctions.addBinaryOpJOML("max", VectorType.VEC2, Vector2f::max);
				ExpressionFunctions.addBinaryOpJOML("max", VectorType.VEC3, Vector3f::max);
				ExpressionFunctions.addBinaryOpJOML("max", VectorType.VEC4, Vector4f::max);

				// min(vecN, float) and max(vecN, float), as in GLSL.
				addComponentWise("min", new boolean[]{false, true}, args -> Math.min(args[0], args[1]));
				addComponentWise("max", new boolean[]{false, true}, args -> Math.max(args[0], args[1]));

				{
					// Fake varargs: fixed-arity overloads for 3..16 arguments.
					for (int length = 3; length <= 16; length++) {
						{
							// min float
							Type[] inputs = new Type[length];
							Arrays.fill(inputs, Type.Float);
							ExpressionFunctions.add("min", new AbstractTypedFunction(Type.Float, inputs) {
								@Override
								public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
									params[0].evaluateTo(context, functionReturn);
									float min = functionReturn.floatReturn;
									for (int i = 1; i < params.length; i++) {
										params[i].evaluateTo(context, functionReturn);
										min = Math.min(min, functionReturn.floatReturn);
									}
									functionReturn.floatReturn = min;
								}
							});
						}
						{
							// max float
							Type[] inputs = new Type[length];
							Arrays.fill(inputs, Type.Float);
							ExpressionFunctions.add("max", new AbstractTypedFunction(Type.Float, inputs) {
								@Override
								public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
									params[0].evaluateTo(context, functionReturn);
									float max = functionReturn.floatReturn;
									for (int i = 1; i < params.length; i++) {
										params[i].evaluateTo(context, functionReturn);
										max = Math.max(max, functionReturn.floatReturn);
									}
									functionReturn.floatReturn = max;
								}
							});
						}
						{
							// min int
							Type[] inputs = new Type[length];
							Arrays.fill(inputs, Type.Int);
							ExpressionFunctions.addVectorizable("min", new AbstractTypedFunction(Type.Int, inputs) {
								@Override
								public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
									params[0].evaluateTo(context, functionReturn);
									int min = functionReturn.intReturn;
									for (int i = 1; i < params.length; i++) {
										params[i].evaluateTo(context, functionReturn);
										min = Math.min(min, functionReturn.intReturn);
									}
									functionReturn.intReturn = min;
								}
							});
						}
						{
							// max int
							Type[] inputs = new Type[length];
							Arrays.fill(inputs, Type.Int);
							ExpressionFunctions.addVectorizable("max", new AbstractTypedFunction(Type.Int, inputs) {
								@Override
								public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
									params[0].evaluateTo(context, functionReturn);
									int max = functionReturn.intReturn;
									for (int i = 1; i < params.length; i++) {
										params[i].evaluateTo(context, functionReturn);
										max = Math.max(max, functionReturn.intReturn);
									}
									functionReturn.intReturn = max;
								}
							});
						}
					}
				}

				// if max < min => undefined behaviour
				ExpressionFunctions.<III2IFunction>addVectorizable("clamp",
					(val, min, max) -> Math.max(min, Math.min(max, val)));
				ExpressionFunctions.<FFF2FFunction>add("clamp",
					(val, min, max) -> Math.max(min, Math.min(max, val)));
				ExpressionFunctions.addTernaryOpJOML("clamp", VectorType.VEC2, (val, min, max, dest) -> {
					val.min(max, dest);
					dest.max(min);
				});
				ExpressionFunctions.addTernaryOpJOML("clamp", VectorType.VEC3, (val, min, max, dest) -> {
					val.min(max, dest);
					dest.max(min);
				});
				ExpressionFunctions.addTernaryOpJOML("clamp", VectorType.VEC4, (val, min, max, dest) -> {
					val.min(max, dest);
					dest.max(min);
				});

				// clamp(vecN, float, float)
				addComponentWise("clamp", new boolean[]{false, true, true}, args -> Math.max(args[1], Math.min(args[2], args[0])));

				// mix(x, y, a) = x + (y - x) * a, for floats, vectors, and vectors with a float a.
				ExpressionFunctions.<FFF2FFunction>add("mix", (x, y, a) -> x + (y - x) * a);
				addComponentWise("mix", new boolean[]{false, false, false}, args -> args[0] + (args[1] - args[0]) * args[2]);
				addComponentWise("mix", new boolean[]{false, false, true}, args -> args[0] + (args[1] - args[0]) * args[2]);

				// step(edge, x): 0 below the edge, else 1; edge(edge, x) is the same function.
				ExpressionFunctions.<II2IFunction>addVectorizable("edge", (edge, x) -> (x < edge) ? 0 : 1);
				ExpressionFunctions.<FF2FFunction>add("edge", (edge, x) -> (x < edge) ? 0 : 1);
				ExpressionFunctions.<FF2FFunction>add("step", (edge, x) -> (x < edge) ? 0 : 1);
				addComponentWise("step", new boolean[]{false, false}, args -> args[1] < args[0] ? 0 : 1);
				addComponentWise("step", new boolean[]{true, false}, args -> args[1] < args[0] ? 0 : 1);

				// smoothstep(edge0, edge1, x): Hermite interpolation between 0 and 1 across the edges.
				ExpressionFunctions.<FFF2FFunction>add("smoothstep", ExpressionFunctions::smoothstep);
				addComponentWise("smoothstep", new boolean[]{false, false, false}, args -> smoothstep(args[0], args[1], args[2]));
				addComponentWise("smoothstep", new boolean[]{true, true, false}, args -> smoothstep(args[0], args[1], args[2]));
			}


			{
				{
					// fmod
					ExpressionFunctions.<II2IFunction>addVectorizable("fmod", Math::floorMod);
					ExpressionFunctions.<FF2FFunction>add("fmod", (a, b) -> (a % b + b) % b);
				}
				{
					Random random = new Random();
					// randomInt(), randomInt(int bound), randomInt(int inclusiveMin, int exclusiveMax)
					ExpressionFunctions.<V2IFunction>addVectorizable("randomInt", random::nextInt);
					ExpressionFunctions.<I2IFunction>addVectorizable("randomInt", random::nextInt);
					ExpressionFunctions.<II2IFunction>addVectorizable("randomInt", (a, b) -> random.nextInt(b - a) + a);

					// random, random(float min, float max)
					ExpressionFunctions.<V2FFunction>add("random", random::nextFloat);
					ExpressionFunctions.<FF2FFunction>add("random", (min, max) ->
						min + random.nextFloat() * (max - min));
				}
				{
					// IF
					// if(boolean, primitive, primitive) -> primitive
					// if(boolean, xvec, xvec) -> xvec
					for (Type.Primitive type : Type.AllPrimitives) {
						add("if", new AbstractTypedFunction(type, new Type[]{Type.Boolean, type, type}) {
							@Override
							public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
								params[0].evaluateTo(context, functionReturn);

								params[
									functionReturn.booleanReturn ? 1 : 2
									].evaluateTo(context, functionReturn);

							}
						});
					}

					for (Type type : VectorType.AllVectorTypes) {
						add("if", new AbstractTypedFunction(type, new Type[]{Type.Boolean, type, type}) {
							@Override
							public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
								params[0].evaluateTo(context, functionReturn);

								params[
									functionReturn.booleanReturn ? 1 : 2
									].evaluateTo(context, functionReturn);

							}
						});
					}

					{
						// Fake varargs: if(c1, v1, ..., cN, vN, else) for N = 2..16.
						for (int length = 2; length <= 16; length++) {
							for (Type.Primitive type : Type.AllPrimitives) {
								Type[] params = new Type[length * 2 + 1];
								for (int i = 0; i < length * 2; i += 2) {
									params[i] = Type.Boolean;
									params[i + 1] = type;
								}
								params[length * 2] = type;
								int finalLength = length * 2;
								add("if", new AbstractTypedFunction(type, params) {
									@Override
									public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
										for (int i = 0; i < finalLength; i += 2) {
											params[i].evaluateTo(context, functionReturn);
											if (functionReturn.booleanReturn) {
												params[i + 1].evaluateTo(context, functionReturn);
												return;
											}
											params[finalLength].evaluateTo(context, functionReturn);
										}
									}
								});
							}
						}
					}
				}
				{
					// smooth

					// smooth(target)
					builder.addDynamicFunction("smooth", Type.Float, () ->
						new AbstractTypedFunction(
							Type.Float,
							new Parameter[]{
								new Parameter(Type.Float, false), // target
							},
							0,
							false
						) {
							private final SmoothFloat smoothFloat = new SmoothFloat();

							@Override
							public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
								params[0].evaluateTo(context, functionReturn);
								float target = functionReturn.floatReturn;
								functionReturn.floatReturn = smoothFloat.updateAndGet(
									target,
									1,
									1
								);
							}
						});

					// smooth(id, target)
					builder.addDynamicFunction("smooth", Type.Float, () ->
						new AbstractTypedFunction(
							Type.Float,
							new Parameter[]{
								new Parameter(Type.Float, true), // id: accepted for compatibility, ignored
								new Parameter(Type.Float, false), // target
							},
							1,
							false
						) {
							private final SmoothFloat smoothFloat = new SmoothFloat();

							@Override
							public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
								params[1].evaluateTo(context, functionReturn);
								float target = functionReturn.floatReturn;
								functionReturn.floatReturn = smoothFloat.updateAndGet(
									target,
									1,
									1
								);
							}
						});

					// smooth(target, fadeTime)
					builder.addDynamicFunction("smooth", Type.Float, () ->
						new AbstractTypedFunction(
							Type.Float,
							new Parameter[]{
								new Parameter(Type.Float, false), // target
								new Parameter(Type.Float, false), // fadeTime
							},
							0,
							false
						) {
							private final SmoothFloat smoothFloat = new SmoothFloat();

							@Override
							public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
								params[0].evaluateTo(context, functionReturn);
								float target = functionReturn.floatReturn;

								params[1].evaluateTo(context, functionReturn);
								float fadeTime = functionReturn.floatReturn;

								functionReturn.floatReturn = smoothFloat.updateAndGet(
									target,
									fadeTime,
									fadeTime
								);
							}
						});

					// smooth(id, target, fadeTime)
					builder.addDynamicFunction("smooth", Type.Float, () ->
						new AbstractTypedFunction(
							Type.Float,
							new Parameter[]{
								new Parameter(Type.Float, true), // id: accepted for compatibility, ignored
								new Parameter(Type.Float, false), // target
								new Parameter(Type.Float, false), // fadeTime
							},
							1,
							false
						) {
							private final SmoothFloat smoothFloat = new SmoothFloat();

							@Override
							public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
								params[1].evaluateTo(context, functionReturn);
								float target = functionReturn.floatReturn;

								params[2].evaluateTo(context, functionReturn);
								float fadeTime = functionReturn.floatReturn;

								functionReturn.floatReturn = smoothFloat.updateAndGet(
									target,
									fadeTime,
									fadeTime
								);
							}
						});

					// smooth(target, fadeUpTime, fadeDownTime)
					builder.addDynamicFunction("smooth", Type.Float, () ->
						new AbstractTypedFunction(
							Type.Float,
							new Parameter[]{
								new Parameter(Type.Float, false), // target
								new Parameter(Type.Float, false), // fadeUpTime
								new Parameter(Type.Float, false), // fadeDownTime
							},
							0,
							false
						) {
							private final SmoothFloat smoothFloat = new SmoothFloat();

							@Override
							public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
								params[0].evaluateTo(context, functionReturn);
								float target = functionReturn.floatReturn;

								params[1].evaluateTo(context, functionReturn);
								float fadeUpTime = functionReturn.floatReturn;
								params[2].evaluateTo(context, functionReturn);
								float fadeDownTime = functionReturn.floatReturn;

								functionReturn.floatReturn = smoothFloat.updateAndGet(
									target,
									fadeUpTime,
									fadeDownTime
								);
							}
						});

					// smooth(id, target, fadeUpTime, fadeDownTime)
					builder.addDynamicFunction("smooth", Type.Float, () ->
						new AbstractTypedFunction(
							Type.Float,
							new Parameter[]{
								new Parameter(Type.Float, true), // id: accepted for compatibility, ignored
								new Parameter(Type.Float, false), // target
								new Parameter(Type.Float, false), // fadeUpTime
								new Parameter(Type.Float, false), // fadeDownTime
							},
							1,
							false
						) {
							private final SmoothFloat smoothFloat = new SmoothFloat();

							@Override
							public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
								params[1].evaluateTo(context, functionReturn);
								float target = functionReturn.floatReturn;

								params[2].evaluateTo(context, functionReturn);
								float fadeUpTime = functionReturn.floatReturn;
								params[3].evaluateTo(context, functionReturn);
								float fadeDownTime = functionReturn.floatReturn;

								functionReturn.floatReturn = smoothFloat.updateAndGet(
									target,
									fadeUpTime,
									fadeDownTime
								);
							}
						});
				}
			}
		}

		// casts
		{
			addImplicitCast(Type.Int, Type.Float, r -> r.floatReturn = r.intReturn);
			// Truncates toward zero.
			addExplicitCast(Type.Float, Type.Int, r -> r.intReturn = (int) r.floatReturn);
		}

		// boolean functions
		{
			ExpressionFunctions.<III2BFunction>add("between", (a, min, max) -> a >= min && a <= max);
			ExpressionFunctions.<FFF2BFunction>add("between", (a, min, max) -> a >= min && a <= max);

			ExpressionFunctions.<FFF2BFunction>add("equals", (a, b, epsilon) -> Math.abs(a - b) <= epsilon);

			// in(value, candidates...) compares floats (ints convert).
			{
				// Fake varargs: fixed-arity overloads for 2..32 arguments.
				for (int length = 2; length <= 32; length++) {
					Type[] params = new Type[length];
					Arrays.fill(params, Type.Float);
					int finalLength = length;
					ExpressionFunctions.add("in", new AbstractTypedFunction(
						Type.Boolean,
						params
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							float value = functionReturn.floatReturn;
							for (int i = 1; i < finalLength; i++) {
								params[i].evaluateTo(context, functionReturn);
								if (functionReturn.floatReturn == value) {
									functionReturn.booleanReturn = true;
									return;
								}
							}
							functionReturn.booleanReturn = false;
						}
					});
				}
			}
		}

		// create vectors
		{
			for (Type.Primitive type : new Type.Primitive[]{Type.Boolean, Type.Int}) {
				for (int size = 2; size <= 4; size++) {
					TypedFunction function = new VectorConstructor(type, size);
					add(
						Character.toLowerCase(
							type.getClass().getSimpleName().charAt(0)
						) + "vec" + size, function);
				}
			}

			add("vec2", new AbstractTypedFunction(
				VectorType.VEC2,
				new Type[]{Type.Float, Type.Float}
			) {
				@Override
				public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
					params[0].evaluateTo(context, functionReturn);
					float x = functionReturn.floatReturn;

					params[1].evaluateTo(context, functionReturn);
					float y = functionReturn.floatReturn;

					functionReturn.objectReturn = new Vector2f(x, y);
				}
			});

			add("vec3", new AbstractTypedFunction(
				VectorType.VEC3,
				new Type[]{Type.Float, Type.Float, Type.Float}
			) {
				@Override
				public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
					params[0].evaluateTo(context, functionReturn);
					float x = functionReturn.floatReturn;

					params[1].evaluateTo(context, functionReturn);
					float y = functionReturn.floatReturn;

					params[2].evaluateTo(context, functionReturn);
					float z = functionReturn.floatReturn;

					functionReturn.objectReturn = new Vector3f(x, y, z);
				}
			});

			add("vec4", new AbstractTypedFunction(
				VectorType.VEC4,
				new Type[]{Type.Float, Type.Float, Type.Float, Type.Float}
			) {
				@Override
				public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
					params[0].evaluateTo(context, functionReturn);
					float x = functionReturn.floatReturn;

					params[1].evaluateTo(context, functionReturn);
					float y = functionReturn.floatReturn;

					params[2].evaluateTo(context, functionReturn);
					float z = functionReturn.floatReturn;

					params[3].evaluateTo(context, functionReturn);
					float w = functionReturn.floatReturn;

					functionReturn.objectReturn = new Vector4f(x, y, z, w);
				}
			});
		}

		// accessors
		{
			String[][] accessNames = new String[][]{
				new String[]{"0", "r", "x", "s"},
				new String[]{"1", "g", "y", "t"},
				new String[]{"2", "b", "z", "p"},
				new String[]{"3", "a", "w", "q"}
			};

			{
				// access$0
				for (String access : accessNames[0]) {
					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC2}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector2f) functionReturn.objectReturn).x;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC2}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector2i) functionReturn.objectReturn).x;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC3}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector3f) functionReturn.objectReturn).x;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC3}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector3i) functionReturn.objectReturn).x;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector4f) functionReturn.objectReturn).x;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector4i) functionReturn.objectReturn).x;
						}
					});
				}
			}

			{
				// access$1
				for (String access : accessNames[1]) {
					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC2}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector2f) functionReturn.objectReturn).y;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC2}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector2i) functionReturn.objectReturn).y;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC3}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector3f) functionReturn.objectReturn).y;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC3}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector3i) functionReturn.objectReturn).y;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector4f) functionReturn.objectReturn).y;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector4i) functionReturn.objectReturn).y;
						}
					});
				}
			}

			{
				// access$2
				for (String access : accessNames[2]) {
					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC3}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector3f) functionReturn.objectReturn).z;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC3}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector3i) functionReturn.objectReturn).z;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector4f) functionReturn.objectReturn).z;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector4i) functionReturn.objectReturn).z;
						}
					});
				}
			}

			{
				// access$3
				for (String access : accessNames[3]) {
					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector4f) functionReturn.objectReturn).w;
						}
					});

					ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector4i) functionReturn.objectReturn).w;
						}
					});
				}
			}

			{
				// matrix access
				for (int i = 0; i < 4; i++) {
					for (String access : accessNames[i]) {
						int finalI = i;
						ExpressionFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
							VectorType.VEC4,
							new Type[]{MatrixType.MAT4}
						) {
							@Override
							public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
								params[0].evaluateTo(context, functionReturn);
								functionReturn.objectReturn = ((Matrix4f) functionReturn.objectReturn).getColumn(finalI, new Vector4f());
							}
						});
					}
				}
			}
		}

		functions = builder.build();
	}

	static <T extends TypedFunction> void addVectorized(String name, T function) {
		if (function.getReturnType() instanceof Type.Primitive) {
			add(name, new VectorizedFunction(function, 2));
			add(name, new VectorizedFunction(function, 3));
			add(name, new VectorizedFunction(function, 4));
		} else {
			throw new IllegalArgumentException(name + " is not vectorizable");
		}
	}

	static <T extends TypedFunction> void addVectorizable(String name, T function) {
		add(name, function);
		addVectorized(name, function);
	}

	static <T extends TypedFunction> void addBooleanVectorizable(String name, T function) {
		assert function.getReturnType().equals(Type.Boolean);
		add(name, function);
		if (function.getReturnType() instanceof Type.Primitive) {
			add(name, new BooleanVectorizedFunction(function, 2));
			add(name, new BooleanVectorizedFunction(function, 3));
			add(name, new BooleanVectorizedFunction(function, 4));
		} else {
			throw new IllegalArgumentException(name + " is not vectorizable");
		}
	}

	static <T> void addUnaryOpJOML(String name, VectorType.JOMLVector<T> type, BiConsumer<T, T> function) {
		builder.add(name, new AbstractTypedFunction(
			type,
			new Type[]{type}
		) {
			final private T vector = type.create();

			@SuppressWarnings("unchecked")
			@Override
			public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
				params[0].evaluateTo(context, functionReturn);
				T a = (T) functionReturn.objectReturn;

				function.accept(a, this.vector);
				functionReturn.objectReturn = this.vector;
			}
		});
	}

	static <T> void addBinaryOpJOML(String name, VectorType.JOMLVector<T> type, TriConsumer<T, T, T> function) {
		builder.add(name, new AbstractTypedFunction(
			type,
			new Type[]{type, type}
		) {
			final private T vector = type.create();

			@SuppressWarnings("unchecked")
			@Override
			public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
				params[0].evaluateTo(context, functionReturn);
				T a = (T) functionReturn.objectReturn;

				params[1].evaluateTo(context, functionReturn);
				T b = (T) functionReturn.objectReturn;

				function.accept(a, b, this.vector);
				functionReturn.objectReturn = this.vector;
			}
		});
	}

	static <T> void addTernaryOpJOML(String name, VectorType.JOMLVector<T> type, QuadConsumer<T, T, T, T> function) {
		builder.add(name, new AbstractTypedFunction(
			type,
			new Type[]{type, type, type}
		) {
			final private T vector = type.create();

			@SuppressWarnings("unchecked")
			@Override
			public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
				params[0].evaluateTo(context, functionReturn);
				T a = (T) functionReturn.objectReturn;

				params[1].evaluateTo(context, functionReturn);
				T b = (T) functionReturn.objectReturn;

				params[2].evaluateTo(context, functionReturn);
				T c = (T) functionReturn.objectReturn;

				function.accept(a, b, c, this.vector);
				functionReturn.objectReturn = this.vector;
			}
		});
	}

	static <T> void addBinaryToBooleanOpJOML(
		String name,
		VectorType.JOMLVector<T> type,
		boolean inverted,
		ObjectObject2BooleanFunction<T, T> function) {
		builder.add(name, new AbstractTypedFunction(
			type,
			new Type[]{type, type}
		) {
			@SuppressWarnings("unchecked")
			@Override
			public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
				params[0].evaluateTo(context, functionReturn);
				T a = (T) functionReturn.objectReturn;

				params[1].evaluateTo(context, functionReturn);
				T b = (T) functionReturn.objectReturn;

				functionReturn.objectReturn = function.apply(a, b) != inverted;
			}
		});
	}

	static <T extends TypedFunction> void add(String name, T function) {
		builder.add(name, function);
	}

	static void addCast(final String name, final Type from, final Type to, final Consumer<FunctionReturn> function) {
		add(name, new TypedFunction() {
			@Override
			public Type getReturnType() {
				return to;
			}

			@Override
			public Parameter[] getParameters() {
				return new Parameter[]{new Parameter(from)};
			}

			@Override
			public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
				params[0].evaluateTo(context, functionReturn);
				function.accept(functionReturn);
			}
		});
	}

	static void addImplicitCast(final Type from, final Type to, final Consumer<FunctionReturn> function) {
		addCast("<cast>", from, to, function);
		addExplicitCast(from, to, function);
	}

	static void addExplicitCast(final Type from, final Type to, final Consumer<FunctionReturn> function) {
		addCast("to" + to.getClass().getSimpleName(), from, to, function);
	}

	/** GLSL smoothstep: 0 at or below {@code edge0}, 1 at or above {@code edge1}, a smooth curve between. */
	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
		return t * t * (3.0f - 2.0f * t);
	}

	/** Reads and writes the components of one float vector type. */
	private record FloatVector<T>(VectorType.JOMLVector<T> type, int size, ComponentGetter<T> get, ComponentSetter<T> set) {
	}

	private interface ComponentGetter<T> {
		float get(T vector, int component);
	}

	private interface ComponentSetter<T> {
		void set(T vector, int component, float value);
	}

	private interface ComponentOp {
		float apply(float[] args);
	}

	/**
	 * Registers {@code name} for vec2, vec3 and vec4, applied per component: parameter {@code i} is a vector of the same
	 * size, or a float used for every component where {@code scalar[i]}.
	 */
	static void addComponentWise(String name, boolean[] scalar, ComponentOp op) {
		for (FloatVector<?> vector : FLOAT_VECTORS) {
			addComponentWise(name, vector, scalar, op);
		}
	}

	private static <T> void addComponentWise(String name, FloatVector<T> vector, boolean[] scalar, ComponentOp op) {
		Type[] params = new Type[scalar.length];
		for (int i = 0; i < scalar.length; i++) {
			params[i] = scalar[i] ? Type.Float : vector.type();
		}
		builder.add(name, new AbstractTypedFunction(vector.type(), params) {
			@SuppressWarnings("unchecked")
			@Override
			public void evaluateTo(Expression[] parameters, FunctionContext context, FunctionReturn functionReturn) {
				// Fresh arrays and result per call: a nested call of this same function must not overwrite them.
				float[] scalars = new float[parameters.length];
				Object[] vectors = new Object[parameters.length];
				for (int i = 0; i < parameters.length; i++) {
					parameters[i].evaluateTo(context, functionReturn);
					if (scalar[i]) {
						scalars[i] = functionReturn.floatReturn;
					} else {
						vectors[i] = functionReturn.objectReturn;
					}
				}
				T result = vector.type().create();
				float[] args = new float[parameters.length];
				for (int k = 0; k < vector.size(); k++) {
					for (int i = 0; i < args.length; i++) {
						args[i] = scalar[i] ? scalars[i] : vector.get().get((T) vectors[i], k);
					}
					vector.set().set(result, k, op.apply(args));
				}
				functionReturn.objectReturn = result;
			}
		});
	}

	interface ObjectObject2BooleanFunction<T, U> {
		boolean apply(T t, U u);
	}

	interface TriConsumer<T, U, V> {
		void accept(T t, U u, V v);
	}

	interface QuadConsumer<T, U, V, W> {
		void accept(T t, U u, V v, W w);
	}
}

