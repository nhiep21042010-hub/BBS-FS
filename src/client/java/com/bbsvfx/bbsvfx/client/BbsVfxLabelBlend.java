package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.graphics.Framebuffer;
import mchorse.bbs_mod.graphics.texture.Texture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Shader-based label blend modes (overlay/soft-light/color-dodge/difference/exclusion) that need to read
 * the destination. Owns the {@code text_blend} program (same vertex format as vanilla {@code rendertype_text}
 * + a {@code Sampler1} for a copy of the scene) and a scratch framebuffer the scene colour is blitted into
 * each time a label draws with such a mode.
 *
 * <p>Built the {@code BbsVfxImpactShader} way: lazy build on the render thread (BBS never calls
 * {@code BBSShaders.setup}) via a ProxyResourceFactory that maps {@code /core/} paths into the {@code bbs}
 * namespace. The scene is sampled at {@code gl_FragCoord.xy / ScreenSize}; the program reads it from
 * texture unit 1 (free — text uses units 0 and 2), bound through the standard {@code setShaderTexture} path.
 */
public final class BbsVfxLabelBlend
{
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("bbsvfx");

    private static ShaderProgram program;
    private static boolean buildTried;

    private static ShaderProgram quadProgram;
    private static boolean quadBuildTried;

    private static Framebuffer scene;

    /** Dimensions of the last captured scene copy (uniform {@code ScreenSize}). */
    public static int sceneW;
    public static int sceneH;

    private BbsVfxLabelBlend()
    {}

    /** Lazily builds and returns the program (null if the build failed). */
    public static ShaderProgram program()
    {
        if (program == null && !buildTried)
        {
            buildTried = true;
            build();
        }

        return program;
    }

    private static void build()
    {
        try
        {
            program = new ShaderProgram(factory(), "text_blend", VertexFormats.POSITION_COLOR_TEXTURE_LIGHT);
        }
        catch (Exception e)
        {
            LOG.error("[label-blend] shader program build failed", e);
        }
    }

    /** Lazily builds the baked-quad blend program (custom fonts under a shader blend mode). */
    public static ShaderProgram quadProgram()
    {
        if (quadProgram == null && !quadBuildTried)
        {
            quadBuildTried = true;

            try
            {
                quadProgram = new ShaderProgram(factory(), "quad_blend", VertexFormats.POSITION_TEXTURE_COLOR);
            }
            catch (Exception e)
            {
                LOG.error("[label-blend] quad blend program build failed", e);
            }
        }

        return quadProgram;
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

    /** Blits the active scene framebuffer's colour into the scratch texture; returns its GL id (-1 on fail). */
    public static int captureScene()
    {
        net.minecraft.client.gl.Framebuffer main = MinecraftClient.getInstance().getFramebuffer();

        if (main == null)
        {
            return -1;
        }

        int w = main.textureWidth;
        int h = main.textureHeight;

        if (w <= 0 || h <= 0)
        {
            return -1;
        }

        Framebuffer fb = ensureScene();
        Texture tex = fb.getMainTexture();

        if (tex.width != w || tex.height != h)
        {
            fb.resize(w, h);
        }

        int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fb.id);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);

        sceneW = w;
        sceneH = h;

        return fb.getMainTexture().id;
    }

    private static Framebuffer ensureScene()
    {
        if (scene == null)
        {
            scene = new Framebuffer();

            Texture texture = new Texture();

            texture.setSize(2, 2);
            texture.setFilter(GL11.GL_NEAREST);
            texture.setWrap(GL13.GL_CLAMP_TO_EDGE);

            scene.attach(texture, GL30.GL_COLOR_ATTACHMENT0);
            scene.unbind();
        }

        return scene;
    }
}
