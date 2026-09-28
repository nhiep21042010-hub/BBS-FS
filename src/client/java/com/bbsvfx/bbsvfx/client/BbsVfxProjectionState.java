package com.bbsvfx.bbsvfx.client;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame list of label "projectors" captured during the form render (a LabelForm in projection mode).
 * {@link BbsVfxProjectionShader} consumes and clears it at the end of the world render, casting each text
 * texture onto the scene via depth-reprojection. Client-only (the LabelForm renderer writes it; the post
 * pass reads it) — unlike the impact state, no main-side clip is involved.
 */
public final class BbsVfxProjectionState
{
    /**
     * One captured projector: its clip matrix, the view-space reconstruction matrix (inverse projection),
     * the projector forward in view space (fx,fy,fz, for the facing fade), the slide texture, blend mode
     * index, edge-fade amount, and tint.
     */
    public record Projector(Matrix4f projectorVP, Matrix4f invCameraVP, float fx, float fy, float fz,
                            int textureId, int blendMode, float fade, float r, float g, float b, float a)
    {}

    private static final List<Projector> LIST = new ArrayList<>();

    private BbsVfxProjectionState()
    {}

    public static void add(Projector projector)
    {
        LIST.add(projector);
    }

    public static boolean isEmpty()
    {
        return LIST.isEmpty();
    }

    public static List<Projector> list()
    {
        return LIST;
    }

    public static void reset()
    {
        LIST.clear();
    }
}
