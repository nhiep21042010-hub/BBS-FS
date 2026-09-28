package com.bbsvfx.bbsvfx.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL14;
import com.bbsvfx.bbsvfx.forms.BlendMode;

/**
 * Stage 1 (fixed-function) GL blend state for the whole-form blend mode. Maps a {@link BlendMode} index
 * to {@code glBlendFunc}/{@code glBlendEquation}, run right where BBS would otherwise call
 * {@code RenderSystem.defaultBlendFunc()} before a form draw.
 *
 * <p>The {@code factor} (0..1 effect strength) is applied through {@code glBlendColor} + a
 * {@code CONSTANT_ALPHA} source factor for the additive-style modes (Add / Screen), so those can be faded
 * smoothly and animated. Multiply / Darken / Lighten can't lerp toward "no effect" in a single
 * fixed-function pass, so for them the factor acts as an on/off gate (full when &gt; ~0, off otherwise);
 * smooth strength for every mode is Stage 2 (a post-composite shader). The shader-only modes
 * (Overlay/Difference/…) likewise fall back to Normal until Stage 2.</p>
 */
public final class BbsVfxBlendGL
{
    private BbsVfxBlendGL()
    {}

    /**
     * Sets the GL blend state for the given mode/factor. Returns true if a non-default blend was applied
     * (the caller must then {@link #resetEquation()} after the draw); false means "leave it normal" and
     * the caller should run the usual {@code defaultBlendFunc()}.
     */
    public static boolean apply(int modeIndex, float factor)
    {
        BlendMode bm = BlendMode.byIndex(modeIndex);

        if (bm == BlendMode.NORMAL || !bm.fixedFunction || factor <= 0.004F)
        {
            return false;
        }

        RenderSystem.enableBlend();
        GL14.glBlendColor(0F, 0F, 0F, Math.min(1F, factor));

        switch (bm)
        {
            case ADD:
                RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.CONSTANT_ALPHA, GlStateManager.DstFactor.ONE, GlStateManager.SrcFactor.ZERO, GlStateManager.DstFactor.ONE);
                break;
            case SCREEN:
                RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.CONSTANT_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_COLOR, GlStateManager.SrcFactor.ZERO, GlStateManager.DstFactor.ONE);
                break;
            case MULTIPLY:
                RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.DST_COLOR, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SrcFactor.ZERO, GlStateManager.DstFactor.ONE);
                break;
            case DARKEN:
                GlStateManager._blendEquation(GL14.GL_MIN);
                RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE, GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE);
                break;
            case LIGHTEN:
                GlStateManager._blendEquation(GL14.GL_MAX);
                RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE, GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE);
                break;
            default:
                break;
        }

        return true;
    }

    /** Restores the additive blend equation; call after the draw when {@link #apply} returned true. */
    public static void resetEquation()
    {
        GlStateManager._blendEquation(GL14.GL_FUNC_ADD);
    }

    /**
     * Convenience for the renderers that call {@code RenderSystem.defaultBlendFunc()} right before a draw:
     * if a whole-form blend is active, apply it; otherwise fall back to the default func. The blend
     * equation is reset once per top-level form render by {@code FormBlendStateMixin}.
     */
    public static void applyActiveOrDefault()
    {
        if (!(BbsVfxBlendState.active && apply(BbsVfxBlendState.mode, BbsVfxBlendState.factor)))
        {
            RenderSystem.defaultBlendFunc();
        }
    }
}
