package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import com.bbsvfx.bbsvfx.forms.DestructionBlock;
import com.bbsvfx.bbsvfx.forms.DestructionBoxForm;

import java.util.List;

/**
 * GPU path for the Destruction Box: bakes the whole structure into a static VBO once (rest / form-local
 * space, plus per-block attributes) and draws it with a single {@code glDrawArrays}. The shatter
 * displacement (position + tumble) runs in the {@code destruction_box} vertex shader from uniforms, so
 * animating the form is ~free on the CPU — no per-frame geometry work.
 *
 * <p>Vertex source is {@code renderBlockAsEntity} captured once per block into {@link RecordingConsumer}
 * (quads → triangles); per-block attributes (origin, random unit, tumble axis+magnitude) are baked
 * alongside so the shader can reproduce {@code localProgress} / {@code displacedPosition} / {@code blockSpin}
 * exactly. Rebuild is needed only when the block list or the seed changes.</p>
 */
public class DestructionVAO
{
    /* Attribute locations — must match the "attributes" order in destruction_box.json. */
    private static final int A_POSITION = 0;
    private static final int A_COLOR = 1;
    private static final int A_UV0 = 2;
    private static final int A_UV1 = 3;
    private static final int A_UV2 = 4;
    private static final int A_NORMAL = 5;
    private static final int A_ORIG = 6;
    private static final int A_RAND = 7;
    private static final int A_AXIS = 8;
    private static final int A_MAG = 9;

    private int vao;
    private int posBuffer, colorBuffer, uvBuffer, normalBuffer, origBuffer, randBuffer, axisBuffer, magBuffer;
    private int count;
    private boolean built;

    public void build(DestructionBoxForm form, List<DestructionBlock> blocks)
    {
        this.buildCore(blocks, form::bakedRandomUnit, form::bakedSpin);
    }

    private void buildCore(List<DestructionBlock> blocks,
        java.util.function.IntFunction<Vector3f> randFn, java.util.function.IntFunction<Vector4f> spinFn)
    {
        this.delete();

        int n = blocks.size();

        /* Per-block attributes (constant per block, replicated to its vertices below). */
        float[] boX = new float[n], boY = new float[n], boZ = new float[n];
        float[] brX = new float[n], brY = new float[n], brZ = new float[n];
        float[] saX = new float[n], saY = new float[n], saZ = new float[n], sMag = new float[n];

        RecordingConsumer rec = new RecordingConsumer();
        Provider provider = new Provider(rec);
        MatrixStack ms = new MatrixStack();

        for (int i = 0; i < n; i++)
        {
            DestructionBlock block = blocks.get(i);

            boX[i] = block.x.get();
            boY[i] = block.y.get();
            boZ[i] = block.z.get();

            Vector3f rand = randFn.apply(i);
            brX[i] = rand.x; brY[i] = rand.y; brZ[i] = rand.z;

            Vector4f spin = spinFn.apply(i);
            saX[i] = spin.x; saY[i] = spin.y; saZ[i] = spin.z; sMag[i] = spin.w;

            rec.currentBlock = i;

            ms.push();
            /* Rest position: at progress 0 the CPU path resolves each vertex to origPos + modelVertex. */
            ms.translate(block.x.get(), block.y.get(), block.z.get());
            DestructionBlockDraw.draw(block.blockState(), block.tint.get(),
                block.blockState().getRenderingSeed(new net.minecraft.util.math.BlockPos(
                    block.x.get(), block.y.get(), block.z.get())),
                ms.peek(), provider, 0x00F000F0, OverlayTexture.DEFAULT_UV);
            ms.pop();
        }

        /* Commit the last pending vertex (1.21 has no next() to finalize it). */
        rec.flush();

        /* Recorded vertices arrive as quads (4 per face); expand to triangles for glDrawArrays. */
        int quads = rec.count / 4;
        this.count = quads * 6;

        float[] pos = new float[this.count * 3];
        float[] col = new float[this.count * 4];
        float[] uv = new float[this.count * 2];
        float[] norm = new float[this.count * 3];
        float[] orig = new float[this.count * 3];
        float[] rnd = new float[this.count * 3];
        float[] axis = new float[this.count * 3];
        float[] mag = new float[this.count];

        int o = 0;

        for (int q = 0; q < quads; q++)
        {
            int base = q * 4;
            int b = rec.blockOf[base];

            /* Two triangles: (0,1,2) and (0,2,3). */
            int[] order = {base, base + 1, base + 2, base, base + 2, base + 3};

            for (int k = 0; k < 6; k++)
            {
                int v = order[k];

                pos[o * 3] = rec.px[v]; pos[o * 3 + 1] = rec.py[v]; pos[o * 3 + 2] = rec.pz[v];
                col[o * 4] = rec.cr[v]; col[o * 4 + 1] = rec.cg[v]; col[o * 4 + 2] = rec.cb[v]; col[o * 4 + 3] = rec.ca[v];
                uv[o * 2] = rec.u[v]; uv[o * 2 + 1] = rec.vv[v];
                norm[o * 3] = rec.nx[v]; norm[o * 3 + 1] = rec.ny[v]; norm[o * 3 + 2] = rec.nz[v];

                orig[o * 3] = boX[b]; orig[o * 3 + 1] = boY[b]; orig[o * 3 + 2] = boZ[b];
                rnd[o * 3] = brX[b]; rnd[o * 3 + 1] = brY[b]; rnd[o * 3 + 2] = brZ[b];
                axis[o * 3] = saX[b]; axis[o * 3 + 1] = saY[b]; axis[o * 3 + 2] = saZ[b];
                mag[o] = sMag[b];

                o++;
            }
        }

        this.upload(pos, col, uv, norm, orig, rnd, axis, mag);
        this.built = true;
    }

