package com.bbsvfx.vfxlights.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import com.bbsvfx.vfxlights.light.Light;

import java.util.List;

/**
 * Procedural lens flares — the camera's answer to a lamp, in the vocabulary of Optical Flares: glow,
 * streaks or an anamorphic slash, the ghost train down the optical axis, a chromatic halo, orbs.
 *
 * <p>Drawn from the end of the volumetric pass' post stage, over the FINISHED frame: a flare is an
 * artifact born inside the lens, so nothing in the scene may draw over it — and riding that hook
 * means it lands in the film preview and export for free. Occlusion is real (the depth snapshot: the
 * flare dies when the source hides behind geometry), the rest of the behaviour lives in the shader.</p>
 */
public final class FlarePass
{
    private static final int MAX_FLARES = 8;

    private static ShaderProgram shader;

    private FlarePass()
    {
    }

    public static void setShader(ShaderProgram program)
    {
        shader = program;
    }

    public static boolean eligible(Light light)
    {
        /* No intensity gate: the flare dial is independent of the lamp's brightness — a gaffer
         * keeps the lens answer of a dimmed practical (the "lamp off, flare stays" request). */
        return light.flare > 0.005F && light.type != Light.Type.AMBIENT;
    }

    /** Draw every eligible light's flare. Caller has additive blending and no depth test set up. */
    public static void draw(List<Light> lights, Matrix4f viewProj, Vec3d cameraPos,
        int depthTexture, int width, int height)
    {
        if (shader == null)
        {
            return;
        }

        int budget = MAX_FLARES;
        float aspect = height > 0 ? (float) width / (float) height : 1.777F;
        boolean bound = false;

        for (Light light : lights)
        {
            if (!eligible(light) || budget <= 0)
            {
                continue;
            }

            /* Project the lamp into the snapshot's screen space. The matrix maps camera-relative
             * world, same convention as the volumetric shader. */
            Vector4f clip = new Vector4f(
                (float) (light.x - cameraPos.x),
                (float) (light.y - cameraPos.y),
                (float) (light.z - cameraPos.z), 1F);

            viewProj.transform(clip);

            if (clip.w <= 0.05F)
            {
                continue;
            }

            float u = clip.x / clip.w * 0.5F + 0.5F;
            float v = clip.y / clip.w * 0.5F + 0.5F;
            float depth01 = clip.z / clip.w * 0.5F + 0.5F;

            /* A margin past the frame: the flare still throws elements into shot from off-screen. */
            if (u < -0.35F || u > 1.35F || v < -0.35F || v > 1.35F)
            {
                continue;
            }

            if (!bound)
            {
                shader.addSampler("DepthSampler", depthTexture);
                shader.bind();

                int location = GlUniform.getUniformLocation(shader.getGlRef(), "DepthSampler");

                if (location >= 0)
                {
                    GlUniform.uniform1(location, 0);
                }

                /* Same sampler-object hygiene as the beams: Iris leaves compare-mode samplers on the
                 * units, and a depth read through one returns garbage. */
                org.lwjgl.opengl.GL33.glBindSampler(0, 0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
                com.mojang.blaze3d.platform.GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                com.mojang.blaze3d.platform.GlStateManager._bindTexture(depthTexture);
                bound = true;
            }

            budget--;

            setVec4("FlareScreen", u, v, depth01, aspect);
            /* The flare dial alone: no intensity multiplier — the flare is the LENS's answer to
             * the source, and it stays when the lamp is dimmed down. (Was flare × min(intensity/4,
             * 2) — dimming the lamp killed the flare with it.) */
            setVec4("FlareParams",
                light.flare,
                light.flareStyle,
                /* RAW clock, not the caustic-wrapped phase: the twinkle multipliers (9.7, 13.3 …)
                 * share no whole cycle with the caustic period, so the aligned wrap would snap
                 * the twinkle every ~17 min where the legacy 83-minute one is what shipped. */
                Light.effectClock(),
                0F);
            setVec3("FlareColor", light.r, light.g, light.b);

            RenderSystem.setShader(() -> shader);
            drawQuad();
        }
    }

    private static void drawQuad()
    {
        /* Force additive blend raw — the cache lies after BBS's raw-GL state, and a REPLACE blend left in
         * third-person makes a fullscreen quad overwrite the world with black (see LightCompositor). */
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        org.lwjgl.opengl.GL14.glBlendEquation(org.lwjgl.opengl.GL14.GL_FUNC_ADD);
        org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_ONE, org.lwjgl.opengl.GL11.GL_ONE);

        BufferBuilder buffer = Tessellator.getInstance().getBuffer();

        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
        buffer.vertex(-1F, -1F, 0F).next();
        buffer.vertex(1F, -1F, 0F).next();
        buffer.vertex(1F, 1F, 0F).next();
        buffer.vertex(-1F, 1F, 0F).next();

        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static void setVec3(String name, float x, float y, float z)
    {
        GlUniform uniform = shader.getUniform(name);

        if (uniform != null)
        {
            uniform.set(x, y, z);
        }
    }

    private static void setVec4(String name, float x, float y, float z, float w)
    {
        GlUniform uniform = shader.getUniform(name);

        if (uniform != null)
        {
            uniform.set(x, y, z, w);
        }
    }
}
