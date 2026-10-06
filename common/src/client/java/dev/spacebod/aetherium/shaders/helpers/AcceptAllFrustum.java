package dev.spacebod.aetherium.shaders.helpers;

/** A vanilla frustum that can be told to contain everything (the pack's {@code frustum.culling=false}). */
public interface AcceptAllFrustum {
	void aetherium$acceptAll();
}
