package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.items.FoldState;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.UI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mchorse.bbs_mod.ui.utils.UIConstants;
import com.bbsvfx.bbsvfx.camera.ImpactClip;

/**
 * Editor panel for the {@link ImpactClip} impact-frame modifier. The master pulse is the inherited
 * envelope (shown by the base {@link UIClip}); here we expose the per-effect amounts. Stage 1: invert,
 * white flash, grayscale. Stage 2: threshold (duotone with two colours), chromatic aberration, and a
 * manual focus point (screen UV) for the centre-based effects.
 */
public class UIImpactClip extends UIClip<ImpactClip>
{
    public UITrackpad invert;
    public UITrackpad flash;
    public UITrackpad grayscale;

    public UITrackpad threshold;
    public UITrackpad thresholdLevel;
    public UITrackpad thresholdSoft;
    public UIColor darkColor;
    public UIColor lightColor;

    public UITrackpad chroma;
    public UITrackpad focusX;
    public UITrackpad focusY;

    public UITrackpad zoomBlur;
    public UICirculate blurMode;

    public UITrackpad zoomLines;
    public UITrackpad linesCount;
    public UITrackpad linesThickness;
    public UITrackpad linesInner;
    public UICirculate linesMode;
    public UITrackpad linesSeed;
    public UIColor linesColor;

    public UITrackpad shapes;
    public UITrackpad shapesCount;
    public UITrackpad shapesSize;
    public UITrackpad shapesSpread;
    public UIToggle centerStar;
    public UIToggle centerCircle;
    public UIColor shapesColor;
    public UITrackpad shapesDelay;

    public UITrackpad silhouette;
    public UIColor silColor;
    public UIColor bgColor;
    public UIButton preset;
    public UIKeyframeEditor keyframes;
    public UIButton editKeyframes;
    public UIButton target;
    public UIButton palette;
    public UITrackpad silStrokes;
    public UITrackpad silStrokeAngle;
    public UITrackpad silStrokeLength;
    public UITrackpad silStrokeScale;
    public UITrackpad silStrokeRough;

    public UITrackpad inkBurst;
    public UIColor inkColor;
    public UITrackpad inkRadius;
    public UITrackpad inkInner;
    public UITrackpad inkSpikes;
    public UITrackpad inkRough;
    public UITrackpad inkSeed;

    public UITrackpad shockwave;
    public UITrackpad shockwaveProgress;
    public UIColor shockwaveColor;
    public UITrackpad shockwaveRadius;
    public UITrackpad shockwaveWidth;

    public UITrackpad flashStar;
    public UITrackpad flashStarSize;
    public UITrackpad flashStarWidth;
    public UITrackpad flashStarGlow;
    public UITrackpad flashStarRotation;
    public UIColor flashStarColor;

