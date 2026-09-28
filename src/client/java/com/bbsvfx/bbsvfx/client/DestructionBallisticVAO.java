package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import org.joml.Vector3f;
import org.lwjgl.opengl.ARBDrawInstanced;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;
import com.bbsvfx.bbsvfx.forms.DestructionBlock;
import com.bbsvfx.bbsvfx.forms.DestructionBoxForm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier-C debris: the far bulk of a big (50-100k) destruction/explosion, rendered as ONE instanced draw
 * of a unit cube. Per-instance attributes carry the whole closed-form flight — rest position, launch
 * velocity (the same Direction+Radial+Random+Explosion recipe the sim uses), tumble axis+rate, landing
 * time against the structure's base plane, the block's PARTICLE sprite UV rect and biome tint — and the
 * {@code destruction_ballistic} vertex shader evaluates position(t) from two uniforms (time, gravity).
 * Zero per-frame CPU geometry work, ~70 bytes per block of VBO: this is what makes city-block scans
 * real-time. Scrubbing is exact by construction (closed form, no integration).
 *
 * <p>LOD approximation: every far block renders as a full textured cube (its particle sprite), not its
 * exact model — stairs/slabs read fine at tier-C distances. Vanilla-only (a custom core shader is not
 * picked up by Iris); under a pack the caller uses {@link DestructionStaticVAO} instead.</p>
 */
public class DestructionBallisticVAO
{
    private static final int MAX_CACHED = 3;

    private static final LinkedHashMap<Long, DestructionBallisticVAO> CACHE = new LinkedHashMap<>(8, 0.75F, true);

    /* Attribute locations resolved from the LINKED program (glGetAttribLocation): assuming the json
     * "attributes" order maps to 0..N is not reliable for the custom (non-vertex-format) attributes —
     * a mismatched location feeds the wrong buffer to IMisc/IVel and the flight turns to garbage. */
    private int aPosition, aColor, aUv0, aUv1, aUv2, aNormal;
    private int aOrig, aVel, aSpin, aMisc, aUvRect, aTint;

    private int vao = -1;
    private int cubePos, cubeColor, cubeUv, cubeNormal;
    private int instOrig, instVel, instSpin, instMisc, instUvRect, instTint;
    private int instances;

    /** Landing columns that were UNLOADED at build time (world join): their landing is provisional
     *  (base plane) and the VBO re-builds every few seconds until the heightmap sees them all. */
    private int unloadedColumns;
    private long builtAt;

    /* Wind parameters (from the explosion form), stashed at build: the CPU landing march and the
     * shader uniforms must agree on the exact same field or debris lands beside its visual spot. */
    private float wEx, wEy, wEz, wStr, wFront = 25F, wDecay = 0.8F, wAmbX, wAmbZ;

    /**
     * Instancing availability: MC 1.20.x asks GLFW for an OpenGL 3.2 CORE context, so the GL 3.3 core
     * {@code glVertexAttribDivisor} is NOT callable (LWJGL aborts the JVM — the Ф1 test crash) — but
     * virtually every driver exposes the same entry points via the ARB extensions. Checked once.
     */
    private static Boolean supported;

    public static boolean isSupported()
    {
        if (supported == null)
        {
            GLCapabilities caps = GL.getCapabilities();

            supported = (caps.OpenGL33 || caps.GL_ARB_instanced_arrays)
                && (caps.OpenGL31 || caps.GL_ARB_draw_instanced);

            if (!supported)
            {
                System.err.println("[bbsvfx] Destruction ballistic path unavailable: no instancing "
                    + "(GL33/ARB_instanced_arrays + GL31/ARB_draw_instanced) — falling back to the static shell");
            }
        }

        return supported;
    }

