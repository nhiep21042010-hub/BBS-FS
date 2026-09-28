package com.bbsvfx.bbsvfx.client;

/**
 * Active whole-form blend, set for the duration of a TOP-LEVEL form render (see {@code FormBlendStateMixin}
 * on {@code FormUtilsClient.render}) and read by every per-renderer blend hook (model / block / curve).
 *
 * <p>It is keyed to the top-level form rather than each GPU draw because some forms render through nested
 * sub-forms (a Destruction Box draws its blocks via a reused {@code BlockForm}; the 3D curve draws blocks
 * the same way) — those sub-forms carry no blend of their own, so the outer form's blend has to stay
 * active across the whole render. Mirrors {@code SmearRenderState}.</p>
 */
public final class BbsVfxBlendState
{
    public static boolean active;
    public static int mode;
    public static float factor;

    private BbsVfxBlendState()
    {}

    public static void begin(int mode, float factor)
    {
        BbsVfxBlendState.active = true;
        BbsVfxBlendState.mode = mode;
        BbsVfxBlendState.factor = factor;
    }

    public static void end()
    {
        BbsVfxBlendState.active = false;
        BbsVfxBlendState.mode = 0;
        BbsVfxBlendState.factor = 1F;
    }
}
