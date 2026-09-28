package com.bbsvfx.bbsvfx.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import mchorse.bbs_mod.forms.forms.LabelForm;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.LabelFormRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Re-draws flat (non-projection) labels AFTER an external shaderpack's shading, so that under Iris/OptiFine
 * (a) the default MC-font text stops glowing (the pack treats in-world {@code rendertype_text} as emissive)
 * and (b) the shader-based blend modes work (the pack overrides custom core shaders during the world pass,
 * but not after it). During the world pass {@code LabelFormRendererMixin} captures each flat label's draw
 * (proj + actor matrix + light + colour) and cancels the in-world draw; here we replay it at
 * {@code onWorldRenderEnd} into the final framebuffer, depth-tested against the scene depth.
 *
 * <p>Only used while a shaderpack is active; without one, labels draw in-world as normal (no glow there).</p>
 */
public final class BbsVfxLabelOverlay
{
    /** Set while replaying so the renderer mixin's deferral is bypassed (draws normally on the re-entry). */
    public static volatile boolean replaying;

    private record Deferred(LabelFormRenderer renderer, LabelForm form, Matrix4f proj, Matrix4f model, int light, int color, float transition)
    {}

    private static final List<Deferred> LIST = new ArrayList<>();

    private BbsVfxLabelOverlay()
    {}

    public static void defer(LabelFormRenderer renderer, LabelForm form, Matrix4f proj, Matrix4f model, int light, int color, float transition)
    {
        LIST.add(new Deferred(renderer, form, proj, model, light, color, transition));
    }

    public static void render(net.minecraft.client.gl.Framebuffer main)
    {
        if (LIST.isEmpty())
        {
            return;
        }

        if (main == null)
        {
            LIST.clear();
            return;
        }

        main.beginWrite(true);

        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());

        BbsVfxRenderCompat.pushIdentityModelView();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        replaying = true;

        try
        {
            for (Deferred d : LIST)
            {
                RenderSystem.setProjectionMatrix(d.proj, VertexSorter.BY_DISTANCE);

                MatrixStack stack = new MatrixStack();

                stack.multiplyPositionMatrix(d.model);

                FormRenderingContext ctx = new FormRenderingContext();

                ctx.type = FormRenderType.ENTITY;
                ctx.stack = stack;
                ctx.world = new MatrixStack();
                ctx.light = d.light;
                ctx.overlay = 0;
                ctx.color = d.color;
                ctx.transition = d.transition;
                ctx.stencilMap = null;

                try
                {
                    d.renderer.render3D(ctx);
                }
                catch (Throwable ignored)
                {
                }
            }
        }
        finally
        {
            replaying = false;
        }

        RenderSystem.setProjectionMatrix(oldProj, VertexSorter.BY_DISTANCE);
        BbsVfxRenderCompat.popModelView();

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();

        LIST.clear();
    }
}
