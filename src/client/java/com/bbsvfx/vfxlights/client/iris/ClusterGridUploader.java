package com.bbsvfx.vfxlights.client.iris;

import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

/**
 * The screen-tile cluster grid for the patched-pack light loop — the answer to "every pixel
 * shades every lamp". The screen is cut into a FIXED {@value #GRID_X}×{@value #GRID_Y} tile
 * grid (resolution-independent; ~60 px tiles at 1080p), and each tile carries a 64-bit bitmask
 * of which packed lamps' influence spheres reach it, built on the CPU from the exact list
 * {@link PackLightUploader} just shipped (same order, so bit i == SSBO lamp i). The shader
 * tests one bit per lamp before fetching a single slot; the picture is bit-identical to the
 * full loop, so the grid is always on when a pack is patched.
 *
 * <p>Design follows qualet's irl-core ClusterGridBuffer (MIT) — 2D tiles, CPU rasterisation,
 * bitmask rather than index lists — reimplemented against this mod's upload. Their hard-won
 * subtleties kept here: conservative projection (8 AABB corners of the influence sphere, a
 * full flood when the camera is inside or the sphere crosses the near plane, 1 tile of slack
 * so the boundary never shows), and float chunks for GLSL-120 packs (no uints: exact integers
 * stop at 2^24, so the 64-bit mask is three 24-bit float chunks, decoded like the group
 * filter's bits).</p>
 *
 * <p>Kill-switch {@code -Dvfxlights.cluster.off}: the header uploads active=0 and the shader
 * runs the pre-cluster full loop, bit-identical — the bisect hatch for any mask mismatch.</p>
 */
public final class ClusterGridUploader
{
    /** Binding point for the cluster SSBO. 5 is our lights, 6/7 are IRLite's — stay clear. */
    public static final int BINDING = 8;
    public static final int GRID_X = 32;
    public static final int GRID_Y = 18;
    private static final int TILES = GRID_X * GRID_Y;
    /** Bits per float chunk: the exact-integer ceiling of a float, same rule as the group mask. */
    private static final int CHUNK_BITS = 24;
    private static final int CHUNKS = 3;
    /** Flood margin: a sphere this close across the near plane lights every tile (conservatism
     *  the volumetric march needs too — its segment starts AT the eye). */
    private static final float NEAR_FLOOD_MARGIN = 1.0F;
    /** Tile of slack around every projected rect: the mask must be a superset of the truth. */
    private static final int TILE_SLACK = 1;

    private static final boolean OFF = System.getProperty("vfxlights.cluster.off") != null;

    private static int buffer = -1;
    private static java.nio.FloatBuffer scratch;

    /* The recorded packed lamps, camera-relative (the upload's own space). */
    private static final float[] posX = new float[PackLightUploader.MAX_LIGHTS];
    private static final float[] posY = new float[PackLightUploader.MAX_LIGHTS];
    private static final float[] posZ = new float[PackLightUploader.MAX_LIGHTS];
    private static final float[] range = new float[PackLightUploader.MAX_LIGHTS];
    private static int count;

    /** Tile bitmasks as 24-bit chunks — int until the float cast at upload. */
    private static final int[][] masks = new int[TILES][CHUNKS];

    private static final Vector3f viewCorner = new Vector3f();
    private static final Vector4f clip = new Vector4f();

    private ClusterGridUploader()
    {
    }

    /** Start the frame's recording — before {@link PackLightUploader}'s packing loop. */
    public static void begin()
    {
        count = 0;
    }

    /** Record one packed lamp, camera-relative, at its SSBO index. */
    public static void record(float rx, float ry, float rz, float lampRange, int index)
    {
        posX[index] = rx;
        posY[index] = ry;
        posZ[index] = rz;
        range[index] = lampRange;
        count = Math.max(count, index + 1);
    }

