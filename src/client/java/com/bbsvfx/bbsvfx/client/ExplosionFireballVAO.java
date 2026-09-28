package com.bbsvfx.bbsvfx.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import com.bbsvfx.bbsvfx.forms.ExplosionForm;

/**
 * The MESH fireball — the anime-explosion construction every Blender generator (Kanazawa, Dedene,
 * Mix3D) uses and billboards can't fake: a cluster of REAL noise-displaced spheres with a HARD-BANDED
 * toon ramp. Bumps that push OUT read bright (white-yellow), crevices read dark (red-brown rim), the
 * bands stay crisp because the colour comes from a stepped 1D ramp texture sampled by per-vertex
 * "temperature" — not from vertex interpolation. Death = EROSION: vertex alpha collapses under the
 * cutout threshold on a per-vertex noise order, so the ball burns out in ragged chunks (works under
 * shaderpack alpha-testing by construction).
 *
 * <p>Geometry is one static unit UV-sphere instanced CPU-side into ~14 lobes (≈4k verts total — the
 * per-frame displacement loop is trivial); drawn with the VANILLA entity cutout shader, fullbright
 * lightmap (packs bloom the bright bands). Everything is a closed form of the scrub time.</p>
 */
public final class ExplosionFireballVAO
{
    private static final Identifier RAMP_TEX = Identifier.of("bbsvfx", "fx/fireramp");

    private static final int LOBES = 14;
    private static final int RINGS = 16;
    private static final int SEGS = 22;

    private static ExplosionFireballVAO instance;

    /* Unit sphere: per-vertex unit normal (position on the unit sphere), non-indexed triangles. */
    private float[] sphere;
    private int sphereVerts;

    /* Per-lobe layout (unit ball space): direction, radius fraction, noise phase. */
    private final float[] lobeDir = new float[LOBES * 3];
    private final float[] lobeR = new float[LOBES];
    private final float[] lobePhase = new float[LOBES];

    private int vao = -1;
    private int posBuffer, colorBuffer, uvBuffer, normalBuffer, midUvBuffer, tangentBuffer;
    private float[] pos;
    private float[] color;
    private float[] uv;
    private float[] normal;
    private int totalVerts;
    private boolean texturesReady;

    public static ExplosionFireballVAO get()
    {
        if (instance == null)
        {
            instance = new ExplosionFireballVAO();
            instance.build();
        }

        return instance;
    }

