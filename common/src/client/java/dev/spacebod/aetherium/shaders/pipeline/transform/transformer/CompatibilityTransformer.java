package dev.spacebod.aetherium.shaders.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.FunctionParameter;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.EmptyDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.LayoutQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.NamedLayoutQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.specifier.ArraySpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.FunctionPrototype;
import io.github.douira.glsl_transformer.ast.node.type.specifier.TypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.struct.StructDeclarator;
import io.github.douira.glsl_transformer.ast.node.type.struct.StructMember;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.match.AutoHintedMatcher;
import io.github.douira.glsl_transformer.ast.query.match.Matcher;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.ast.transform.Template;
import io.github.douira.glsl_transformer.ast.transform.TransformationException;
import io.github.douira.glsl_transformer.parser.ParseShape;
import io.github.douira.glsl_transformer.util.Type;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.shader.ShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.PatchShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.Parameters;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CompatibilityTransformer {
	private static final Logger LOGGER = LogManager.getLogger(CompatibilityTransformer.class);

	private static final AutoHintedMatcher<Expression> sildursWaterFract = new AutoHintedMatcher<>(
		"fract(worldpos.y + 0.001)", ParseShape.EXPRESSION);
	private static final ShaderType[] pipeline = {ShaderType.VERTEX, ShaderType.TESSELATION_CONTROL, ShaderType.TESSELATION_EVAL, ShaderType.GEOMETRY, ShaderType.FRAGMENT};
	private static final Matcher<ExternalDeclaration> outDeclarationMatcher = new DeclarationMatcher(
		StorageType.OUT);
	private static final Matcher<ExternalDeclaration> inDeclarationMatcher = new DeclarationMatcher(
		StorageType.IN);
	private static final String tagPrefix = "aeth_template_";
	private static final Template<ExternalDeclaration> declarationTemplate = Template
		.withExternalDeclaration("out __type __name;");
	private static final Template<Statement> initTemplate = Template.withStatement("__decl = __value;");
	private static final Template<ExternalDeclaration> variableTemplate = Template
		.withExternalDeclaration("__type __internalDecl;");
	private static final Template<Statement> statementTemplate = Template
		.withStatement("__oldDecl = vec3(__internalDecl);");
	private static final Template<Statement> statementTemplateVector = Template
		.withStatement("__oldDecl = vec3(__internalDecl, vec4(0));");
	private static final Matcher<ExternalDeclaration> nonLayoutOutDeclarationMatcher = new Matcher<>(
		"out float name;",
		ParseShape.EXTERNAL_DECLARATION) {
		{
			markClassWildcard("qualifier", pattern.getRoot().nodeIndex.getUnique(TypeQualifier.class));
			markClassWildcard("type", pattern.getRoot().nodeIndex.getUnique(BuiltinNumericTypeSpecifier.class));
			markClassWildcard("name*",
				pattern.getRoot().identifierIndex.getUnique("name").getAncestor(DeclarationMember.class));
		}

		@Override
		public boolean matchesExtract(ExternalDeclaration tree) {
			boolean result = super.matchesExtract(tree);
			if (!result) {
				return false;
			}

			// look for an out qualifier but no layout qualifier
			TypeQualifier qualifier = getNodeMatch("qualifier", TypeQualifier.class);
			var hasOutQualifier = false;
			for (TypeQualifierPart part : qualifier.getParts()) {
				if (part instanceof StorageQualifier storageQualifier) {
					if (storageQualifier.storageType == StorageType.OUT) {
						hasOutQualifier = true;
					}
				} else if (part instanceof LayoutQualifier) {
					return false;
				}
			}
			return hasOutQualifier;
		}
	};
	private static final Template<ExternalDeclaration> layoutedOutDeclarationTemplate = Template
		.withExternalDeclaration("out __type __name;");
	private static final String attachTargetPrefix = "outColor";
	private static final List<String> reservedWords = List.of("texture", "sample");

	static {
		declarationTemplate
			.markLocalReplacement(declarationTemplate.getSourceRoot().nodeIndex.getUnique(TypeQualifier.class));
		declarationTemplate.markLocalReplacement("__type", TypeSpecifier.class);
		declarationTemplate.markIdentifierReplacement("__name");
		initTemplate.markIdentifierReplacement("__decl");
		initTemplate.markLocalReplacement("__value", ReferenceExpression.class);
		variableTemplate.markLocalReplacement("__type", TypeSpecifier.class);
		variableTemplate.markIdentifierReplacement("__internalDecl");
		statementTemplate.markIdentifierReplacement("__oldDecl");
		statementTemplate.markIdentifierReplacement("__internalDecl");
		statementTemplate.markLocalReplacement(
			statementTemplate.getSourceRoot().nodeIndex.getStream(BuiltinNumericTypeSpecifier.class)
				.filter(specifier -> specifier.type == Type.F32VEC3).findAny().get());
		statementTemplateVector.markIdentifierReplacement("__oldDecl");
		statementTemplateVector.markIdentifierReplacement("__internalDecl");
		statementTemplateVector.markLocalReplacement(
			statementTemplateVector.getSourceRoot().nodeIndex.getStream(BuiltinNumericTypeSpecifier.class)
				.filter(specifier -> specifier.type == Type.F32VEC3).findAny().get());
	}

	static {
		layoutedOutDeclarationTemplate.markLocalReplacement(
			layoutedOutDeclarationTemplate.getSourceRoot().nodeIndex.getOne(TypeQualifier.class));
		layoutedOutDeclarationTemplate.markLocalReplacement("__type", TypeSpecifier.class);
		layoutedOutDeclarationTemplate.markLocalReplacement("__name", DeclarationMember.class);
	}

	private static StorageQualifier getConstQualifier(TypeQualifier qualifier) {
		if (qualifier == null) {
			return null;
		}
		for (TypeQualifierPart constQualifier : qualifier.getChildren()) {
			if (constQualifier instanceof StorageQualifier storageQualifier) {
				if (storageQualifier.storageType == StorageQualifier.StorageType.CONST) {
					return storageQualifier;
				}
			}
		}
		return null;
	}

	public static void transformEach(ASTParser t, TranslationUnit tree, Root root, Parameters parameters) {
		if (parameters.type == PatchShaderType.VERTEX) {
			if (root.replaceExpressionMatches(t, sildursWaterFract, "fract(worldpos.y + 0.01)")) {
				AetheriumShaders.logger.debug("Patched fract(worldpos.y + 0.001) to fract(worldpos.y + 0.01) to fix " +
					"waving water disconnecting from other water blocks");
			}
		}

		/*
		  Strips const from function-local declarations initialised from const parameters, transitively.
		  Const parameters are only immutable, not constant expressions, so drivers disagree on whether
		  they may initialise a const declaration.
		  See https://wiki.shaderlabs.org/wiki/Compiler_Behavior_Notes
		 */
		Map<FunctionDefinition, Set<String>> constFunctions = new HashMap<>();
		Set<String> processingSet = new HashSet<>();
		List<FunctionDefinition> unusedFunctions = new LinkedList<>();
		for (FunctionDefinition definition : root.nodeIndex.get(FunctionDefinition.class)) {
			FunctionPrototype prototype = definition.getFunctionPrototype();
			String functionName = prototype.getName().getName();
			if (!functionName.equals("main") && root.identifierIndex.getStream(functionName).count() <= 1) {
				// Unused functions are dropped (outside debug mode): drivers check them inconsistently, and
				// it sidesteps bugs in dead code.
				unusedFunctions.add(definition);
				continue;
			}

			if (prototype.getChildren().isEmpty()) {
				continue;
			}

			// find the const parameters
			Set<String> names = new HashSet<>(prototype.getChildren().size());
			for (FunctionParameter parameter : prototype.getChildren()) {
				if (getConstQualifier(parameter.getType().getTypeQualifier()) != null) {
					String name = parameter.getName().getName();
					names.add(name);
					processingSet.add(name);
				}
			}
			if (!names.isEmpty()) {
				constFunctions.put(definition, names);
			}
		}

		if (!AetheriumShaders.getShaderConfig().areDebugOptionsEnabled()) {
			for (FunctionDefinition definition : unusedFunctions) {
				definition.detachAndDelete();
			}
		}

		// Walk references to const names; each const-stripped declaration adds its members to the queue.
		boolean constDeclarationHit = false;
		Deque<String> processingQueue = new ArrayDeque<>(processingSet);
		while (!processingQueue.isEmpty()) {
			String name = processingQueue.poll();
			processingSet.remove(name);
			for (Identifier id : root.identifierIndex.get(name)) {
				// reference expressions only, so declaration member names aren't matched
				ReferenceExpression reference = id.getAncestor(ReferenceExpression.class);
				if (reference == null) {
					continue;
				}
				TypeAndInitDeclaration taid = reference.getAncestor(TypeAndInitDeclaration.class);
				if (taid == null) {
					continue;
				}
				FunctionDefinition inDefinition = taid.getAncestor(FunctionDefinition.class);
				if (inDefinition == null) {
					continue;
				}
				Set<String> constIdsInFunction = constFunctions.get(inDefinition);
				if (constIdsInFunction == null) {
					continue;
				}
				if (constIdsInFunction.contains(name)) {
					// strip const from the enclosing declaration
					TypeQualifier qualifier = taid.getType().getTypeQualifier();
					StorageQualifier constQualifier = getConstQualifier(qualifier);
					if (constQualifier == null) {
						continue;
					}
					constQualifier.detachAndDelete();
					if (qualifier.getChildren().isEmpty()) {
						qualifier.detachAndDelete();
					}
					constDeclarationHit = true;

					// its members are now non-constant too
					for (DeclarationMember member : taid.getMembers()) {
						String memberName = member.getName().getName();

						// shadowing a const parameter is not allowed
						if (constIdsInFunction.contains(memberName)) {
							throw new TransformationException("Illegal redefinition of const parameter " + name);
						}

						constIdsInFunction.add(memberName);

						// may already be queued from another scope
						if (!processingSet.contains(memberName)) {
							processingQueue.add(memberName);
							processingSet.add(memberName);
						}
					}
				}
			}
		}

		if (constDeclarationHit) {
			LOGGER.debug(
				"Removed the const keyword from declarations that use const parameters.");
		}

		boolean emptyDeclarationHit = root.process(
			root.nodeIndex.getStream(EmptyDeclaration.class),
			ASTNode::detachAndDelete);
		if (emptyDeclarationHit) {
			LOGGER.debug(
				"Removed empty external declarations (\";\").");
		}

		// identifiers that are reserved words in newer GLSL
		for (String reservedWord : reservedWords) {
			String newName = "aeth_renamed_" + reservedWord;
			if (root.process(root.identifierIndex.getStream(reservedWord).filter(
					id -> !(id.getParent() instanceof FunctionCallExpression)
						&& !(id.getParent() instanceof FunctionPrototype)),
				id -> id.setName(newName))) {
				LOGGER.debug("Renamed reserved word \"" + reservedWord + "\" to \"" + newName + "\".");
			}
		}

		// Move unsized array specifiers on struct members from the type to each declarator; some drivers
		// don't recognise the unsized array on the type.
		for (StructMember structMember : root.nodeIndex.get(StructMember.class)) {
			TypeSpecifier typeSpecifier = structMember.getType().getTypeSpecifier();
			ArraySpecifier arraySpecifier = typeSpecifier.getArraySpecifier();
			if (arraySpecifier == null) {
				continue;
			}

			if (!arraySpecifier.getChildren().isNullEmpty()) {
				continue;
			}

			arraySpecifier.detach();

			boolean reusedOriginal = false;
			for (StructDeclarator declarator : structMember.getDeclarators()) {
				if (declarator.getArraySpecifier() != null) {
					throw new TransformationException("Member already has an array specifier");
				}

				// first declarator reuses the original, the rest get clones
				declarator.setArraySpecifier(reusedOriginal ? arraySpecifier.cloneInto(root) : arraySpecifier);
				reusedOriginal = true;
			}

			LOGGER.debug(
				"Moved unsized array specifier (of the form []) from the type to each of the the declaration member(s) "
					+ structMember.getDeclarators().stream().map(StructDeclarator::getName).map(Identifier::getName)
					.collect(Collectors.joining(", "))
					+ ".");
		}
	}

	private static Statement getInitializer(Root root, String name, Type type) {
		return initTemplate.getInstanceFor(root,
			new Identifier(name),
			type.isScalar()
				? LiteralExpression.getDefaultValue(type)
				: root.indexNodes(() -> new FunctionCallExpression(
				new Identifier(type.getMostCompactName()),
				Stream.of(LiteralExpression.getDefaultValue(type)))));
	}

	private static TypeQualifier makeQualifierOut(TypeQualifier typeQualifier) {
		for (TypeQualifierPart qualifierPart : typeQualifier.getParts()) {
			if (qualifierPart instanceof StorageQualifier storageQualifier) {
				if (storageQualifier.storageType == StorageType.IN) {
					storageQualifier.storageType = StorageType.OUT;
				}
			}
		}
		return typeQualifier;
	}

	// Transformations that need type data across stages.
	public static void transformGrouped(
		ASTParser t,
		Map<PatchShaderType, TranslationUnit> trees,
		Parameters parameters) {
		/*
		  For each "in" with no matching "out" in the previous stage, add and initialise the "out"; for
		  type mismatches, route the previous stage's value through a cast.

		  Array specifiers are ignored: they are only legal on geometry-shader inputs, and the matching
		  output of the previous stage is still a single value.
		 */
		ShaderType prevType = null;
		for (ShaderType type : pipeline) {
			PatchShaderType[] patchTypes = PatchShaderType.fromGlShaderType(type);

			boolean hasAny = false;
			for (PatchShaderType currentType : patchTypes) {
				if (trees.get(currentType) != null) {
					hasAny = true;
				}
			}
			if (!hasAny) {
				continue;
			}

			// first stage with a source: nothing to compare against yet
			if (prevType == null) {
				prevType = type;
				continue;
			}

			PatchShaderType prevPatchTypes = PatchShaderType.fromGlShaderType(prevType)[0];
			TranslationUnit prevTree = trees.get(prevPatchTypes);
			Root prevRoot = prevTree.getRoot();

			// the pack already uses our tag prefix; renaming would collide
			if (prevRoot.getPrefixIdentifierIndex().prefixQueryFlat(tagPrefix).findAny().isPresent()) {
				LOGGER.debug("The prefix tag " + tagPrefix + " is used in the shader, bailing compatibility transformation.");
				return;
			}

			Map<String, BuiltinNumericTypeSpecifier> outDeclarations = new HashMap<>();
			for (DeclarationExternalDeclaration declaration : prevRoot.nodeIndex.get(DeclarationExternalDeclaration.class)) {
				if (outDeclarationMatcher.matchesExtract(declaration)) {
					BuiltinNumericTypeSpecifier extractedType = outDeclarationMatcher.getNodeMatch("type",
						BuiltinNumericTypeSpecifier.class);
					for (DeclarationMember member : outDeclarationMatcher
						.getNodeMatch("name*", DeclarationMember.class)
						.getAncestor(TypeAndInitDeclaration.class)
						.getMembers()) {
						String name = member.getName().getName();
						if (!name.startsWith("gl_")) {
							outDeclarations.put(name, extractedType);
						}
					}
				}
			}

			for (PatchShaderType currentType : patchTypes) {
				TranslationUnit currentTree = trees.get(currentType);
				if (currentTree == null) {
					continue;
				}
				Root currentRoot = currentTree.getRoot();

				for (ExternalDeclaration declaration : currentRoot.nodeIndex.get(DeclarationExternalDeclaration.class)) {
					if (!inDeclarationMatcher.matchesExtract(declaration)) {
						continue;
					}

					BuiltinNumericTypeSpecifier inTypeSpecifier = inDeclarationMatcher.getNodeMatch("type",
						BuiltinNumericTypeSpecifier.class);
					for (DeclarationMember inDeclarationMember : inDeclarationMatcher
						.getNodeMatch("name*", DeclarationMember.class)
						.getAncestor(TypeAndInitDeclaration.class)
						.getMembers()) {
						String name = inDeclarationMember.getName().getName();
						if (name.startsWith("gl_")) {
							continue;
						}

						// patch missing declarations with an initialization
						if (!outDeclarations.containsKey(name)) {
							// only if the in is actually read
							if (currentRoot.identifierIndex.getAncestors(name, ReferenceExpression.class).findAny().isEmpty()) {
								continue;
							}

							if (inTypeSpecifier == null) {
								LOGGER.debug(
									"The in declaration '" + name + "' in the " + parameters.name + " " + currentType.glShaderType.name()
										+ " shader that has a missing corresponding out declaration in the previous stage "
										+ prevType.name()
										+ " has a non-numeric type and could not be compatibility-patched.");
								continue;
							}
							Type inType = inTypeSpecifier.type;

							// new out declaration with the in's qualifiers, in turned to out
							TypeQualifier outQualifier = (TypeQualifier) inDeclarationMatcher
								.getNodeMatch("qualifier").cloneInto(prevRoot);
							makeQualifierOut(outQualifier);
							prevTree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS, declarationTemplate.getInstanceFor(prevRoot,
								outQualifier,
								inTypeSpecifier.cloneInto(prevRoot),
								new Identifier(name)));

							prevTree.prependMainFunctionBody(getInitializer(prevRoot, name, inType));

							// null marks it handled
							outDeclarations.put(name, null);

							LOGGER.debug(
								"The in declaration '" + name + "' in the " + parameters.name + " " + currentType.glShaderType.name()
									+ " shader is missing a corresponding out declaration in the previous stage "
									+ prevType.name()
									+ " and has been compatibility-patched.");
						}

						// patch mismatching declaration with a local variable and a cast
						else {
							BuiltinNumericTypeSpecifier outTypeSpecifier = outDeclarations.get(name);

							// already handled
							if (outTypeSpecifier == null) {
								continue;
							}

							Type inType = inTypeSpecifier.type;
							Type outType = outTypeSpecifier.type;

							// An array out means both sides are arrays (a geometry shader's array in pairs with a
							// scalar out), which isn't patched.
							if (outTypeSpecifier.getArraySpecifier() != null) {
								LOGGER.debug(
									"The out declaration '" + name + "' in the " + parameters.name + " " + prevPatchTypes.glShaderType.name()
										+ " shader that has a missing corresponding in declaration in the next stage "
										+ type.name()
										+ " has an array type and could not be compatibility-patched.");
								continue;
							}

							if (inType == outType) {
								// types match; only add an initialiser if it's never assigned
								if (prevRoot.identifierIndex.get(name).size() > 1) {
									continue;
								}

								prevTree.prependMainFunctionBody(getInitializer(prevRoot, name, inType));
								outDeclarations.put(name, null);

								LOGGER.debug(
									"The in declaration '" + name + "' in the " + parameters.name + " " + currentType.glShaderType.name()
										+ " shader that is never assigned to in the previous stage "
										+ prevType.name()
										+ " has been compatibility-patched by adding an initialization for it.");
								continue;
							}

							if (outType.getDimension() != inType.getDimension()) {
								LOGGER.debug(
									"The in declaration '" + name + "' in the " + parameters.name + " " + currentType.glShaderType.name()
										+ " shader has a mismatching dimensionality (scalar/vector/matrix) with the out declaration in the previous stage "
										+ prevType.name()
										+ " and could not be compatibility-patched.");
								continue;
							}

							boolean isVector = outType.isVector();

							// references now go to a tagPrefix-named global of the old type
							String newName = tagPrefix + name;
							prevRoot.identifierIndex.rename(name, newName);

							// the out declaration itself keeps the original name
							TypeAndInitDeclaration outDeclaration = outTypeSpecifier.getAncestor(TypeAndInitDeclaration.class);
							if (outDeclaration == null) {
								continue;
							}

							List<DeclarationMember> outMembers = outDeclaration.getMembers();
							DeclarationMember outMember = null;
							for (DeclarationMember member : outMembers) {
								if (member.getName().getName().equals(newName)) {
									outMember = member;
								}
							}
							if (outMember == null) {
								throw new TransformationException("The targeted out declaration member is missing!");
							}
							outMember.getName().replaceByAndDelete(new Identifier(name));

							// split it out of a multi-member declaration so the others keep their type
							if (outMembers.size() > 1) {
								outMember.detach();
								outTypeSpecifier = outTypeSpecifier.cloneInto(prevRoot);
								DeclarationExternalDeclaration singleOutDeclaration = (DeclarationExternalDeclaration) declarationTemplate
									.getInstanceFor(prevRoot,
										makeQualifierOut(outDeclaration.getType().getTypeQualifier().cloneInto(prevRoot)),
										outTypeSpecifier,
										new Identifier(name));
								((TypeAndInitDeclaration) singleOutDeclaration.getDeclaration()).getMembers().set(0, outMember);
								prevTree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS, singleOutDeclaration);
							}

							prevTree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS, variableTemplate.getInstanceFor(prevRoot,
								outTypeSpecifier.cloneInto(prevRoot),
								new Identifier(newName)));

							// at the end of main, cast the global into the out
							prevTree.appendMainFunctionBody(
								(isVector && outType.getDimensions()[0] < inType.getDimensions()[0] ? statementTemplateVector
									: statementTemplate).getInstanceFor(prevRoot,
									new Identifier(name),
									new Identifier(newName),
									inTypeSpecifier.cloneInto(prevRoot)));

							// the out takes the next stage's in type
							outTypeSpecifier.replaceByAndDelete(inTypeSpecifier.cloneInto(prevRoot));

							// null marks it handled
							outDeclarations.put(name, null);

							LOGGER.debug(
								"The out declaration '" + name + "' in the " + parameters.name + " " + prevType.name()
									+ " shader has a different type " + outType.getMostCompactName()
									+ " than the corresponding in declaration of type " + inType.getMostCompactName()
									+ " in the following stage " + currentType.glShaderType.name()
									+ " and has been compatibility-patched.");
						}
					}
				}
			}

			prevType = type;
		}
	}

	public static void transformFragmentCore(ASTParser t, TranslationUnit tree, Root root, Parameters parameters) {
		// Give outColorN outputs (N in 0..7) without a layout qualifier an explicit layout(location = N).
		ArrayList<NewDeclarationData> newDeclarationData = new ArrayList<>();
		ArrayList<ExternalDeclaration> declarationsToRemove = new ArrayList<>();
		for (DeclarationExternalDeclaration declaration : root.nodeIndex.get(DeclarationExternalDeclaration.class)) {
			if (!nonLayoutOutDeclarationMatcher.matchesExtract(declaration)) {
				continue;
			}

			List<DeclarationMember> members = nonLayoutOutDeclarationMatcher
				.getNodeMatch("name*", DeclarationMember.class)
				.getAncestor(TypeAndInitDeclaration.class)
				.getMembers();
			TypeQualifier typeQualifier = nonLayoutOutDeclarationMatcher.getNodeMatch("qualifier", TypeQualifier.class);
			BuiltinNumericTypeSpecifier typeSpecifier = nonLayoutOutDeclarationMatcher.getNodeMatch("type",
				BuiltinNumericTypeSpecifier.class);
			int addedDeclarations = 0;
			for (DeclarationMember member : members) {
				String name = member.getName().getName();
				if (!name.startsWith(attachTargetPrefix)) {
					continue;
				}

				String numberSuffix = name.substring(attachTargetPrefix.length());
				if (numberSuffix.isEmpty()) {
					continue;
				}

				int number;
				try {
					number = Integer.parseInt(numberSuffix);
				} catch (NumberFormatException e) {
					continue;
				}
				if (number < 0 || 7 < number) {
					continue;
				}

				newDeclarationData.add(new NewDeclarationData(typeQualifier, typeSpecifier, member, number));
				addedDeclarations++;
			}

			// every member moved out: drop the declaration
			if (addedDeclarations == members.size()) {
				declarationsToRemove.add(declaration);
			}
		}
		tree.getChildren().removeAll(declarationsToRemove);
		for (ExternalDeclaration declaration : declarationsToRemove) {
			declaration.detachParent();
		}

		// Already inside the caller's Root.indexBuildSession.
		ArrayList<ExternalDeclaration> newDeclarations = new ArrayList<>();
		for (NewDeclarationData data : newDeclarationData) {
			DeclarationMember member = data.member;
			member.detach();
			TypeQualifier newQualifier = data.qualifier.cloneInto(root);
			newQualifier.getChildren()
				.add(new LayoutQualifier(Stream.of(new NamedLayoutQualifierPart(
					new Identifier("location"),
					new LiteralExpression(Type.INT32, data.number)))));
			ExternalDeclaration newDeclaration = layoutedOutDeclarationTemplate.getInstanceFor(root,
				newQualifier,
				data.type.cloneInto(root),
				member);
			newDeclarations.add(newDeclaration);
		}
		tree.injectNodes(ASTInjectionPoint.BEFORE_DECLARATIONS, newDeclarations);
	}

	private static class DeclarationMatcher extends Matcher<ExternalDeclaration> {
		private final StorageType storageType;

		{
			markClassWildcard("qualifier", pattern.getRoot().nodeIndex.getUnique(TypeQualifier.class));
			markClassWildcard("type", pattern.getRoot().nodeIndex.getUnique(BuiltinNumericTypeSpecifier.class));
			markClassWildcard("name*",
				pattern.getRoot().identifierIndex.getUnique("name").getAncestor(DeclarationMember.class));
		}

		public DeclarationMatcher(StorageType storageType) {
			super("out float name;", ParseShape.EXTERNAL_DECLARATION);
			this.storageType = storageType;
		}

		@Override
		public boolean matchesExtract(ExternalDeclaration tree) {
			boolean result = super.matchesExtract(tree);
			if (!result) {
				return false;
			}
			TypeQualifier qualifier = getNodeMatch("qualifier", TypeQualifier.class);
			for (TypeQualifierPart part : qualifier.getParts()) {
				if (part instanceof StorageQualifier storageQualifier) {
					if (storageQualifier.storageType == storageType) {
						return true;
					}
				}
			}
			return false;
		}
	}

	record NewDeclarationData(TypeQualifier qualifier, TypeSpecifier type, DeclarationMember member, int number) {
	}
}
