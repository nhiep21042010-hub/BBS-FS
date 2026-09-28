package com.bbsvfx.bbsvfx.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import com.bbsvfx.bbsvfx.forms.DestructionBlock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier-C debris under IRIS: the far bulk drawn as a build-once STATIC shell at rest. A custom core
 * shader is not picked up by shaderpacks, so the ballistic path can't run there — instead the far rim
 * simply stays intact-looking while the tier-A hero zone (PhysX + the CPU VAO path) does the flying.
 * Known Ф1 compromise; impostors later.
 *
 * <p>Because the shell NEVER moves, rest-adjacency face culling is valid: interior faces between two
 * occluding structure blocks are dropped at build, so a solid 80k-block rim collapses to its surface —
 * a small VBO uploaded once, drawn with the VANILLA entity shader (pack-lit, tangents baked once for
 * Iris's normal mapping), zero per-frame CPU.</p>
 */
public class DestructionStaticVAO
{
    private static final int MAX_CACHED = 3;

    private static final LinkedHashMap<Long, DestructionStaticVAO> CACHE = new LinkedHashMap<>(8, 0.75F, true);

    private int vao = -1;
    private int posBuffer, colorBuffer, uvBuffer, normalBuffer, tangentBuffer, midUvBuffer, lightBuffer;
    private int vertexCount;

    /** Build a shell WITHOUT the shared cache — the caller owns the lifecycle (delete()). Used by the
     *  dome's banded Iris path, which manages a few dozen band shells of its own. */
    public static DestructionStaticVAO create(List<DestructionBlock> allBlocks, int[] indices)
    {
        DestructionStaticVAO built = new DestructionStaticVAO();

        try
        {
            built.build(allBlocks, indices);
        }
        catch (Exception e)
        {
            System.err.println("[bbsvfx] Destruction static VAO build failed: " + e);
            built.delete();

            return null;
        }

        return built;
    }

    /** Fetch (or build) the static shell for this tier-C set. Null when the build failed. */
    public static DestructionStaticVAO of(long key, List<DestructionBlock> allBlocks, int[] tierC)
    {
        DestructionStaticVAO cached = CACHE.get(key);

        if (cached != null)
        {
            return cached;
        }

        DestructionStaticVAO built = new DestructionStaticVAO();

        try
        {
            built.build(allBlocks, tierC);
        }
        catch (Exception e)
        {
            System.err.println("[bbsvfx] Destruction static VAO build failed: " + e);
            built.delete();

            return null;
        }

        CACHE.put(key, built);

        while (CACHE.size() > MAX_CACHED)
        {
            Map.Entry<Long, DestructionStaticVAO> eldest = CACHE.entrySet().iterator().next();

            eldest.getValue().delete();
            CACHE.remove(eldest.getKey());
        }

        return built;
    }

    private void build(List<DestructionBlock> allBlocks, int[] tierC)
    {
        /* Occluding cells of the WHOLE structure (both tiers): a tier-C face against a tier-A block is
         * still hidden at rest — and by the time that block flies away the whole hero zone is moving,
         * nobody looks at one missing face on the rim. */
        it.unimi.dsi.fastutil.longs.LongOpenHashSet occluders = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(allBlocks.size() * 2);

        for (DestructionBlock block : allBlocks)
        {
            if (DestructionBoxFormRenderer.isOccluder(block.blockState()))
            {
                occluders.add(BlockPos.asLong(block.x.get(), block.y.get(), block.z.get()));
            }
        }

        Recorder recorder = new Recorder();
        Provider provider = new Provider(recorder);
        MatrixStack ms = new MatrixStack();

        java.util.List<int[]> lightRanges = new java.util.ArrayList<>();

        for (int i : tierC)
        {
            DestructionBlock block = allBlocks.get(i);
            int bx = block.x.get(), by = block.y.get(), bz = block.z.get();
            int startVert = recorder.triCount();

            recorder.beginBlock(bx, by, bz, occluders);

            ms.push();
            ms.translate(bx, by, bz);
            DestructionBlockDraw.draw(block.blockState(), block.tint.get(),
                block.blockState().getRenderingSeed(new BlockPos(bx, by, bz)),
                ms.peek(), provider, 0x00F000F0, OverlayTexture.DEFAULT_UV);
            ms.pop();

            recorder.endBlock();
            lightRanges.add(new int[] {startVert, recorder.triCount(), block.light.get(),
                DestructionBlockDraw.unshadedPlant(block.blockState()) ? 1 : 0});
        }

        this.vertexCount = recorder.triCount();

        if (this.vertexCount == 0)
        {
            return;
        }

        float[] pos = recorder.positions();
        float[] normals = recorder.normals();
        float[] uvs = recorder.uvs();
        float[] tangents = new float[this.vertexCount * 4];

        /* Plants render unshaded in the block pipeline — force their normals UP (see unshadedPlant). */
        for (int[] range : lightRanges)
        {
            if (range[3] == 0)
            {
                continue;
            }

            for (int v = range[0]; v < range[1]; v++)
            {
                normals[v * 3] = 0F;
                normals[v * 3 + 1] = 1F;
                normals[v * 3 + 2] = 0F;
            }
        }

        /* Iris's entity program consumes tangents (loc 9) + mid-texcoords (loc 8); baked once. */
        BBSRendering.calculateTangents(tangents, pos, normals, uvs);

        int previousVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        this.vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(this.vao);

        this.posBuffer = attrib(0, 3, pos);
        this.colorBuffer = attrib(1, 4, recorder.colors());
        this.uvBuffer = attrib(2, 2, uvs);
        this.normalBuffer = attrib(5, 3, normals);
        this.midUvBuffer = attrib(8, 2, uvs);
        this.tangentBuffer = attrib(9, 4, tangents);

        /* Per-VERTEX light captured PRE-cut, SMOOTHED over the 8 cells around each vertex corner —
         * flat per-block light read as a patchwork against the world's interpolated terrain. */
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap lightMap =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(allBlocks.size() * 2);

        lightMap.defaultReturnValue(Integer.MIN_VALUE);

        for (DestructionBlock b : allBlocks)
        {
            if (b.light.get() >= 0)
            {
                lightMap.put(BlockPos.asLong(b.x.get(), b.y.get(), b.z.get()), b.light.get().intValue());
            }
        }

        int[] lights = new int[this.vertexCount * 2];

        for (int[] range : lightRanges)
        {
            int ownB = range[2] >= 0 ? range[2] & 0xFFFF : 0;
            int ownS = range[2] >= 0 ? (range[2] >> 16) & 0xFFFF : 0xF0;

            for (int v = range[0]; v < range[1]; v++)
            {
                float wx = pos[v * 3], wy = pos[v * 3 + 1], wz = pos[v * 3 + 2];
                int sumB = 0, sumS = 0;

                for (int c = 0; c < 8; c++)
                {
                    int cx = (int) Math.floor(wx + ((c & 1) == 0 ? -0.5F : 0.5F) * 0.99F);
                    int cy = (int) Math.floor(wy + ((c & 2) == 0 ? -0.5F : 0.5F) * 0.99F);
                    int cz = (int) Math.floor(wz + ((c & 4) == 0 ? -0.5F : 0.5F) * 0.99F);
                    int l = lightMap.get(BlockPos.asLong(cx, cy, cz));

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
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, lights, GL15.GL_STATIC_DRAW);
        GL30.glVertexAttribIPointer(4, 2, GL15.GL_INT, 0, 0);

        GL30.glBindVertexArray(previousVao);
        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private static int attrib(int location, int size, float[] data)
    {
        int buffer = GL30.glGenBuffers();

        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(location, size, GL15.GL_FLOAT, false, 0, 0);

        return buffer;
    }

    /** One static draw with the vanilla entity shader (Iris swaps in the pack's program). */
    public void render(MatrixStack stack, float r, float g, float b, float a, int light, int overlay)
    {
        this.render(stack, r, g, b, a, light, overlay, true);
    }

    /** As above, but {@code cull=false} draws BOTH faces — needed for the dome's tumbling clusters, which
     *  rotate freely and would otherwise show see-through gaps from their back / culled sides. */
    public void render(MatrixStack stack, float r, float g, float b, float a, int light, int overlay, boolean cull)
    {
        if (this.vao == -1 || this.vertexCount == 0)
        {
            return;
        }

        boolean iris = BBSRendering.isIrisShadersEnabled();
        int previousVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        MinecraftClient mc = MinecraftClient.getInstance();

        RenderSystem.setShaderTexture(0, PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);
        mc.gameRenderer.getLightmapTextureManager().enable();
        mc.gameRenderer.getOverlayTexture().setupOverlayColor();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();

        if (cull)
        {
            RenderSystem.enableCull();
        }
        else
        {
            RenderSystem.disableCull();
        }

        ShaderProgram shader = GameRenderer.getRenderTypeEntityTranslucentCullProgram();

        ModelVAORenderer.setupUniforms(stack, shader);

        GlUniform colorModulator = shader.getUniform("ColorModulator");

        if (colorModulator != null)
        {
            colorModulator.set(r, g, b, a);
        }

        shader.bind();

        GL30.glBindVertexArray(this.vao);
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

        if (!cull)
        {
            RenderSystem.enableCull();
        }
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
     * Captures a block's quads (already rest-translated) and culls BOUNDARY faces whose neighbour cell
     * is an occluding structure block: the quad's normal picks the face direction, and the quad only
     * culls when all 4 vertices lie on that face's plane (cross-model quads and inner geometry stay).
     */
    private static final class Recorder implements VertexConsumer
    {
        /* Per-quad staging (4 vertices). */
        private final float[] qp = new float[4 * 3];
        private final float[] qc = new float[4 * 4];
        private final float[] qu = new float[4 * 2];
        private final float[] qn = new float[4 * 3];
        private int quadVerts;

        /* Final triangulated stream. */
        private float[] pos = new float[8192 * 3];
        private float[] col = new float[8192 * 4];
        private float[] uv = new float[8192 * 2];
        private float[] nrm = new float[8192 * 3];
        private int tris;

        private int bx, by, bz;
        private it.unimi.dsi.fastutil.longs.LongOpenHashSet occluders;

        private float tx, ty, tz, tr = 1F, tg = 1F, tb = 1F, ta = 1F, tu, tv, tnx, tny, tnz = 1F;
        private boolean pendingVertex;

        void beginBlock(int bx, int by, int bz, it.unimi.dsi.fastutil.longs.LongOpenHashSet occluders)
        {
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.occluders = occluders;
            this.quadVerts = 0;
            this.pendingVertex = false;
        }

        void endBlock()
        {
            this.commit();

            /* A trailing partial quad (shouldn't happen) is dropped. */
            this.quadVerts = 0;
        }

        int triCount()
        {
            return this.tris;
        }

        float[] positions()
        {
            return java.util.Arrays.copyOf(this.pos, this.tris * 3);
        }

        float[] colors()
        {
            return java.util.Arrays.copyOf(this.col, this.tris * 4);
        }

        float[] uvs()
        {
            return java.util.Arrays.copyOf(this.uv, this.tris * 2);
        }

        float[] normals()
        {
            return java.util.Arrays.copyOf(this.nrm, this.tris * 3);
        }

        /** A full quad is staged — cull or triangulate it. */
        private void flushQuad()
        {
            /* Face direction from the (flat-shaded) quad normal. */
            int dx = this.qn[0] > 0.5F ? 1 : this.qn[0] < -0.5F ? -1 : 0;
            int dy = this.qn[1] > 0.5F ? 1 : this.qn[1] < -0.5F ? -1 : 0;
            int dz = this.qn[2] > 0.5F ? 1 : this.qn[2] < -0.5F ? -1 : 0;

            boolean cull = false;

            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1
                && this.occluders.contains(BlockPos.asLong(this.bx + dx, this.by + dy, this.bz + dz)))
            {
                /* Neighbour occludes — cull only if the quad LIES ON the boundary plane. */
                float plane = dx != 0 ? this.bx + (dx > 0 ? 1F : 0F)
                    : dy != 0 ? this.by + (dy > 0 ? 1F : 0F)
                    : this.bz + (dz > 0 ? 1F : 0F);
                int axis = dx != 0 ? 0 : dy != 0 ? 1 : 2;

                cull = true;

                for (int v = 0; v < 4; v++)
                {
                    if (Math.abs(this.qp[v * 3 + axis] - plane) > 1e-4F)
                    {
                        cull = false;

                        break;
                    }
                }
            }

            if (!cull)
            {
                int[] order = {0, 1, 2, 2, 3, 0};

                for (int k : order)
                {
                    this.ensureCapacity();

                    int o = this.tris;

                    this.pos[o * 3] = this.qp[k * 3];
                    this.pos[o * 3 + 1] = this.qp[k * 3 + 1];
                    this.pos[o * 3 + 2] = this.qp[k * 3 + 2];
                    this.col[o * 4] = this.qc[k * 4];
                    this.col[o * 4 + 1] = this.qc[k * 4 + 1];
                    this.col[o * 4 + 2] = this.qc[k * 4 + 2];
                    this.col[o * 4 + 3] = this.qc[k * 4 + 3];
                    this.uv[o * 2] = this.qu[k * 2];
                    this.uv[o * 2 + 1] = this.qu[k * 2 + 1];
                    this.nrm[o * 3] = this.qn[k * 3];
                    this.nrm[o * 3 + 1] = this.qn[k * 3 + 1];
                    this.nrm[o * 3 + 2] = this.qn[k * 3 + 2];
                    this.tris++;
                }
            }

            this.quadVerts = 0;
        }

        private void ensureCapacity()
        {
            if (this.tris * 3 == this.pos.length)
            {
                this.pos = grow(this.pos);
                this.col = grow(this.col);
                this.uv = grow(this.uv);
                this.nrm = grow(this.nrm);
            }
        }

        private static float[] grow(float[] a)
        {
            float[] b = new float[a.length * 2];

            System.arraycopy(a, 0, b, 0, a.length);

            return b;
        }

        /* 1.20-era entry (abstract there; an extra method on 1.21) — hence no @Override. */
        public VertexConsumer vertex(double x, double y, double z)
        {
            return this.vertex((float) x, (float) y, (float) z);
        }

        /* 1.21-era entry (abstract there). A new vertex commits the pending one. */
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

            if (this.quadVerts == 4)
            {
                this.flushQuad();
            }
        }

        /* 1.20-only interface members (gone in 1.21) — plain methods, no @Override. */
        public void fixedColor(int red, int green, int blue, int alpha)
        {
        }

        public void unfixColor()
        {
        }
    }
}
