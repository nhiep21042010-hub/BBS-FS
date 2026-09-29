package com.bbsvfx.bbsvfx.camera;

import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import java.util.ArrayList;
import java.util.List;

/**
 * "Impact frame" camera modifier ({@code bbsvfx:impact}) — a stylised full-screen flash for the moment
 * of impact (anime/sakuga impact frame). Unlike the other modifiers it does NOT move the camera: it
 * publishes its effect amounts into {@link BbsVfxImpactState}, and a client-side full-screen post pass
 * ({@code BbsVfxImpactShader}, run after the scene is rendered) applies them to the frame.
 *
 * <p>The master pulse comes from the clip's {@code envelope} (fade in/out, or its own keyframe channel),
 * so the impact ramps up and snaps off over the clip; each effect's static amount is scaled by that
 * factor. Stage 1: invert, white flash, grayscale. Stage 2: threshold (duotone), chromatic aberration,
 * + a manual focus point (screen UV) for the centre-based effects.</p>
 */
public class ImpactClip extends CameraClip
{
    /* Silhouette / negative-space layer (impact redesign S1): replace the frame with a flat background
     * and the actor silhouettes filled flat. */
    public final ValueFloat silhouette = new ValueFloat("silhouette", 0F, 0F, 1F);
    public final ValueInt silColor = new ValueInt("silColor", 0x000000).color();
    public final ValueInt bgColor = new ValueInt("bgColor", 0xFFFFFF).color();
    /* Target actor whose silhouette is used (-1 = all actors). Replay index in the film's replay list. */
    public final ValueInt target = new ValueInt("target", -1);

    /* Rough directional brush strokes on the silhouette (charcoal-redraw look). */
    public final ValueFloat silStrokes = new ValueFloat("silStrokes", 0F, 0F, 1F);
    public final ValueFloat silStrokeAngle = new ValueFloat("silStrokeAngle", 0F, -180F, 180F);
    public final ValueFloat silStrokeLength = new ValueFloat("silStrokeLength", 0.04F, 0F, 0.3F);
    public final ValueFloat silStrokeScale = new ValueFloat("silStrokeScale", 40F, 4F, 120F);
    public final ValueFloat silStrokeRough = new ValueFloat("silStrokeRough", 0.6F, 0F, 1F);

    /* Rough ink burst (S2) — a hand-drawn radial brush burst from the focus, behind the silhouette. */
    public final ValueFloat inkBurst = new ValueFloat("inkBurst", 0F, 0F, 1F);
    public final ValueInt inkColor = new ValueInt("inkColor", 0x000000).color();
    public final ValueFloat inkRadius = new ValueFloat("inkRadius", 0.5F, 0.05F, 1.5F);
    public final ValueFloat inkInner = new ValueFloat("inkInner", 0.08F, 0F, 0.5F);
    public final ValueFloat inkSpikes = new ValueFloat("inkSpikes", 18F, 3F, 64F);
    public final ValueFloat inkRough = new ValueFloat("inkRough", 0.5F, 0F, 1F);
    public final ValueInt inkSeed = new ValueInt("inkSeed", 0, 0, 9999);

    /* Shockwave (S3) — an expanding ring from the focus; the radius is driven by a manual progress slider
     * (0..1), so the user animates the expansion via the envelope/keyframes instead of it auto-playing. */
    public final ValueFloat shockwave = new ValueFloat("shockwave", 0F, 0F, 1F);
    public final ValueFloat shockwaveProgress = new ValueFloat("shockwaveProgress", 0F, 0F, 1F);
    public final ValueInt shockwaveColor = new ValueInt("shockwaveColor", 0xFFFFFF).color();
    public final ValueFloat shockwaveRadius = new ValueFloat("shockwaveRadius", 0.7F, 0.1F, 2F);
    public final ValueFloat shockwaveWidth = new ValueFloat("shockwaveWidth", 0.04F, 0.005F, 0.3F);