    public UIImpactClip(ImpactClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.preset = new UIButton(IKey.constant("Preset…"), (b) -> this.bbsvfx$openPresets());
        this.preset.tooltip(IKey.constant("Apply a ready-made impact-frame preset (overwrites the values below)"));

        this.keyframes = new UIKeyframeEditor((consumer) -> new UIFilmKeyframes(this.editor, consumer));
        this.keyframes.view.rulerRenderer((context) -> UIReplaysEditor.renderRuler(
            context, this.keyframes.view, (UIClipsPanel) this.editor, (Clips) this.clip.getParent(), this.clip.tick.get()));
        this.keyframes.view.duration(() -> this.clip.duration.get());
        this.keyframes.setUndoId("impact_keyframes");

        this.editKeyframes = new UIButton(IKey.constant("Edit keyframes"), (b) ->
        {
            this.bbsvfx$setupSheets();
            this.editor.embedView(this.keyframes);
            this.keyframes.view.resetView();
            this.keyframes.view.getGraph().clearSelection();
        });
        this.editKeyframes.keys().register(Keys.FORMS_EDIT, () -> this.editKeyframes.clickItself());
        this.editKeyframes.tooltip(IKey.constant("Animate the impact parameters over the clip (a keyframed channel overrides its slider)"));

        this.silhouette = new UITrackpad((value) -> this.clip.silhouette.set(value.floatValue()));
        this.silhouette.limit(0F, 1F).tooltip(IKey.constant("Silhouette / negative space amount (redraws the frame as flat actor silhouettes)"));

        this.silColor = new UIColor((c) -> this.clip.silColor.set(c)).noLabel();
        this.silColor.tooltip(IKey.constant("Silhouette fill colour"));

        this.bgColor = new UIColor((c) -> this.clip.bgColor.set(c)).noLabel();
        this.bgColor.tooltip(IKey.constant("Negative-space background colour"));

        this.target = new UIButton(IKey.constant("All actors"), (b) -> this.bbsvfx$cycleTarget());
        this.target.tooltip(IKey.constant("Which actor's silhouette to use (cycles through the film's actors; All = every actor)"));

        this.palette = new UIButton(IKey.constant("Palette: B&W"), (b) -> this.bbsvfx$cyclePalette());
        this.palette.tooltip(IKey.constant("Apply a colour preset to silhouette / background / ink (then fine-tune with the pickers)"));

        this.silStrokes = new UITrackpad((value) -> this.clip.silStrokes.set(value.floatValue()));
        this.silStrokes.limit(0F, 1F).tooltip(IKey.constant("Rough directional brush strokes on the silhouette (charcoal redraw)"));

        this.silStrokeAngle = new UITrackpad((value) -> this.clip.silStrokeAngle.set(value.floatValue()));
        this.silStrokeAngle.limit(-180F, 180F).tooltip(IKey.constant("Stroke direction (degrees)"));

        this.silStrokeLength = new UITrackpad((value) -> this.clip.silStrokeLength.set(value.floatValue()));
        this.silStrokeLength.limit(0F, 0.3F).tooltip(IKey.constant("Directional streak length"));

        this.silStrokeScale = new UITrackpad((value) -> this.clip.silStrokeScale.set(value.floatValue()));
        this.silStrokeScale.limit(4F, 120F).tooltip(IKey.constant("Stroke frequency (thinner/denser strokes)"));

        this.silStrokeRough = new UITrackpad((value) -> this.clip.silStrokeRough.set(value.floatValue()));
        this.silStrokeRough.limit(0F, 1F).tooltip(IKey.constant("Stroke roughness / gaps"));

        this.inkBurst = new UITrackpad((value) -> this.clip.inkBurst.set(value.floatValue()));
        this.inkBurst.limit(0F, 1F).tooltip(IKey.constant("Rough ink burst amount (radial brush burst from the focus)"));

        this.inkColor = new UIColor((c) -> this.clip.inkColor.set(c)).noLabel();
        this.inkColor.tooltip(IKey.constant("Ink colour"));

        this.inkRadius = new UITrackpad((value) -> this.clip.inkRadius.set(value.floatValue()));
        this.inkRadius.limit(0.05F, 1.5F).tooltip(IKey.constant("Ink reach (outer radius)"));

        this.inkInner = new UITrackpad((value) -> this.clip.inkInner.set(value.floatValue()));
        this.inkInner.limit(0F, 0.5F).tooltip(IKey.constant("Clear core radius (negative space at the centre)"));

        this.inkSpikes = new UITrackpad((value) -> this.clip.inkSpikes.set(value.floatValue()));
        this.inkSpikes.limit(3F, 64F).increment(1F).integer().tooltip(IKey.constant("Spike count"));

        this.inkRough = new UITrackpad((value) -> this.clip.inkRough.set(value.floatValue()));
        this.inkRough.limit(0F, 1F).tooltip(IKey.constant("Edge roughness / gaps between strokes"));

        this.inkSeed = new UITrackpad((value) -> this.clip.inkSeed.set(value.intValue()));
        this.inkSeed.limit(0F, 9999F).increment(1F).integer().tooltip(IKey.constant("Ink seed (changes the pattern)"));

        this.shockwave = new UITrackpad((value) -> this.clip.shockwave.set(value.floatValue()));
        this.shockwave.limit(0F, 1F).tooltip(IKey.constant("Shockwave ring opacity"));

        this.shockwaveProgress = new UITrackpad((value) -> this.clip.shockwaveProgress.set(value.floatValue()));
        this.shockwaveProgress.limit(0F, 1F).tooltip(IKey.constant("Shockwave progress: ring radius 0..max (animate via the envelope/keyframes)"));

        this.shockwaveColor = new UIColor((c) -> this.clip.shockwaveColor.set(c)).noLabel();
        this.shockwaveColor.tooltip(IKey.constant("Shockwave colour"));

        this.shockwaveRadius = new UITrackpad((value) -> this.clip.shockwaveRadius.set(value.floatValue()));
        this.shockwaveRadius.limit(0.1F, 2F).tooltip(IKey.constant("Shockwave max radius"));

        this.shockwaveWidth = new UITrackpad((value) -> this.clip.shockwaveWidth.set(value.floatValue()));
        this.shockwaveWidth.limit(0.005F, 0.3F).tooltip(IKey.constant("Shockwave ring thickness"));

        this.flashStar = new UITrackpad((value) -> this.clip.flashStar.set(value.floatValue()));
        this.flashStar.limit(0F, 1F).tooltip(IKey.constant("Contact flash star amount (4-point cross-star + glow at the focus)"));

        this.flashStarSize = new UITrackpad((value) -> this.clip.flashStarSize.set(value.floatValue()));
        this.flashStarSize.limit(0.02F, 1F).tooltip(IKey.constant("Flash star ray length"));

        this.flashStarWidth = new UITrackpad((value) -> this.clip.flashStarWidth.set(value.floatValue()));
        this.flashStarWidth.limit(0.002F, 0.1F).tooltip(IKey.constant("Flash star ray thickness"));

        this.flashStarGlow = new UITrackpad((value) -> this.clip.flashStarGlow.set(value.floatValue()));
        this.flashStarGlow.limit(0F, 1F).tooltip(IKey.constant("Flash star central glow size"));

        this.flashStarRotation = new UITrackpad((value) -> this.clip.flashStarRotation.set(value.floatValue()));
        this.flashStarRotation.limit(-90F, 90F).tooltip(IKey.constant("Flash star rotation (degrees)"));

        this.flashStarColor = new UIColor((c) -> this.clip.flashStarColor.set(c)).noLabel();
        this.flashStarColor.tooltip(IKey.constant("Flash star colour"));

        this.invert = new UITrackpad((value) -> this.clip.invert.set(value.floatValue()));
        this.invert.limit(0F, 1F).tooltip(IKey.constant("Invert colours"));

        this.flash = new UITrackpad((value) -> this.clip.flash.set(value.floatValue()));
        this.flash.limit(0F, 1F).tooltip(IKey.constant("White flash"));

        this.grayscale = new UITrackpad((value) -> this.clip.grayscale.set(value.floatValue()));
        this.grayscale.limit(0F, 1F).tooltip(IKey.constant("Grayscale"));

        this.threshold = new UITrackpad((value) -> this.clip.threshold.set(value.floatValue()));
        this.threshold.limit(0F, 1F).tooltip(IKey.constant("Threshold (duotone) amount"));

        this.thresholdLevel = new UITrackpad((value) -> this.clip.thresholdLevel.set(value.floatValue()));
        this.thresholdLevel.limit(0F, 1F).tooltip(IKey.constant("Threshold level (luma cutoff)"));

        this.thresholdSoft = new UITrackpad((value) -> this.clip.thresholdSoft.set(value.floatValue()));
        this.thresholdSoft.limit(0F, 1F).tooltip(IKey.constant("Threshold edge softness"));

        this.darkColor = new UIColor((c) -> this.clip.darkColor.set(c)).noLabel();
        this.darkColor.tooltip(IKey.constant("Threshold dark colour"));

        this.lightColor = new UIColor((c) -> this.clip.lightColor.set(c)).noLabel();
        this.lightColor.tooltip(IKey.constant("Threshold light colour"));

        this.chroma = new UITrackpad((value) -> this.clip.chroma.set(value.floatValue()));
        this.chroma.limit(0F, 1F).tooltip(IKey.constant("Chromatic aberration (radial from focus)"));

        this.focusX = new UITrackpad((value) -> this.clip.focusX.set(value.floatValue()));
        this.focusX.limit(0F, 1F).tooltip(IKey.constant("Focus X (0..1 screen)"));

        this.focusY = new UITrackpad((value) -> this.clip.focusY.set(value.floatValue()));
        this.focusY.limit(0F, 1F).tooltip(IKey.constant("Focus Y (0..1 screen)"));

        this.zoomBlur = new UITrackpad((value) -> this.clip.zoomBlur.set(value.floatValue()));
        this.zoomBlur.limit(0F, 1F).tooltip(IKey.constant("Blur amount"));

        this.blurMode = new UICirculate((b) -> this.clip.blurMode.set(b.getValue()));
        this.blurMode.addLabel(IKey.constant("Zoom"));
        this.blurMode.addLabel(IKey.constant("Horizontal"));
        this.blurMode.addLabel(IKey.constant("Vertical"));

        this.zoomLines = new UITrackpad((value) -> this.clip.zoomLines.set(value.floatValue()));
        this.zoomLines.limit(0F, 1F).tooltip(IKey.constant("Zoom lines amount (concentration lines)"));

        this.linesCount = new UITrackpad((value) -> this.clip.linesCount.set(value.floatValue()));
        this.linesCount.limit(1F, 360F).increment(1F).integer().tooltip(IKey.constant("Lines count"));

        this.linesThickness = new UITrackpad((value) -> this.clip.linesThickness.set(value.floatValue()));
        this.linesThickness.limit(0.02F, 1F).tooltip(IKey.constant("Lines thickness"));

        this.linesInner = new UITrackpad((value) -> this.clip.linesInner.set(value.floatValue()));
        this.linesInner.limit(0F, 1F).tooltip(IKey.constant("Lines inner radius (centre kept clear)"));

        this.linesMode = new UICirculate((b) -> this.clip.linesMode.set(b.getValue()));
        this.linesMode.addLabel(IKey.constant("Zoom"));
        this.linesMode.addLabel(IKey.constant("Vertical"));
        this.linesMode.addLabel(IKey.constant("Horizontal"));

        this.linesSeed = new UITrackpad((value) -> this.clip.linesSeed.set(value.intValue()));
        this.linesSeed.limit(0F, 9999F).increment(1F).integer().tooltip(IKey.constant("Lines seed (changes the pattern)"));

        this.linesColor = new UIColor((c) -> this.clip.linesColor.set(c)).noLabel();
        this.linesColor.tooltip(IKey.constant("Lines colour"));

        this.shapes = new UITrackpad((value) -> this.clip.shapes.set(value.floatValue()));
        this.shapes.limit(0F, 1F).tooltip(IKey.constant("Shapes amount"));

        this.shapesCount = new UITrackpad((value) -> this.clip.shapesCount.set(value.floatValue()));
        this.shapesCount.limit(1F, 24F).increment(1F).integer().tooltip(IKey.constant("Shapes count"));

        this.shapesSize = new UITrackpad((value) -> this.clip.shapesSize.set(value.floatValue()));
        this.shapesSize.limit(0.005F, 0.5F).tooltip(IKey.constant("Shapes size"));

        this.shapesSpread = new UITrackpad((value) -> this.clip.shapesSpread.set(value.floatValue()));
        this.shapesSpread.limit(0F, 2F).tooltip(IKey.constant("Shapes spread around focus"));

        this.centerStar = new UIToggle(IKey.constant("Center star"), (b) -> this.clip.centerStar.set(b.getValue() ? 1F : 0F));
        this.centerCircle = new UIToggle(IKey.constant("Center circle"), (b) -> this.clip.centerCircle.set(b.getValue() ? 1F : 0F));

        this.shapesColor = new UIColor((c) -> this.clip.shapesColor.set(c)).noLabel();
        this.shapesColor.tooltip(IKey.constant("Shapes colour"));

        this.shapesDelay = new UITrackpad((value) -> this.clip.shapesDelay.set(value.floatValue()));
        this.shapesDelay.limit(0F, 20F).tooltip(IKey.constant("Debris beat: delay the scattered shapes by N ticks (land after the impact)"));
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(this.preset.marginTop(UIConstants.SECTION_GAP));
        this.panels.add(BbsVfxUI.section(IKey.constant("Keyframes"), this.editKeyframes));

        this.panels.add(BbsVfxUI.section(
            IKey.constant("Silhouette / negative space"),
            UI.row(this.silhouette),
            this.target,
            this.palette,
            UI.row(this.silColor, this.bgColor),
            UI.row(this.silStrokes, this.silStrokeRough),
            UI.row(this.silStrokeAngle, this.silStrokeLength),
            UI.row(this.silStrokeScale)
        ));

        this.panels.add(BbsVfxUI.section(
            IKey.constant("Ink burst"),
            UI.row(this.inkBurst, this.inkColor),
            UI.row(this.inkRadius, this.inkInner),
            UI.row(this.inkSpikes, this.inkRough),
            UI.row(this.inkSeed)
        ));

        this.panels.add(BbsVfxUI.section(
            IKey.constant("Shockwave"),
            UI.row(this.shockwave, this.shockwaveProgress),
            UI.row(this.shockwaveColor),
            UI.row(this.shockwaveRadius, this.shockwaveWidth)
        ));

        this.panels.add(BbsVfxUI.section(
            IKey.constant("Flash star"),
            UI.row(this.flashStar, this.flashStarColor),
            UI.row(this.flashStarSize, this.flashStarWidth),
            UI.row(this.flashStarGlow, this.flashStarRotation)
        ));

        this.panels.add(BbsVfxUI.section(
            IKey.constant("Impact"),
            UI.row(this.invert, this.flash, this.grayscale)
        ));

        this.panels.add(BbsVfxUI.section(
            IKey.constant("Threshold"),
            UI.row(this.threshold, this.thresholdLevel, this.thresholdSoft),
            UI.row(this.darkColor, this.lightColor)
        ));

        this.panels.add(BbsVfxUI.section(
            IKey.constant("Chromatic aberration + focus"),
            UI.row(this.chroma, this.focusX, this.focusY)
        ));

        this.panels.add(BbsVfxUI.section(
            IKey.constant("Zoom blur"),
            UI.row(this.zoomBlur, this.blurMode)
        ));

        this.panels.add(BbsVfxUI.section(
            IKey.constant("Lines (zoom / vertical / horizontal)"),
            UI.row(this.linesMode),
            UI.row(this.zoomLines, this.linesCount),
            UI.row(this.linesThickness, this.linesInner),
            UI.row(this.linesSeed),
            UI.row(this.linesColor)
        ));

        this.panels.add(BbsVfxUI.section(
            IKey.constant("Shapes (stars + ring circles)"),
            UI.row(this.shapes, this.shapesCount, this.shapesSize),
            UI.row(this.shapesSpread, this.shapesDelay),
            UI.row(this.centerStar, this.centerCircle),
            UI.row(this.shapesColor)
        ));
    }

