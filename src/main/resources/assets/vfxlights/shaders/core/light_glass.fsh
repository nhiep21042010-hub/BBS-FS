#version 150

in vec4 vertexColor;
in float viewDepth;

uniform float Range;

out vec4 fragColor;

/*
 * The glass caster's contribution to the atlas' colour map. RGB is the tint (blended
 * multiplicatively, so stacked panes compound); alpha is THIS fragment's depth (blended with MIN, so
 * the nearest pane wins) — shading later applies the tint only to receivers BEHIND that depth.
 *
 * The depth is LINEAR (view-axis blocks over the lamp's range), not gl_FragCoord.z: the projective
 * depth crams every caster beyond ~2 blocks into the top 5% of the 8-bit alpha — about 12 usable
 * levels — and the behind-the-pane test flipped along quantization contours, cutting white arcs
 * through the stained pools. Linear depth spends all 256 levels evenly across the range.
 */
void main()
{
    fragColor = vec4(vertexColor.rgb, clamp(viewDepth / Range, 0.0, 1.0));
}