    /* Contact flash star (S3) — a bright 4-point cross-star + glow at the focus, on the impact beat. */
    public final ValueFloat flashStar = new ValueFloat("flashStar", 0F, 0F, 1F);
    public final ValueFloat flashStarSize = new ValueFloat("flashStarSize", 0.25F, 0.02F, 1F);
    public final ValueFloat flashStarWidth = new ValueFloat("flashStarWidth", 0.012F, 0.002F, 0.1F);
    public final ValueFloat flashStarGlow = new ValueFloat("flashStarGlow", 0.3F, 0F, 1F);
    public final ValueFloat flashStarRotation = new ValueFloat("flashStarRotation", 0F, -90F, 90F);
    public final ValueInt flashStarColor = new ValueInt("flashStarColor", 0xFFFFFF).color();

    public final ValueFloat invert = new ValueFloat("invert", 0F, 0F, 1F);
    public final ValueFloat flash = new ValueFloat("flash", 0F, 0F, 1F);
    public final ValueFloat grayscale = new ValueFloat("grayscale", 0F, 0F, 1F);

    public final ValueFloat threshold = new ValueFloat("threshold", 0F, 0F, 1F);
    public final ValueFloat thresholdLevel = new ValueFloat("thresholdLevel", 0.5F, 0F, 1F);
    public final ValueFloat thresholdSoft = new ValueFloat("thresholdSoft", 0.05F, 0F, 1F);
    public final ValueInt darkColor = new ValueInt("darkColor", 0x000000).color();
    public final ValueInt lightColor = new ValueInt("lightColor", 0xFFFFFF).color();

    public final ValueFloat chroma = new ValueFloat("chroma", 0F, 0F, 1F);

    public final ValueFloat focusX = new ValueFloat("focusX", 0.5F, 0F, 1F);
    public final ValueFloat focusY = new ValueFloat("focusY", 0.5F, 0F, 1F);

    public final ValueFloat zoomBlur = new ValueFloat("zoomBlur", 0F, 0F, 1F);
    public final ValueInt blurMode = new ValueInt("blurMode", 0, 0, 2);

    public final ValueFloat zoomLines = new ValueFloat("zoomLines", 0F, 0F, 1F);
    public final ValueFloat linesCount = new ValueFloat("linesCount", 60F, 1F, 360F);
    public final ValueFloat linesThickness = new ValueFloat("linesThickness", 0.3F, 0.02F, 1F);
    public final ValueFloat linesInner = new ValueFloat("linesInner", 0.4F, 0F, 1F);
    public final ValueInt linesMode = new ValueInt("linesMode", 0, 0, 2);
    public final ValueInt linesSeed = new ValueInt("linesSeed", 0, 0, 9999);
    public final ValueInt linesColor = new ValueInt("linesColor", 0x000000).color();

    public final ValueFloat shapes = new ValueFloat("shapes", 0F, 0F, 1F);
    public final ValueFloat shapesCount = new ValueFloat("shapesCount", 8F, 1F, 24F);
    public final ValueFloat shapesSize = new ValueFloat("shapesSize", 0.05F, 0.005F, 0.5F);
    public final ValueFloat shapesSpread = new ValueFloat("shapesSpread", 0.5F, 0F, 2F);
    public final ValueFloat centerStar = new ValueFloat("centerStar", 0F, 0F, 1F);
    public final ValueFloat centerCircle = new ValueFloat("centerCircle", 0F, 0F, 1F);
    public final ValueInt shapesColor = new ValueInt("shapesColor", 0xFFFFFF).color();
    /* Debris beat (S3): delay the scattered shapes by this many ticks so they land a few frames AFTER the
     * impact peak (the centre star/circle stay on the impact). */
    public final ValueFloat shapesDelay = new ValueFloat("shapesDelay", 0F, 0F, 20F);