    @Override
    public void fillData()
    {
        super.fillData();

        this.bbsvfx$setupSheets();

        this.silhouette.setValue(this.clip.silhouette.get());
        this.silColor.setColor(this.clip.silColor.get());
        this.bgColor.setColor(this.clip.bgColor.get());
        this.silStrokes.setValue(this.clip.silStrokes.get());
        this.silStrokeAngle.setValue(this.clip.silStrokeAngle.get());
        this.silStrokeLength.setValue(this.clip.silStrokeLength.get());
        this.silStrokeScale.setValue(this.clip.silStrokeScale.get());
        this.silStrokeRough.setValue(this.clip.silStrokeRough.get());
        this.target.label = IKey.constant(this.bbsvfx$targetLabel(this.clip.target.get()));

        this.inkBurst.setValue(this.clip.inkBurst.get());
        this.inkColor.setColor(this.clip.inkColor.get());
        this.inkRadius.setValue(this.clip.inkRadius.get());
        this.inkInner.setValue(this.clip.inkInner.get());
        this.inkSpikes.setValue(this.clip.inkSpikes.get());
        this.inkRough.setValue(this.clip.inkRough.get());
        this.inkSeed.setValue(this.clip.inkSeed.get());

        this.shockwave.setValue(this.clip.shockwave.get());
        this.shockwaveProgress.setValue(this.clip.shockwaveProgress.get());
        this.shockwaveColor.setColor(this.clip.shockwaveColor.get());
        this.shockwaveRadius.setValue(this.clip.shockwaveRadius.get());
        this.shockwaveWidth.setValue(this.clip.shockwaveWidth.get());

        this.flashStar.setValue(this.clip.flashStar.get());
        this.flashStarSize.setValue(this.clip.flashStarSize.get());
        this.flashStarWidth.setValue(this.clip.flashStarWidth.get());
        this.flashStarGlow.setValue(this.clip.flashStarGlow.get());
        this.flashStarRotation.setValue(this.clip.flashStarRotation.get());
        this.flashStarColor.setColor(this.clip.flashStarColor.get());

        this.invert.setValue(this.clip.invert.get());
        this.flash.setValue(this.clip.flash.get());
        this.grayscale.setValue(this.clip.grayscale.get());

        this.threshold.setValue(this.clip.threshold.get());
        this.thresholdLevel.setValue(this.clip.thresholdLevel.get());
        this.thresholdSoft.setValue(this.clip.thresholdSoft.get());
        this.darkColor.setColor(this.clip.darkColor.get());
        this.lightColor.setColor(this.clip.lightColor.get());

        this.chroma.setValue(this.clip.chroma.get());
        this.focusX.setValue(this.clip.focusX.get());
        this.focusY.setValue(this.clip.focusY.get());

        this.zoomBlur.setValue(this.clip.zoomBlur.get());
        this.blurMode.setValue(this.clip.blurMode.get());

        this.zoomLines.setValue(this.clip.zoomLines.get());
        this.linesCount.setValue(this.clip.linesCount.get());
        this.linesThickness.setValue(this.clip.linesThickness.get());
        this.linesInner.setValue(this.clip.linesInner.get());
        this.linesMode.setValue(this.clip.linesMode.get());
        this.linesSeed.setValue(this.clip.linesSeed.get());
        this.linesColor.setColor(this.clip.linesColor.get());

        this.shapes.setValue(this.clip.shapes.get());
        this.shapesCount.setValue(this.clip.shapesCount.get());
        this.shapesSize.setValue(this.clip.shapesSize.get());
        this.shapesSpread.setValue(this.clip.shapesSpread.get());
        this.centerStar.setValue(this.clip.centerStar.get() != 0F);
        this.centerCircle.setValue(this.clip.centerCircle.get() != 0F);
        this.shapesColor.setColor(this.clip.shapesColor.get());
        this.shapesDelay.setValue(this.clip.shapesDelay.get());
    }

