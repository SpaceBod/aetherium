package dev.spacebod.aetherium.shaders.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.match.AutoHintedMatcher;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.parser.ParseShape;
import dev.spacebod.aetherium.shaders.gl.shader.ShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.parameter.VanillaParameters;

public class EntityPatcher {
	private static final AutoHintedMatcher<ExternalDeclaration> uniformVec4EntityColor = new AutoHintedMatcher<>(
		"uniform vec4 entityColor;", ParseShape.EXTERNAL_DECLARATION);

	private static final AutoHintedMatcher<ExternalDeclaration> uniformIntEntityId = new AutoHintedMatcher<>(
		"uniform int entityId;", ParseShape.EXTERNAL_DECLARATION);

	private static final AutoHintedMatcher<ExternalDeclaration> uniformIntBlockEntityId = new AutoHintedMatcher<>(
		"uniform int blockEntityId;", ParseShape.EXTERNAL_DECLARATION);

	private static final AutoHintedMatcher<ExternalDeclaration> uniformIntCurrentRenderedItemId = new AutoHintedMatcher<>(
		"uniform int currentRenderedItemId;", ParseShape.EXTERNAL_DECLARATION);

	// Feeds the pack's entityColor uniform from the overlay texture (hurt flash), passed through every stage.
	public static void patchOverlayColor(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		VanillaParameters parameters) {
		root.processMatches(t, uniformVec4EntityColor, ASTNode::detachAndDelete);

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			// entityColor is output even if this stage never declared it; later stages need the pass-through.
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"uniform sampler2D aeth_overlay;",
				"out vec4 entityColor;",
				"out vec4 aeth_vertexColor;",
				parameters.inputs.isIE() ? "uniform ivec2 aeth_OverlayUV;" : "in ivec2 aeth_UV1;");

