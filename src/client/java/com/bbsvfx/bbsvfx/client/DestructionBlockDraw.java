package com.bbsvfx.bbsvfx.client;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Renders one captured block for the Destruction Box, shared by the GPU bake ({@link DestructionVAO}) and
 * the CPU path ({@link DestructionBoxFormRenderer}).
 *
 * <p>Differs from {@code renderBlockAsEntity} in two ways that fix long-standing bugs:</p>
 * <ul>
 *   <li><b>Biome tint</b> — the model is drawn with the tint captured from the world (per {@link
 *   DestructionBlock#tint}), applied only to tinted quads, instead of {@code renderBlockAsEntity}'s
 *   world-less default tint (grass/leaves/water were the wrong colour).</li>
 *   <li><b>Block entities skipped</b> — {@code renderBlockAsEntity} draws the full builtin item model for
 *   {@code ENTITYBLOCK_ANIMATED} blocks, so a two-block bed rendered a whole bed at each half (a
 *   duplicate). Only {@code MODEL} blocks are drawn; beds/chests/etc. are skipped.</li>
 * </ul>
 */
public final class DestructionBlockDraw
{
    private DestructionBlockDraw()
    {
    }

    /** True if the block is drawn by this path (a normal model block, not a block-entity-rendered one). */
    public static boolean isRenderable(BlockState state)
    {
        return state.getRenderType() == BlockRenderType.MODEL;
    }

    /**
     * Cross-model plants render UNSHADED in the vanilla block pipeline (shade=false, full brightness)
     * — the entity program's directional diffuse over their sideways normals made every captured tuft
     * visibly darker than the world's own. Callers force these units' normals UP to match vanilla.
     */
    public static boolean unshadedPlant(BlockState state)
    {
        net.minecraft.block.Block block = state.getBlock();

        return block instanceof net.minecraft.block.PlantBlock
            || block instanceof net.minecraft.block.SugarCaneBlock;
    }

    public static void draw(BlockState state, int tint, MatrixStack.Entry entry, VertexConsumerProvider provider,
        int light, int overlay)
    {
        /* Legacy entry: BlockModelRenderer's fixed seed (42). Prefer the seeded overload. */
        draw(state, tint, 42L, entry, provider, light, overlay);
    }

    /**
     * Seeded draw: vanilla terrain picks weighted model variants and RANDOM TEXTURE ROTATIONS per
     * position ({@code state.getRenderingSeed(pos)}) — the entity-model renderer hardcodes seed 42,
     * which stamped every captured grass/dirt/stone top with the SAME rotation and made the resting
     * zone read as a uniform carpet against the varied world. Pass the block's rendering seed.
     */
    public static void draw(BlockState state, int tint, long seed, MatrixStack.Entry entry,
        VertexConsumerProvider provider, int light, int overlay)
    {
        if (state.getRenderType() != BlockRenderType.MODEL)
        {
            return;
        }

        float r = ((tint >> 16) & 0xFF) / 255F;
        float g = ((tint >> 8) & 0xFF) / 255F;
        float b = (tint & 0xFF) / 255F;

        BlockRenderManager brm = MinecraftClient.getInstance().getBlockRenderManager();
        net.minecraft.client.render.model.BakedModel model = brm.getModel(state);
        VertexConsumer vc = provider.getBuffer(RenderLayers.getEntityBlockLayer(state, false));
        net.minecraft.util.math.random.Random random = net.minecraft.util.math.random.Random.create();

        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values())
        {
            random.setSeed(seed);

            for (net.minecraft.client.render.model.BakedQuad quad : model.getQuads(state, dir, random))
            {
                emit(vc, entry, quad, r, g, b, light, overlay);
            }
        }

        random.setSeed(seed);

        for (net.minecraft.client.render.model.BakedQuad quad : model.getQuads(state, null, random))
        {
            emit(vc, entry, quad, r, g, b, light, overlay);
        }
    }

    private static void emit(VertexConsumer vc, MatrixStack.Entry entry,
        net.minecraft.client.render.model.BakedQuad quad, float r, float g, float b, int light, int overlay)
    {
        /* Tint applies only to tinted quads — same rule as BlockModelRenderer.render. */
        if (quad.hasColor())
        {
            vc.quad(entry, quad, r, g, b, light, overlay);
        }
        else
        {
            vc.quad(entry, quad, 1F, 1F, 1F, light, overlay);
        }
    }
}