    /**
     * Fetch (or build) the instanced VBO for this tier-C set. Null when the build failed.
     *
     * <p>{@code world}/{@code ox,oy,oz} (the structure's world origin) enable TERRAIN-AWARE landing:
     * debris freezes on the real surface height at its landing column instead of hovering on the
     * structure's base plane ("invisible floor" over lower ground). Pass a null world (form-editor
     * preview) for the plane-only behaviour.</p>
     */
    public static DestructionBallisticVAO of(long key, DestructionBoxForm form, List<DestructionBlock> allBlocks,
        int[] tierC, float groundY, net.minecraft.world.World world, double ox, double oy, double oz)
    {
        if (!isSupported())
        {
            return null;
        }

        DestructionBallisticVAO cached = CACHE.get(key);

        if (cached != null)
        {
            /* A build made while the surrounding chunks were still streaming in (world join) solved
             * those landings against the base plane — retry every few seconds until clean. */
            if (cached.unloadedColumns > 0 && world != null
                && System.currentTimeMillis() - cached.builtAt > 4000L)
            {
                CACHE.remove(key);
                cached.delete();
            }
            else
            {
                return cached;
            }
        }

        DestructionBallisticVAO built = new DestructionBallisticVAO();

        try
        {
            built.build(form, allBlocks, tierC, groundY, world, ox, oy, oz);
        }
        catch (Exception e)
        {
            System.err.println("[bbsvfx] Destruction ballistic VAO build failed: " + e);
            built.delete();

            return null;
        }

        CACHE.put(key, built);

        while (CACHE.size() > MAX_CACHED)
        {
            Map.Entry<Long, DestructionBallisticVAO> eldest = CACHE.entrySet().iterator().next();

            eldest.getValue().delete();
            CACHE.remove(eldest.getKey());
        }

        return built;
    }

