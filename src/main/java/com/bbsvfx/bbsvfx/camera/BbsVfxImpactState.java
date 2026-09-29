package com.bbsvfx.bbsvfx.camera;

/**
 * Per-frame bridge between the {@link ImpactClip} camera modifier (common side, sets the state during
 * camera evaluation) and the client-side full-screen post pass ({@code BbsVfxImpactShader}, reads it
 * after the scene is rendered). Plain static data so it can live in the common source set.
 *
 * <p>{@link ImpactClip#applyClip} writes the fields each frame (effect amounts scaled by the envelope
 * factor; colours / level / focus as-is); the render hook consumes them and calls {@link #reset()}.
 * Overlapping clips are "last applied wins" (rare for impact frames). Lives in {@code main} because the
 * clip ({@code main}) writes it and the render mixin ({@code client}) reads it.</p>
 */
public final class BbsVfxImpactState
{
    private static final float EPS = 0.001F;

    public static boolean active;

    /* Silhouette / negative-space layer: the frame is replaced by a flat background (BgColor) with the
     * actor silhouettes filled flat (SilColor). Amount 0..1 (scaled by the envelope factor); the actor
     * coverage comes from an offscreen pass (BbsVfxImpactSilhouette). Stage 1 of the impact redesign. */
    public static float silhouette;
    public static float silR, silG, silB;            /* silhouette fill, default black */
    public static float bgR = 1F, bgG = 1F, bgB = 1F; /* negative-space background, default white */
    public static int silTarget = -1;                /* target actor (replay index), -1 = all actors */

    /* Rough directional strokes on the silhouette (S3): the actor coverage is streaked along a direction and
     * broken by directional noise, so the silhouette reads as bold charcoal brush strokes, not a flat fill. */
    public static float silStrokes;
    public static float silStrokeAngle;              /* degrees */
    public static float silStrokeLength = 0.04F;     /* streak length (uv) */
    public static float silStrokeScale = 40F;        /* stroke frequency across the direction */
    public static float silStrokeRough = 0.6F;

    /* Rough ink burst (S2): a hand-drawn-looking radial brush burst from the focus point, drawn behind the
     * silhouette (over the negative-space background). Procedural in impact.fsh. */
    public static float inkBurst;
    public static float inkR, inkG, inkB;            /* ink colour, default black */
    public static float inkRadius = 0.5F;            /* outer reach (screen uv) */
    public static float inkInner = 0.08F;            /* clear core radius */
    public static float inkSpikes = 18F;             /* spike count / angular frequency */
    public static float inkRough = 0.5F;             /* edge roughness / gap amount */
    public static float inkSeed;

    /* Shockwave (S3): an expanding ring from the focus. Radius = shockwaveRadius * progress, where progress
     * is the clip's playhead position 0..1 through its duration; the ring thins and fades as it expands. */
    public static float shockwave;
    public static float shockwaveProgress;
    public static float shockwaveRadius = 0.7F;
    public static float shockwaveWidth = 0.04F;
    public static float swR = 1F, swG = 1F, swB = 1F; /* shockwave colour, default white */

    /* Contact flash star (S3): a bright 4-point cross-star + central glow at the focus (the hard contact
     * glint). Long tapered needles, own size/width/glow/rotation/colour. */
    public static float flashStar;
    public static float flashStarSize = 0.25F;
    public static float flashStarWidth = 0.012F;
    public static float flashStarGlow = 0.3F;
    public static float flashStarRotation;
    public static float fsR = 1F, fsG = 1F, fsB = 1F; /* flash star colour, default white */

    /* Effect amounts (0..1), already scaled by the clip envelope factor. */
    public static float invert;
    public static float flash;
    public static float grayscale;
    public static float threshold;
    public static float chroma;

    /* White-flash colour. */
    public static float flashR = 1F;
    public static float flashG = 1F;
    public static float flashB = 1F;

    /* Threshold duotone: luma below level -> dark colour, above -> light colour (soft = edge width). */
    public static float thresholdLevel = 0.5F;
    public static float thresholdSoft = 0.05F;
    public static float darkR, darkG, darkB;
    public static float lightR = 1F, lightG = 1F, lightB = 1F;

    /* Focus point in screen UV (0..1), shared by the centre-based effects (chromatic aberration, zoom
     * blur, zoom lines, shapes). */
    public static float focusX = 0.5F;
    public static float focusY = 0.5F;

    /* Zoom blur (radial blur from focus). */
    public static float zoomBlur;
    public static float blurMode; /* 0 = zoom (radial), 1 = horizontal, 2 = vertical */

    /* Zoom lines (concentration / speed lines). */
    public static float zoomLines;
    public static float linesCount = 60F;
    public static float linesThickness = 0.3F;
    public static float linesInner = 0.4F;
    public static float linesMode; /* 0 = zoom (radial), 1 = vertical, 2 = horizontal */
    public static float linesSeed;
    public static float linesR, linesG, linesB;

    /* Procedural shapes scattered around focus (a mix of 4-point stars and ring circles), plus an
     * optional single star / ring circle placed exactly at the focus. */
    public static float shapes;
    public static float shapesCount = 8F;
    public static float shapesSize = 0.05F;
    public static float shapesSpread = 0.5F;
    public static float centerStar;
    public static float centerCircle;
    public static float shapesR = 1F, shapesG = 1F, shapesB = 1F;

    private BbsVfxImpactState()
    {}

    /** Clear only the per-frame effect amounts; settings / focus / markerTTL persist (re-set each frame
     * by the active clip or the editor panel). */
    public static void reset()
    {
        active = false;
        silhouette = 0F;
        inkBurst = 0F;
        shockwave = 0F;
        flashStar = 0F;
        invert = flash = grayscale = threshold = chroma = zoomBlur = zoomLines = shapes = 0F;
        centerStar = centerCircle = 0F;
    }

    public static boolean hasEffect()
    {
        return active && (silhouette > EPS || inkBurst > EPS || shockwave > EPS || flashStar > EPS || invert > EPS || flash > EPS || grayscale > EPS || threshold > EPS
            || chroma > EPS || zoomBlur > EPS || zoomLines > EPS || shapes > EPS
            || centerStar > EPS || centerCircle > EPS);
    }
}