    private void upload(float[] pos, float[] col, float[] uv, float[] norm,
        float[] orig, float[] rnd, float[] axis, float[] mag)
    {
        this.vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(this.vao);

        this.posBuffer = attrib(A_POSITION, 3, pos);
        this.colorBuffer = attrib(A_COLOR, 4, col);
        this.uvBuffer = attrib(A_UV0, 2, uv);
        this.normalBuffer = attrib(A_NORMAL, 3, norm);
        this.origBuffer = attrib(A_ORIG, 3, orig);
        this.randBuffer = attrib(A_RAND, 3, rnd);
        this.axisBuffer = attrib(A_AXIS, 3, axis);
        this.magBuffer = attrib(A_MAG, 1, mag);

        GL30.glBindVertexArray(0);
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

    public boolean isBuilt()
    {
        return this.built;
    }

    /** Draw the baked structure with the shatter shader; uniforms come from the form's current values. */
    public void render(DestructionBoxForm form, MatrixStack stack, int light, int overlay)
    {
        ShaderProgram shader = DestructionShader.PROGRAM;

        if (!this.built || this.count == 0 || shader == null)
        {
            return;
        }

        int prevVAO = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevElem = GL30.glGetInteger(GL30.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        RenderLayer layer = RenderLayer.getEntityCutout(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);
        layer.startDrawing();

        ModelVAORenderer.setupUniforms(stack, shader);
        this.setShatterUniforms(form, shader);

        GL30.glVertexAttribI2i(A_UV1, overlay & 0xFFFF, overlay >> 16 & 0xFFFF);
        GL30.glVertexAttribI2i(A_UV2, light & 0xFFFF, light >> 16 & 0xFFFF);

        shader.bind();

        GL30.glBindVertexArray(this.vao);
        GL30.glEnableVertexAttribArray(A_POSITION);
        GL30.glEnableVertexAttribArray(A_COLOR);
        GL30.glEnableVertexAttribArray(A_UV0);
        GL30.glEnableVertexAttribArray(A_NORMAL);
        GL30.glEnableVertexAttribArray(A_ORIG);
        GL30.glEnableVertexAttribArray(A_RAND);
        GL30.glEnableVertexAttribArray(A_AXIS);
        GL30.glEnableVertexAttribArray(A_MAG);

        GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, this.count);

        GL30.glDisableVertexAttribArray(A_POSITION);
        GL30.glDisableVertexAttribArray(A_COLOR);
        GL30.glDisableVertexAttribArray(A_UV0);
        GL30.glDisableVertexAttribArray(A_NORMAL);
        GL30.glDisableVertexAttribArray(A_ORIG);
        GL30.glDisableVertexAttribArray(A_RAND);
        GL30.glDisableVertexAttribArray(A_AXIS);
        GL30.glDisableVertexAttribArray(A_MAG);

        shader.unbind();

        GL30.glBindVertexArray(prevVAO);
        GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, prevElem);

        layer.endDrawing();
    }

    private void setShatterUniforms(DestructionBoxForm form, ShaderProgram shader)
    {
        Vector3f point = form.point();
        Vector3f center = form.center();
        Vector3f dir = form.blastDir();
        float maxDist = form.maxDistanceToPoint(point);

        setF(shader, "uDestruction", form.destruction.get());
        setI(shader, "uPointMode", form.pointMode.get() ? 1 : 0);
        setF(shader, "uStagger", form.stagger.get());
        setI(shader, "uInvert", form.invertOrder.get() ? 1 : 0);
        setV3(shader, "uPoint", point);
        setF(shader, "uMaxDist", maxDist);
        setV3(shader, "uCenter", center);
        setV3(shader, "uDir", dir);
        setF(shader, "uDirStrength", form.dirStrength.get());
        setF(shader, "uRadialStrength", form.radialStrength.get());
        setF(shader, "uRandomAmount", form.randomAmount.get());
        setF(shader, "uRotationAmount", form.rotationAmount.get());
        setF(shader, "uPointStrength", form.pointStrength.get());
        setI(shader, "uPointAway", form.pointAway.get() ? 1 : 0);
    }

    private static void setF(ShaderProgram shader, String name, float value)
    {
        GlUniform u = shader.getUniform(name);
        if (u != null) u.set(value);
    }