    private void build(DestructionBoxForm form, List<DestructionBlock> allBlocks, int[] tierC, float groundY,
        net.minecraft.world.World world, double ox, double oy, double oz)
    {
        int n = tierC.length;
        float gravity = form.physGravity.get();
        Vector3f center = form.center();
        Vector3f scratch = new Vector3f();

        if (form instanceof com.bbsvfx.bbsvfx.forms.ExplosionForm explosion)
        {
            Vector3f epic = form.point();

            this.wEx = epic.x;
            this.wEy = epic.y;
            this.wEz = epic.z;
            this.wStr = explosion.windStrength.get();
            this.wFront = Math.max(1e-4F, explosion.windFrontSpeed.get());
            this.wDecay = Math.max(0.05F, explosion.windDecay.get());
            this.wAmbX = explosion.windAmbientX();
            this.wAmbZ = explosion.windAmbientZ();
        }

        /* Per-COLUMN floor of the captured region (form-local): for every (x,z) column the LOWEST
         * captured y = the crater/structure floor there once the cut lands. Landing inside the cut
         * uses this profile — a flat "base plane" was wrong for sphere captures (the sphere's minY is
         * the crater's deepest point; freezing the whole footprint at that depth sank most debris out
         * of sight — the "falls into the void" report). Also race-proof: no world reads. */
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap colFloor = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(allBlocks.size() / 8);

        colFloor.defaultReturnValue(Integer.MIN_VALUE);

        for (DestructionBlock block : allBlocks)
        {
            long col = ((long) block.x.get() << 32) ^ (block.z.get() & 0xFFFFFFFFL);
            int floor = colFloor.get(col);
            int y = block.y.get();

            if (floor == Integer.MIN_VALUE || y < floor)
            {
                colFloor.put(col, y);
            }
        }

        float[] orig = new float[n * 3];
        float[] vel = new float[n * 3];
        float[] spin = new float[n * 4];
        float[] misc = new float[n * 2];
        float[] uvRect = new float[n * 4];
        float[] tint = new float[n * 4];

        MinecraftClient mc = MinecraftClient.getInstance();

        for (int k = 0; k < n; k++)
        {
            int i = tierC[k];
            DestructionBlock block = allBlocks.get(i);
            float bx = block.x.get(), by = block.y.get(), bz = block.z.get();

            orig[k * 3] = bx;
            orig[k * 3 + 1] = by;
            orig[k * 3 + 2] = bz;

            /* Launch velocity — full-list index keeps the per-block randomness stable however the tier
             * split falls. */
            scratch.set(bx, by, bz);

            Vector3f v = form.initialVelocity(i, scratch, center);

            vel[k * 3] = v.x;
            vel[k * 3 + 1] = v.y;
            vel[k * 3 + 2] = v.z;

            /* Tumble: axis + angular speed (rad/s), same recipe the sim launches with. */
            Vector3f ang = form.initialAngularVelocity(i);
            float rate = ang.length();

            if (rate > 1e-5F)
            {
                spin[k * 4] = ang.x / rate;
                spin[k * 4 + 1] = ang.y / rate;
                spin[k * 4 + 2] = ang.z / rate;
                spin[k * 4 + 3] = rate;
            }
            else
            {
                spin[k * 4 + 1] = 1F;
            }

            /* Landing = the FIRST ground crossing along the trajectory, found by marching the parabola
             * (0.15s steps + 3 bisection refinements). This stops debris at mid-flight obstacles —
             * hills, walls, roofs in the path — instead of phasing through and only landing at the
             * final column. Ground per column = the captured region's own floor profile inside the cut
             * (colFloor — also race-safe), the world heightmap outside it, the base plane in preview. */
            float tLand = 1e9F;

            if (gravity > 1e-5F)
            {
                /* March no further than the fall to well below any plausible ground. */
                float tCap = Math.min(12F, landTime(by, v.y, gravity, groundY - 96F));
                float prev = 0F;

                for (float tt = 0.15F; tt <= tCap; tt += 0.15F)
                {
                    if (this.trajectoryBelowGround(bx, by, bz, v, gravity, tt, colFloor, world, ox, oy, oz, groundY))
                    {
                        /* Crossed between prev and tt — bisect to the impact time. */
                        float lo = prev, hi = tt;

                        for (int r = 0; r < 3; r++)
                        {
                            float mid = (lo + hi) * 0.5F;

                            if (this.trajectoryBelowGround(bx, by, bz, v, gravity, mid, colFloor, world, ox, oy, oz, groundY))
                            {
                                hi = mid;
                            }
                            else
                            {
                                lo = mid;
                            }
                        }

                        tLand = hi;

                        break;
                    }

                    prev = tt;
                }
            }

            misc[k * 2] = tLand;

            /* Per-block hash → the shader jitters restitution/friction per block, so the field's
             * landings don't play in lockstep (the "not physical" tell against the PhysX tier). */
            int hh = (i ^ 0x9e3779b9) * 0x85ebca6b;

            hh ^= hh >>> 13;
            misc[k * 2 + 1] = (hh & 0xFFFF) / (float) 0xFFFF;

            /* The block's particle sprite (what vanilla break particles use) as the cube texture. */
            Sprite sprite = mc.getBlockRenderManager().getModels().getModelParticleSprite(block.blockState());

            uvRect[k * 4] = sprite.getMinU();
            uvRect[k * 4 + 1] = sprite.getMinV();
            uvRect[k * 4 + 2] = sprite.getMaxU() - sprite.getMinU();
            uvRect[k * 4 + 3] = sprite.getMaxV() - sprite.getMinV();

            int t = block.tint.get();

            if (t >= 0)
            {
                tint[k * 4] = (t >> 16 & 255) / 255F;
                tint[k * 4 + 1] = (t >> 8 & 255) / 255F;
                tint[k * 4 + 2] = (t & 255) / 255F;
                tint[k * 4 + 3] = 1F;
            }
            else
            {
                tint[k * 4] = 1F;
                tint[k * 4 + 1] = 1F;
                tint[k * 4 + 2] = 1F;
                tint[k * 4 + 3] = 1F;
            }
        }

        this.instances = n;

        /* Resolve the REAL attribute locations from the linked program. The per-instance ones are
         * mandatory — a missing (optimized-out) one means the shader can't do its job. */
        int program = DestructionShader.BALLISTIC.getGlRef();

        this.aPosition = GL20.glGetAttribLocation(program, "Position");
        this.aColor = GL20.glGetAttribLocation(program, "Color");
        this.aUv0 = GL20.glGetAttribLocation(program, "UV0");
        this.aUv1 = GL20.glGetAttribLocation(program, "UV1");
        this.aUv2 = GL20.glGetAttribLocation(program, "UV2");
        this.aNormal = GL20.glGetAttribLocation(program, "Normal");
        this.aOrig = GL20.glGetAttribLocation(program, "IOrig");
        this.aVel = GL20.glGetAttribLocation(program, "IVel");
        this.aSpin = GL20.glGetAttribLocation(program, "ISpin");
        this.aMisc = GL20.glGetAttribLocation(program, "IMisc");
        this.aUvRect = GL20.glGetAttribLocation(program, "IUvRect");
        this.aTint = GL20.glGetAttribLocation(program, "ITint");

        if (this.aOrig < 0 || this.aVel < 0 || this.aSpin < 0 || this.aMisc < 0 || this.aUvRect < 0 || this.aTint < 0)
        {
            throw new IllegalStateException("instance attribute missing in destruction_ballistic (locations: "
                + this.aOrig + "," + this.aVel + "," + this.aSpin + "," + this.aMisc + "," + this.aUvRect + "," + this.aTint + ")");
        }

        /* One-shot sanity stats for the landing solver (tLand drives the whole look). */
        float tMin = Float.POSITIVE_INFINITY, tMax = 0F;
        double tSum = 0D;
        int flyOff = 0;

        for (int k = 0; k < n; k++)
        {
            float tl = misc[k * 2];

            if (tl >= 1e8F)
            {
                flyOff++;

                continue;
            }

            tMin = Math.min(tMin, tl);
            tMax = Math.max(tMax, tl);
            tSum += tl;
        }

        this.builtAt = System.currentTimeMillis();

        System.out.println("[bbsvfx] ballistic VBO: " + n + " instances, tLand min/avg/max = "
            + String.format("%.2f/%.2f/%.2f", tMin, n > flyOff ? tSum / (n - flyOff) : 0D, tMax)
            + "s, flyOff = " + flyOff + ", unloaded cols = " + this.unloadedColumns
            + (this.unloadedColumns > 0 ? " (provisional, will rebuild)" : ""));

        int previousVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        this.vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(this.vao);

        /* Shared unit-cube geometry (divisor 0). */
        float[][] cube = unitCube();

        this.cubePos = attrib(this.aPosition, 3, cube[0], 0);
        this.cubeColor = attrib(this.aColor, 4, cube[1], 0);
        this.cubeUv = attrib(this.aUv0, 2, cube[2], 0);
        this.cubeNormal = attrib(this.aNormal, 3, cube[3], 0);

        /* Per-instance attributes (divisor 1). */
        this.instOrig = attrib(this.aOrig, 3, orig, 1);
        this.instVel = attrib(this.aVel, 3, vel, 1);
        this.instSpin = attrib(this.aSpin, 4, spin, 1);
        this.instMisc = attrib(this.aMisc, 2, misc, 1);
        this.instUvRect = attrib(this.aUvRect, 4, uvRect, 1);
        this.instTint = attrib(this.aTint, 4, tint, 1);

        GL30.glBindVertexArray(previousVao);
        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /**
     * Whether the parabola point at time {@code t} sits at/below the ground of ITS column: the cut
     * region's floor profile (colFloor) inside the capture, the world heightmap outside it, the base
     * plane without a world. Unloaded columns count toward the provisional rebuild and fall back to
     * the plane.
     */
    /** Wind displacement at flight time t — MUST mirror windDisp() in destruction_ballistic.vsh. */
    private Vector3f windDisp(float bx, float by, float bz, float t, Vector3f out)
    {
        float rx = bx + 0.5F - this.wEx;
        float ry = by + 0.5F - this.wEy;
        float rz = bz + 0.5F - this.wEz;
        float d = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        float tw = Math.max(t - d / this.wFront, 0F);
        float wmag = this.wStr * this.wDecay * (tw - this.wDecay * (1F - (float) Math.exp(-tw / this.wDecay)));

        if (d > 1e-4F)
        {
            out.set(rx / d * wmag, ry / d * wmag, rz / d * wmag);
        }
        else
        {
            out.set(0F, wmag, 0F);
        }

        out.x += 0.5F * this.wAmbX * t * t;
        out.z += 0.5F * this.wAmbZ * t * t;

        return out;
    }

    private final Vector3f windScratch = new Vector3f();

    private boolean trajectoryBelowGround(float bx, float by, float bz, Vector3f v, float gravity, float t,
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap colFloor, net.minecraft.world.World world,
        double ox, double oy, double oz, float groundY)
    {
        Vector3f wd = this.wStr != 0F || this.wAmbX != 0F || this.wAmbZ != 0F
            ? this.windDisp(bx, by, bz, t, this.windScratch)
            : this.windScratch.set(0F, 0F, 0F);
        float y = by + v.y * t - 0.5F * gravity * t * t + wd.y;
        float lx = bx + 0.5F + v.x * t + wd.x;
        float lz = bz + 0.5F + v.z * t + wd.z;
        long col = ((long) (int) Math.floor(lx) << 32) ^ ((int) Math.floor(lz) & 0xFFFFFFFFL);
        int cut = colFloor.get(col);
        float ground;

        if (cut != Integer.MIN_VALUE)
        {
            ground = cut;
        }
        else if (world != null)
        {
            int wx = (int) Math.floor(ox + lx);
            int wz = (int) Math.floor(oz + lz);
            /* MOTION_BLOCKING, not NO_LEAVES: the server only syncs MOTION_BLOCKING + WORLD_SURFACE
             * heightmaps to the client — NO_LEAVES reads as permanently empty here. */
            int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, wx, wz);

            if (top <= world.getBottomY() + 1)
            {
                this.unloadedColumns++;
                ground = groundY;
            }
            else
            {
                ground = (float) (top - oy);
            }
        }
        else
        {
            ground = groundY;
        }

        return y <= ground;
    }

