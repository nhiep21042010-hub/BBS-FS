package com.bbsvfx.bbsvfx.client;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Draws ONE sector (octant) of a full-cube block as the actual PIECE of the block's texture: every
 * exterior face is the block's face quad cropped to the octant's quarter (UVs interpolated from the
 * baked quad, so orientation/rotation of the sprite is preserved and the 8 sectors reassemble
 * pixel-exact), and the three interior cut faces reuse the parallel exterior face's crop — a stone
 * sector looks like broken stone, not like a tiny whole block.
 *
 * <p>Only handles models whose six faces are each a SINGLE full-face quad (the classic full cube —
 * stone, planks, bricks, ...). Anything else (slabs, stairs, cross models) reports {@code canDraw}
 * false and the renderer falls back to the S1 half-scale model.</p>
 */
public final class DestructionSectorDraw
{
    /**
     * Per state: 6 faces × 4 corners × (u,v) in a canonical face parameterization, plus a per-face
     * tint flag. Empty optional = model not sector-sliceable.
     */
    private static final Map<BlockState, Optional<Faces>> FACES = new HashMap<>();

    /** In-plane axes per direction, chosen right-handed so that uAxis × vAxis = the face normal. */
    private static final int[][] FACE_AXES = new int[6][];

    static
    {
        FACE_AXES[Direction.WEST.ordinal()] = new int[] {2, 1};  // -x: u=z, v=y
        FACE_AXES[Direction.EAST.ordinal()] = new int[] {1, 2};  // +x: u=y, v=z
        FACE_AXES[Direction.DOWN.ordinal()] = new int[] {0, 2};  // -y: u=x, v=z
        FACE_AXES[Direction.UP.ordinal()] = new int[] {2, 0};    // +y: u=z, v=x
        FACE_AXES[Direction.NORTH.ordinal()] = new int[] {1, 0}; // -z: u=y, v=x
        FACE_AXES[Direction.SOUTH.ordinal()] = new int[] {0, 1}; // +z: u=x, v=y
    }

    private record Faces(float[][] uv, boolean[] tinted)
    {
    }

    private DestructionSectorDraw()
    {
    }

    /** Whether this state can be rendered as honest texture pieces (else use the half-scale fallback). */
    public static boolean canDraw(BlockState state)
    {
        return faces(state).isPresent();
    }

    /**
     * Emit the sector's 6 quads in BLOCK space (the octant's corner of [0,1]³) through {@code entry}.
     * Octant bits: 1 = +x half, 2 = +y half, 4 = +z half.
     */
    public static boolean draw(BlockState state, int tint, int octant, MatrixStack.Entry entry, VertexConsumerProvider provider,
        int light, int overlay)
    {
        Optional<Faces> maybe = faces(state);

        if (maybe.isEmpty())
        {
            return false;
        }

        Faces faces = maybe.get();
        VertexConsumer vc = provider.getBuffer(RenderLayers.getEntityBlockLayer(state, false));

        float tr = ((tint >> 16) & 0xFF) / 255F;
        float tg = ((tint >> 8) & 0xFF) / 255F;
        float tb = (tint & 0xFF) / 255F;

        for (Direction dir : Direction.values())
        {
            int normalAxis = dir.getAxis().ordinal();
            boolean positive = dir.getDirection() == Direction.AxisDirection.POSITIVE;
            boolean high = (octant & (1 << normalAxis)) != 0;

            /* Exterior face when the octant touches this side of the block, else the interior cut
             * plane at 0.5 (facing outward from the sector, textured like the parallel exterior). */
            boolean exterior = high == positive;
            float plane = exterior ? (positive ? 1F : 0F) : 0.5F;

            int[] axes = FACE_AXES[dir.ordinal()];
            float a0 = (octant & (1 << axes[0])) != 0 ? 0.5F : 0F;
            float b0 = (octant & (1 << axes[1])) != 0 ? 0.5F : 0F;
            float[] uv = faces.uv[dir.ordinal()];
            boolean tinted = faces.tinted[dir.ordinal()];
            float r = tinted ? tr : 1F, g = tinted ? tg : 1F, b = tinted ? tb : 1F;

            emitVertex(vc, entry, dir, plane, a0, b0, uv, r, g, b, light, overlay);
            emitVertex(vc, entry, dir, plane, a0 + 0.5F, b0, uv, r, g, b, light, overlay);
            emitVertex(vc, entry, dir, plane, a0 + 0.5F, b0 + 0.5F, uv, r, g, b, light, overlay);
            emitVertex(vc, entry, dir, plane, a0, b0 + 0.5F, uv, r, g, b, light, overlay);
        }

        return true;
    }

