package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.resources.Pixels;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Smear-frame dissolve baked into the texture's alpha, so it survives external shader packs.
 *
 * <p>The original dissolve was a {@code discard} in our own {@code model_dissolve} core shader, but BBS
 * deliberately renders models with the vanilla entity shader while Iris/OptiFine is active (so the pack
 * can light them) — our shader, and its {@code Dissolve} uniform, never run. Instead we punch holes into
 * a copy of the actor's texture (alpha {@code = 0} at the same noise cells the shader discarded): every
 * entity shader (vanilla or pack) already alpha-tests ({@code if (color.a < 0.1) discard;}), so the holes
 * are cut away identically with or without a shader pack — one mechanism everywhere.</p>
 *
 * <p><b>Deferred build (important):</b> the holed variants are NOT created while the model is drawing.
 * {@code TextureManagerDissolveMixin} calls {@link #get} inside {@code ModelInstance.render}'s
 * bind-then-draw loop — creating a GL texture there (glGenTextures / glTexImage2D, interleaved with VAO
 * draws under Sodium/Iris) corrupted render state and crashed with a matrix-stack imbalance. So {@link #get}
 * only ever does a cache lookup: a missing variant is queued and the original texture is used for that one
 * frame; {@link #processPending} builds the queue at a safe point (end of the world render — no active
 * draw, no pushed matrix). The one-frame delay before a variant appears is invisible in motion.</p>
 *
 * <p>Variants are cached per ({@link Link}, quantized level); texture edits mid-session aren't picked up
 * until reload (same trade-off as {@link BbsVfxTextureColor}). The noise grid matches the shader's
 * {@code floor(texCoord0 * 80.0)} so the look is unchanged — only now it sticks to the surface (texture
 * space) instead of the screen.</p>
 */
public final class BbsVfxDissolveTexture
{
    /** Quantization of the dissolve amount into cached levels (bounds the texture cache). */
    private static final int LEVELS = 16;

    /** Noise cells across the texture — must match {@code texCoord0 * 80.0} in the dissolve shader. */
    private static final float CELLS = 80F;

    private static final Map<Key, Texture> CACHE = new HashMap<>();

    /** Variants requested during rendering, built later at {@link #processPending} (key -> texture filter). */
    private static final Map<Key, Integer> PENDING = new LinkedHashMap<>();

    /** Variants whose pixels couldn't be read (e.g. procedural textures) — don't retry every frame. */
    private static final Set<Key> FAILED = new HashSet<>();

    private BbsVfxDissolveTexture()
    {}

    /**
     * The holed variant of {@code original} for the given dissolve amount, if already built; otherwise
     * queues it (built later by {@link #processPending}) and returns {@code original} for this frame.
     * Never creates a GL texture — safe to call mid-render.
     */
    public static Texture get(Link link, float dissolve, Texture original)
    {
        if (link == null || dissolve <= 0F)
        {
            return original;
        }

        int level = Math.max(1, Math.min(LEVELS, Math.round(dissolve * LEVELS)));
        Key key = new Key(link, level);
        Texture cached = CACHE.get(key);

        if (cached != null)
        {
            return cached;
        }

        if (!FAILED.contains(key) && !PENDING.containsKey(key))
        {
            PENDING.put(key, original != null ? original.getFilter() : GL11.GL_NEAREST);
        }

        return original;
    }

    /**
     * Build every queued variant. MUST be called on the render thread at a point with no active draw and
     * no pushed matrix (end of the world render) — never from inside a model render.
     */
    public static void processPending()
    {
        if (PENDING.isEmpty())
        {
            return;
        }

        for (Map.Entry<Key, Integer> entry : PENDING.entrySet())
        {
            Key key = entry.getKey();

            if (CACHE.containsKey(key))
            {
                continue;
            }

            Texture built = bbsvfx$build(key.link(), key.level() / (float) LEVELS, entry.getValue());

            if (built != null)
            {
                CACHE.put(key, built);
            }
            else
            {
                FAILED.add(key);
            }
        }

        PENDING.clear();
    }

    private static Texture bbsvfx$build(Link link, float dissolve, int filter)
    {
        Pixels src = null;

        try
        {
            src = BBSModClient.getTextures().getPixels(link);

            /* A 1x1 colour link (or a decode failure) has no meaningful noise grid — leave it intact. */
            if (src == null || src.width < 2 || src.height < 2)
            {
                return null;
            }

            int w = src.width;
            int h = src.height;
            Pixels out = Pixels.fromSize(w, h);
            Color tmp = new Color();

            for (int y = 0; y < h; y++)
            {
                for (int x = 0; x < w; x++)
                {
                    Color c = src.getColor(x, y); // shared instance — read immediately
                    float r = c.r;
                    float g = c.g;
                    float b = c.b;
                    float a = c.a;

                    int cx = (int) Math.floor((x + 0.5F) / w * CELLS);
                    int cy = (int) Math.floor((y + 0.5F) / h * CELLS);

                    if (a > 0F && bbsvfx$hash(cx, cy) < dissolve)
                    {
                        a = 0F;
                    }

                    tmp.set(r, g, b, a);
                    out.setColor(x, y, tmp);
                }
            }

            /* setColor leaves the buffer at its end; glTexImage2D reads from the current position. */
            out.getBuffer().rewind();

            return Texture.textureFromPixels(out, filter); // uploads + deletes `out`
        }
        catch (Throwable e)
        {
            return null;
        }
        finally
        {
            if (src != null)
            {
                src.delete();
            }
        }
    }

    /** Mirrors the shader's {@code fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453)} with p = (cx, cy). */
    private static float bbsvfx$hash(int cx, int cy)
    {
        double s = Math.sin(cx * 127.1 + cy * 311.7) * 43758.5453;

        return (float) (s - Math.floor(s));
    }

    private record Key(Link link, int level)
    {}
}