    /** Time at which a block launched at (y0, vy) under gravity g (down) crosses the ground plane. */
    static float landTime(float y0, float vy, float g, float groundY)
    {
        if (g <= 1e-5F)
        {
            /* No (or anti-) gravity: never lands. */
            return 1e9F;
        }

        float drop = Math.max(0F, y0 - groundY);
        float disc = vy * vy + 2F * g * drop;

        return (vy + (float) Math.sqrt(disc)) / g;
    }

    private static int attrib(int location, int size, float[] data, int divisor)
    {
        if (location < 0)
        {
            /* Optimized-out non-essential attribute — nothing to feed. */
            return -1;
        }

        int buffer = GL30.glGenBuffers();

        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(location, size, GL15.GL_FLOAT, false, 0, 0);

        if (divisor > 0)
        {
            /* 3.2-core context: the GL33 core entry point is absent — use the ARB alias (same GPU
             * feature, same signature; see isSupported). */
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

    /** One draw for the whole tier: uniforms carry the scrub time + gravity + bounce, the VSH does the rest. */
    public void render(MatrixStack stack, float time, float gravity, float bounce, int light, int overlay)
    {
        ShaderProgram shader = DestructionShader.BALLISTIC;

        if (this.vao == -1 || this.instances == 0 || shader == null)
        {
            return;
        }

        int prevVAO = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevElem = GL30.glGetInteger(GL30.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        RenderLayer layer = RenderLayer.getEntityCutout(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);

        layer.startDrawing();

        ModelVAORenderer.setupUniforms(stack, shader);
        setF(shader, "uTime", time);
        setF(shader, "uGravity", gravity);
        setF(shader, "uBounce", bounce);

        /* Wind uniforms come from the values STASHED at build — the CPU landing march used exactly
         * these, so the visual flight and the precomputed landings agree. */
        GlUniform epicenter = shader.getUniform("uEpicenter");

        if (epicenter != null)
        {
            epicenter.set(this.wEx, this.wEy, this.wEz);
        }

        setF(shader, "uWindStr", this.wStr);
        setF(shader, "uWindFront", this.wFront);
        setF(shader, "uWindDecay", this.wDecay);

        GlUniform ambient = shader.getUniform("uWindAmb");

        if (ambient != null)
        {
            ambient.set(this.wAmbX, this.wAmbZ);
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
        int[] locations = {this.aPosition, this.aColor, this.aUv0, this.aNormal,
            this.aOrig, this.aVel, this.aSpin, this.aMisc, this.aUvRect, this.aTint};

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
            GL15.glDeleteBuffers(this.instVel);
            GL15.glDeleteBuffers(this.instSpin);
            GL15.glDeleteBuffers(this.instMisc);
            GL15.glDeleteBuffers(this.instUvRect);
            GL15.glDeleteBuffers(this.instTint);
            this.vao = -1;
        }
    }

    /**
     * Unit cube: 6 faces × 2 triangles, positions in 0..1, per-face 0..1 UVs, axis normals. Colours
     * carry vanilla's per-face directional shade (same constants the block renderer uses) so the cubes
     * match neighbouring intact blocks instead of looking flat.
     */
    private static float[][] unitCube()
    {
        /* Face: 4 corners (x,y,z each) + normal; corners ordered so both triangles wind CCW. */
        float[][] faces = {
            /* down  (y=0), shade 0.5 */ {0,0,0, 1,0,0, 1,0,1, 0,0,1,  0,-1,0, 0.5F},
            /* up    (y=1), shade 1.0 */ {0,1,1, 1,1,1, 1,1,0, 0,1,0,  0,1,0,  1.0F},
            /* north (z=0), shade 0.8 */ {1,1,0, 1,0,0, 0,0,0, 0,1,0,  0,0,-1, 0.8F},
            /* south (z=1), shade 0.8 */ {0,1,1, 0,0,1, 1,0,1, 1,1,1,  0,0,1,  0.8F},
            /* west  (x=0), shade 0.6 */ {0,1,0, 0,0,0, 0,0,1, 0,1,1,  -1,0,0, 0.6F},
            /* east  (x=1), shade 0.6 */ {1,1,1, 1,0,1, 1,0,0, 1,1,0,  1,0,0,  0.6F}
        };
        /* Per-corner UVs matching the order above. */
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
                col[o * 4] = shade;
                col[o * 4 + 1] = shade;
                col[o * 4 + 2] = shade;
                col[o * 4 + 3] = 1F;
                uv[o * 2] = uvs[f][k * 2];
                uv[o * 2 + 1] = uvs[f][k * 2 + 1];
                nrm[o * 3] = face[12];
                nrm[o * 3 + 1] = face[13];
                nrm[o * 3 + 2] = face[14];
                o++;
            }
        }

        return new float[][] {pos, col, uv, nrm};
    }
}