    /* ---- Full impact-frame presets (curated by the references) ---- */

    private static final List<String> PRESET_NAMES = Arrays.asList(
        "Charcoal Slam (B&W)", "Color Swap (aura)", "Manga Speed Lines", "Spider-Verse Pop", "White Flash Hit");

    /* Sheet colours for the keyframe channels (ARGB), cycled. */
    private static final int[] KF_COLORS = {
        0xFFFF5A5A, 0xFFFFA64D, 0xFFFFE14D, 0xFF9BD34D, 0xFF4DD39B, 0xFF4DC3D3, 0xFF4D8FD3, 0xFF6A4DD3,
        0xFFB34DD3, 0xFFD34D9B, 0xFFD3804D, 0xFFBBBBBB, 0xFFFFFFFF, 0xFF4DD3D3, 0xFFD34D4D, 0xFF9B9BFF,
        0xFF4DFFB3, 0xFFFFB34D};

    private int bbsvfx$colorIdx;

    /** Track rows the user has folded right now; handed to the dope sheet, which folds them in place. */
    private final FoldState<String> bbsvfx$folds = new FoldState<>();

    /** (Re)build the graph tracks as collapsible SECTION groups (mirrors the slider sections). Each
     * section's first channel is its header (titled by the section); the rest hang under it via
     * UIKeyframeSheet#setParent, which is how BBS 2.6 nests rows (it replaced the old
     * configurePoseTabs(parent -> children) maps). */
    private void bbsvfx$setupSheets()
    {
        UIKeyframes view = this.keyframes.view;

        view.removeAllSheets();
        this.bbsvfx$colorIdx = 0;

        ImpactClip c = this.clip;

        this.bbsvfx$section(view, "Silhouette",
            new KeyframeChannel[]{c.silhouetteKf, c.silStrokesKf, c.silStrokeAngleKf, c.silStrokeLengthKf, c.silStrokeScaleKf, c.silStrokeRoughKf},
            new String[]{"Strokes", "Stroke angle", "Stroke length", "Stroke scale", "Stroke rough"});
        this.bbsvfx$section(view, "Ink burst",
            new KeyframeChannel[]{c.inkBurstKf, c.inkRadiusKf, c.inkInnerKf, c.inkSpikesKf, c.inkRoughKf, c.inkSeedKf},
            new String[]{"Radius", "Inner", "Spikes", "Rough", "Seed"});
        this.bbsvfx$section(view, "Shockwave",
            new KeyframeChannel[]{c.shockwaveKf, c.shockwaveProgressKf, c.shockwaveRadiusKf, c.shockwaveWidthKf},
            new String[]{"Progress", "Radius", "Width"});
        this.bbsvfx$section(view, "Flash star",
            new KeyframeChannel[]{c.flashStarKf, c.flashStarSizeKf, c.flashStarWidthKf, c.flashStarGlowKf, c.flashStarRotationKf},
            new String[]{"Size", "Width", "Glow", "Rotation"});
        this.bbsvfx$section(view, "Focus",
            new KeyframeChannel[]{c.focusXKf, c.focusYKf},
            new String[]{"Focus Y"});
        this.bbsvfx$section(view, "Full-screen",
            new KeyframeChannel[]{c.invertKf, c.flashKf, c.grayscaleKf, c.thresholdKf, c.thresholdLevelKf, c.thresholdSoftKf, c.chromaKf},
            new String[]{"Flash", "Grayscale", "Threshold", "Thr. level", "Thr. soft", "Chroma"});
        this.bbsvfx$section(view, "Zoom blur",
            new KeyframeChannel[]{c.zoomBlurKf},
            new String[]{});
        this.bbsvfx$section(view, "Lines",
            new KeyframeChannel[]{c.zoomLinesKf, c.linesCountKf, c.linesThicknessKf, c.linesInnerKf, c.linesSeedKf},
            new String[]{"Count", "Thickness", "Inner", "Seed"});
        this.bbsvfx$section(view, "Shapes",
            new KeyframeChannel[]{c.shapesKf, c.shapesCountKf, c.shapesSizeKf, c.shapesSpreadKf, c.shapesDelayKf, c.centerStarKf, c.centerCircleKf},
            new String[]{"Count", "Size", "Spread", "Delay", "Center star", "Center circle"});

        view.getDopeSheet().setExpanded(this.bbsvfx$folds);
    }

