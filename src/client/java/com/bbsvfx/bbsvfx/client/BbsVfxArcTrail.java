package com.bbsvfx.bbsvfx.client;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates the world positions a smeared bone passes through during the arc echo copies, so a
 * connecting "motion trail" ribbon can be drawn through them — the band that binds the discrete copies
 * into one continuous smear. {@code SmearRenderMixin.bbsvfx$renderArc} calls {@link #begin()} before the
 * copy loop; {@code ModelFormRendererLinesMixin} appends each shown bone's position per copy (it has the
 * renderer's bone matrix cache); then the trail is drawn after the loop and {@link #end()} clears it.
 *
 * <p>Per bone the list is ordered farthest-copy → nearest (the loop runs k = count … 1).</p>
 */
public final class BbsVfxArcTrail
{
    public static boolean active;
    /** Per bone: the hand-tip world position at each copy (farthest → nearest). */
    public static final Map<String, List<Vector3f>> points = new LinkedHashMap<>();
    /** Per bone: the bone's own origin (the pivot the limb swings around) — apex of the swept fan. */
    public static final Map<String, Vector3f> pivots = new LinkedHashMap<>();

    private BbsVfxArcTrail()
    {}

    public static void begin()
    {
        active = true;
        points.clear();
        pivots.clear();
    }

    public static void add(String bone, Vector3f pos)
    {
        points.computeIfAbsent(bone, k -> new ArrayList<>()).add(pos);
    }

    public static void setPivot(String bone, Vector3f pivot)
    {
        pivots.put(bone, pivot);
    }

    public static void end()
    {
        active = false;
        points.clear();
        pivots.clear();
    }
}
