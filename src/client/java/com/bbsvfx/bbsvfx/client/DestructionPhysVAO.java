package com.bbsvfx.bbsvfx.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import com.bbsvfx.bbsvfx.forms.DestructionBlock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fast render path for the PHYSICS mode: the units' REST geometry (in unit-local space) is captured
 * ONCE into VBOs; each frame one tight CPU loop transforms positions+normals by the baked unit poses
 * straight into the dynamic buffers and the whole structure goes out as a single {@code glDrawArrays}.
 * This removes the per-vertex consumer-chain cost that capped the CPU path (~50 fps at 27k blocks).
 *
 * <p><b>Shaderpack-safe by design:</b> the draw uses the VANILLA entity shader
 * ({@code rendertype_entity_translucent_cull}) — exactly what BBS's own {@code BOBJModelVAO} does — so
 * Iris swaps in the pack's entity program and the debris is lit like any model. Under Iris the VAO also
 * feeds the pack's extra vertex attributes (tangent @9, mid-texcoord @8, via
 * {@link BBSRendering#calculateTangents}). No custom core shader anywhere.</p>
 *
 * <p>Instances are cached globally by {@link DestructionPhysics.Bake} identity (renderer instances die
 * on every form copy) with a small LRU that deletes evicted GL objects.</p>
 */
public class DestructionPhysVAO
{
    /** Emergency master switch (fall back to the per-block CPU path). */
    public static boolean USE_VAO = true;

    private static final int MAX_CACHED = 3;

    private static final LinkedHashMap<DestructionPhysics.Bake, DestructionPhysVAO> CACHE = new LinkedHashMap<>(8, 0.75F, true);

    private int vao = -1;
    private int posBuffer;
    private int colorBuffer;
    private int uvBuffer;
    private int normalBuffer;
    private int tangentBuffer;
    private int midUvBuffer;
    private int lightBuffer;
    private int vertexCount;

    /** Per unit: first vertex; the unit's range ends at the next entry (a unitCount+1 array). */
    private int[] unitStart;

    /* Rest-space CPU copies: pos/normal/tangent transformed per frame. Rest tangents are computed ONCE
     * (the per-frame recalculation from UVs was the Iris fps killer) and only ROTATED per unit. */
    private float[] localPos;
    private float[] localNormal;
    private float[] localTangent;
    private float[] tmpPos;
    private float[] tmpNormal;
    private float[] tmpTangents;

    /** Fetch (or build) the VAO for this bake. Null when the build failed (caller uses the CPU path). */
    public static DestructionPhysVAO of(DestructionPhysics.Bake bake, List<DestructionBlock> blocks)
    {
        DestructionPhysVAO cached = CACHE.get(bake);

        if (cached != null)
        {
            return cached;
        }

        DestructionPhysVAO built = new DestructionPhysVAO();

        try
        {
            built.build(bake, blocks);
        }
        catch (Exception e)
        {
            System.err.println("[bbsvfx] Destruction physics VAO build failed: " + e);
            built.delete();

            return null;
        }

        CACHE.put(bake, built);

        while (CACHE.size() > MAX_CACHED)
        {
            Map.Entry<DestructionPhysics.Bake, DestructionPhysVAO> eldest = CACHE.entrySet().iterator().next();

            eldest.getValue().delete();
            CACHE.remove(eldest.getKey());
        }

        return built;
    }

    /** Capture every unit's rest geometry (unit-local space, quads → triangles) and set up the VAO. */
    private void build(DestructionPhysics.Bake bake, List<DestructionBlock> blocks)
    {
        Recorder recorder = new Recorder();
        Provider provider = new Provider(recorder);
        MatrixStack identity = new MatrixStack();

        this.unitStart = new int[bake.units + 1];

        for (int u = 0; u < bake.units; u++)
        {
            this.unitStart[u] = recorder.triCount();

            int bi = bake.unitBlock[u];
            int oct = bake.unitOctant[u];
            DestructionBlock block = blocks.get(bi);

            recorder.beginUnit();

            if (oct >= 0 && DestructionSectorDraw.canDraw(block.blockState()))
            {
                DestructionSectorDraw.draw(block.blockState(), block.tint.get(), oct, identity.peek(), provider, 0, 0);
                recorder.endUnit(
                    (oct & 1) == 0 ? 0.25F : 0.75F,
                    (oct & 2) == 0 ? 0.25F : 0.75F,
                    (oct & 4) == 0 ? 0.25F : 0.75F, 1F);
            }
            else if (oct >= 0)
            {
                /* Non-sliceable model: half-scale whole model, centred like the render path. */
                DestructionBlockDraw.draw(block.blockState(), block.tint.get(), block.blockState().getRenderingSeed(new net.minecraft.util.math.BlockPos(block.x.get(), block.y.get(), block.z.get())), identity.peek(), provider, 0, 0);
                recorder.endUnit(0.5F, 0.5F, 0.5F, 0.5F);
            }
            else
            {
                DestructionBlockDraw.draw(block.blockState(), block.tint.get(), block.blockState().getRenderingSeed(new net.minecraft.util.math.BlockPos(block.x.get(), block.y.get(), block.z.get())), identity.peek(), provider, 0, 0);
                recorder.endUnit(0.5F, 0.5F, 0.5F, 1F);
            }
        }

        this.unitStart[bake.units] = recorder.triCount();
        this.vertexCount = recorder.triCount();
        this.localPos = recorder.positions();
        this.localNormal = recorder.normals();

        /* Plants render unshaded in the block pipeline — force their normals UP so the entity
         * program's directional diffuse doesn't darken captured tufts (see unshadedPlant). */
        for (int u = 0; u < bake.units; u++)
        {
            if (!DestructionBlockDraw.unshadedPlant(blocks.get(bake.unitBlock[u]).blockState()))
            {
                continue;
            }

            for (int v = this.unitStart[u], end = this.unitStart[u + 1]; v < end; v++)
            {
                this.localNormal[v * 3] = 0F;
                this.localNormal[v * 3 + 1] = 1F;
                this.localNormal[v * 3 + 2] = 0F;
            }
        }
        this.tmpPos = new float[this.vertexCount * 3];
        this.tmpNormal = new float[this.vertexCount * 3];
        this.tmpTangents = new float[this.vertexCount * 4];
        this.localTangent = new float[this.vertexCount * 4];

        float[] uvs = recorder.uvs();

        BBSRendering.calculateTangents(this.localTangent, this.localPos, this.localNormal, uvs);

        int previousVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        this.vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(this.vao);

        this.posBuffer = staticBuffer(0, 3, this.localPos, GL15.GL_DYNAMIC_DRAW);
        this.colorBuffer = staticBuffer(1, 4, recorder.colors(), GL15.GL_STATIC_DRAW);
        this.uvBuffer = staticBuffer(2, 2, uvs, GL15.GL_STATIC_DRAW);
        this.normalBuffer = staticBuffer(5, 3, this.localNormal, GL15.GL_DYNAMIC_DRAW);
        this.midUvBuffer = staticBuffer(8, 2, uvs, GL15.GL_STATIC_DRAW);
        this.tangentBuffer = staticBuffer(9, 4, this.tmpTangents, GL15.GL_DYNAMIC_DRAW);

        /* PER-VERTEX light captured PRE-cut (cave debris stays dark, sunlit rubble stays bright)
         * instead of the one flat sample; -1 (old saves) → sky-lit. SMOOTHED like vanilla smooth
         * lighting: each vertex averages the captured light of the 8 cells around its corner — flat
         * per-block light made the resting zone a visible PATCHWORK against the world's interpolated
         * terrain (worst under shaderpacks). */
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap lightMap =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(blocks.size() * 2);

        lightMap.defaultReturnValue(Integer.MIN_VALUE);

        for (DestructionBlock b : blocks)
        {
            if (b.light.get() >= 0)
            {
                lightMap.put(net.minecraft.util.math.BlockPos.asLong(b.x.get(), b.y.get(), b.z.get()), b.light.get().intValue());
            }
        }

        int[] lights = new int[this.vertexCount * 2];

        for (int u = 0; u < bake.units; u++)
        {
            DestructionBlock block = blocks.get(bake.unitBlock[u]);
            int oct = bake.unitOctant[u];
            int packed = block.light.get();
            int ownB = packed >= 0 ? packed & 0xFFFF : 0;
            int ownS = packed >= 0 ? (packed >> 16) & 0xFFFF : 0xF0;
            /* Unit rest centre in form-local space. */
            float ucx = block.x.get() + (oct >= 0 ? ((oct & 1) == 0 ? 0.25F : 0.75F) : 0.5F);
            float ucy = block.y.get() + (oct >= 0 ? ((oct & 2) == 0 ? 0.25F : 0.75F) : 0.5F);
            float ucz = block.z.get() + (oct >= 0 ? ((oct & 4) == 0 ? 0.25F : 0.75F) : 0.5F);

            for (int v = this.unitStart[u], end = this.unitStart[u + 1]; v < end; v++)
            {
                float wx = this.localPos[v * 3] + ucx;
                float wy = this.localPos[v * 3 + 1] + ucy;
                float wz = this.localPos[v * 3 + 2] + ucz;
                int sumB = 0, sumS = 0;

                for (int c = 0; c < 8; c++)
                {
                    int cx = (int) Math.floor(wx + ((c & 1) == 0 ? -0.5F : 0.5F) * 0.99F);
                    int cy = (int) Math.floor(wy + ((c & 2) == 0 ? -0.5F : 0.5F) * 0.99F);
                    int cz = (int) Math.floor(wz + ((c & 4) == 0 ? -0.5F : 0.5F) * 0.99F);
                    int l = lightMap.get(net.minecraft.util.math.BlockPos.asLong(cx, cy, cz));

                    if (l == Integer.MIN_VALUE)
                    {
                        sumB += ownB;
                        sumS += ownS;
                    }
                    else
                    {
                        sumB += l & 0xFFFF;
                        sumS += (l >> 16) & 0xFFFF;
                    }
                }

                lights[v * 2] = sumB >> 3;
                lights[v * 2 + 1] = sumS >> 3;
            }
        }

        this.lightBuffer = GL30.glGenBuffers();
        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.lightBuffer);
        GL30.glBufferData(GL15.GL_ARRAY_BUFFER, lights, GL15.GL_STATIC_DRAW);
        GL30.glVertexAttribIPointer(4, 2, GL15.GL_INT, 0, 0L);

        GL30.glBindVertexArray(previousVao);
    }

    private static int staticBuffer(int location, int size, float[] data, int usage)
    {
        int buffer = GL30.glGenBuffers();

        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL30.glBufferData(GL15.GL_ARRAY_BUFFER, data, usage);
        GL30.glVertexAttribPointer(location, size, GL15.GL_FLOAT, false, 0, 0L);

        return buffer;
    }

    /**
     * Transform the rest geometry by the sampled unit poses (physPose: 7 floats per unit — centre xyz +
     * quat xyzw, exactly what the renderer's pass 1 fills) and draw everything in one call.
     */
    public void render(float[] physPose, int unitCount, MatrixStack stack, float r, float g, float b, float a, int light, int overlay)
    {
        this.render(physPose, null, unitCount, stack, r, g, b, a, light, overlay);
    }

    /**
     * As {@link #render(float[], int, MatrixStack, float, float, float, float, int, int)} but with an
     * optional per-unit uniform SCALE ({@code scale[u]}, null = all 1) folded into the transform — the
     * beam devour shrinks its shatter fragments smoothly to nothing so they dissolve instead of popping.
     */
    public void render(float[] physPose, float[] scale, int unitCount, MatrixStack stack, float r, float g, float b, float a, int light, int overlay)
    {
        boolean iris = BBSRendering.isIrisShadersEnabled();

        for (int u = 0; u < unitCount && u < this.unitStart.length - 1; u++)
        {
            int o = u * 7;
            float px = physPose[o], py = physPose[o + 1], pz = physPose[o + 2];
            float qx = physPose[o + 3], qy = physPose[o + 4], qz = physPose[o + 5], qw = physPose[o + 6];

            /* Quaternion → 3×3 once per unit, then a tight vertex loop. */
            float xx = qx * qx, yy = qy * qy, zz = qz * qz;
            float xy = qx * qy, xz = qx * qz, yz = qy * qz;
            float wx = qw * qx, wy = qw * qy, wz = qw * qz;
            float m00 = 1F - 2F * (yy + zz), m01 = 2F * (xy - wz), m02 = 2F * (xz + wy);
            float m10 = 2F * (xy + wz), m11 = 1F - 2F * (xx + zz), m12 = 2F * (yz - wx);
            float m20 = 2F * (xz - wy), m21 = 2F * (yz + wx), m22 = 1F - 2F * (xx + yy);

            if (scale != null)
            {
                float sc = scale[u];

                m00 *= sc; m01 *= sc; m02 *= sc;
                m10 *= sc; m11 *= sc; m12 *= sc;
                m20 *= sc; m21 *= sc; m22 *= sc;
            }

            for (int v = this.unitStart[u], end = this.unitStart[u + 1]; v < end; v++)
            {
                int i = v * 3;
                float x = this.localPos[i], y = this.localPos[i + 1], z = this.localPos[i + 2];

                this.tmpPos[i] = m00 * x + m01 * y + m02 * z + px;
                this.tmpPos[i + 1] = m10 * x + m11 * y + m12 * z + py;
                this.tmpPos[i + 2] = m20 * x + m21 * y + m22 * z + pz;

                float nx = this.localNormal[i], ny = this.localNormal[i + 1], nz = this.localNormal[i + 2];

                this.tmpNormal[i] = m00 * nx + m01 * ny + m02 * nz;
                this.tmpNormal[i + 1] = m10 * nx + m11 * ny + m12 * nz;
                this.tmpNormal[i + 2] = m20 * nx + m21 * ny + m22 * nz;

                if (iris)
                {
                    /* Tangents rotate with the unit; handedness (w) is rotation-invariant. */
                    int t = v * 4;
                    float tx = this.localTangent[t], ty = this.localTangent[t + 1], tz = this.localTangent[t + 2];

                    this.tmpTangents[t] = m00 * tx + m01 * ty + m02 * tz;
                    this.tmpTangents[t + 1] = m10 * tx + m11 * ty + m12 * tz;
                    this.tmpTangents[t + 2] = m20 * tx + m21 * ty + m22 * tz;
                    this.tmpTangents[t + 3] = this.localTangent[t + 3];
                }
            }
        }

        int previousVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        GL30.glBindVertexArray(this.vao);
        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.posBuffer);
        GL30.glBufferData(GL15.GL_ARRAY_BUFFER, this.tmpPos, GL15.GL_DYNAMIC_DRAW);
        GL30.glVertexAttribPointer(0, 3, GL15.GL_FLOAT, false, 0, 0L);
        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.normalBuffer);
        GL30.glBufferData(GL15.GL_ARRAY_BUFFER, this.tmpNormal, GL15.GL_DYNAMIC_DRAW);
        GL30.glVertexAttribPointer(5, 3, GL15.GL_FLOAT, false, 0, 0L);

        if (iris)
        {
            /* The pack's entity program consumes tangents + mid-texcoords (locations 9/8) — feed them
             * or the pack's normal mapping breaks. Rest tangents were computed once at build. */
            GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.tangentBuffer);
            GL30.glBufferData(GL15.GL_ARRAY_BUFFER, this.tmpTangents, GL15.GL_DYNAMIC_DRAW);
            GL30.glVertexAttribPointer(9, 4, GL15.GL_FLOAT, false, 0, 0L);
        }

        /* Vanilla entity shader — Iris swaps it for the pack's gbuffers_entities, so packs light the
         * debris natively (the whole reason this path exists instead of a custom core shader). */
        MinecraftClient mc = MinecraftClient.getInstance();

        RenderSystem.setShaderTexture(0, PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);
        mc.gameRenderer.getLightmapTextureManager().enable();
        mc.gameRenderer.getOverlayTexture().setupOverlayColor();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();

        ShaderProgram shader = GameRenderer.getRenderTypeEntityTranslucentCullProgram();

        ModelVAORenderer.setupUniforms(stack, shader);

        GlUniform colorModulator = shader.getUniform("ColorModulator");

        if (colorModulator != null)
        {
            /* The form's fade/tint — the CPU path applies it via the colour substitute. */
            colorModulator.set(r, g, b, a);
        }

        shader.bind();

        GL30.glVertexAttribI2i(3, overlay & 0xFFFF, (overlay >> 16) & 0xFFFF);
        GL30.glEnableVertexAttribArray(0);
        GL30.glEnableVertexAttribArray(1);
        GL30.glEnableVertexAttribArray(2);
        /* Per-vertex light array (captured pre-cut per block). */
        GL30.glEnableVertexAttribArray(4);
        GL30.glEnableVertexAttribArray(5);

        if (iris)
        {
            GL30.glEnableVertexAttribArray(8);
            GL30.glEnableVertexAttribArray(9);
        }

        GL30.glDrawArrays(GL15.GL_TRIANGLES, 0, this.vertexCount);

        GL30.glDisableVertexAttribArray(0);
        GL30.glDisableVertexAttribArray(1);
        GL30.glDisableVertexAttribArray(2);
        GL30.glDisableVertexAttribArray(4);
        GL30.glDisableVertexAttribArray(5);

        if (iris)
        {
            GL30.glDisableVertexAttribArray(8);
            GL30.glDisableVertexAttribArray(9);
        }

        shader.unbind();
        GL30.glBindVertexArray(previousVao);
        mc.gameRenderer.getLightmapTextureManager().disable();
        mc.gameRenderer.getOverlayTexture().teardownOverlayColor();
        RenderSystem.disableBlend();
    }

    public void delete()
    {
        if (this.vao != -1)
        {
            GL30.glDeleteVertexArrays(this.vao);
            GL15.glDeleteBuffers(this.posBuffer);
            GL15.glDeleteBuffers(this.colorBuffer);
            GL15.glDeleteBuffers(this.uvBuffer);
            GL15.glDeleteBuffers(this.normalBuffer);
            GL15.glDeleteBuffers(this.tangentBuffer);
            GL15.glDeleteBuffers(this.midUvBuffer);
            GL15.glDeleteBuffers(this.lightBuffer);
            this.vao = -1;
        }
    }

    private static final class Provider implements VertexConsumerProvider
    {
        private final Recorder recorder;

        private Provider(Recorder recorder)
        {
            this.recorder = recorder;
        }

        @Override
        public VertexConsumer getBuffer(RenderLayer layer)
        {
            return this.recorder;
        }
    }

    /**
     * Captures the raw QUAD vertex stream of one unit, then {@link #endUnit} recentres it to the unit
     * origin (and optionally scales — the half-scale fallback) and triangulates into the final arrays.
     */
    private static final class Recorder implements VertexConsumer
    {
        /* Per-unit quad staging. */
        private float[] qp = new float[256 * 3];
        private float[] qc = new float[256 * 4];
        private float[] qu = new float[256 * 2];
        private float[] qn = new float[256 * 3];
        private int quadVerts;

        /* Final triangulated stream. */
        private final FloatList pos = new FloatList();
        private final FloatList col = new FloatList();
        private final FloatList uv = new FloatList();
        private final FloatList nrm = new FloatList();
        private int tris;

        private float tx, ty, tz, tr = 1F, tg = 1F, tb = 1F, ta = 1F, tu, tv, tnx, tny, tnz = 1F;
        private boolean pendingVertex;

        void beginUnit()
        {
            this.quadVerts = 0;
            this.pendingVertex = false;
        }

        /** Recentre this unit's captured geometry to {@code (cx,cy,cz)} (scaled by {@code scale}) and triangulate. */
        void endUnit(float cx, float cy, float cz, float scale)
        {
            /* Commit the last pending vertex (the 1.21 path has no next()). */
            this.commit();
            for (int q = 0; q + 3 < this.quadVerts; q += 4)
            {
                int[] order = {0, 1, 2, 2, 3, 0};

                for (int k : order)
                {
                    int i = q + k;

                    this.pos.add((this.qp[i * 3] - cx) * scale);
                    this.pos.add((this.qp[i * 3 + 1] - cy) * scale);
                    this.pos.add((this.qp[i * 3 + 2] - cz) * scale);
                    this.col.add(this.qc[i * 4]);
                    this.col.add(this.qc[i * 4 + 1]);
                    this.col.add(this.qc[i * 4 + 2]);
                    this.col.add(this.qc[i * 4 + 3]);
                    this.uv.add(this.qu[i * 2]);
                    this.uv.add(this.qu[i * 2 + 1]);
                    this.nrm.add(this.qn[i * 3]);
                    this.nrm.add(this.qn[i * 3 + 1]);
                    this.nrm.add(this.qn[i * 3 + 2]);
                    this.tris++;
                }
            }

            this.quadVerts = 0;
        }

        int triCount()
        {
            return this.tris;
        }

        float[] positions()
        {
            return this.pos.array();
        }

        float[] colors()
        {
            return this.col.array();
        }

        float[] uvs()
        {
            return this.uv.array();
        }

        float[] normals()
        {
            return this.nrm.array();
        }

        /* 1.20-era entry (abstract there; an extra method on 1.21) — hence no @Override. */
        public VertexConsumer vertex(double x, double y, double z)
        {
            return this.vertex((float) x, (float) y, (float) z);
        }

        /* 1.21-era entry (abstract there). A new vertex commits the pending one — 1.21's way of
         * finalizing (next() is gone); on 1.20 next() already committed, so this is a no-op. */
        public VertexConsumer vertex(float x, float y, float z)
        {
            this.commit();
            this.tx = x;
            this.ty = y;
            this.tz = z;
            this.pendingVertex = true;

            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha)
        {
            this.tr = red / 255F;
            this.tg = green / 255F;
            this.tb = blue / 255F;
            this.ta = alpha / 255F;

            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v)
        {
            this.tu = u;
            this.tv = v;

            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z)
        {
            this.tnx = x;
            this.tny = y;
            this.tnz = z;

            return this;
        }

        /* 1.20-era finalizer; absent from the 1.21 interface — hence no @Override. */
        public void next()
        {
            this.commit();
        }

        private void commit()
        {
            if (!this.pendingVertex)
            {
                return;
            }

            if (this.quadVerts * 3 >= this.qp.length)
            {
                this.qp = grow(this.qp);
                this.qc = grow(this.qc);
                this.qu = grow(this.qu);
                this.qn = grow(this.qn);
            }

            int i = this.quadVerts;

            this.qp[i * 3] = this.tx;
            this.qp[i * 3 + 1] = this.ty;
            this.qp[i * 3 + 2] = this.tz;
            this.qc[i * 4] = this.tr;
            this.qc[i * 4 + 1] = this.tg;
            this.qc[i * 4 + 2] = this.tb;
            this.qc[i * 4 + 3] = this.ta;
            this.qu[i * 2] = this.tu;
            this.qu[i * 2 + 1] = this.tv;
            this.qn[i * 3] = this.tnx;
            this.qn[i * 3 + 1] = this.tny;
            this.qn[i * 3 + 2] = this.tnz;
            this.quadVerts++;
            this.pendingVertex = false;
        }

        private static float[] grow(float[] a)
        {
            float[] b = new float[a.length * 2];

            System.arraycopy(a, 0, b, 0, a.length);

            return b;
        }

        /* 1.20-only interface members (gone in 1.21) — plain methods, no @Override. */
        public void fixedColor(int red, int green, int blue, int alpha)
        {
        }

        public void unfixColor()
        {
        }
    }

    /** Minimal growable float array (the recorder's output streams). */
    private static final class FloatList
    {
        private float[] data = new float[4096];
        private int size;

        void add(float value)
        {
            if (this.size == this.data.length)
            {
                float[] next = new float[this.data.length * 2];

                System.arraycopy(this.data, 0, next, 0, this.data.length);
                this.data = next;
            }

            this.data[this.size++] = value;
        }

        float[] array()
        {
            return java.util.Arrays.copyOf(this.data, this.size);
        }
    }
}
