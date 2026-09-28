package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import org.lwjgl.opengl.ARBDrawInstanced;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import com.bbsvfx.bbsvfx.forms.DestructionBlock;

import java.util.List;

/**
 * GPU world-levelling for the dome: the whole razed field is ONE instanced draw of a unit cube. Per
 * block only ~44 bytes of instance data (rest corner + particle-sprite UV rect + tint); the radial-front
 * settle+dissolve (mirror of {@code DomeFormRenderer.computeLevelPoses}) runs in the {@code
 * dome_level_inst} vertex shader from the {@code uFront} uniform. Zero per-frame CPU geometry — this is
 * what lets a 100k+-block level animate in real time (the per-vertex bake OOM-crashed at that scale).
 *
 * <p>LOD: each block is a full cube textured with its PARTICLE sprite (not its exact model) — reads fine
 * for a field being vaporised. Vanilla only (a custom core program isn't pack-lit; caller uses the CPU
 * path under Iris).</p>
 */
public class DomeLevelVAO
{
    private static Boolean supported;

    private int aPosition, aColor, aUv0, aUv1, aUv2, aNormal, aOrig, aUvRect, aTint;

    private int vao = -1;
    private int cubePos, cubeColor, cubeUv, cubeNormal, instOrig, instUvRect, instTint;
    private int instances;

    public static boolean isSupported()
    {
        if (supported == null)
        {
            supported = (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_instanced_arrays)
                && (GL.getCapabilities().OpenGL31 || GL.getCapabilities().GL_ARB_draw_instanced);
        }

        return supported;
    }

    public boolean isBuilt()
    {
        return this.vao != -1;
    }

    public void build(List<DestructionBlock> blocks)
    {
        this.delete();

        ShaderProgram program = DestructionShader.DOME;

        if (program == null)
        {
            return;
        }

        int n = blocks.size();
        int pid = program.getGlRef();

        this.aPosition = GL20.glGetAttribLocation(pid, "Position");
        this.aColor = GL20.glGetAttribLocation(pid, "Color");
        this.aUv0 = GL20.glGetAttribLocation(pid, "UV0");
        this.aUv1 = GL20.glGetAttribLocation(pid, "UV1");
        this.aUv2 = GL20.glGetAttribLocation(pid, "UV2");
        this.aNormal = GL20.glGetAttribLocation(pid, "Normal");
        this.aOrig = GL20.glGetAttribLocation(pid, "IOrig");
        this.aUvRect = GL20.glGetAttribLocation(pid, "IUvRect");
        this.aTint = GL20.glGetAttribLocation(pid, "ITint");

        if (this.aOrig < 0 || this.aUvRect < 0 || this.aTint < 0)
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        float[] orig = new float[n * 3];
        float[] uvRect = new float[n * 4];
        float[] tint = new float[n * 4];

        for (int k = 0; k < n; k++)
        {
            DestructionBlock block = blocks.get(k);

            orig[k * 3] = block.x.get();
            orig[k * 3 + 1] = block.y.get();
            orig[k * 3 + 2] = block.z.get();

            Sprite sprite = mc.getBlockRenderManager().getModels().getModelParticleSprite(block.blockState());

            uvRect[k * 4] = sprite.getMinU();
            uvRect[k * 4 + 1] = sprite.getMinV();
            uvRect[k * 4 + 2] = sprite.getMaxU() - sprite.getMinU();
            uvRect[k * 4 + 3] = sprite.getMaxV() - sprite.getMinV();

            int t = block.tint.get();

            if ((t & 0xFFFFFF) != 0xFFFFFF && (t & 0xFFFFFF) != 0)
            {
                tint[k * 4] = (t >> 16 & 255) / 255F;
                tint[k * 4 + 1] = (t >> 8 & 255) / 255F;
                tint[k * 4 + 2] = (t & 255) / 255F;
                tint[k * 4 + 3] = 1F;
            }
            else
            {
                tint[k * 4] = 1F; tint[k * 4 + 1] = 1F; tint[k * 4 + 2] = 1F; tint[k * 4 + 3] = 1F;
            }
        }

        this.instances = n;

        int previousVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        this.vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(this.vao);

        float[][] cube = unitCube();

        this.cubePos = attrib(this.aPosition, 3, cube[0], 0);
        this.cubeColor = attrib(this.aColor, 4, cube[1], 0);
        this.cubeUv = attrib(this.aUv0, 2, cube[2], 0);
        this.cubeNormal = attrib(this.aNormal, 3, cube[3], 0);

        this.instOrig = attrib(this.aOrig, 3, orig, 1);
        this.instUvRect = attrib(this.aUvRect, 4, uvRect, 1);
        this.instTint = attrib(this.aTint, 4, tint, 1);

        GL30.glBindVertexArray(previousVao);
        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        int err = org.lwjgl.opengl.GL11.glGetError();

        org.slf4j.LoggerFactory.getLogger("bbsvfx").info("[dome-gpu] built {} instances, locs pos={} col={} uv0={} nrm={} orig={} uvRect={} tint={} glError={}",
            n, this.aPosition, this.aColor, this.aUv0, this.aNormal, this.aOrig, this.aUvRect, this.aTint, err);
    }

