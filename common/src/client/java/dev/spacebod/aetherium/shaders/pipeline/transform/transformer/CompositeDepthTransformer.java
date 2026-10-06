package dev.spacebod.aetherium.shaders.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.match.HintedMatcher;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.parser.ParseShape;

class CompositeDepthTransformer {
	private static final HintedMatcher<ExternalDeclaration> uniformFloatCenterDepthSmooth = new HintedMatcher<>(
		"uniform float name;", ParseShape.EXTERNAL_DECLARATION, "centerDepthSmooth") {
		{
			markClassWildcard("name*",
				pattern.getRoot().identifierIndex.getUnique("name").getAncestor(DeclarationMember.class));
		}
	};

	public static void transform(
		ASTParser t,
		TranslationUnit tree,
		Root root) {
		// Swap the float uniform for a 1x1 texture read; packs that never declare it don't get it.
		if (root.processMatches(t, uniformFloatCenterDepthSmooth, (match) -> {
			TypeAndInitDeclaration declaration = ((TypeAndInitDeclaration) ((DeclarationExternalDeclaration) match)
				.getDeclaration());
			DeclarationMember memberToDelete = null;
			for (DeclarationMember member : declaration.getMembers()) {
				if (member.getName().getName().equals("centerDepthSmooth")) {
					memberToDelete = member;
					break;
				}
			}
			if (memberToDelete != null) {
				if (declaration.getMembers().size() == 1) {
					match.detachAndDelete();
				} else {
					memberToDelete.detachAndDelete();
				}
			}
		})) {
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"uniform sampler2D aeth_centerDepthSmooth;");

			root.replaceReferenceExpressions(t, "centerDepthSmooth",
				"texture(aeth_centerDepthSmooth, vec2(0.5)).r");
		}
	}
}