    /* ---- Keyframe channels (hybrid): when a channel has keyframes it OVERRIDES the matching slider value;
     * empty channel = use the slider. Edited via the graph editor (UIImpactClip "Edit keyframes"). ---- */
    public final KeyframeChannel<Double> silhouetteKf = new KeyframeChannel<>("silhouette_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> silStrokesKf = new KeyframeChannel<>("silStrokes_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> inkBurstKf = new KeyframeChannel<>("inkBurst_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> shockwaveKf = new KeyframeChannel<>("shockwave_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> shockwaveProgressKf = new KeyframeChannel<>("shockwaveProgress_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> flashStarKf = new KeyframeChannel<>("flashStar_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> focusXKf = new KeyframeChannel<>("focusX_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> focusYKf = new KeyframeChannel<>("focusY_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> invertKf = new KeyframeChannel<>("invert_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> flashKf = new KeyframeChannel<>("flash_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> grayscaleKf = new KeyframeChannel<>("grayscale_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> thresholdKf = new KeyframeChannel<>("threshold_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> chromaKf = new KeyframeChannel<>("chroma_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> zoomBlurKf = new KeyframeChannel<>("zoomBlur_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> zoomLinesKf = new KeyframeChannel<>("zoomLines_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> shapesKf = new KeyframeChannel<>("shapes_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> centerStarKf = new KeyframeChannel<>("centerStar_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> centerCircleKf = new KeyframeChannel<>("centerCircle_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> silStrokeAngleKf = new KeyframeChannel<>("silStrokeAngle_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> silStrokeLengthKf = new KeyframeChannel<>("silStrokeLength_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> silStrokeScaleKf = new KeyframeChannel<>("silStrokeScale_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> silStrokeRoughKf = new KeyframeChannel<>("silStrokeRough_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> inkRadiusKf = new KeyframeChannel<>("inkRadius_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> inkInnerKf = new KeyframeChannel<>("inkInner_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> inkSpikesKf = new KeyframeChannel<>("inkSpikes_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> inkRoughKf = new KeyframeChannel<>("inkRough_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> inkSeedKf = new KeyframeChannel<>("inkSeed_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> shockwaveRadiusKf = new KeyframeChannel<>("shockwaveRadius_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> shockwaveWidthKf = new KeyframeChannel<>("shockwaveWidth_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> flashStarSizeKf = new KeyframeChannel<>("flashStarSize_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> flashStarWidthKf = new KeyframeChannel<>("flashStarWidth_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> flashStarGlowKf = new KeyframeChannel<>("flashStarGlow_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> flashStarRotationKf = new KeyframeChannel<>("flashStarRotation_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> thresholdLevelKf = new KeyframeChannel<>("thresholdLevel_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> thresholdSoftKf = new KeyframeChannel<>("thresholdSoft_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> linesCountKf = new KeyframeChannel<>("linesCount_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> linesThicknessKf = new KeyframeChannel<>("linesThickness_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> linesInnerKf = new KeyframeChannel<>("linesInner_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> linesSeedKf = new KeyframeChannel<>("linesSeed_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> shapesCountKf = new KeyframeChannel<>("shapesCount_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> shapesSizeKf = new KeyframeChannel<>("shapesSize_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> shapesSpreadKf = new KeyframeChannel<>("shapesSpread_kf", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> shapesDelayKf = new KeyframeChannel<>("shapesDelay_kf", KeyframeFactories.DOUBLE);

    /** All keyframe channels with display names, for the graph editor. */
    public final List<KeyframeChannel<Double>> kfChannels = new ArrayList<>();
    public final List<String> kfNames = new ArrayList<>();

    public ImpactClip()
    {
        super();

        this.add(this.silhouette);
        this.add(this.silColor);
        this.add(this.bgColor);
        this.add(this.target);
        this.add(this.silStrokes);
        this.add(this.silStrokeAngle);
        this.add(this.silStrokeLength);
        this.add(this.silStrokeScale);
        this.add(this.silStrokeRough);
        this.add(this.inkBurst);
        this.add(this.inkColor);
        this.add(this.inkRadius);
        this.add(this.inkInner);
        this.add(this.inkSpikes);
        this.add(this.inkRough);
        this.add(this.inkSeed);
        this.add(this.shockwave);
        this.add(this.shockwaveProgress);
        this.add(this.shockwaveColor);
        this.add(this.shockwaveRadius);
        this.add(this.shockwaveWidth);
        this.add(this.flashStar);
        this.add(this.flashStarSize);
        this.add(this.flashStarWidth);
        this.add(this.flashStarGlow);
        this.add(this.flashStarRotation);
        this.add(this.flashStarColor);
        this.add(this.invert);
        this.add(this.flash);
        this.add(this.grayscale);
        this.add(this.threshold);
        this.add(this.thresholdLevel);
        this.add(this.thresholdSoft);
        this.add(this.darkColor);
        this.add(this.lightColor);
        this.add(this.chroma);
        this.add(this.focusX);
        this.add(this.focusY);
        this.add(this.zoomBlur);
        this.add(this.blurMode);
        this.add(this.zoomLines);
        this.add(this.linesCount);
        this.add(this.linesThickness);
        this.add(this.linesInner);
        this.add(this.linesMode);
        this.add(this.linesSeed);
        this.add(this.linesColor);
        this.add(this.shapes);
        this.add(this.shapesCount);
        this.add(this.shapesSize);
        this.add(this.shapesSpread);
        this.add(this.centerStar);
        this.add(this.centerCircle);
        this.add(this.shapesColor);
        this.add(this.shapesDelay);

        this.kf(this.silhouetteKf, "Silhouette");
        this.kf(this.silStrokesKf, "Sil strokes");
        this.kf(this.inkBurstKf, "Ink burst");
        this.kf(this.shockwaveKf, "Shockwave");
        this.kf(this.shockwaveProgressKf, "Shockwave progress");
        this.kf(this.flashStarKf, "Flash star");
        this.kf(this.focusXKf, "Focus X");
        this.kf(this.focusYKf, "Focus Y");
        this.kf(this.invertKf, "Invert");
        this.kf(this.flashKf, "Flash");
        this.kf(this.grayscaleKf, "Grayscale");
        this.kf(this.thresholdKf, "Threshold");
        this.kf(this.chromaKf, "Chroma");
        this.kf(this.zoomBlurKf, "Zoom blur");
        this.kf(this.zoomLinesKf, "Zoom lines");
        this.kf(this.shapesKf, "Shapes");
        this.kf(this.centerStarKf, "Center star");
        this.kf(this.centerCircleKf, "Center circle");
        this.kf(this.silStrokeAngleKf, "Sil stroke angle");
        this.kf(this.silStrokeLengthKf, "Sil stroke length");
        this.kf(this.silStrokeScaleKf, "Sil stroke scale");
        this.kf(this.silStrokeRoughKf, "Sil stroke rough");
        this.kf(this.inkRadiusKf, "Ink radius");
        this.kf(this.inkInnerKf, "Ink inner");
        this.kf(this.inkSpikesKf, "Ink spikes");
        this.kf(this.inkRoughKf, "Ink rough");
        this.kf(this.inkSeedKf, "Ink seed");
        this.kf(this.shockwaveRadiusKf, "Shockwave radius");
        this.kf(this.shockwaveWidthKf, "Shockwave width");
        this.kf(this.flashStarSizeKf, "Flash star size");
        this.kf(this.flashStarWidthKf, "Flash star width");
        this.kf(this.flashStarGlowKf, "Flash star glow");
        this.kf(this.flashStarRotationKf, "Flash star rotation");
        this.kf(this.thresholdLevelKf, "Threshold level");
        this.kf(this.thresholdSoftKf, "Threshold soft");
        this.kf(this.linesCountKf, "Lines count");
        this.kf(this.linesThicknessKf, "Lines thickness");
        this.kf(this.linesInnerKf, "Lines inner");
        this.kf(this.linesSeedKf, "Lines seed");
        this.kf(this.shapesCountKf, "Shapes count");
        this.kf(this.shapesSizeKf, "Shapes size");
        this.kf(this.shapesSpreadKf, "Shapes spread");
        this.kf(this.shapesDelayKf, "Shapes delay");
    }

    /** Register a keyframe channel as a serialised child + add it to the editor lists. */
    private void kf(KeyframeChannel<Double> channel, String name)
    {
        this.add(channel);
        this.kfChannels.add(channel);
        this.kfNames.add(name);
    }

    /** Hybrid read: the channel value at t when it has keyframes, otherwise the slider's static value. */
    private float kf(KeyframeChannel<Double> channel, ValueFloat slider, float t)
    {
        return channel.isEmpty() ? slider.get() : channel.interpolate(t).floatValue();
    }

    private float kf(KeyframeChannel<Double> channel, ValueInt slider, float t)
    {
        return channel.isEmpty() ? slider.get() : channel.interpolate(t).floatValue();
    }

    @Override
    protected void applyClip(ClipContext context, Position position)
    {
        float t = context.relativeTick + context.transition;

        /* Master pulse: the envelope factor (fade in/out or keyframed), same as every other modifier. */
        float factor = this.envelope.factorEnabled(this.duration.get(), t);

        BbsVfxImpactState.active = true;

        BbsVfxImpactState.silhouette = this.kf(this.silhouetteKf, this.silhouette, t) * factor;

        /* TEMP DIAG (unconditional for the hunt): prove the clip applies at all — the capture
         * hook stays silent in every session so far, so first answer "does applyClip even run". */
        org.slf4j.LoggerFactory.getLogger("bbsvfx").info(
            "[impact-diag] applyClip t={} factor={} silhouette={} target={}",
            t, factor, BbsVfxImpactState.silhouette, BbsVfxImpactState.silTarget);
        BbsVfxImpactState.silTarget = this.target.get();

        BbsVfxImpactState.silStrokes = this.kf(this.silStrokesKf, this.silStrokes, t);
        BbsVfxImpactState.silStrokeAngle = this.kf(this.silStrokeAngleKf, this.silStrokeAngle, t);
        BbsVfxImpactState.silStrokeLength = this.kf(this.silStrokeLengthKf, this.silStrokeLength, t);
        BbsVfxImpactState.silStrokeScale = this.kf(this.silStrokeScaleKf, this.silStrokeScale, t);
        BbsVfxImpactState.silStrokeRough = this.kf(this.silStrokeRoughKf, this.silStrokeRough, t);

        Color sil = Color.rgb(this.silColor.get());
        BbsVfxImpactState.silR = sil.r;
        BbsVfxImpactState.silG = sil.g;
        BbsVfxImpactState.silB = sil.b;

        Color bg = Color.rgb(this.bgColor.get());
        BbsVfxImpactState.bgR = bg.r;
        BbsVfxImpactState.bgG = bg.g;
        BbsVfxImpactState.bgB = bg.b;

        BbsVfxImpactState.inkBurst = this.kf(this.inkBurstKf, this.inkBurst, t) * factor;

        Color ink = Color.rgb(this.inkColor.get());
        BbsVfxImpactState.inkR = ink.r;
        BbsVfxImpactState.inkG = ink.g;
        BbsVfxImpactState.inkB = ink.b;

        BbsVfxImpactState.inkRadius = this.kf(this.inkRadiusKf, this.inkRadius, t);
        BbsVfxImpactState.inkInner = this.kf(this.inkInnerKf, this.inkInner, t);
        BbsVfxImpactState.inkSpikes = this.kf(this.inkSpikesKf, this.inkSpikes, t);
        BbsVfxImpactState.inkRough = this.kf(this.inkRoughKf, this.inkRough, t);
        BbsVfxImpactState.inkSeed = this.kf(this.inkSeedKf, this.inkSeed, t);

        BbsVfxImpactState.shockwave = this.kf(this.shockwaveKf, this.shockwave, t);
        BbsVfxImpactState.shockwaveProgress = this.kf(this.shockwaveProgressKf, this.shockwaveProgress, t);
        BbsVfxImpactState.shockwaveRadius = this.kf(this.shockwaveRadiusKf, this.shockwaveRadius, t);
        BbsVfxImpactState.shockwaveWidth = this.kf(this.shockwaveWidthKf, this.shockwaveWidth, t);

        Color sw = Color.rgb(this.shockwaveColor.get());
        BbsVfxImpactState.swR = sw.r;
        BbsVfxImpactState.swG = sw.g;
        BbsVfxImpactState.swB = sw.b;

        BbsVfxImpactState.flashStar = this.kf(this.flashStarKf, this.flashStar, t) * factor;
        BbsVfxImpactState.flashStarSize = this.kf(this.flashStarSizeKf, this.flashStarSize, t);
        BbsVfxImpactState.flashStarWidth = this.kf(this.flashStarWidthKf, this.flashStarWidth, t);
        BbsVfxImpactState.flashStarGlow = this.kf(this.flashStarGlowKf, this.flashStarGlow, t);
        BbsVfxImpactState.flashStarRotation = this.kf(this.flashStarRotationKf, this.flashStarRotation, t);

        Color fs = Color.rgb(this.flashStarColor.get());
        BbsVfxImpactState.fsR = fs.r;
        BbsVfxImpactState.fsG = fs.g;
        BbsVfxImpactState.fsB = fs.b;

        BbsVfxImpactState.invert = this.kf(this.invertKf, this.invert, t) * factor;
        BbsVfxImpactState.flash = this.kf(this.flashKf, this.flash, t) * factor;
        BbsVfxImpactState.grayscale = this.kf(this.grayscaleKf, this.grayscale, t) * factor;
        BbsVfxImpactState.threshold = this.kf(this.thresholdKf, this.threshold, t) * factor;
        BbsVfxImpactState.chroma = this.kf(this.chromaKf, this.chroma, t) * factor;

        BbsVfxImpactState.thresholdLevel = this.kf(this.thresholdLevelKf, this.thresholdLevel, t);
        BbsVfxImpactState.thresholdSoft = this.kf(this.thresholdSoftKf, this.thresholdSoft, t);

        Color dark = Color.rgb(this.darkColor.get());
        BbsVfxImpactState.darkR = dark.r;
        BbsVfxImpactState.darkG = dark.g;
        BbsVfxImpactState.darkB = dark.b;

        Color light = Color.rgb(this.lightColor.get());
        BbsVfxImpactState.lightR = light.r;
        BbsVfxImpactState.lightG = light.g;
        BbsVfxImpactState.lightB = light.b;

        BbsVfxImpactState.focusX = this.kf(this.focusXKf, this.focusX, t);
        BbsVfxImpactState.focusY = this.kf(this.focusYKf, this.focusY, t);

        BbsVfxImpactState.zoomBlur = this.kf(this.zoomBlurKf, this.zoomBlur, t) * factor;
        BbsVfxImpactState.blurMode = this.blurMode.get();

        BbsVfxImpactState.zoomLines = this.kf(this.zoomLinesKf, this.zoomLines, t) * factor;
        BbsVfxImpactState.linesCount = this.kf(this.linesCountKf, this.linesCount, t);
        BbsVfxImpactState.linesThickness = this.kf(this.linesThicknessKf, this.linesThickness, t);
        BbsVfxImpactState.linesInner = this.kf(this.linesInnerKf, this.linesInner, t);
        BbsVfxImpactState.linesMode = this.linesMode.get();
        BbsVfxImpactState.linesSeed = this.kf(this.linesSeedKf, this.linesSeed, t);

        Color lines = Color.rgb(this.linesColor.get());
        BbsVfxImpactState.linesR = lines.r;
        BbsVfxImpactState.linesG = lines.g;
        BbsVfxImpactState.linesB = lines.b;

        /* Debris beat: the scattered shapes use an envelope factor evaluated shapesDelay ticks in the past,
         * so they ramp up that many frames after the main impact (centre star/circle stay on the impact). */
        float debrisFactor = this.envelope.factorEnabled(this.duration.get(),
            t - this.kf(this.shapesDelayKf, this.shapesDelay, t));

        BbsVfxImpactState.shapes = this.kf(this.shapesKf, this.shapes, t) * debrisFactor;
        BbsVfxImpactState.shapesCount = this.kf(this.shapesCountKf, this.shapesCount, t);
        BbsVfxImpactState.shapesSize = this.kf(this.shapesSizeKf, this.shapesSize, t);
        BbsVfxImpactState.shapesSpread = this.kf(this.shapesSpreadKf, this.shapesSpread, t);
        BbsVfxImpactState.centerStar = this.kf(this.centerStarKf, this.centerStar, t) * factor;
        BbsVfxImpactState.centerCircle = this.kf(this.centerCircleKf, this.centerCircle, t) * factor;

        Color shapesCol = Color.rgb(this.shapesColor.get());
        BbsVfxImpactState.shapesR = shapesCol.r;
        BbsVfxImpactState.shapesG = shapesCol.g;
        BbsVfxImpactState.shapesB = shapesCol.b;
    }

    @Override
    public Clip create()
    {
        return new ImpactClip();
    }
}