    public void render(MatrixStack stack, float front, float clearSpan, float launch, int light, int overlay)
    {
        ShaderProgram shader = DestructionShader.DOME;

        if (this.vao == -1 || this.instances == 0 || shader == null)
        {
            return;
        }

        int prevVAO = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevElem = GL30.glGetInteger(GL30.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        RenderLayer layer = RenderLayer.getEntityCutout(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);

        layer.startDrawing();

        ModelVAORenderer.setupUniforms(stack, shader);
        setF(shader, "uFront", front);
        setF(shader, "uClearSpan", clearSpan);
        setF(shader, "uLaunch", launch);

        GlUniform center = shader.getUniform("uCenter");

        if (center != null)
        {
            center.set(0F, 0F, 0F);
        }

        if (this.aUv1 >= 0)
        {
            GL30.glVertexAttribI2i(this.aUv1, overlay & 0xFFFF, overlay >> 16 & 0xFFFF);
        }

        if (this.aUv2 >= 0)
        {
            GL30.glVertexAttribI2i(this.aUv2, light & 0xFFFF, light >> 16 & 0xFFFF);
        }

        shader.bind();

        GL30.glBindVertexArray(this.vao);
        this.setArraysEnabled(true);

        if (GL.getCapabilities().OpenGL31)
        {
            GL31.glDrawArraysInstanced(GL30.GL_TRIANGLES, 0, 36, this.instances);
        }
        else
        {
            ARBDrawInstanced.glDrawArraysInstancedARB(GL30.GL_TRIANGLES, 0, 36, this.instances);
        }

        this.setArraysEnabled(false);

        shader.unbind();

        GL30.glBindVertexArray(prevVAO);
        GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, prevElem);

        layer.endDrawing();
    }

    private void setArraysEnabled(boolean enabled)
    {
        int[] locations = {this.aPosition, this.aColor, this.aUv0, this.aNormal, this.aOrig, this.aUvRect, this.aTint};

        for (int location : locations)
        {
            if (location >= 0)
            {
                if (enabled)
                {
                    GL30.glEnableVertexAttribArray(location);
                }
                else
                {
                    GL30.glDisableVertexAttribArray(location);
                }
            }
        }
    }

    private static int attrib(int location, int size, float[] data, int divisor)
    {
        if (location < 0)
        {
            return -1;
        }

        int buffer = GL30.glGenBuffers();

        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(location, size, GL15.GL_FLOAT, false, 0, 0);

        if (divisor > 0)
        {
            /* 3.2-core context: GL33 core entry point may be absent — use the ARB alias (same feature). */
            if (GL.getCapabilities().OpenGL33)
            {
                GL33.glVertexAttribDivisor(location, divisor);
            }
            else
            {
                ARBInstancedArrays.glVertexAttribDivisorARB(location, divisor);
            }
        }

        return buffer;
    }

    private static void setF(ShaderProgram shader, String name, float value)
    {
        GlUniform u = shader.getUniform(name);

        if (u != null)
        {
            u.set(value);
        }
    }

    public void delete()
    {
        if (this.vao != -1)
        {
            GL30.glDeleteVertexArrays(this.vao);
            GL15.glDeleteBuffers(this.cubePos);
            GL15.glDeleteBuffers(this.cubeColor);
            GL15.glDeleteBuffers(this.cubeUv);
            GL15.glDeleteBuffers(this.cubeNormal);
            GL15.glDeleteBuffers(this.instOrig);
            GL15.glDeleteBuffers(this.instUvRect);
            GL15.glDeleteBuffers(this.instTint);
            this.vao = -1;
        }
    }

    /** Unit cube: 6 faces × 2 triangles, positions 0..1, per-face 0..1 UVs, axis normals + directional shade. */
    private static float[][] unitCube()
    {
        float[][] faces = {
            {0,0,0, 1,0,0, 1,0,1, 0,0,1,  0,-1,0, 0.5F},
            {0,1,1, 1,1,1, 1,1,0, 0,1,0,  0,1,0,  1.0F},
            {1,1,0, 1,0,0, 0,0,0, 0,1,0,  0,0,-1, 0.8F},
            {0,1,1, 0,0,1, 1,0,1, 1,1,1,  0,0,1,  0.8F},
            {0,1,0, 0,0,0, 0,0,1, 0,1,1,  -1,0,0, 0.6F},
            {1,1,1, 1,0,1, 1,0,0, 1,1,0,  1,0,0,  0.6F}
        };
        float[][] uvs = {{0,0, 1,0, 1,1, 0,1}, {0,0, 1,0, 1,1, 0,1}, {0,0, 0,1, 1,1, 1,0},
            {0,0, 0,1, 1,1, 1,0}, {0,0, 0,1, 1,1, 1,0}, {0,0, 0,1, 1,1, 1,0}};

        int[] order = {0, 1, 2, 0, 2, 3};
        float[] pos = new float[36 * 3];
        float[] col = new float[36 * 4];
        float[] uv = new float[36 * 2];
        float[] nrm = new float[36 * 3];
        int o = 0;

        for (int f = 0; f < 6; f++)
        {
            float[] face = faces[f];
            float shade = face[15];

            for (int k : order)
            {
                pos[o * 3] = face[k * 3];
                pos[o * 3 + 1] = face[k * 3 + 1];
                pos[o * 3 + 2] = face[k * 3 + 2];
                col[o * 4] = shade; col[o * 4 + 1] = shade; col[o * 4 + 2] = shade; col[o * 4 + 3] = 1F;
                uv[o * 2] = uvs[f][k * 2];
                uv[o * 2 + 1] = uvs[f][k * 2 + 1];
                nrm[o * 3] = face[12]; nrm[o * 3 + 1] = face[13]; nrm[o * 3 + 2] = face[14];
                o++;
            }
        }

        return new float[][] {pos, col, uv, nrm};
    }
}
