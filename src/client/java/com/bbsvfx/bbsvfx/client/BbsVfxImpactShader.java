package com.bbsvfx.bbsvfx.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.graphics.Framebuffer;
import mchorse.bbs_mod.graphics.texture.Texture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import com.bbsvfx.bbsvfx.camera.BbsVfxImpactState;

import java.io.IOException;

/**
 * The "impact frame" full-screen post pass. After BBS renders the film scene into its framebuffer, this
 * copies that frame into a scratch texture and draws it back through our {@code impact} program with the
 * effect uniforms from {@link BbsVfxImpactState} (set by {@code ImpactClip} that frame).
 *
 * <p>Own uniquely-named program ({@code impact}) loaded the {@code SmearDissolveShader} way (rewrite
 * {@code /core/} paths to the {@code bbs} namespace) so there's no resource collision. Read+write of the
 * same colour attachment isn't allowed, hence the blit into a scratch framebuffer first.</p>
 */
public final class BbsVfxImpactShader
{
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("bbsvfx");

    private static ShaderProgram program;
    private static Framebuffer scratch;

    private BbsVfxImpactShader()
    {}

    /** (Re)build the program on the render thread (valid GL context, after resources load). */
    public static void build()
    {
        if (program != null)
        {
            program.close();
            program = null;
        }

        try
        {
            program = new ShaderProgram(factory(), "impact", VertexFormats.POSITION_TEXTURE);
        }
        catch (Exception e)
        {
            LOG.error("[impact] shader program build failed", e);
        }
    }

    /** Mirrors BBS's ProxyResourceFactory: core shader files live under the {@code bbs} namespace. */
    private static ResourceFactory factory()
    {
        return (id) ->
        {
            ResourceFactory manager = MinecraftClient.getInstance().getResourceManager();

            if (id.getPath().contains("/core/"))
            {
                return manager.getResource(Identifier.of("bbs", id.getPath()));
            }

            return manager.getResource(id);
        };
    }

    private static boolean buildTried;

