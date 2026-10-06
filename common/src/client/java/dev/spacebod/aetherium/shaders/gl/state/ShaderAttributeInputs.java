package dev.spacebod.aetherium.shaders.gl.state;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;

public class ShaderAttributeInputs {
	private boolean ie;
	private boolean color;
	private boolean tex;
	private boolean overlay;
	private boolean light;
	private boolean normal;
	private boolean newLines;
	private boolean glint;
	private boolean text;
	private int entityComponents;
	// hashCode and equals are hand-written: update both when adding fields.

	public ShaderAttributeInputs(VertexFormat format, boolean isFullbright, boolean isLines, boolean glint, boolean text, boolean ie) {
		this.ie = ie;
		this.text = text;
		this.glint = glint;

		this.newLines = isLines;

		format.getElements().stream().map(VertexFormatElement::name).forEach(name -> {
			// The chunk mesh's compact elements (ChunkMeshFormat) carry colour, texture and lightmap coordinates too.
			if ("Color".equals(name) || "a_Color".equals(name)) {
				color = true;
			}

			if ("LineWidth".equals(name)) {
				newLines = true;
			}

			if ("UV0".equals(name) || "a_TexCoord".equals(name)) {
				tex = true;
			}

			if ("UV1".equals(name)) {
				overlay = true;
			}

			if (("UV2".equals(name) || "a_LightAndData".equals(name)) && !isFullbright) {
				light = true;
			}

			if ("Normal".equals(name)) {
				normal = true;
			}
		});

		// Packs declare mc_Entity as a float, so it reads back zero and the transformer has to
		// redeclare it. Zero here means there is nothing to fix.
		// Elements are (name, offset, GpuFormat); integer attributes are the *_UINT / *_SINT formats.
		for (VertexFormatElement element : format.getElements()) {
			String formatName = element.format().name();
			if (!formatName.endsWith("_UINT") && !formatName.endsWith("_SINT")) {
				continue;
			}

			if ("mc_Entity".equals(element.name())) {
				entityComponents = element.format().componentCount();
			}
		}
	}

	public ShaderAttributeInputs(boolean color, boolean tex, boolean overlay, boolean light, boolean normal) {
		this.color = color;
		this.tex = tex;
		this.overlay = overlay;
		this.light = light;
		this.normal = normal;
	}

	public boolean hasColor() {
		return color;
	}

	public boolean hasTex() {
		return tex;
	}

	public boolean hasOverlay() {
		return overlay;
	}

	public boolean hasLight() {
		return light;
	}

	public boolean hasNormal() {
		return normal;
	}

	public boolean isNewLines() {
		return newLines;
	}

	public boolean isGlint() {
		return glint;
	}

	public int getEntityComponents() {
		return entityComponents;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (color ? 1231 : 1237);
		result = prime * result + (tex ? 1231 : 1237);
		result = prime * result + (overlay ? 1231 : 1237);
		result = prime * result + (light ? 1231 : 1237);
		result = prime * result + (normal ? 1231 : 1237);
		result = prime * result + (newLines ? 1231 : 1237);
		result = prime * result + (glint ? 1231 : 1237);
		result = prime * result + (text ? 1231 : 1237);
		result = prime * result + entityComponents;
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ShaderAttributeInputs other = (ShaderAttributeInputs) obj;
		if (color != other.color)
			return false;
		if (tex != other.tex)
			return false;
		if (overlay != other.overlay)
			return false;
		if (light != other.light)
			return false;
		if (normal != other.normal)
			return false;
		if (newLines != other.newLines)
			return false;
		if (glint != other.glint)
			return false;
		if (text != other.text)
			return false;
		return entityComponents == other.entityComponents;
	}

	public boolean isText() {
		return text;
	}

	public boolean isIE() {
		return ie;
	}
}