    private static void emitVertex(VertexConsumer vc, MatrixStack.Entry entry, Direction dir, float plane,
        float a, float b, float[] uv, float r, float g, float bl, int light, int overlay)
    {
        int[] axes = FACE_AXES[dir.ordinal()];
        float[] pos = new float[3];

        pos[dir.getAxis().ordinal()] = plane;
        pos[axes[0]] = a;
        pos[axes[1]] = b;

        /* Bilinear UV over the face's stored corner UVs — preserves whatever sprite orientation the
         * model baked in. Corner index = (aHigh) | (bHigh << 1). */
        float u = lerp(lerp(uv[0], uv[2], a), lerp(uv[4], uv[6], a), b);
        float v = lerp(lerp(uv[1], uv[3], a), lerp(uv[5], uv[7], a), b);

        /* Transform the normal ourselves: the Matrix3f overload of normal() was removed in 1.21. */
        org.joml.Vector3f n = new org.joml.Vector3f(dir.getOffsetX(), dir.getOffsetY(), dir.getOffsetZ());

        entry.getNormalMatrix().transform(n);
        BbsVfxRenderCompat.next(vc.vertex(entry.getPositionMatrix(), pos[0], pos[1], pos[2])
            .color(r, g, bl, 1F)
            .texture(u, v)
            .overlay(overlay)
            .light(light)
            .normal(n.x, n.y, n.z));
    }

    private static float lerp(float from, float to, float t)
    {
        return from + (to - from) * t;
    }

    private static Optional<Faces> faces(BlockState state)
    {
        Optional<Faces> cached = FACES.get(state);

        if (cached != null)
        {
            return cached;
        }

        Optional<Faces> built = Optional.ofNullable(build(state));

        FACES.put(state, built);

        return built;
    }

    /** Null when any face isn't exactly one full-face quad (then the state can't be sector-sliced). */
    private static Faces build(BlockState state)
    {
        if (!DestructionBlockDraw.isRenderable(state))
        {
            return null;
        }

        BakedModel model = MinecraftClient.getInstance().getBlockRenderManager().getModel(state);
        Random random = Random.create(42L);
        float[][] uv = new float[6][];
        boolean[] tinted = new boolean[6];

        for (Direction dir : Direction.values())
        {
            List<BakedQuad> quads = model.getQuads(state, dir, random);

            if (quads.size() != 1)
            {
                return null;
            }

            BakedQuad quad = quads.get(0);
            int[] data = quad.getVertexData();

            if (data.length < 32)
            {
                return null;
            }

            int[] axes = FACE_AXES[dir.ordinal()];
            float[] corners = new float[8];
            int seen = 0;

            for (int i = 0; i < 4; i++)
            {
                float x = Float.intBitsToFloat(data[i * 8]);
                float y = Float.intBitsToFloat(data[i * 8 + 1]);
                float z = Float.intBitsToFloat(data[i * 8 + 2]);
                float qu = Float.intBitsToFloat(data[i * 8 + 4]);
                float qv = Float.intBitsToFloat(data[i * 8 + 5]);
                float[] pos = {x, y, z};
                float a = pos[axes[0]];
                float b = pos[axes[1]];

                /* Full-face quads only: every vertex must sit on a corner of the [0,1]² face. */
                if (a > 0.01F && a < 0.99F || b > 0.01F && b < 0.99F)
                {
                    return null;
                }

                int corner = (a > 0.5F ? 1 : 0) | (b > 0.5F ? 2 : 0);

                corners[corner * 2] = qu;
                corners[corner * 2 + 1] = qv;
                seen |= 1 << corner;
            }

            if (seen != 0b1111)
            {
                return null;
            }

            uv[dir.ordinal()] = corners;
            tinted[dir.ordinal()] = quad.hasColor();
        }

        return new Faces(uv, tinted);
    }
}