    /** Apply the impact effect to BBS's scene framebuffer in place. */
    public static void render(net.minecraft.client.gl.Framebuffer main)
    {
        /* Lazy build: BBS only calls BBSShaders.setup() from a manual debug panel, so build the program
         * on the render thread on first use instead (valid GL context, resources loaded). */
        if (program == null && !buildTried)
        {
            buildTried = true;
            build();
        }

        if (program == null || main == null || !BbsVfxImpactState.hasEffect())
        {
            return;
        }

        int w = main.textureWidth;
        int h = main.textureHeight;

        if (w <= 0 || h <= 0)
        {
            return;
        }

        Framebuffer fb = ensureScratch();
        Texture tex = fb.getMainTexture();

        if (tex.width != w || tex.height != h)
        {
            fb.resize(w, h);
        }

        /* Copy the rendered frame into the scratch texture (can't sample + write the same attachment). */
        int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fb.id);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);

        /* Draw the processed frame back into the main framebuffer. */
        main.beginWrite(true);

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShaderTexture(0, fb.getMainTexture().id);

        /* Silhouette coverage on unit 1 (built by BbsVfxImpactSilhouette just before this pass). Fall back to
         * the scene copy when not built — harmless since Silhouette is then 0. */
        int silTex = BbsVfxImpactSilhouette.ready() ? BbsVfxImpactSilhouette.textureId() : fb.getMainTexture().id;
        RenderSystem.setShaderTexture(1, silTex);
        RenderSystem.setShader(() -> program);

        program.getUniformOrDefault("Silhouette").set(BbsVfxImpactState.silhouette);
        program.getUniformOrDefault("SilColor").set(BbsVfxImpactState.silR, BbsVfxImpactState.silG, BbsVfxImpactState.silB);
        program.getUniformOrDefault("BgColor").set(BbsVfxImpactState.bgR, BbsVfxImpactState.bgG, BbsVfxImpactState.bgB);

        program.getUniformOrDefault("SilStrokes").set(BbsVfxImpactState.silStrokes);
        program.getUniformOrDefault("SilStrokeAngle").set(BbsVfxImpactState.silStrokeAngle);
        program.getUniformOrDefault("SilStrokeLength").set(BbsVfxImpactState.silStrokeLength);
        program.getUniformOrDefault("SilStrokeScale").set(BbsVfxImpactState.silStrokeScale);
        program.getUniformOrDefault("SilStrokeRough").set(BbsVfxImpactState.silStrokeRough);

        program.getUniformOrDefault("InkBurst").set(BbsVfxImpactState.inkBurst);
        program.getUniformOrDefault("InkColor").set(BbsVfxImpactState.inkR, BbsVfxImpactState.inkG, BbsVfxImpactState.inkB);
        program.getUniformOrDefault("InkRadius").set(BbsVfxImpactState.inkRadius);
        program.getUniformOrDefault("InkInner").set(BbsVfxImpactState.inkInner);
        program.getUniformOrDefault("InkSpikes").set(BbsVfxImpactState.inkSpikes);
        program.getUniformOrDefault("InkRough").set(BbsVfxImpactState.inkRough);
        program.getUniformOrDefault("InkSeed").set(BbsVfxImpactState.inkSeed);

        program.getUniformOrDefault("Shockwave").set(BbsVfxImpactState.shockwave);
        program.getUniformOrDefault("ShockwaveProgress").set(BbsVfxImpactState.shockwaveProgress);
        program.getUniformOrDefault("ShockwaveRadius").set(BbsVfxImpactState.shockwaveRadius);
        program.getUniformOrDefault("ShockwaveWidth").set(BbsVfxImpactState.shockwaveWidth);
        program.getUniformOrDefault("ShockwaveColor").set(BbsVfxImpactState.swR, BbsVfxImpactState.swG, BbsVfxImpactState.swB);

        program.getUniformOrDefault("FlashStar").set(BbsVfxImpactState.flashStar);
        program.getUniformOrDefault("FlashStarSize").set(BbsVfxImpactState.flashStarSize);
        program.getUniformOrDefault("FlashStarWidth").set(BbsVfxImpactState.flashStarWidth);
        program.getUniformOrDefault("FlashStarGlow").set(BbsVfxImpactState.flashStarGlow);
        program.getUniformOrDefault("FlashStarRotation").set(BbsVfxImpactState.flashStarRotation);
        program.getUniformOrDefault("FlashStarColor").set(BbsVfxImpactState.fsR, BbsVfxImpactState.fsG, BbsVfxImpactState.fsB);

        program.getUniformOrDefault("Invert").set(BbsVfxImpactState.invert);
        program.getUniformOrDefault("Flash").set(BbsVfxImpactState.flash);
        program.getUniformOrDefault("Grayscale").set(BbsVfxImpactState.grayscale);
        program.getUniformOrDefault("Threshold").set(BbsVfxImpactState.threshold);
        program.getUniformOrDefault("ThresholdLevel").set(BbsVfxImpactState.thresholdLevel);
        program.getUniformOrDefault("ThresholdSoft").set(BbsVfxImpactState.thresholdSoft);
        program.getUniformOrDefault("Chroma").set(BbsVfxImpactState.chroma);
        program.getUniformOrDefault("FlashColor").set(BbsVfxImpactState.flashR, BbsVfxImpactState.flashG, BbsVfxImpactState.flashB);
        program.getUniformOrDefault("DarkColor").set(BbsVfxImpactState.darkR, BbsVfxImpactState.darkG, BbsVfxImpactState.darkB);
        program.getUniformOrDefault("LightColor").set(BbsVfxImpactState.lightR, BbsVfxImpactState.lightG, BbsVfxImpactState.lightB);
        program.getUniformOrDefault("Focus").set(BbsVfxImpactState.focusX, BbsVfxImpactState.focusY);

        program.getUniformOrDefault("ZoomBlur").set(BbsVfxImpactState.zoomBlur);
        program.getUniformOrDefault("BlurMode").set(BbsVfxImpactState.blurMode);
        program.getUniformOrDefault("ZoomLines").set(BbsVfxImpactState.zoomLines);
        program.getUniformOrDefault("LinesCount").set(BbsVfxImpactState.linesCount);
        program.getUniformOrDefault("LinesThickness").set(BbsVfxImpactState.linesThickness);
        program.getUniformOrDefault("LinesInner").set(BbsVfxImpactState.linesInner);
        program.getUniformOrDefault("LinesMode").set(BbsVfxImpactState.linesMode);
        program.getUniformOrDefault("LinesSeed").set(BbsVfxImpactState.linesSeed);
        program.getUniformOrDefault("LinesColor").set(BbsVfxImpactState.linesR, BbsVfxImpactState.linesG, BbsVfxImpactState.linesB);
        program.getUniformOrDefault("Shapes").set(BbsVfxImpactState.shapes);
        program.getUniformOrDefault("ShapesCount").set(BbsVfxImpactState.shapesCount);
        program.getUniformOrDefault("ShapesSize").set(BbsVfxImpactState.shapesSize);
        program.getUniformOrDefault("ShapesSpread").set(BbsVfxImpactState.shapesSpread);
        program.getUniformOrDefault("CenterStar").set(BbsVfxImpactState.centerStar);
        program.getUniformOrDefault("CenterCircle").set(BbsVfxImpactState.centerCircle);
        program.getUniformOrDefault("ShapesColor").set(BbsVfxImpactState.shapesR, BbsVfxImpactState.shapesG, BbsVfxImpactState.shapesB);
        program.getUniformOrDefault("Aspect").set((float) w / (float) h);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(buffer.vertex(-1F, -1F, 0F).texture(0F, 0F));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(buffer.vertex(1F, -1F, 0F).texture(1F, 0F));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(buffer.vertex(1F, 1F, 0F).texture(1F, 1F));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(buffer.vertex(-1F, 1F, 0F).texture(0F, 1F));
        BufferRenderer.drawWithGlobalProgram(buffer.end());

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();

        /* Restore the active texture unit to 0. Sampling through Sampler1 leaves the active unit at 1;
         * MC/BBS (and GlStateManager's cache) assume unit 0 afterwards, and the desync makes the next
         * frame's FIRST-rendered actor's skin bind/filter land on the wrong unit — driver-dependent, it
         * showed up as a Linear-filtered skin on one tester (clear weather only: the weather pass resets
         * the unit; first actor only: it renders first and eats the leaked state). */
        com.mojang.blaze3d.platform.GlStateManager._activeTexture(GL13.GL_TEXTURE0);

        /* Consume-and-clear so the next frame starts clean (the clip re-sets it if still active). */
        BbsVfxImpactState.reset();
    }

    private static Framebuffer ensureScratch()
    {
        if (scratch == null)
        {
            scratch = new Framebuffer();

            Texture texture = new Texture();

            texture.setSize(2, 2);
            texture.setFilter(GL11.GL_NEAREST);
            texture.setWrap(GL13.GL_CLAMP_TO_EDGE);

            scratch.attach(texture, GL30.GL_COLOR_ATTACHMENT0);
            scratch.unbind();
        }

        return scratch;
    }
}
