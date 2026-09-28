package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.resources.Pixels;
import org.lwjgl.opengl.GL11;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bakes a custom-font label string into GL textures. The fill and the (hollow) outline are baked in their
 * final colours into a {@code colored} texture, plus a white {@code alpha} silhouette (for the drop shadow
 * and for picking). The label renderer draws these as plain textured quads with the VANILLA
 * position-tex-colour program — crucially this works under external shaderpacks (Iris/OptiFine), unlike a
 * custom core shader, which the pack overrides during the world pass (the same constraint as the
 * smear-dissolve work).
 *
 * <p>Because colours are baked, changing the fill/stroke colour rebakes (cheap, cached); the per-frame
 * actor fade still tints live via the quad's vertex colour. Shader-based blend modes (overlay/difference/…)
 * are NOT available on custom fonts (they need a custom shader); fixed-function blends work everywhere.</p>
 *
 * <p><b>Deferred build</b> (the {@link BbsVfxDissolveTexture} lesson): {@link #get} never creates a GL
 * texture — a miss is queued and {@code null} is returned (the label skips one frame); {@link #processPending}
 * builds the queue at a safe point (end of world render). Cached with an LRU cap; AWT baking happens at
 * {@link #QUALITY}× the on-screen size and is drawn scaled down for crisp magnification.</p>
 */
public final class BbsVfxFontTexture
{
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("bbsvfx");

    /** Bake at QUALITY× the on-screen font size (then the quad is scaled down) for crisp magnification. */
    private static final int QUALITY = 4;

    /** Clamp for the baked pixel size — keeps tiny fonts sharp and caps texture memory for huge ones. */
    private static final int MIN_BAKE_PX = 48;
    private static final int MAX_BAKE_PX = 512;

    /** Max cached entries before the least-recently-used is evicted (and its GL textures deleted). */
    private static final int CACHE_CAP = 256;

    private static final Map<Key, Pair> CACHE = new LinkedHashMap<>(16, 0.75F, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Pair> eldest)
        {
            if (size() > CACHE_CAP)
            {
                Pair e = eldest.getValue();

                if (e != null)
                {
                    e.delete();
                }

                return true;
            }

            return false;
        }
    };

    private static final Map<Key, Boolean> PENDING = new LinkedHashMap<>();
    private static final Set<Key> FAILED = new HashSet<>();

    private BbsVfxFontTexture()
    {}

    /** Baked colour + silhouette textures plus the scale to draw them at (baked larger than on-screen). */
    public record Baked(Texture colored, Texture alpha, float scale)
    {}

    private record Pair(Texture colored, Texture alpha)
    {
        void delete()
        {
            if (this.colored != null)
            {
                this.colored.delete();
            }

            if (this.alpha != null)
            {
                this.alpha.delete();
            }
        }
    }

    /**
     * The baked textures (+ draw scale) for the given parameters if ready; otherwise queues a build and
     * returns {@code null} (the caller skips drawing for this one frame). Never creates a GL texture.
     */
    public static Baked get(String font, String text, float size, float tracking, int max, float strokeWidth, int fillARGB, int strokeARGB, boolean strokeOnly, boolean gradient, int gradStartARGB, int gradEndARGB, float gradAngle)
    {
        if (font == null || font.isEmpty() || text == null || text.isEmpty() || size <= 0F)
        {
            return null;
        }

        int bakePx = Math.max(MIN_BAKE_PX, Math.min(MAX_BAKE_PX, Math.round(size * QUALITY)));
        int trackBaked = Math.round(tracking * bakePx / size);
        int maxBaked = max > 10 ? Math.round(max * bakePx / size) : 0;
        int strokeBaked = strokeWidth > 0F ? Math.max(1, Math.round(strokeWidth * bakePx / size)) : 0;
        int gradAngleI = gradient ? Math.round(gradAngle) : 0;
        float scale = size / bakePx;
        Key key = new Key(font, text, bakePx, trackBaked, maxBaked, strokeBaked, fillARGB, strokeARGB, strokeOnly, gradient, gradStartARGB, gradEndARGB, gradAngleI);

        Pair cached = CACHE.get(key);

        if (cached != null)
        {
            return new Baked(cached.colored, cached.alpha, scale);
        }

        if (!FAILED.contains(key) && !PENDING.containsKey(key))
        {
            PENDING.put(key, Boolean.TRUE);
        }

        return null;
    }

    /** Build every queued entry. Render thread, at a point with no active draw (end of world render). */
    public static void processPending()
    {
        if (PENDING.isEmpty())
        {
            return;
        }

        for (Key key : new ArrayList<>(PENDING.keySet()))
        {
            if (CACHE.containsKey(key))
            {
                continue;
            }

            Pair built = bake(key);

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

    private static Pair bake(Key key)
    {
        try
        {
            Font font = BbsVfxFontManager.resolve(key.font, key.bakePx);

            if (font == null)
            {
                return null;
            }

            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D pg = probe.createGraphics();

            applyHints(pg);
            pg.setFont(font);

            FontRenderContext frc = pg.getFontRenderContext();
            FontMetrics fm = pg.getFontMetrics();
            int ascent = fm.getAscent();
            int lineH = fm.getHeight();
            int pad = Math.max(2, key.bakePx / 8) + key.strokeBaked + 1;

            List<String> lines = new ArrayList<>();

            for (String seg : key.text.split("\n", -1))
            {
                if (key.maxBaked > 0)
                {
                    wrapInto(lines, seg, fm, key.trackBaked, key.maxBaked);
                }
                else
                {
                    lines.add(seg);
                }
            }

            int textW = 0;

            for (String line : lines)
            {
                textW = Math.max(textW, measure(fm, line, key.trackBaked));
            }

            pg.dispose();

            int w = textW + pad * 2;
            int h = lineH * lines.size() + pad * 2;

            if (w <= 0 || h <= 0)
            {
                return null;
            }

            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();

            applyHints(g);

            java.awt.Color fillColor = awt(key.fillARGB);
            java.awt.Color strokeColor = awt(key.strokeARGB);
            boolean stroke = key.strokeBaked > 0;
            BasicStroke ringStroke = stroke ? new BasicStroke(key.strokeBaked * 2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND) : null;

            /* Gradient fill paint spanning the whole image along the gradient axis (start = fill colour). */
            java.awt.Paint fillPaint = fillColor;

            if (key.gradient)
            {
                float angle = (float) Math.toRadians(key.gradAngle);
                float dx = (float) Math.cos(angle);
                float dy = (float) Math.sin(angle);
                float half = (Math.abs(dx) * w + Math.abs(dy) * h) / 2F;
                float cx = w / 2F;
                float cy = h / 2F;

                fillPaint = new java.awt.GradientPaint(cx - dx * half, cy - dy * half, awt(key.gradStartARGB), cx + dx * half, cy + dy * half, awt(key.gradEndARGB));
            }

            int y = pad + ascent;

            for (String line : lines)
            {
                Path2D path = buildPath(frc, font, fm, line, pad, y, key.trackBaked);

                /* Outline ring first (behind), then the fill on top — gives a hollow outline. */
                if (stroke)
                {
                    g.setColor(strokeColor);
                    g.setStroke(ringStroke);
                    g.draw(path);
                }

                if (!key.strokeOnly)
                {
                    g.setPaint(fillPaint);
                    g.fill(path);
                }

                y += lineH;
            }

            g.dispose();

            return toTextures(img);
        }
        catch (Throwable e)
        {
            LOG.error("[fonts] bake failed for {}", key.font, e);

            return null;
        }
    }

    /** Builds the outline geometry of one line, advancing each glyph by its width + tracking. */
    private static Path2D buildPath(FontRenderContext frc, Font font, FontMetrics fm, String line, int x, int baseY, int trackBaked)
    {
        Path2D.Float path = new Path2D.Float();
        float cx = x;

        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);

            if (!Character.isWhitespace(c))
            {
                GlyphVector gv = font.createGlyphVector(frc, String.valueOf(c));

                path.append(gv.getOutline(cx, baseY), false);
            }

            cx += fm.charWidth(c) + trackBaked;
        }

        return path;
    }

    private static java.awt.Color awt(int argb)
    {
        return new java.awt.Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }

    private static void applyHints(Graphics2D g)
    {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    /** Greedy word-wrap of one paragraph to {@code maxBaked} px (keeps over-long words on their own line). */
    private static void wrapInto(List<String> out, String seg, FontMetrics fm, int trackBaked, int maxBaked)
    {
        if (seg.isEmpty())
        {
            out.add("");

            return;
        }

        StringBuilder cur = new StringBuilder();

        for (String word : seg.split(" "))
        {
            String trial = cur.length() == 0 ? word : cur + " " + word;

            if (cur.length() == 0 || measure(fm, trial, trackBaked) <= maxBaked)
            {
                cur = new StringBuilder(trial);
            }
            else
            {
                out.add(cur.toString());
                cur = new StringBuilder(word);
            }
        }

        out.add(cur.toString());
    }

    private static int measure(FontMetrics fm, String line, int trackBaked)
    {
        if (line.isEmpty())
        {
            return 0;
        }

        int w = 0;

        for (int i = 0; i < line.length(); i++)
        {
            w += fm.charWidth(line.charAt(i));

            if (i < line.length() - 1)
            {
                w += trackBaked;
            }
        }

        return w;
    }

    /** Builds the colour texture (straight RGBA) and a white silhouette (rgb=1, a=coverage) from one image. */
    private static Pair toTextures(BufferedImage img)
    {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);

        Pixels colored = Pixels.fromSize(w, h);
        Pixels alpha = Pixels.fromSize(w, h);
        Color tmp = new Color();

        for (int yy = 0; yy < h; yy++)
        {
            for (int xx = 0; xx < w; xx++)
            {
                int p = px[yy * w + xx];
                float a = ((p >>> 24) & 0xFF) / 255F;
                float r = ((p >> 16) & 0xFF) / 255F;
                float gg = ((p >> 8) & 0xFF) / 255F;
                float b = (p & 0xFF) / 255F;

                tmp.set(r, gg, b, a);
                colored.setColor(xx, yy, tmp);

                tmp.set(1F, 1F, 1F, a);
                alpha.setColor(xx, yy, tmp);
            }
        }

        colored.getBuffer().rewind();
        alpha.getBuffer().rewind();

        Texture coloredTex = Texture.textureFromPixels(colored, GL11.GL_LINEAR);
        Texture alphaTex = Texture.textureFromPixels(alpha, GL11.GL_LINEAR);

        return new Pair(coloredTex, alphaTex);
    }

    private record Key(String font, String text, int bakePx, int trackBaked, int maxBaked, int strokeBaked, int fillARGB, int strokeARGB, boolean strokeOnly, boolean gradient, int gradStartARGB, int gradEndARGB, int gradAngle)
    {}
}
