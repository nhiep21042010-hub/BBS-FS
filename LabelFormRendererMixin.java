package com.bbsvfx.bbsvfx.mixin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.LabelForm;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.LabelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.FormColorBlend;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import org.lwjgl.opengl.GL14;
import com.bbsvfx.bbsvfx.client.BbsVfxFontManager;
import com.bbsvfx.bbsvfx.client.BbsVfxFontTexture;
import com.bbsvfx.bbsvfx.client.BbsVfxLabelBlend;
import com.bbsvfx.bbsvfx.client.BbsVfxLabelOverlay;
import com.bbsvfx.bbsvfx.client.BbsVfxProjectionState;
import com.bbsvfx.bbsvfx.forms.BlendMode;
import com.bbsvfx.bbsvfx.forms.ILabelExtras;

import java.util.List;

/**
 * Takes over {@link LabelFormRenderer#render3D} when any addon label feature is active, drawing the text
 * ourselves so we can apply per-character letter spacing (and, in later stages, outline / blend modes /
 * custom fonts). When no feature is active the stock path runs unchanged, so plain labels are untouched.
 *
 * <p>Picking is always left to the stock path — the exact glyph layout doesn't matter for selecting the
 * actor, and this avoids reimplementing the picker-shader target setup.
 */
@Mixin(value = LabelFormRenderer.class, remap = false)
public abstract class LabelFormRendererMixin
{
    @Inject(method = "render3D", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsvfx$render3D(FormRenderingContext context, CallbackInfo ci)
    {
        LabelForm form = ((LabelFormRenderer) (Object) this).getForm();
        ILabelExtras extras = (ILabelExtras) (Object) form;

        /* Projection mode: capture the projector + slide and cast it onto the world in the post pass
         * (works under shaderpacks). Don't draw the flat label. The editor preview (PREVIEW type) has no
         * world post pass, so there we fall through and draw the flat label as a stand-in. */
        if (!context.isPicking() && context.type != FormRenderType.PREVIEW && !BbsVfxLabelOverlay.replaying
            && !com.bbsvfx.bbsvfx.client.BbsVfxImpactSilhouette.replaying
            && extras.bbsvfx$projection().get())
        {
            /* The Iris shadow pass re-renders forms with the light's ortho matrices — capturing there adds
             * a garbage projector. A projection label also shouldn't cast a flat-label shadow, so cancel
             * without capturing. */
            if (!BBSRendering.isIrisShadowPass())
            {
                bbsvfx$captureProjection(context, form, extras);
            }

            ci.cancel();

            return;
        }

        /* Under a shaderpack, defer flat labels to a post-overlay (drawn after the pack's shading): stops
         * the default text glowing (the pack lights in-world text as emissive) and lets the shader blend
         * modes work (the pack overrides our core shaders during the world pass). Replays here via the
         * {@code replaying} flag, which bypasses this. */
        if (!context.isPicking() && context.type != FormRenderType.PREVIEW && !BbsVfxLabelOverlay.replaying
            && BBSRendering.isIrisShadersEnabled() && !BBSRendering.isIrisShadowPass())
        {
            BbsVfxLabelOverlay.defer((LabelFormRenderer) (Object) this, form,
                new Matrix4f(RenderSystem.getProjectionMatrix()),
                new Matrix4f(RenderSystem.getModelViewMatrix()).mul(context.stack.peek().getPositionMatrix()),
                context.light, context.color, context.getTransition());
            ci.cancel();

            return;
        }

        if (!bbsvfx$needsCustom(form, context))
        {
            return;
        }

        bbsvfx$render(context, form);
        ci.cancel();
    }

    /** Captures this label as a slide projector for the depth-reproject post pass. */
    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$captureProjection(FormRenderingContext context, LabelForm form, ILabelExtras extras)
    {
        String custom = extras.bbsvfx$font().get();
        String fontName = (custom == null || custom.isEmpty() || custom.equals(BbsVfxFontManager.DEFAULT)) ? "SansSerif" : custom;
        String text = bbsvfx$stripCodes(form.text.get());

        if (text.isEmpty())
        {
            return;
        }

        float size = Math.max(1F, extras.bbsvfx$fontSize().get());

        BbsVfxFontTexture.Baked baked = BbsVfxFontTexture.get(fontName, text, size,
            extras.bbsvfx$tracking().get(), form.max.get(), extras.bbsvfx$strokeWidth().get(),
            form.color.get().getARGBColor(), extras.bbsvfx$strokeColor().get().getARGBColor(), extras.bbsvfx$strokeOnly().get(),
            extras.bbsvfx$gradient().get(), extras.bbsvfx$gradientStart().get().getARGBColor(), extras.bbsvfx$gradientEnd().get().getARGBColor(), extras.bbsvfx$gradientAngle().get());

        if (baked == null)
        {
            /* Slide not baked yet — built at end of frame; project next frame. */
            return;
        }

        Texture tex = baked.colored();

        /* Work in VIEW space: inverse(proj) reconstructs view-space position from screen+depth, and the
         * projector pose in view space is modelview * stack. On 1.20 the global modelview is identity
         * during the world pass (the camera view is baked into context.stack), on 1.21 it's the other way
         * around — the stack is camera-relative and the view rotation lives in the global modelview —
         * so composing the two is correct on both. */
        Matrix4f proj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f invProj = new Matrix4f(proj).invert();

        Matrix4f stackM = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(context.stack.peek().getPositionMatrix());

        /* ORTHOGRAPHIC projector sized to the label's actual world size (a fixed-size decal that does NOT
         * grow with distance). The flat label draws at tex.size*scale local px then ×1/16 to blocks, so
         * its world size is tex.size*scale/16; the box matches that. */
        float worldW = tex.width * baked.scale() / 16F;
        float worldH = tex.height * baked.scale() / 16F;
        float range = Math.max(0.1F, extras.bbsvfx$projRange().get());
        Matrix4f projectorVP = new Matrix4f()
            .ortho(-worldW / 2F, worldW / 2F, -worldH / 2F, worldH / 2F, 0.05F, range)
            .mul(new Matrix4f(stackM).invert());

        /* Projector forward (its local -Z) in view space, for the facing fade. */
        org.joml.Vector3f fwd = stackM.transformDirection(new org.joml.Vector3f(0F, 0F, -1F)).normalize();

        int blendMode = extras.bbsvfx$blendMode().get();
        float fade = Math.max(0F, Math.min(1F, extras.bbsvfx$projFade().get()));

        /* Tint = the actor's render colour (fade / parent tint); the slide already carries the form colour. */
        Color tint = new Color().set(context.color, true);

        BbsVfxProjectionState.add(new BbsVfxProjectionState.Projector(projectorVP, invProj, fwd.x, fwd.y, fwd.z,
            tex.id, blendMode, fade, tint.r, tint.g, tint.b, tint.a));
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean bbsvfx$needsCustom(LabelForm form, FormRenderingContext context)
    {
        ILabelExtras x = (ILabelExtras) (Object) form;

        /* During picking only the custom-font path needs us — it draws the glyph silhouette through the
         * picker shader so the hover highlight traces the custom font (not the default). The MC-glyph
         * tweaks (tracking/stroke/blend) don't change the silhouette enough to bother; let stock pick. */
        if (context.isPicking())
        {
            return bbsvfx$hasCustomFont(x);
        }

        return x.bbsvfx$tracking().get() != 0F
            || x.bbsvfx$strokeWidth().get() > 0F
            || x.bbsvfx$strokeOnly().get()
            || x.bbsvfx$blendMode().get() != 0
            || x.bbsvfx$gradient().get()
            || bbsvfx$hasCustomFont(x);
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean bbsvfx$hasCustomFont(ILabelExtras x)
    {
        String f = x.bbsvfx$font().get();

        return f != null && !f.isEmpty() && !f.equals(BbsVfxFontManager.DEFAULT);
    }

    /** Mirrors stock {@link LabelFormRenderer#render3D} setup, then draws via our tracked text path. */
    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$render(FormRenderingContext context, LabelForm form)
    {
        MatrixStack stack = context.stack;

        stack.push();

        if (form.billboard.get())
        {
            Matrix4f modelMatrix = stack.peek().getPositionMatrix();
            Vector3f scale = Vectors.TEMP_3F;

            modelMatrix.getScale(scale);

            modelMatrix.m00(1).m01(0).m02(0);
            modelMatrix.m10(0).m11(1).m12(0);
            modelMatrix.m20(0).m21(0).m22(1);

            modelMatrix.scale(scale);

            stack.peek().getNormalMatrix().identity();
        }

        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
        float s = 1F / 16F;
        int light = context.light;
        float tracking = ((ILabelExtras) (Object) form).bbsvfx$tracking().get();

        MatrixStackUtils.scaleStack(stack, s, -s, s);

        RenderSystem.disableCull();

        ILabelExtras extras = (ILabelExtras) (Object) form;

        if (bbsvfx$hasCustomFont(extras))
        {
            bbsvfx$renderBaked(context, form, extras);
        }
        else if (form.max.get() <= 10)
        {
            bbsvfx$renderString(context, form, consumers, renderer, light, tracking);
        }
        else
        {
            bbsvfx$renderLimited(context, form, consumers, renderer, light, tracking);
        }

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();

        stack.pop();
    }

    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$renderString(FormRenderingContext context, LabelForm form, CustomVertexConsumerProvider consumers, TextRenderer renderer, int light, float tracking)
    {
        MatrixStack stack = context.stack;
        String content = StringUtils.processColoredText(form.text.get());

        int w = bbsvfx$width(renderer, content, tracking) - 1;
        int h = renderer.fontHeight - 2;
        int x = (int) (-w * form.anchorX.get());
        int y = (int) (-h * form.anchorY.get());

        ILabelExtras extras = (ILabelExtras) (Object) form;
        boolean strokeOnly = extras.bbsvfx$strokeOnly().get();

        Color shadowColor = form.shadowColor.get().copy();
        Color color = new Color().set(context.color, true);

        FormColorBlend.blend(color, form.color.get());
        shadowColor.mul(context.color);

        bbsvfx$stroke(context, extras, consumers, renderer, content, x, y, tracking, light);

        if (shadowColor.a > 0)
        {
            stack.push();
            stack.translate(0F, 0F, -0.1F);
            bbsvfx$drawTracked(renderer, consumers, stack, content,
                (int) (x + form.shadowX.get()), (int) (y + form.shadowY.get()),
                shadowColor.getARGBColor(), tracking, light);
            stack.pop();
        }

        if (!strokeOnly)
        {
            if (extras.bbsvfx$gradient().get())
            {
                Color start = extras.bbsvfx$gradientStart().get().copy();
                Color end = extras.bbsvfx$gradientEnd().get().copy();

                start.mul(context.color);
                end.mul(context.color);

                bbsvfx$drawGradient(renderer, consumers, stack, content, x, y, start.getARGBColor(), end.getARGBColor(),
                    extras.bbsvfx$gradientAngle().get(), tracking, light, x, y, w, h);
            }
            else
            {
                bbsvfx$drawTracked(renderer, consumers, stack, content, x, y, color.getARGBColor(), tracking, light);
            }
        }

        RenderSystem.enableDepthTest();
        bbsvfx$drawWithBlend(consumers, BlendMode.byIndex(extras.bbsvfx$blendMode().get()));

        bbsvfx$background(context, form, x, y, w, h);
    }

    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$renderLimited(FormRenderingContext context, LabelForm form, CustomVertexConsumerProvider consumers, TextRenderer renderer, int light, float tracking)
    {
        MatrixStack stack = context.stack;
        String content = StringUtils.processColoredText(form.text.get());
        List<String> lines = FontRenderer.wrap(renderer, content, form.max.get());

        if (lines.size() <= 1)
        {
            bbsvfx$renderString(context, form, consumers, renderer, light, tracking);

            return;
        }

        for (int i = 0; i < lines.size(); i++)
        {
            lines.set(i, lines.get(i).trim());
        }

        int w = 0;
        int h = renderer.fontHeight - 2;

        for (String line : lines)
        {
            w = Math.max(bbsvfx$width(renderer, line, tracking) - 1, w);
            h += 12;
        }

        h -= 12;

        int x = (int) (-w * form.anchorX.get());
        int y = (int) (-h * form.anchorY.get());

        ILabelExtras extras = (ILabelExtras) (Object) form;
        boolean strokeOnly = extras.bbsvfx$strokeOnly().get();
        float strokeWidth = extras.bbsvfx$strokeWidth().get();

        Color shadowColor = form.shadowColor.get().copy();

        shadowColor.mul(context.color);

        if (strokeWidth > 0F)
        {
            Color strokeCol = extras.bbsvfx$strokeColor().get().copy();
            strokeCol.mul(context.color);
            int strokeARGB = strokeCol.getARGBColor();
            int iSw = Math.max(1, (int) strokeWidth);

            stack.push();
            stack.translate(0F, 0F, -0.05F);

            int ys = y;

            for (String line : lines)
            {
                int lineW = bbsvfx$width(renderer, line, tracking);
                int x2 = x + (form.anchorLines.get() ? (int) ((w - lineW) * form.anchorX.get()) : 0);

                for (int dy = -iSw; dy <= iSw; dy += iSw)
                {
                    for (int dx = -iSw; dx <= iSw; dx += iSw)
                    {
                        if (dx == 0 && dy == 0)
                        {
                            continue;
                        }

                        bbsvfx$drawTracked(renderer, consumers, stack, line, x2 + dx, ys + dy, strokeARGB, tracking, light);
                    }
                }

                ys += 12;
            }

            stack.pop();
        }

        if (shadowColor.a > 0)
        {
            stack.push();
            stack.translate(0F, 0F, -0.1F);

            int ys = y;

            for (String line : lines)
            {
                int lineW = bbsvfx$width(renderer, line, tracking);
                int x2 = x + (form.anchorLines.get() ? (int) ((w - lineW) * form.anchorX.get()) : 0);

                bbsvfx$drawTracked(renderer, consumers, stack, line,
                    (int) (x2 + form.shadowX.get()), (int) (ys + form.shadowY.get()),
                    shadowColor.getARGBColor(), tracking, light);

                ys += 12;
            }

            stack.pop();
        }

        if (!strokeOnly)
        {
            Color color = new Color().set(context.color, true);

            FormColorBlend.blend(color, form.color.get());

            int argb = color.getARGBColor();
            boolean gradient = extras.bbsvfx$gradient().get();
            int startArgb = argb;
            int endArgb = argb;
            float gradAngle = extras.bbsvfx$gradientAngle().get();

            if (gradient)
            {
                Color start = extras.bbsvfx$gradientStart().get().copy();
                Color end = extras.bbsvfx$gradientEnd().get().copy();

                start.mul(context.color);
                end.mul(context.color);
                startArgb = start.getARGBColor();
                endArgb = end.getARGBColor();
            }

            int ys = y;

            for (String line : lines)
            {
                int lineW = bbsvfx$width(renderer, line, tracking);
                int x2 = x + (form.anchorLines.get() ? (int) ((w - lineW) * form.anchorX.get()) : 0);

                if (gradient)
                {
                    bbsvfx$drawGradient(renderer, consumers, stack, line, x2, ys, startArgb, endArgb, gradAngle, tracking, light, x, y, w, h);
                }
                else
                {
                    bbsvfx$drawTracked(renderer, consumers, stack, line, x2, ys, argb, tracking, light);
                }

                ys += 12;
            }
        }

        bbsvfx$drawWithBlend(consumers, BlendMode.byIndex(extras.bbsvfx$blendMode().get()));

        RenderSystem.enableDepthTest();

        bbsvfx$background(context, form, x, y, w, h);
    }

    /** Renders the label with a custom (AWT-baked) font as a textured quad. */
    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$renderBaked(FormRenderingContext context, LabelForm form, ILabelExtras extras)
    {
        MatrixStack stack = context.stack;
        String text = bbsvfx$stripCodes(form.text.get());
        float size = Math.max(1F, extras.bbsvfx$fontSize().get());
        float tracking = extras.bbsvfx$tracking().get();
        float strokeWidth = extras.bbsvfx$strokeWidth().get();
        boolean strokeOnly = extras.bbsvfx$strokeOnly().get();

        int fillARGB = form.color.get().getARGBColor();
        int strokeARGB = extras.bbsvfx$strokeColor().get().getARGBColor();

        BbsVfxFontTexture.Baked baked = BbsVfxFontTexture.get(extras.bbsvfx$font().get(), text, size, tracking, form.max.get(), strokeWidth, fillARGB, strokeARGB, strokeOnly,
            extras.bbsvfx$gradient().get(), extras.bbsvfx$gradientStart().get().getARGBColor(), extras.bbsvfx$gradientEnd().get().getARGBColor(), extras.bbsvfx$gradientAngle().get());

        if (baked == null)
        {
            /* Not baked yet — queued, built at end of frame; skip this one frame. */
            return;
        }

        Texture colored = baked.colored();
        Texture alpha = baked.alpha();
        float w = colored.width * baked.scale();
        float h = colored.height * baked.scale();
        float x = -w * form.anchorX.get();
        float y = -h * form.anchorY.get();

        if (context.isPicking())
        {
            bbsvfx$texQuadPicking(context, stack.peek().getPositionMatrix(), alpha, x, y, w, h);

            return;
        }

        BlendMode bm = BlendMode.byIndex(extras.bbsvfx$blendMode().get());

        /* Shadow: the white silhouette texture, tinted with the shadow colour. */
        Color shadowColor = form.shadowColor.get().copy();

        shadowColor.mul(context.color);

        if (shadowColor.a > 0)
        {
            stack.push();
            stack.translate(0F, 0F, -0.1F);
            bbsvfx$drawTexQuad(stack.peek().getPositionMatrix(), alpha, x + form.shadowX.get(), y + form.shadowY.get(), w, h, shadowColor, BlendMode.NORMAL);
            stack.pop();
        }

        /* Main: the colour-baked texture (fill + outline), tinted only by the actor fade colour. Drawn
         * with the vanilla program so it shows under external shaderpacks; fixed-function blend applies. */
        Color tint = new Color().set(context.color, true);

        bbsvfx$drawTexQuad(stack.peek().getPositionMatrix(), colored, x, y, w, h, tint, bm);

        bbsvfx$background(context, form, (int) x, (int) y, (int) w, (int) h);
    }

    /**
     * Draws one textured quad. Fixed-function blend modes set GL blend state + the vanilla position-tex-colour
     * program. Shader blend modes (overlay/…/exclusion) use the {@code quad_blend} program reading a copy of
     * the scene — this works in-world WITHOUT a shaderpack and, under a shaderpack, in the post-overlay
     * replay (both are points where our core shaders aren't overridden). {@code color} tints the texture.
     */
    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$drawTexQuad(Matrix4f m, Texture tex, float x, float y, float w, float h, Color color, BlendMode bm)
    {
        boolean ff = bm != BlendMode.NORMAL && bm.fixedFunction;
        boolean shader = bm != BlendMode.NORMAL && !bm.fixedFunction;

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();

        if (ff)
        {
            bbsvfx$applyBlend(bm);
        }
        else
        {
            RenderSystem.defaultBlendFunc();
        }

        ShaderProgram quad = shader ? BbsVfxLabelBlend.quadProgram() : null;
        int sceneTex = quad == null ? -1 : BbsVfxLabelBlend.captureScene();

        if (quad != null && sceneTex >= 0)
        {
            RenderSystem.setShader(() -> quad);
            RenderSystem.setShaderTexture(0, tex.id);
            RenderSystem.setShaderTexture(1, sceneTex);
            quad.getUniformOrDefault("ScreenSize").set((float) BbsVfxLabelBlend.sceneW, (float) BbsVfxLabelBlend.sceneH);
            quad.getUniformOrDefault("BlendMode").set(bm.ordinal());
        }
        else
        {
            RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
            RenderSystem.setShaderTexture(0, tex.id);
        }

        float r = color.r;
        float g = color.g;
        float b = color.b;
        float a = color.a;

        BufferBuilder builder = com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x, y, 0F).texture(0F, 0F).color(r, g, b, a));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x, y + h, 0F).texture(0F, 1F).color(r, g, b, a));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x + w, y + h, 0F).texture(1F, 1F).color(r, g, b, a));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x + w, y, 0F).texture(1F, 0F).color(r, g, b, a));
        BufferRenderer.drawWithGlobalProgram(builder.end());

        if (ff)
        {
            GlStateManager._blendEquation(GL14.GL_FUNC_ADD);
            RenderSystem.defaultBlendFunc();
        }
    }

    /**
     * Draws the baked-font quad into the picker/stencil buffer through BBS's picker_models program, which
     * alpha-tests {@code Sampler0} — so the hover highlight traces the custom glyph silhouette, not a box
     * or the default font. Uses {@code POSITION_COLOR_TEXTURE} to match the picker program's attribute
     * order (Position, Color, UV0); the unused UV2 reads 0, so the object index is just {@code Target}.
     */
    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$texQuadPicking(FormRenderingContext context, Matrix4f m, Texture tex, float x, float y, float w, float h)
    {
        ShaderProgram picker = BBSShaders.getPickerModelsProgram();
        GlUniform target = picker.getUniform("Target");

        if (target != null)
        {
            target.set(context.getPickingIndex());
        }

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(() -> picker);
        RenderSystem.setShaderTexture(0, tex.id);

        BufferBuilder builder = com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.begin(VertexFormat.DrawMode.QUADS, com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.positionColorTexture());
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x, y, 0F).color(1F, 1F, 1F, 1F).texture(0F, 0F));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x, y + h, 0F).color(1F, 1F, 1F, 1F).texture(0F, 1F));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x + w, y + h, 0F).color(1F, 1F, 1F, 1F).texture(1F, 1F));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x + w, y, 0F).color(1F, 1F, 1F, 1F).texture(1F, 0F));
        BufferRenderer.drawWithGlobalProgram(builder.end());
    }

    /** Strips Minecraft {@code §x} colour/format codes (meaningless for an AWT-baked font). */
    @org.spongepowered.asm.mixin.Unique
    private static String bbsvfx$stripCodes(String s)
    {
        return s == null ? "" : s.replaceAll("§.", "");
    }

    /**
     * Flushes the buffered text through the chosen blend mode. Normal (and not-yet-implemented shader
     * modes) just draw. Fixed-function modes set GL blend state per render layer via the provider's
     * hijack hook (the layer's own {@code startDrawing} would otherwise reset it), then restore defaults.
     */
    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$drawWithBlend(CustomVertexConsumerProvider consumers, BlendMode bm)
    {
        if (bm == BlendMode.NORMAL)
        {
            consumers.draw();

            return;
        }

        if (bm.fixedFunction)
        {
            CustomVertexConsumerProvider.hijackVertexFormat((layer) -> bbsvfx$applyBlend(bm));
            consumers.draw();
            CustomVertexConsumerProvider.clearRunnables();

            GlStateManager._blendEquation(GL14.GL_FUNC_ADD);
            RenderSystem.defaultBlendFunc();

            return;
        }

        /* Shader mode (overlay/soft-light/color-dodge/difference/exclusion): draw the glyphs through our
         * text_blend program, which reads a copy of the scene behind the label and blends per fragment. */
        ShaderProgram prog = BbsVfxLabelBlend.program();
        int sceneTex = prog == null ? -1 : BbsVfxLabelBlend.captureScene();

        if (prog == null || sceneTex < 0)
        {
            consumers.draw();

            return;
        }

        int idx = bm.ordinal();

        CustomVertexConsumerProvider.hijackVertexFormat((layer) ->
        {
            RenderSystem.setShader(() -> prog);
            RenderSystem.setShaderTexture(1, sceneTex);
            prog.getUniformOrDefault("ScreenSize").set((float) BbsVfxLabelBlend.sceneW, (float) BbsVfxLabelBlend.sceneH);
            prog.getUniformOrDefault("BlendMode").set(idx);
        });
        consumers.draw();
        CustomVertexConsumerProvider.clearRunnables();
    }

    /** Sets the GL blend state for a fixed-function blend mode (run per layer, after its startDrawing). */
    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$applyBlend(BlendMode bm)
    {
        RenderSystem.enableBlend();

        switch (bm)
        {
            case ADD:
                RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE, GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE);
                break;
            case SCREEN:
                RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE_MINUS_SRC_COLOR, GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE);
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
    }

    /** 8-direction outline pass for a single line (no-op when stroke width is 0). */
    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$stroke(FormRenderingContext context, ILabelExtras extras, CustomVertexConsumerProvider consumers, TextRenderer renderer, String content, int x, int y, float tracking, int light)
    {
        float strokeWidth = extras.bbsvfx$strokeWidth().get();

        if (strokeWidth <= 0F)
        {
            return;
        }

        Color strokeCol = extras.bbsvfx$strokeColor().get().copy();

        strokeCol.mul(context.color);

        int strokeARGB = strokeCol.getARGBColor();
        int iSw = Math.max(1, (int) strokeWidth);
        MatrixStack stack = context.stack;

        stack.push();
        stack.translate(0F, 0F, -0.05F);

        for (int dy = -iSw; dy <= iSw; dy += iSw)
        {
            for (int dx = -iSw; dx <= iSw; dx += iSw)
            {
                if (dx == 0 && dy == 0)
                {
                    continue;
                }

                bbsvfx$drawTracked(renderer, consumers, stack, content, x + dx, y + dy, strokeARGB, tracking, light);
            }
        }

        stack.pop();
    }

    /** Draws a string character by character, inserting {@code tracking} pixels of extra advance. */
    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$drawTracked(TextRenderer renderer, CustomVertexConsumerProvider consumers, MatrixStack stack, String text, int x, int y, int argb, float tracking, int light)
    {
        Matrix4f matrix = stack.peek().getPositionMatrix();
        int curX = x;

        for (int i = 0; i < text.length(); i++)
        {
            String ch = String.valueOf(text.charAt(i));

            renderer.draw(ch, curX, y, argb, false, matrix, consumers, TextRenderer.TextLayerType.NORMAL, 0, light);

            curX += renderer.getWidth(ch) + (int) tracking;
        }
    }

    /**
     * Like {@link #bbsvfx$drawTracked} but interpolates each character's colour from {@code startArgb} to
     * {@code endArgb} along the gradient axis (degrees) across the text block ({@code gx,gy,gw,gh}).
     * Per-character granularity (the MC font draws whole glyphs in one colour).
     */
    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$drawGradient(TextRenderer renderer, CustomVertexConsumerProvider consumers, MatrixStack stack, String text, int x, int y, int startArgb, int endArgb, float angleDeg, float tracking, int light, int gx, int gy, int gw, int gh)
    {
        Matrix4f matrix = stack.peek().getPositionMatrix();
        int curX = x;

        float angle = (float) Math.toRadians(angleDeg);
        float cosA = (float) Math.cos(angle);
        float sinA = (float) Math.sin(angle);
        float maxP = Math.max(cosA, 0F) + Math.max(sinA, 0F);
        float minP = Math.min(cosA, 0F) + Math.min(sinA, 0F);
        float range = maxP - minP;

        if (range < 1.0E-4F)
        {
            range = 1F;
        }

        float ty = gh > 0 ? (y - gy) / (float) gh : 0F;

        for (int i = 0; i < text.length(); i++)
        {
            String ch = String.valueOf(text.charAt(i));
            float tx = gw > 0 ? (curX - gx) / (float) gw : 0F;
            float t = Math.max(0F, Math.min(1F, (cosA * tx + sinA * ty - minP) / range));
            int argb = bbsvfx$lerpArgb(startArgb, endArgb, t);

            renderer.draw(ch, curX, y, argb, false, matrix, consumers, TextRenderer.TextLayerType.NORMAL, 0, light);

            curX += renderer.getWidth(ch) + (int) tracking;
        }
    }

    /** Linear interpolation between two ARGB colours. */
    @org.spongepowered.asm.mixin.Unique
    private static int bbsvfx$lerpArgb(int a, int b, float t)
    {
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int oa = aa + (int) ((ba - aa) * t);
        int or = ar + (int) ((br - ar) * t);
        int og = ag + (int) ((bg - ag) * t);
        int ob = ab + (int) ((bb - ab) * t);

        return (oa << 24) | (or << 16) | (og << 8) | ob;
    }

    /** Total width of a string with per-character tracking applied (no trailing tracking). */
    @org.spongepowered.asm.mixin.Unique
    private int bbsvfx$width(TextRenderer renderer, String text, float tracking)
    {
        if (text.isEmpty())
        {
            return 0;
        }

        int w = 0;

        for (int i = 0; i < text.length(); i++)
        {
            w += renderer.getWidth(String.valueOf(text.charAt(i)));

            if (i < text.length() - 1)
            {
                w += (int) tracking;
            }
        }

        return w;
    }

    /** Background quad behind the text (mirrors stock {@code renderShadow}). */
    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$background(FormRenderingContext context, LabelForm form, int x, int y, int w, int h)
    {
        float offset = form.offset.get();
        Color color = form.background.get().copy();

        color.mul(context.color);

        if (color.a <= 0)
        {
            return;
        }

        MatrixStack stack = context.stack;

        stack.push();
        stack.translate(0F, 0F, -0.2F);

        Matrix4f m = stack.peek().getPositionMatrix();
        float x1 = x - offset;
        float y1 = y - offset;
        float x2 = x + w + offset;
        float y2 = y + h + offset;
        float r = color.r;
        float g = color.g;
        float b = color.b;
        float a = color.a;

        BufferBuilder builder = com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x1, y1, 0F).color(r, g, b, a));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x1, y2, 0F).color(r, g, b, a));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x2, y2, 0F).color(r, g, b, a));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x1, y1, 0F).color(r, g, b, a));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x2, y2, 0F).color(r, g, b, a));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(m, x2, y1, 0F).color(r, g, b, a));

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferRenderer.drawWithGlobalProgram(builder.end());

        stack.pop();
    }
}