			tree.prependMainFunctionBody(t,
				"vec4 overlayColor = texelFetch(aeth_overlay, " + (parameters.inputs.isIE() ? "aeth_OverlayUV" : "aeth_UV1") + ", 0);",
				"entityColor = vec4(overlayColor.rgb, 1.0 - overlayColor.a);",
				"aeth_vertexColor = aeth_Color;",
				// Some packs ignore alpha and assume rgb is zero when there is no hit flash.
				"entityColor.rgb *= float(entityColor.a != 0.0);");
		} else if (parameters.type.glShaderType == ShaderType.TESSELATION_CONTROL) {
			// reads take this invocation's vertex
			root.replaceReferenceExpressions(t, "entityColor", "entityColor[gl_InvocationID]");

			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"patch out vec4 entityColorTCS;",
				"in vec4 entityColor[];",
				"out vec4 aeth_vertexColorTCS[];",
				"in vec4 aeth_vertexColor[];");
			tree.prependMainFunctionBody(t,
				"entityColorTCS = entityColor[gl_InvocationID];",
				"aeth_vertexColorTCS[gl_InvocationID] = aeth_vertexColor[gl_InvocationID];");
		} else if (parameters.type.glShaderType == ShaderType.TESSELATION_EVAL) {
			// reads take the per-patch value
			root.replaceReferenceExpressions(t, "entityColor", "entityColorTCS");

			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"out vec4 entityColorTES;",
				"patch in vec4 entityColorTCS;",
				"out vec4 aeth_vertexColorTES;",
				"in vec4 aeth_vertexColorTCS[];");
			tree.prependMainFunctionBody(t,
				"entityColorTES = entityColorTCS;",
				"aeth_vertexColorTES = aeth_vertexColorTCS[0];");
		} else if (parameters.type.glShaderType == ShaderType.GEOMETRY) {
			// reads take the first vertex
			root.replaceReferenceExpressions(t, "entityColor", "entityColor[0]");

			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"out vec4 entityColorGS;",
				"in vec4 entityColor[];",
				"out vec4 aeth_vertexColorGS;",
				"in vec4 aeth_vertexColor[];");
			tree.prependMainFunctionBody(t,
				"entityColorGS = entityColor[0];",
				"aeth_vertexColorGS = aeth_vertexColor[0];");

			if (parameters.hasTesselation) {
				root.rename("aeth_vertexColor", "aeth_vertexColorTES");
				root.rename("entityColor", "entityColorTES");
			}
		} else if (parameters.type.glShaderType == ShaderType.FRAGMENT) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"in vec4 entityColor;", "in vec4 aeth_vertexColor;");

			tree.prependMainFunctionBody(t, "float aeth_vertexColorAlpha = aeth_vertexColor.a;");

			// read the last upstream stage's suffixed output
			if (parameters.hasGeometry) {
				root.rename("entityColor", "entityColorGS");
				root.rename("aeth_vertexColor", "aeth_vertexColorGS");
			} else if (parameters.hasTesselation) {
				root.rename("entityColor", "entityColorTES");
				root.rename("aeth_vertexColor", "aeth_vertexColorTES");
			}
		}
	}

	public static void patchEntityId(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		VanillaParameters parameters) {
		root.processMatches(t, uniformIntEntityId, ASTNode::detachAndDelete);
		root.processMatches(t, uniformIntBlockEntityId, ASTNode::detachAndDelete);
		root.processMatches(t, uniformIntCurrentRenderedItemId, ASTNode::detachAndDelete);

		if (parameters.type.glShaderType == ShaderType.GEOMETRY) {
			// geometry reads the TES output when tessellation is present
			String input = "aeth_entityInfo" + (parameters.hasTesselation ? "TES" : "") + "[0]";
			root.replaceReferenceExpressions(t, "entityId", input + ".x");

			root.replaceReferenceExpressions(t, "blockEntityId", input + ".y");

			root.replaceReferenceExpressions(t, "currentRenderedItemId", input + ".z");
		} else {
			root.replaceReferenceExpressions(t, "entityId",
				"aeth_entityInfo.x");

			root.replaceReferenceExpressions(t, "blockEntityId",
				"aeth_entityInfo.y");

			root.replaceReferenceExpressions(t, "currentRenderedItemId",
				"aeth_entityInfo.z");
		}

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat out ivec3 aeth_entityInfo;",
				"in ivec3 aeth_Entity;");

			tree.prependMainFunctionBody(t,
				"aeth_entityInfo = aeth_Entity;");
		} else if (parameters.type.glShaderType == ShaderType.TESSELATION_CONTROL) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat out ivec3 aeth_entityInfoTCS[];",
				"flat in ivec3 aeth_entityInfo[];");
			root.replaceReferenceExpressions(t, "aeth_entityInfo", "aeth_entityInfo[gl_InvocationID]");

			tree.prependMainFunctionBody(t,
				"aeth_entityInfoTCS[gl_InvocationID] = aeth_entityInfo[gl_InvocationID];");
		} else if (parameters.type.glShaderType == ShaderType.TESSELATION_EVAL) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat out ivec3 aeth_entityInfoTES;",
				"flat in ivec3 aeth_entityInfoTCS[];");
			tree.prependMainFunctionBody(t,
				"aeth_entityInfoTES = aeth_entityInfoTCS[0];");

			root.replaceReferenceExpressions(t, "aeth_entityInfo", "aeth_entityInfoTCS[0]");

		} else if (parameters.type.glShaderType == ShaderType.GEOMETRY) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat out ivec3 aeth_entityInfoGS;",
				"flat in ivec3 aeth_entityInfo" + (parameters.hasTesselation ? "TES" : "") + "[];");
			tree.prependMainFunctionBody(t,
				"aeth_entityInfoGS = aeth_entityInfo" + (parameters.hasTesselation ? "TES" : "") + "[0];");
		} else if (parameters.type.glShaderType == ShaderType.FRAGMENT) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"flat in ivec3 aeth_entityInfo;");

			// read the last upstream stage's suffixed output
			if (parameters.hasGeometry) {
				root.rename("aeth_entityInfo", "aeth_entityInfoGS");
			} else if (parameters.hasTesselation) {
				root.rename("aeth_entityInfo", "aeth_entityInfoTES");
			}
		}
	}
}