    private void build()
    {
        /* Unit UV-sphere, expanded to plain triangles (poles included). */
        java.util.List<float[]> tris = new java.util.ArrayList<>();

        for (int r = 0; r < RINGS; r++)
        {
            float t0 = (float) Math.PI * r / RINGS;
            float t1 = (float) Math.PI * (r + 1) / RINGS;

            for (int s = 0; s < SEGS; s++)
            {
                float p0 = 6.2831853F * s / SEGS;
                float p1 = 6.2831853F * (s + 1) / SEGS;

                float[] a = sph(t0, p0), b = sph(t0, p1), c = sph(t1, p1), d = sph(t1, p0);

                if (r > 0)
                {
                    tris.add(a);
                    tris.add(b);
                    tris.add(c);
                }

                if (r < RINGS - 1)
                {
                    tris.add(a);
                    tris.add(c);
                    tris.add(d);
                }
            }
        }

        this.sphereVerts = tris.size();
        this.sphere = new float[this.sphereVerts * 3];

        for (int i = 0; i < this.sphereVerts; i++)
        {
            float[] v = tris.get(i);

            this.sphere[i * 3] = v[0];
            this.sphere[i * 3 + 1] = v[1];
            this.sphere[i * 3 + 2] = v[2];
        }

        /* Lobe layout: one core + a ring of big lobes + a few crown lobes, mostly upper hemisphere. */
        for (int k = 0; k < LOBES; k++)
        {
            float ha = hash(k, 1), hb = hash(k, 2), hc = hash(k, 3);

            if (k == 0)
            {
                this.lobeDir[0] = 0F;
                this.lobeDir[1] = 0.25F;
                this.lobeDir[2] = 0F;
                this.lobeR[0] = 0.62F;
            }
            else
            {
                float az = k * 2.399963F + ha * 0.7F;
                float el = 0.05F + 1.15F * hb;

                this.lobeDir[k * 3] = (float) (Math.cos(az) * Math.cos(el)) * 0.62F;
                this.lobeDir[k * 3 + 1] = (float) Math.sin(el) * 0.55F;
                this.lobeDir[k * 3 + 2] = (float) (Math.sin(az) * Math.cos(el)) * 0.62F;
                this.lobeR[k] = 0.34F + 0.2F * hc;
            }

            this.lobePhase[k] = hash(k, 4) * 37F;
        }

        this.totalVerts = this.sphereVerts * LOBES;
        this.pos = new float[this.totalVerts * 3];
        this.color = new float[this.totalVerts * 4];
        this.uv = new float[this.totalVerts * 2];
        this.normal = new float[this.totalVerts * 3];

        int previousVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        this.vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(this.vao);

        this.posBuffer = attrib(0, 3, this.pos);
        this.colorBuffer = attrib(1, 4, this.color);
        this.uvBuffer = attrib(2, 2, this.uv);
        this.normalBuffer = attrib(5, 3, this.normal);
        this.midUvBuffer = attrib(8, 2, this.uv);

        /* Static dummy tangents (toon bands don't need normal mapping; Iris just wants the attribute). */
        float[] tangents = new float[this.totalVerts * 4];

        for (int i = 0; i < this.totalVerts; i++)
        {
            tangents[i * 4] = 1F;
            tangents[i * 4 + 3] = 1F;
        }

        this.tangentBuffer = attrib(9, 4, tangents);

        GL30.glBindVertexArray(previousVao);
        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private static float[] sph(float theta, float phi)
    {
        return new float[] {
            (float) (Math.sin(theta) * Math.cos(phi)),
            (float) Math.cos(theta),
            (float) (Math.sin(theta) * Math.sin(phi))};
    }

    private static int attrib(int location, int size, float[] data)
    {
        int buffer = GL30.glGenBuffers();

        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
        GL30.glVertexAttribPointer(location, size, GL15.GL_FLOAT, false, 0, 0);

        return buffer;
    }

    /**
     * Displace + shade + draw the ball for this frame. {@code ballR} = current ball radius,
     * {@code cx/cy/cz} = ball centre (form-local), {@code cool} 0..1 = cooling progress (drives the
     * ramp shift and the erosion), {@code roll} = accumulated roll time for the churning motion.
     */
    public void render(ExplosionForm form, MatrixStack stack, float ballR, float cx, float cy, float cz,
        float cool, float roll, int overlay)
    {
        if (this.vao == -1 || ballR <= 0.1F)
        {
            return;
        }

        this.ensureRamp();

        for (int k = 0; k < LOBES; k++)
        {
            float lcx = cx + this.lobeDir[k * 3] * ballR;
            float lcy = cy + this.lobeDir[k * 3 + 1] * ballR;
            float lcz = cz + this.lobeDir[k * 3 + 2] * ballR;
            float lr = this.lobeR[k] * ballR;
            float phase = this.lobePhase[k];
            /* Per-lobe vertical position inside the ball → lower lobes shade darker. */
            float lobeShade = 0.78F + 0.22F * Math.max(0F, this.lobeDir[k * 3 + 1] * 1.6F + 0.5F);

            for (int v = 0, base = k * this.sphereVerts; v < this.sphereVerts; v++)
            {
                int i = (base + v) * 3;
                float nx = this.sphere[v * 3], ny = this.sphere[v * 3 + 1], nz = this.sphere[v * 3 + 2];

                /* Rolling lumpy displacement: two octaves of value noise on the sphere surface,
                 * drifting with the roll time — the cauliflower churn. */
                float n = noise3(nx * 2.1F + phase, ny * 2.1F + roll * 0.55F, nz * 2.1F - phase)
                    * 0.65F + noise3(nx * 4.4F - roll * 0.3F, ny * 4.4F + phase, nz * 4.4F) * 0.35F;
                float bump = (n - 0.5F) * 2F;
                float r = lr * (1F + 0.3F * bump);

                this.pos[i] = lcx + nx * r;
                this.pos[i + 1] = lcy + ny * r;
                this.pos[i + 2] = lcz + nz * r;
                this.normal[i] = nx;
                this.normal[i + 1] = ny;
                this.normal[i + 2] = nz;

                /* Temperature → ramp U: bumps out bright, crevices dark, whole ball cools with time,
                 * upper surfaces brighter. The stepped ramp texture turns this into hard flat bands. */
                float temp = 0.62F + 0.55F * bump + 0.18F * ny - cool * 0.9F;

                int o = (base + v) * 2;

                this.uv[o] = Math.max(0.02F, Math.min(0.98F, temp)) * lobeShade;
                this.uv[o + 1] = 0.5F;

                /* Erosion: per-vertex burn-out order from noise — cutout discards eroded verts'
                 * triangles edge-first, but only in the last stretch of the cooling. */
                float erode = (cool - 0.55F) * 2.2F;
                float order = noise3(nx * 3.3F + phase * 2F, ny * 3.3F, nz * 3.3F + phase);
                float alpha = order < erode ? 0F : 1F;

                int c = (base + v) * 4;

                this.color[c] = 1F;
                this.color[c + 1] = 1F;
                this.color[c + 2] = 1F;
                this.color[c + 3] = alpha;
            }
        }

        int previousVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        GL30.glBindVertexArray(this.vao);
        upload(this.posBuffer, 0, 3, this.pos);
        upload(this.colorBuffer, 1, 4, this.color);
        upload(this.uvBuffer, 2, 2, this.uv);
        upload(this.normalBuffer, 5, 3, this.normal);

        MinecraftClient mc = MinecraftClient.getInstance();

        RenderSystem.setShaderTexture(0, RAMP_TEX);
        mc.gameRenderer.getLightmapTextureManager().enable();
        mc.gameRenderer.getOverlayTexture().setupOverlayColor();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();

        ShaderProgram shader = GameRenderer.getRenderTypeEntityCutoutNoNullProgram();

        ModelVAORenderer.setupUniforms(stack, shader);
        shader.bind();

        boolean iris = BBSRendering.isIrisShadersEnabled();

        GL30.glVertexAttribI2i(3, overlay & 0xFFFF, (overlay >> 16) & 0xFFFF);
        GL30.glVertexAttribI2i(4, 0xF0, 0xF0);
        GL30.glEnableVertexAttribArray(0);
        GL30.glEnableVertexAttribArray(1);
        GL30.glEnableVertexAttribArray(2);
        GL30.glEnableVertexAttribArray(5);

        if (iris)
        {
            GL30.glEnableVertexAttribArray(8);
            GL30.glEnableVertexAttribArray(9);
        }

        GL30.glDrawArrays(GL15.GL_TRIANGLES, 0, this.totalVerts);
        shader.unbind();

        /* VOLUMETRIC SOFT SHELLS — «размыть сам файрбол»: the same geometry puffed outward along the
         * normals THREE times with falling alpha, drawn translucent over the opaque core. The edge
         * dissolves gradually like the smoke does instead of cutting a crisp cartoon silhouette. */
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        ShaderProgram shell = GameRenderer.getRenderTypeEntityTranslucentCullProgram();

        ModelVAORenderer.setupUniforms(stack, shell);
        shell.bind();

        float prevOffset = 0F;
        float[] offsets = {0.06F, 0.14F, 0.24F};
        float[] alphas = {0.4F, 0.22F, 0.11F};

        for (int s = 0; s < offsets.length; s++)
        {
            float delta = (offsets[s] - prevOffset) * ballR;

            prevOffset = offsets[s];

            for (int i = 0; i < this.totalVerts; i++)
            {
                int p = i * 3;

                this.pos[p] += this.normal[p] * delta;
                this.pos[p + 1] += this.normal[p + 1] * delta;
                this.pos[p + 2] += this.normal[p + 2] * delta;
            }

            for (int i = 0; i < this.totalVerts; i++)
            {
                /* Preserve the erosion zeros; scale the surviving verts to this shell's alpha. */
                if (this.color[i * 4 + 3] > 0F)
                {
                    this.color[i * 4 + 3] = alphas[s];
                }
            }

            upload(this.posBuffer, 0, 3, this.pos);
            upload(this.colorBuffer, 1, 4, this.color);
            GL30.glDrawArrays(GL15.GL_TRIANGLES, 0, this.totalVerts);
        }

        shell.unbind();
        RenderSystem.disableBlend();

        GL30.glDisableVertexAttribArray(0);
        GL30.glDisableVertexAttribArray(1);
        GL30.glDisableVertexAttribArray(2);
        GL30.glDisableVertexAttribArray(5);

        if (iris)
        {
            GL30.glDisableVertexAttribArray(8);
            GL30.glDisableVertexAttribArray(9);
        }

        GL30.glBindVertexArray(previousVao);
        RenderSystem.enableCull();
        mc.gameRenderer.getLightmapTextureManager().disable();
        mc.gameRenderer.getOverlayTexture().teardownOverlayColor();
    }

    private static void upload(int buffer, int location, int size, float[] data)
    {
        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
        GL30.glVertexAttribPointer(location, size, GL15.GL_FLOAT, false, 0, 0);
    }

    /** The fire ramp: dark red rim | flat orange | thin bright line | blinding core — bands kept flat
     *  but with FEATHERED borders + linear filtering, so the surface reads soft, not pixel-stepped. */
    private void ensureRamp()
    {
        if (this.texturesReady)
        {
            return;
        }

        /* Smooth cinematic gradient — the toon bands read «слишком мультяшно» against the world;
         * a soft dark-red → orange → yellow → white ramp keeps the fire shape but blends like the
         * smoke does. */
        float[][] stops = {
            {0.00F, 96, 26, 12},
            {0.30F, 224, 92, 16},
            {0.55F, 255, 150, 30},
            {0.75F, 255, 214, 90},
            {1.00F, 255, 250, 226}};

        NativeImage ramp = new NativeImage(128, 4, false);

        for (int x = 0; x < 128; x++)
        {
            float u = x / 127F;
            int seg = 0;

            while (seg < stops.length - 2 && u > stops[seg + 1][0])
            {
                seg++;
            }

            float span = Math.max(1e-4F, stops[seg + 1][0] - stops[seg][0]);
            float t = Math.min(1F, Math.max(0F, (u - stops[seg][0]) / span));
            int r = (int) (stops[seg][1] + (stops[seg + 1][1] - stops[seg][1]) * t);
            int g = (int) (stops[seg][2] + (stops[seg + 1][2] - stops[seg][2]) * t);
            int b = (int) (stops[seg][3] + (stops[seg + 1][3] - stops[seg][3]) * t);

            for (int y = 0; y < 4; y++)
            {
                ramp.setColor(x, y, 0xFF000000 | b << 16 | g << 8 | r);
            }
        }

        NativeImageBackedTexture texture = new NativeImageBackedTexture(ramp);

        texture.setFilter(true, false);
        MinecraftClient.getInstance().getTextureManager().registerTexture(RAMP_TEX, texture);
        this.texturesReady = true;
    }

    private static float hash(int i, int salt)
    {
        int x = (i ^ 0x9e3779b9) * 0x85ebca6b + salt * 0x165667b1;

        x ^= x >>> 13;
        x *= 0x27d4eb2d;
        x ^= x >>> 15;

        return (x & 0xFFFF) / (float) 0xFFFF;
    }

    /** Cheap 3D value noise, 0..1 (trilinear over hashed lattice). */
    private static float noise3(float x, float y, float z)
    {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        float fx = x - x0, fy = y - y0, fz = z - z0;

        fx = fx * fx * (3F - 2F * fx);
        fy = fy * fy * (3F - 2F * fy);
        fz = fz * fz * (3F - 2F * fz);

        float c000 = cell3(x0, y0, z0), c100 = cell3(x0 + 1, y0, z0);
        float c010 = cell3(x0, y0 + 1, z0), c110 = cell3(x0 + 1, y0 + 1, z0);
        float c001 = cell3(x0, y0, z0 + 1), c101 = cell3(x0 + 1, y0, z0 + 1);
        float c011 = cell3(x0, y0 + 1, z0 + 1), c111 = cell3(x0 + 1, y0 + 1, z0 + 1);

        float c00 = c000 + (c100 - c000) * fx;
        float c10 = c010 + (c110 - c010) * fx;
        float c01 = c001 + (c101 - c001) * fx;
        float c11 = c011 + (c111 - c011) * fx;
        float c0 = c00 + (c10 - c00) * fy;
        float c1 = c01 + (c11 - c01) * fy;

        return c0 + (c1 - c0) * fz;
    }

    private static float cell3(int x, int y, int z)
    {
        int h = x * 374761393 + y * 668265263 + z * 1274126177;

        h = (h ^ (h >> 13)) * 0x85ebca6b;

        return ((h ^ (h >> 16)) & 0xFFFF) / (float) 0xFFFF;
    }
}