    /**
     * Rasterise the recorded lamps into tile bitmasks and ship the grid. {@code view} is the
     * world render's live model-view (bob and all — the mask must agree with what the pack
     * shades) and {@code projection} the frame's live projection; both come from the frame-START
     * world render context, the same matrices the beam scissor learned to trust.
     */
    public static void upload(Matrix4f view, Matrix4f projection)
    {
        if (!PackLightUploader.isSupported())
        {
            return;
        }

        if (buffer == -1)
        {
            buffer = GL15.glGenBuffers();
            scratch = BufferUtils.createFloatBuffer(4 + TILES * 4);
        }

        boolean active = !OFF && count > 0;

        for (int t = 0; t < TILES; t++)
        {
            masks[t][0] = 0;
            masks[t][1] = 0;
            masks[t][2] = 0;
        }

        if (active)
        {
            for (int i = 0; i < count; i++)
            {
                rasterize(i, view, projection);
            }
        }

        scratch.clear();
        scratch.put(GRID_X).put(GRID_Y).put(active ? 1F : 0F).put(0F);

        for (int t = 0; t < TILES; t++)
        {
            scratch.put(masks[t][0]).put(masks[t][1]).put(masks[t][2]).put(0F);
        }

        scratch.flip();

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, scratch, GL15.GL_STREAM_DRAW);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BINDING, buffer);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    /** One lamp's influence sphere into the tiles it can touch — conservatively a superset. */
    private static void rasterize(int index, Matrix4f view, Matrix4f projection)
    {
        /* Rotation only: the positions are already camera-relative, the view matrix' translation
         * would double-count the camera. */
        float vx = view.m00() * posX[index] + view.m10() * posY[index] + view.m20() * posZ[index];
        float vy = view.m01() * posX[index] + view.m11() * posY[index] + view.m21() * posZ[index];
        float vz = view.m02() * posX[index] + view.m12() * posY[index] + view.m22() * posZ[index];
        float r = Math.max(range[index], 0.05F);

        float distSq = vx * vx + vy * vy + vz * vz;

        /* Camera inside the sphere, or the sphere across the near plane: the rect cannot be
         * trusted, and the beam starting at the eye must find the lamp in every tile anyway. */
        if (distSq < (r * 1.05F + NEAR_FLOOD_MARGIN) * (r * 1.05F + NEAR_FLOOD_MARGIN)
            || vz + r > -NEAR_FLOOD_MARGIN)
        {
            setBitsAllTiles(index);

            return;
        }

        float minU = 2F;
        float minV = 2F;
        float maxU = -2F;
        float maxV = -2F;

        for (int corner = 0; corner < 8; corner++)
        {
            float cx = vx + ((corner & 1) == 0 ? -r : r);
            float cy = vy + ((corner & 2) == 0 ? -r : r);
            float cz = vz + ((corner & 4) == 0 ? -r : r);

            clip.set(cx, cy, cz, 1F);
            projection.transform(clip);

            if (clip.w < 0.001F)
            {
                /* A corner slipped behind the eye — flood rather than guess. */
                setBitsAllTiles(index);

                return;
            }

            float u = clip.x / clip.w * 0.5F + 0.5F;
            float v = clip.y / clip.w * 0.5F + 0.5F;

            minU = Math.min(minU, u);
            minV = Math.min(minV, v);
            maxU = Math.max(maxU, u);
            maxV = Math.max(maxV, v);
        }

        int x0 = Math.max(0, (int) Math.floor(minU * GRID_X) - TILE_SLACK);
        int y0 = Math.max(0, (int) Math.floor(minV * GRID_Y) - TILE_SLACK);
        int x1 = Math.min(GRID_X - 1, (int) Math.floor(maxU * GRID_X) + TILE_SLACK);
        int y1 = Math.min(GRID_Y - 1, (int) Math.floor(maxV * GRID_Y) + TILE_SLACK);

        if (x1 < x0 || y1 < y0)
        {
            return;
        }

        for (int ty = y0; ty <= y1; ty++)
        {
            for (int tx = x0; tx <= x1; tx++)
            {
                setBit(ty * GRID_X + tx, index);
            }
        }
    }

    private static void setBitsAllTiles(int index)
    {
        for (int t = 0; t < TILES; t++)
        {
            setBit(t, index);
        }
    }

    private static void setBit(int tile, int index)
    {
        masks[tile][index / CHUNK_BITS] |= 1 << (index % CHUNK_BITS);
    }
}