    /** Add one section: a header sheet (channels[0], titled by the section) + collapsible child sheets. */
    private void bbsvfx$section(UIKeyframes view, String section,
        KeyframeChannel[] channels, String[] childNames)
    {
        UIKeyframeSheet header = new UIKeyframeSheet(channels[0].getId(), IKey.constant(section),
            KF_COLORS[this.bbsvfx$colorIdx++ % KF_COLORS.length], channels[0], null);
        view.addSheet(header);

        for (int i = 0; i < childNames.length; i++)
        {
            UIKeyframeSheet child = new UIKeyframeSheet(channels[i + 1].getId(), IKey.constant(childNames[i]),
                KF_COLORS[this.bbsvfx$colorIdx++ % KF_COLORS.length], channels[i + 1], null);

            child.setParent(header);
            view.addSheet(child);
        }
    }

    private void bbsvfx$openPresets()
    {
        UIListOverlayPanel overlay = new UIListOverlayPanel(IKey.constant("Impact preset"), (value) ->
        {
            int idx = PRESET_NAMES.indexOf(value);

            if (idx >= 0)
            {
                this.bbsvfx$applyPreset(idx);
            }
        });

        overlay.addValues(PRESET_NAMES);
        UIOverlay.addOverlay(this.getContext(), overlay);
    }

