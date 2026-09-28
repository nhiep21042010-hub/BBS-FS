package com.bbsvfx.bbsvfx.client;

import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Smear-frame dissolve for the BLOCK forms. Blocks render through the vanilla block atlas (not BBS's
 * {@code getTexture(Link)} path that {@link BbsVfxDissolveTexture} hooks), so the per-texture dissolve never
 * reached them. BBS renders blocks as ENTITIES though ({@code renderBlockAsEntity} → an EntityCutout layer
 * that alpha-tests), so the same trick works: hand back a copy of the atlas with alpha holes punched in,
 * and the cutout layer discards them — dissolve, surviving shader packs.
 *
 * <p>The holed atlas is downloaded from the GPU, holed and re-uploaded — heavy, so it's built ONCE per
 * quantized level, DEFERRED (off-render, same reason as {@link BbsVfxDissolveTexture}: never allocate GL
 * textures mid-draw). {@link #get} is a pure cache lookup; misses queue and use the original for that frame.</p>
 */
public final class BbsVfxAtlasDissolve
{
    private static final int LEVELS = 16;

    /** Atlas pixels per noise cell — small, so a 16px block sprite gets several holes. */
    private static final int CELL = 3;

    private static final Map<Integer, AbstractTexture> CACHE = new HashMap<>();
    private static final Map<Integer, AbstractTexture> PENDING = new LinkedHashMap<>();
    private static final Set<Integer> FAILED = new HashSet<>();

    private BbsVfxAtlasDissolve()
    {}

    /** The holed atlas for this dissolve amount if built; else queue it and return the original this frame. */
    public static AbstractTexture get(AbstractTexture original, float dissolve)
    {
        if (original == null || dissolve <= 0F)
        {
            return original;
        }

        int level = Math.max(1, Math.min(LEVELS, Math.round(dissolve * LEVELS)));
        AbstractTexture cached = CACHE.get(level);

        if (cached != null)
        {
            return cached;
        }

        if (!FAILED.contains(level) && !PENDING.containsKey(level))
        {
            PENDING.put(level, original);
        }

        return original;
    }

    /** Build queued holed atlases. Render thread, no active draw / pushed matrix (end of world render). */
    public static void processPending()
    {
        if (PENDING.isEmpty())
        {
            return;
        }

        for (Map.Entry<Integer, AbstractTexture> entry : PENDING.entrySet())
        {
            int level = entry.getKey();

            if (CACHE.containsKey(level))
            {
                continue;
            }

            AbstractTexture built = bbsvfx$build(entry.getValue(), level / (float) LEVELS);

            if (built != null)
            {
                CACHE.put(level, built);
            }
            else
            {
                FAILED.add(level);
            }
        }

        PENDING.clear();
    }

    private static AbstractTexture bbsvfx$build(AbstractTexture original, float dissolve)
    {
        try
        {
            original.bindTexture();

            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

            if (w < 2 || h < 2)
            {
                return null;
            }

            NativeImage image = new NativeImage(w, h, false);
            image.loadFromTextureImage(0, false); // download the bound atlas into the image

            for (int y = 0; y < h; y++)
            {
                for (int x = 0; x < w; x++)
                {
                    int abgr = image.getColor(x, y); // NativeImage stores ABGR (alpha = top byte)
                    int alpha = (abgr >>> 24) & 0xFF;

                    if (alpha > 0 && bbsvfx$hash(x / CELL, y / CELL) < dissolve)
                    {
                        image.setColor(x, y, abgr & 0x00FFFFFF); // zero alpha → hole
                    }
                }
            }

            return new NativeImageBackedTexture(image); // uploads + owns the image
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    private static float bbsvfx$hash(int cx, int cy)
    {
        double s = Math.sin(cx * 127.1 + cy * 311.7) * 43758.5453;

        return (float) (s - Math.floor(s));
    }
}
