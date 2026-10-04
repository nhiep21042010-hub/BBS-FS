package com.bbsvfx.vfxlights.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
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
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL33;
import com.bbsvfx.vfxlights.light.Light;

import java.util.List;

/**
 * Dust motes: little orbs adrift inside the beam, the projector-room look.
 *
 * <p>Deliberately NOT raymarched. A march step and a mote lattice resonate into shells and rings no
 * matter the hash (the step spacing lands commensurate with the cell size), and a mote smaller than
 * the step aliases straight through the 24-sample march. Billboards have neither problem: crisp
 * spheres of any size, occluded by the same depth snapshot the flares use, additive like the beam,
 * and riding the same post hook means they land in the film preview and export for free.</p>
 */
public final class DustPass
{
    private static final int ORBS_PER_LIGHT = 28;
    private static final int MAX_DUST_LIGHTS = 6;

    private static ShaderProgram shader;

    private DustPass()
    {
    }

    public static void setShader(ShaderProgram program)
    {
        shader = program;
    }

    public static boolean eligible(Light light)
    {
        return light.dust > 0.005F
            && (light.type == Light.Type.POINT || light.type == Light.Type.SPOT || light.type == Light.Type.AREA);
    }

    /** Draw every eligible light's motes. Called from the volumetric post stage, before the flares. */
    public static void draw(List<Light> lights, Matrix4f viewProj, Vec3d cameraPos,
        int depthTexture, int width, int height, float timeSec)
    {
        if (shader == null)
        {
            return;
        }

        /* Same first-person-hand trick as the beams: the fragment stamps the SCENE depth, a LEQUAL
         * test against the live main buffer rejects motes where the hand hides the scene behind it.
         * Depth-mask stays off; packs manage their own hand. */
        boolean maskHand = !com.bbsvfx.vfxlights.client.iris.IrisCompat.packInUse();

        if (maskHand)
        {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(false);
        }

        float aspect = height > 0 ? (float) width / (float) height : 1.777F;
        float projY = Math.abs(viewProj.m11());
        int budget = MAX_DUST_LIGHTS;
        boolean bound = false;

        for (Light light : lights)
        {
            if (!eligible(light) || budget <= 0)
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

                /* Same sampler-object hygiene as the beams/flares: Iris leaves compare-mode samplers
                 * on the units, and a depth read through one returns garbage. */
                GL33.glBindSampler(0, 0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
                GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                GlStateManager._bindTexture(depthTexture);
                RenderSystem.setShader(() -> shader);
                bound = true;
            }

            budget--;

            drawLight(light, viewProj, cameraPos, timeSec, aspect, projY);
        }

        if (maskHand)
        {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
    }

    private static void drawLight(Light light, Matrix4f viewProj, Vec3d cameraPos,
        float timeSec, float aspect, float projY)
    {
        /* Beam frame. Point lights have no axis — their motes fill the halo ball instead of a cone. */
        Vector3f fwd = new Vector3f(light.dirX, light.dirY, light.dirZ);

        if (fwd.lengthSquared() < 1e-6F)
        {
            fwd.set(0F, -1F, 0F);
        }
        else
        {
            fwd.normalize();
        }

        Vector3f up = new Vector3f(light.upX, light.upY, light.upZ);

        if (up.lengthSquared() < 1e-6F)
        {
            up = Math.abs(fwd.y) > 0.9F ? new Vector3f(1F, 0F, 0F) : new Vector3f(0F, 1F, 0F);
        }

        Vector3f right = new Vector3f(fwd).cross(up).normalize();

        up = new Vector3f(right).cross(fwd).normalize();

        /* Keep the swarm inside the readable throw: further out the motes go subpixel anyway. */
        float length = Math.min(light.range, 32F);

        for (int i = 0; i < ORBS_PER_LIGHT; i++)
        {
            float h1 = hash(i, 1);
            float h2 = hash(i, 2);
            float h3 = hash(i, 3);
            float h4 = hash(i, 4);
            float h5 = hash(i, 5);

            /* Sprinkled along the WHOLE beam from the start — each mote holds its own station and
             * only sways around it, instead of streaming out of the lamp tip on a loop. */
            float sway = (float) Math.sin(timeSec * (0.3F + 0.4F * h3) + h1 * 40F);
            Vector3f pos;

            if (light.type == Light.Type.POINT)
            {
                float z = h2 * 2F - 1F;
                float a = h3 * 6.2832F;
                float rr = (float) Math.sqrt(Math.max(0F, 1F - z * z));
                float dist = (0.15F + 0.85F * h1 + sway * 0.05F) * Math.min(light.range, 12F);

                pos = new Vector3f(rr * (float) Math.cos(a), z, rr * (float) Math.sin(a)).mul(dist);
            }
            else
            {
                float dist = (0.05F + 0.95F * h1 + sway * 0.04F) * length;
                float coneR;

                if (light.type == Light.Type.SPOT)
                {
                    float sinOuter = (float) Math.sqrt(Math.max(0F, 1F - light.cosOuter * light.cosOuter));

                    coneR = sinOuter / Math.max(light.cosOuter, 0.05F) * dist * 0.85F;
                }
                else
                {
                    /* Area panel: the swarm hovers in a column in front of it. */
                    coneR = Math.max(light.width, 0.3F) * 0.6F;
                }

                float ang = h2 * 6.2832F;
                float lat = (float) Math.sqrt(h4) * coneR;

                pos = new Vector3f(fwd).mul(dist)
                    .add(new Vector3f(right).mul((float) Math.cos(ang) * lat))
                    .add(new Vector3f(up).mul((float) Math.sin(ang) * lat));
            }

            /* Per-mote wobble so the drift doesn't move on rails. */
            float wob = timeSec * (0.5F + h5) + h2 * 40F;

            pos.add(new Vector3f(right).mul((float) Math.sin(wob) * 0.05F))
                .add(new Vector3f(up).mul((float) Math.cos(wob * 0.83F) * 0.05F));

            Vector4f clip = new Vector4f(
                (float) (light.x + pos.x - cameraPos.x),
                (float) (light.y + pos.y - cameraPos.y),
                (float) (light.z + pos.z - cameraPos.z), 1F);

            viewProj.transform(clip);

            if (clip.w <= 0.05F)
            {
                continue;
            }

            float ndcX = clip.x / clip.w;
            float ndcY = clip.y / clip.w;
            float u = ndcX * 0.5F + 0.5F;
            float v = ndcY * 0.5F + 0.5F;
            float depth01 = clip.z / clip.w * 0.5F + 0.5F;

            if (u < -0.1F || u > 1.1F || v < -0.1F || v > 1.1F)
            {
                continue;
            }

            /* A mote is SMALL — 2.3-5.7 cm at size 1 — and drops out once it shrinks under a pixel. */
            float worldR = (0.035F + h5 * 0.05F) / 1.5F * light.dustSize;
            float sizeY = worldR * projY / clip.w;
            float sizeX = sizeY / aspect;

            if (sizeY < 0.0004F)
            {
                continue;
            }

            float s = (float) Math.sin(timeSec * 2.0 + h1 * 91.0);
            float tw = 0.35F + 0.65F * s * s;
            float alpha = light.dust * tw * 0.85F;

            setVec4("OrbScreen", u, v, depth01, alpha);
            setVec3("OrbColor", light.r, light.g, light.b);
            drawQuad(ndcX, ndcY, sizeX, sizeY);
        }
    }

    private static void drawQuad(float cx, float cy, float sx, float sy)
    {
        /* Force additive blend raw — the cache lies after BBS's raw-GL state (see LightCompositor). */
        GL11.glEnable(GL11.GL_BLEND);
        org.lwjgl.opengl.GL14.glBlendEquation(org.lwjgl.opengl.GL14.GL_FUNC_ADD);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);

        BufferBuilder buffer = Tessellator.getInstance().getBuffer();

        /* POSITION_TEXTURE: the fragment's radial falloff needs quad-local UVs — NDC position only
         * doubles as UV on fullscreen quads (small billboards drew as plain squares, exactly that). */
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        buffer.vertex(cx - sx, cy - sy, 0F).texture(0F, 0F).next();
        buffer.vertex(cx + sx, cy - sy, 0F).texture(1F, 0F).next();
        buffer.vertex(cx + sx, cy + sy, 0F).texture(1F, 1F).next();
        buffer.vertex(cx - sx, cy + sy, 0F).texture(0F, 1F).next();

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

    private static float frac(float v)
    {
        return v - (float) Math.floor(v);
    }

    private static float hash(int i, int salt)
    {
        int h = (i * 73856093) ^ (salt * 19349663);

        h ^= h >>> 13;
        h *= 0x85ebca6b;
        h ^= h >>> 16;

        return (h & 0xFFFFFF) / (float) 0xFFFFFF;
    }
}