    /** Zero every effect amount so a preset starts from a clean slate (params/colours are then set per preset). */
    private void bbsvfx$zeroAll()
    {
        this.clip.silhouette.set(0F);
        this.clip.silStrokes.set(0F);
        this.clip.inkBurst.set(0F);
        this.clip.shockwave.set(0F);
        this.clip.flashStar.set(0F);
        this.clip.invert.set(0F);
        this.clip.flash.set(0F);
        this.clip.grayscale.set(0F);
        this.clip.threshold.set(0F);
        this.clip.chroma.set(0F);
        this.clip.zoomBlur.set(0F);
        this.clip.zoomLines.set(0F);
        this.clip.shapes.set(0F);
        this.clip.centerStar.set(0F);
        this.clip.centerCircle.set(0F);
    }

    private void bbsvfx$applyPreset(int p)
    {
        this.bbsvfx$zeroAll();

        switch (p)
        {
            case 0 -> /* Charcoal Slam (B&W sumi-e) */
            {
                this.clip.silhouette.set(1F);
                this.clip.silColor.set(0x000000);
                this.clip.bgColor.set(0xFFFFFF);
                this.clip.silStrokes.set(0.7F);
                this.clip.silStrokeRough.set(0.7F);
                this.clip.silStrokeLength.set(0.06F);
                this.clip.silStrokeScale.set(40F);
                this.clip.inkBurst.set(1F);
                this.clip.inkColor.set(0x000000);
                this.clip.inkRadius.set(0.7F);
                this.clip.inkInner.set(0.08F);
                this.clip.inkSpikes.set(26F);
                this.clip.inkRough.set(0.8F);
                this.clip.zoomLines.set(0.6F);
                this.clip.linesMode.set(0);
                this.clip.linesCount.set(80F);
                this.clip.linesColor.set(0x000000);
                this.clip.linesThickness.set(0.25F);
                this.clip.linesInner.set(0.35F);
                this.clip.flashStar.set(0.8F);
                this.clip.flashStarColor.set(0x000000);
                this.clip.flashStarSize.set(0.3F);
                this.clip.flashStarWidth.set(0.01F);
                this.clip.flashStarGlow.set(0F);
                this.clip.shockwave.set(0.6F);
                this.clip.shockwaveColor.set(0x000000);
                this.clip.shockwaveRadius.set(0.8F);
                this.clip.shockwaveWidth.set(0.03F);
                this.clip.shockwaveProgress.set(0.5F);
            }
            case 1 -> /* Color Swap (power-up aura) */
            {
                this.clip.silhouette.set(1F);
                this.clip.silColor.set(0xFF7A1A);
                this.clip.bgColor.set(0x0A0E2A);
                this.clip.silStrokes.set(0.3F);
                this.clip.silStrokeRough.set(0.6F);
                this.clip.inkBurst.set(1F);
                this.clip.inkColor.set(0xFF5A1F);
                this.clip.inkRadius.set(0.85F);
                this.clip.inkSpikes.set(30F);
                this.clip.inkRough.set(0.7F);
                this.clip.zoomLines.set(0.5F);
                this.clip.linesMode.set(0);
                this.clip.linesColor.set(0xFFAA33);
                this.clip.linesCount.set(70F);
                this.clip.flashStar.set(1F);
                this.clip.flashStarColor.set(0xFFFFFF);
                this.clip.flashStarSize.set(0.35F);
                this.clip.flashStarGlow.set(0.4F);
                this.clip.chroma.set(0.3F);
                this.clip.shockwave.set(0.5F);
                this.clip.shockwaveColor.set(0xFF7A1A);
                this.clip.shockwaveProgress.set(0.5F);
            }
            case 2 -> /* Manga Speed Lines */
            {
                this.clip.silhouette.set(1F);
                this.clip.silColor.set(0x000000);
                this.clip.bgColor.set(0xFFFFFF);
                this.clip.zoomLines.set(0.9F);
                this.clip.linesMode.set(0);
                this.clip.linesCount.set(120F);
                this.clip.linesColor.set(0x000000);
                this.clip.linesThickness.set(0.2F);
                this.clip.linesInner.set(0.3F);
                this.clip.inkBurst.set(0.4F);
                this.clip.inkColor.set(0x000000);
                this.clip.inkRadius.set(0.5F);
                this.clip.flashStar.set(0.7F);
                this.clip.flashStarColor.set(0xE01010);
                this.clip.flashStarSize.set(0.3F);
                this.clip.shapes.set(0.5F);
                this.clip.shapesColor.set(0xE01010);
                this.clip.shapesCount.set(10F);
            }
            case 3 -> /* Spider-Verse Pop (graphic comic, no silhouette) */
            {
                this.clip.shapes.set(1F);
                this.clip.shapesColor.set(0xFFFFFF);
                this.clip.shapesCount.set(12F);
                this.clip.shapesSize.set(0.06F);
                this.clip.shapesSpread.set(1F);
                this.clip.centerStar.set(1F);
                this.clip.centerCircle.set(1F);
                this.clip.shapesColor.set(0x33E0FF);
                this.clip.chroma.set(0.5F);
                this.clip.flash.set(0.3F);
                this.clip.zoomBlur.set(0.3F);
                this.clip.blurMode.set(0);
                this.clip.zoomLines.set(0.4F);
                this.clip.linesMode.set(0);
                this.clip.linesColor.set(0x33E0FF);
                this.clip.linesCount.set(40F);
            }
            case 4 -> /* White Flash Hit (quick hard flash) */
            {
                this.clip.flash.set(0.8F);
                this.clip.invert.set(0.4F);
                this.clip.grayscale.set(0.6F);
                this.clip.zoomBlur.set(0.5F);
                this.clip.blurMode.set(0);
                this.clip.zoomLines.set(0.6F);
                this.clip.linesMode.set(0);
                this.clip.linesColor.set(0xFFFFFF);
                this.clip.linesCount.set(60F);
                this.clip.flashStar.set(1F);
                this.clip.flashStarColor.set(0xFFFFFF);
                this.clip.flashStarSize.set(0.4F);
                this.clip.flashStarGlow.set(0.5F);
                this.clip.shockwave.set(0.6F);
                this.clip.shockwaveColor.set(0xFFFFFF);
                this.clip.shockwaveProgress.set(0.5F);
                this.clip.chroma.set(0.2F);
            }
        }

        this.preset.label = IKey.constant("Preset: " + PRESET_NAMES.get(p));
        this.fillData();
    }