    private static void setI(ShaderProgram shader, String name, int value)
    {
        GlUniform u = shader.getUniform(name);
        if (u != null) u.set(value);
    }

    private static void setV3(ShaderProgram shader, String name, Vector3f v)
    {
        GlUniform u = shader.getUniform(name);
        if (u != null) u.set(v.x, v.y, v.z);
    }

    public void delete()
    {
        if (!this.built)
        {
            return;
        }

        GL30.glDeleteVertexArrays(this.vao);
        GL15.glDeleteBuffers(this.posBuffer);
        GL15.glDeleteBuffers(this.colorBuffer);
        GL15.glDeleteBuffers(this.uvBuffer);
        GL15.glDeleteBuffers(this.normalBuffer);
        GL15.glDeleteBuffers(this.origBuffer);
        GL15.glDeleteBuffers(this.randBuffer);
        GL15.glDeleteBuffers(this.axisBuffer);
        GL15.glDeleteBuffers(this.magBuffer);
        this.built = false;
        this.count = 0;
    }

    /** Hands the single recording consumer to {@code renderBlockAsEntity} regardless of render layer. */
    private static final class Provider implements VertexConsumerProvider
    {
        private final RecordingConsumer consumer;

        private Provider(RecordingConsumer consumer)
        {
            this.consumer = consumer;
        }

        @Override
        public VertexConsumer getBuffer(RenderLayer layer)
        {
            return this.consumer;
        }
    }

    /** Captures pos / colour / uv / normal per vertex (tagged with its block) into growable arrays. */
    private static final class RecordingConsumer implements VertexConsumer
    {
        private float[] px = new float[1024], py = new float[1024], pz = new float[1024];
        private float[] cr = new float[1024], cg = new float[1024], cb = new float[1024], ca = new float[1024];
        private float[] u = new float[1024], vv = new float[1024];
        private float[] nx = new float[1024], ny = new float[1024], nz = new float[1024];
        private int[] blockOf = new int[1024];
        private int count;
        private int currentBlock;

        private float tx, ty, tz, tcr, tcg, tcb, tca, tu, tv, tnx, tny, tnz;
        private boolean pendingVertex;
        private int stagedBlock;

        /* 1.20-era entry (abstract there; an extra method on 1.21) — hence no @Override. */
        public VertexConsumer vertex(double x, double y, double z)
        {
            return this.vertex((float) x, (float) y, (float) z);
        }

        /* 1.21-era entry (abstract there). Starting a NEW vertex commits the pending one — that is how
         * 1.21 finalizes vertices (next() is gone); on 1.20 next() already committed, so it's a no-op. */
        public VertexConsumer vertex(float x, float y, float z)
        {
            this.commit();
            this.tx = x; this.ty = y; this.tz = z;
            this.stagedBlock = this.currentBlock;
            this.pendingVertex = true;
            return this;
        }

        /** Commit the last pending vertex (the 1.21 path has no next()). */
        void flush()
        {
            this.commit();
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha)
        {
            this.tcr = red / 255F; this.tcg = green / 255F; this.tcb = blue / 255F; this.tca = alpha / 255F;
            return this;
        }

        @Override
        public VertexConsumer texture(float uu, float vvv)
        {
            this.tu = uu; this.tv = vvv;
            return this;
        }

        @Override
        public VertexConsumer overlay(int uu, int vvv)
        {
            return this;
        }

        @Override
        public VertexConsumer light(int uu, int vvv)
        {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z)
        {
            this.tnx = x; this.tny = y; this.tnz = z;
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

            if (this.count == this.px.length)
            {
                this.grow();
            }

            int i = this.count;

            this.px[i] = this.tx; this.py[i] = this.ty; this.pz[i] = this.tz;
            this.cr[i] = this.tcr; this.cg[i] = this.tcg; this.cb[i] = this.tcb; this.ca[i] = this.tca;
            this.u[i] = this.tu; this.vv[i] = this.tv;
            this.nx[i] = this.tnx; this.ny[i] = this.tny; this.nz[i] = this.tnz;
            this.blockOf[i] = this.stagedBlock;
            this.count++;
            this.pendingVertex = false;
        }

        private void grow()
        {
            int m = this.px.length * 2;

            this.px = grow(this.px, m); this.py = grow(this.py, m); this.pz = grow(this.pz, m);
            this.cr = grow(this.cr, m); this.cg = grow(this.cg, m); this.cb = grow(this.cb, m); this.ca = grow(this.ca, m);
            this.u = grow(this.u, m); this.vv = grow(this.vv, m);
            this.nx = grow(this.nx, m); this.ny = grow(this.ny, m); this.nz = grow(this.nz, m);

            int[] nb = new int[m];
            System.arraycopy(this.blockOf, 0, nb, 0, this.blockOf.length);
            this.blockOf = nb;
        }

        private static float[] grow(float[] a, int n)
        {
            float[] b = new float[n];
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
}
