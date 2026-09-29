package com.bbsvfx.bbsvfx.forms;

/**
 * Duck interface mixed onto BBS's {@code PoseTransform} so every pose bone carries its own smear: a
 * vector (manual direction × length) plus the look knobs (copy count, opacity falloff). Because they
 * live in {@code PoseTransform} they serialise and interpolate with the pose — so the smear animates
 * like any other pose channel. {@code count}/{@code falloff} of 0 mean "use the render defaults".
 */
public interface ISmearBone
{
    float bbsvfx$smearX();

    float bbsvfx$smearY();

    float bbsvfx$smearZ();

    void bbsvfx$setSmear(float x, float y, float z);

    float bbsvfx$smearCount();

    void bbsvfx$setSmearCount(float count);

    float bbsvfx$smearFalloff();

    void bbsvfx$setSmearFalloff(float falloff);

    float bbsvfx$smearDissolve();

    void bbsvfx$setSmearDissolve(float dissolve);

    /** Manual mode enable: &gt; 0 = the straight-vector smear is active (separate from the vector values, so
     *  toggling the mode off stops the smear without losing the configured vector). */
    float bbsvfx$smearManual();

    void bbsvfx$setSmearManual(float manual);

    /**
     * Auto-arc mode: 0 = off (the straight-vector smear above), &gt; 0 = the echo copies follow the
     * bone's REAL motion arc — each copy is the model posed at an earlier moment in time
     * (time-rewound), so a swinging limb leaves a curved trail instead of a straight one.
     */
    float bbsvfx$smearArc();

    void bbsvfx$setSmearArc(float arc);

    /** Trail length in ticks for arc mode (how far back in time the echo reaches). 0 = render default. */
    float bbsvfx$smearTime();

    void bbsvfx$setSmearTime(float time);

    /**
     * Arc-copy deformation strength (multiplier). Each echo copy is stretched along its motion and
     * squashed across (volume-preserving), growing toward the tail where copies also shrink + wash out
     * into abstract smears. 0 = a sensible default; the effect also scales with the bone's actual speed.
     */
    float bbsvfx$smearStretch();

    void bbsvfx$setSmearStretch(float stretch);

    /**
     * Overlap density (arc mode): how many faint, deformed in-between copies are rendered along the
     * trajectory between the crisp multiples. Higher = the stretched copies overlap into a continuous
     * smear (closes the gaps between the fanned copies). 0 = render default.
     */
    float bbsvfx$smearDensity();

    void bbsvfx$setSmearDensity(float density);

    /** Opacity of each faint overlap copy in the band (arc mode). 0 = render default (~0.14). */
    float bbsvfx$smearOpacity();

    void bbsvfx$setSmearOpacity(float opacity);

    /** Motion lines: 0 = off, else opacity/intensity. */
    float bbsvfx$smearLines();

    void bbsvfx$setSmearLines(float lines);

    float bbsvfx$smearLinesCount();

    void bbsvfx$setSmearLinesCount(float count);

    float bbsvfx$smearLinesWidth();

    void bbsvfx$setSmearLinesWidth(float width);

    float bbsvfx$smearLinesSpread();

    void bbsvfx$setSmearLinesSpread(float spread);

    float bbsvfx$smearLinesOffsetX();

    float bbsvfx$smearLinesOffsetY();

    float bbsvfx$smearLinesOffsetZ();

    void bbsvfx$setSmearLinesOffset(float x, float y, float z);

    /**
     * Motion lines colour source: 0 = use the bone's pose colour (the editor's Color picker), &gt; 0 =
     * tint the lines with the actor's texture average instead. The line colour itself reuses the
     * built-in per-bone {@code PoseTransform.color}, so it serialises and animates with the pose.
     */
    float bbsvfx$smearLinesTexture();

    void bbsvfx$setSmearLinesTexture(float texture);

    default boolean bbsvfx$hasSmear()
    {
        return this.bbsvfx$smearX() != 0F || this.bbsvfx$smearY() != 0F || this.bbsvfx$smearZ() != 0F
            || this.bbsvfx$smearArc() > 0F || this.bbsvfx$smearManual() > 0F;
    }
}