    /* Colour presets (RGB) for {silhouette, background, ink}, applied by the Palette button. */
    private static final int[][] PALETTES = {
        {0x000000, 0xFFFFFF, 0x000000},   // B&W
        {0x000000, 0xFFFFFF, 0xE01010},   // B&W + red accent (red ink)
        {0xFFFFFF, 0x000000, 0xFFFFFF},   // Inverted (white on black)
        {0xFF7A1A, 0x0A0E2A, 0xFF3B1F},   // Colour-swap (orange aura on navy)
    };
    private static final String[] PALETTE_NAMES = {"B&W", "B&W + red", "Inverted", "Colour-swap"};
    private int bbsvfx$paletteIndex = 0;

    /** Apply the next colour preset to the silhouette / background / ink, and refresh the pickers. */
    private void bbsvfx$cyclePalette()
    {
        this.bbsvfx$paletteIndex = (this.bbsvfx$paletteIndex + 1) % PALETTE_NAMES.length;
        int[] p = PALETTES[this.bbsvfx$paletteIndex];

        this.clip.silColor.set(p[0]);
        this.clip.bgColor.set(p[1]);
        this.clip.inkColor.set(p[2]);

        this.silColor.setColor(p[0]);
        this.bgColor.setColor(p[1]);
        this.inkColor.setColor(p[2]);

        this.palette.label = IKey.constant("Palette: " + PALETTE_NAMES[this.bbsvfx$paletteIndex]);
    }

    /** Cycle the silhouette target through the film's actors: All -> #0 -> #1 -> ... -> All. */
    private void bbsvfx$cycleTarget()
    {
        Film film = this.editor.getFilm();
        int count = film != null ? film.replays.getList().size() : 0;
        int next = this.clip.target.get() + 1;

        if (next >= count)
        {
            next = -1;
        }

        this.clip.target.set(next);
        this.target.label = IKey.constant(this.bbsvfx$targetLabel(next));
    }

    private String bbsvfx$targetLabel(int index)
    {
        if (index < 0)
        {
            return "All actors";
        }

        Film film = this.editor.getFilm();

        if (film == null || index >= film.replays.getList().size())
        {
            return "All actors";
        }

        String label = film.replays.getList().get(index).label.get();

        return (label == null || label.isEmpty() ? "Actor" : label) + " #" + index;
    }
}
