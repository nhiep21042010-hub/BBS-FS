package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.pose.UIPoseEditor;
import com.bbsvfx.bbsvfx.client.BbsVfxUI;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import com.bbsvfx.bbsvfx.client.BbsVfxSection;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.ISmearPoseEditor;
import com.bbsvfx.bbsvfx.forms.ISmearBone;

import java.util.List;
import java.util.function.Consumer;

/**
 * Adds the per-bone "Smear" controls to the pose editor, organised into two collapsible modes — Manual
 * (the straight smear vector) and Auto (the time-rewound motion arc) — plus a shared Count/Fade/Dissolve
 * row. One bone uses one mode, so the Manual/Auto toggles are mutually exclusive; toggling one expands its
 * settings (Auto also enables arc). The same {@link UIPoseEditor} serves the live pose and the smear track.
 */
@Mixin(UIPoseEditor.class)
public abstract class UIPoseEditorSmearMixin implements ISmearPoseEditor
{
    @Shadow
    private Pose pose;

    @Shadow
    private void forEachSelectedPose(Consumer<? super PoseTransform> consumer)
    {
        throw new AssertionError();
    }

    @Unique private UITrackpad bbsvfx$smearX;
    @Unique private UITrackpad bbsvfx$smearY;
    @Unique private UITrackpad bbsvfx$smearZ;
    @Unique private UITrackpad bbsvfx$smearCount;
    @Unique private UITrackpad bbsvfx$smearFalloff;
    @Unique private UITrackpad bbsvfx$smearDissolve;
    @Unique private UIToggle bbsvfx$smearManual;
    @Unique private UIToggle bbsvfx$smearArc;
    @Unique private UITrackpad bbsvfx$smearTime;
    @Unique private UITrackpad bbsvfx$smearStretch;
    @Unique private UITrackpad bbsvfx$smearDensity;
    @Unique private UITrackpad bbsvfx$smearOpacity;
    @Unique private UIToggle bbsvfx$linesAuto;
    @Unique private UIToggle bbsvfx$linesManual;
    @Unique private UITrackpad bbsvfx$smearLinesCount;
    @Unique private UITrackpad bbsvfx$smearLinesWidth;
    @Unique private UITrackpad bbsvfx$smearLinesSpread;
    @Unique private UITrackpad bbsvfx$smearLinesReach;
    @Unique private UITrackpad bbsvfx$smearLinesLength;
    @Unique private UIToggle bbsvfx$smearLinesBlunt;
    @Unique private UIToggle bbsvfx$smearLinesTexture;

    @Unique private UIElement bbsvfx$linesRow1;
    @Unique private UIElement bbsvfx$linesRow2;
    @Unique private UIElement bbsvfx$linesAutoRow;
    @Unique private boolean bbsvfx$linesAutoOpen;
    @Unique private boolean bbsvfx$linesManualOpen;

    @Unique private UIElement bbsvfx$smearBlock;
    @Unique private UIElement bbsvfx$linesBlock;

    /* Collapsible section wrappers around the (rebuilt-in-place) blocks above — what the factory adds to
     * the pose editor layout. The inner block stays the rebuild target so the Manual/Auto logic is untouched. */
    @Unique private BbsVfxSection bbsvfx$smearSection;
    @Unique private BbsVfxSection bbsvfx$linesSection;

    /* Pre-built rows, added/removed on collapse (BBS columns don't skip invisible children). */
    @Unique private UIElement bbsvfx$manualRow;
    @Unique private UIElement bbsvfx$manualRow2;
    @Unique private UIElement bbsvfx$autoRow1;
    @Unique private UIElement bbsvfx$autoRow2;
    @Unique private UIElement bbsvfx$commonRow;

    @Unique private boolean bbsvfx$manualOpen;
    @Unique private boolean bbsvfx$autoOpen;

    @Override
    public UIElement bbsvfx$smearBlock()
    {
        return this.bbsvfx$smearSection;
    }

    @Override
    public UIElement bbsvfx$linesBlock()
    {
        return this.bbsvfx$linesSection;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$addSmear(CallbackInfo ci)
    {
        this.bbsvfx$smearX = new UITrackpad((v) -> this.bbsvfx$apply(0, v.floatValue())).values(0.05F);
        this.bbsvfx$smearY = new UITrackpad((v) -> this.bbsvfx$apply(1, v.floatValue())).values(0.05F);
        this.bbsvfx$smearZ = new UITrackpad((v) -> this.bbsvfx$apply(2, v.floatValue())).values(0.05F);

        /* Shared look (both modes): copies + fade + dissolve. */
        this.bbsvfx$smearCount = new UITrackpad((v) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearCount(v.floatValue())));
        this.bbsvfx$smearCount.limit(0F, 32F).increment(1).tooltip(IKey.constant("Smear copies (0 = default 4)"));
        this.bbsvfx$smearFalloff = new UITrackpad((v) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearFalloff(v.floatValue())));
        this.bbsvfx$smearFalloff.limit(0F, 1F).values(0.01F).tooltip(IKey.constant("Fade toward the tail (0 = default)"));
        this.bbsvfx$smearDissolve = new UITrackpad((v) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearDissolve(v.floatValue())));
        this.bbsvfx$smearDissolve.limit(0F, 1F).values(0.01F).tooltip(IKey.constant("Dissolve: noise holes eat the trailing copies (shared)"));

        /* Manual mode: the straight smear vector. */
        this.bbsvfx$smearManual = new UIToggle(IKey.constant("Manual"), (b) -> this.bbsvfx$selectMode(false, b.getValue()));
        this.bbsvfx$smearManual.tooltip(IKey.constant("Straight smear along a manual X/Y/Z vector"));

        /* Auto mode: echo copies follow the bone's real motion arc (time-rewound). The toggle also enables arc. */
        this.bbsvfx$smearArc = new UIToggle(IKey.constant("Auto"), (b) -> this.bbsvfx$selectMode(true, b.getValue()));
        this.bbsvfx$smearArc.tooltip(IKey.constant("Follow the bone's real movement arc (time-rewound), not a manual vector"));
        this.bbsvfx$smearTime = new UITrackpad((v) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearTime(v.floatValue())));
        this.bbsvfx$smearTime.limit(0F, 40F).values(0.5F).tooltip(IKey.constant("Arc trail length in ticks (0 = default 4)"));
        this.bbsvfx$smearStretch = new UITrackpad((v) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearStretch(v.floatValue())));
        this.bbsvfx$smearStretch.limit(0F, 4F).values(0.05F).tooltip(IKey.constant("Copy stretch along the bone length + squash (0 = off; keep 0 for a head turn)"));
        this.bbsvfx$smearDensity = new UITrackpad((v) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearDensity(v.floatValue())));
        this.bbsvfx$smearDensity.limit(0F, 48F).increment(1).tooltip(IKey.constant("Overlap density: faint in-between copies merging into a continuous smear (0 = default 16)"));
        this.bbsvfx$smearOpacity = new UITrackpad((v) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearOpacity(v.floatValue())));
        this.bbsvfx$smearOpacity.limit(0F, 1F).values(0.01F).tooltip(IKey.constant("Opacity of each overlap copy (0 = default 0.14)"));

        this.bbsvfx$manualRow = UI.row(this.bbsvfx$smearX, this.bbsvfx$smearY, this.bbsvfx$smearZ);
        this.bbsvfx$manualRow2 = UI.row(this.bbsvfx$smearCount, this.bbsvfx$smearFalloff);
        this.bbsvfx$autoRow1 = UI.row(this.bbsvfx$smearTime, this.bbsvfx$smearStretch);
        this.bbsvfx$autoRow2 = UI.row(this.bbsvfx$smearDensity, this.bbsvfx$smearOpacity);
        this.bbsvfx$commonRow = UI.row(this.bbsvfx$smearDissolve);

        /* Motion lines block — two collapsible modes (Auto arc / Manual hand-aimed), like the smear. */
        this.bbsvfx$linesAuto = new UIToggle(IKey.constant("Auto"), (b) -> this.bbsvfx$selectLinesMode(true, b.getValue()));
        this.bbsvfx$linesAuto.tooltip(IKey.constant("Lines follow the bone's real motion arc (volumetric, speed-driven)"));
        this.bbsvfx$linesManual = new UIToggle(IKey.constant("Manual"), (b) -> this.bbsvfx$selectLinesMode(false, b.getValue()));
        this.bbsvfx$linesManual.tooltip(IKey.constant("A fan of straight streaks you aim by hand (the channel rotation)"));

        this.bbsvfx$smearLinesCount = new UITrackpad((v) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearLinesCount(v.floatValue())));
        this.bbsvfx$smearLinesCount.limit(0F, 64F).increment(1).tooltip(IKey.constant("Lines count (0 = default)"));
        this.bbsvfx$smearLinesWidth = new UITrackpad((v) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearLinesWidth(v.floatValue())));
        this.bbsvfx$smearLinesWidth.limit(0F, Float.POSITIVE_INFINITY).values(0.001F).tooltip(IKey.constant("Lines thickness (0 = default)"));
        this.bbsvfx$smearLinesSpread = new UITrackpad((v) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearLinesSpread(v.floatValue())));
        this.bbsvfx$smearLinesSpread.limit(0F, Float.POSITIVE_INFINITY).values(0.01F).tooltip(IKey.constant("Auto: volume radius around the limb. Manual: fan spread. (0 = default)"));
        this.bbsvfx$smearLinesLength = new UITrackpad((v) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearLinesOffset(s.bbsvfx$smearLinesOffsetX(), v.floatValue(), s.bbsvfx$smearLinesOffsetZ())));
        this.bbsvfx$smearLinesLength.limit(0F, Float.POSITIVE_INFINITY).values(0.05F).tooltip(IKey.constant("Length multiplier (0 = default)"));
        this.bbsvfx$smearLinesReach = new UITrackpad((v) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearLinesOffset(v.floatValue(), s.bbsvfx$smearLinesOffsetY(), s.bbsvfx$smearLinesOffsetZ())));
        this.bbsvfx$smearLinesReach.limit(0F, Float.POSITIVE_INFINITY).values(0.02F).tooltip(IKey.constant("Reach: push the line origins down the limb onto the hand (0 = auto)"));
        this.bbsvfx$smearLinesBlunt = new UIToggle(IKey.constant("Blunt ends"), (b) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearLinesOffset(s.bbsvfx$smearLinesOffsetX(), s.bbsvfx$smearLinesOffsetY(), b.getValue() ? 1F : 0F)));
        this.bbsvfx$smearLinesBlunt.tooltip(IKey.constant("Uniform width instead of tapering the ends to a point"));
        this.bbsvfx$smearLinesTexture = new UIToggle(IKey.constant("Texture color"), (b) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearLinesTexture(b.getValue() ? 1F : 0F)));
        this.bbsvfx$smearLinesTexture.tooltip(IKey.constant("Tint lines with the actor's texture instead of the Color picker"));

        this.bbsvfx$smearBlock = UI.column();
        this.bbsvfx$rebuildSmear();
        this.bbsvfx$smearSection = BbsVfxUI.section(IKey.constant("Smear"), this.bbsvfx$smearBlock);

        /* Pre-build the rows ONCE and re-add the SAME objects on rebuild — building UI.row() fresh each time
         * re-parents the toggles and makes them vanish. */
        this.bbsvfx$linesRow1 = UI.row(this.bbsvfx$smearLinesCount, this.bbsvfx$smearLinesWidth, this.bbsvfx$smearLinesSpread);
        this.bbsvfx$linesRow2 = UI.row(this.bbsvfx$smearLinesLength, this.bbsvfx$smearLinesTexture);
        this.bbsvfx$linesAutoRow = UI.row(this.bbsvfx$smearLinesReach, this.bbsvfx$smearLinesBlunt);

        this.bbsvfx$linesBlock = UI.column();
        this.bbsvfx$rebuildLines();
        this.bbsvfx$linesSection = BbsVfxUI.section(IKey.constant("Motion lines"), this.bbsvfx$linesBlock);
    }

    /** Rebuild the lines block: the two mode toggles, the shared rows, and the auto-only row when Auto is on. */
    @Unique
    private void bbsvfx$rebuildLines()
    {
        this.bbsvfx$linesBlock.removeAll();
        this.bbsvfx$linesBlock.add(this.bbsvfx$linesAuto.marginTop(6));
        this.bbsvfx$linesBlock.add(this.bbsvfx$linesManual);

        if (this.bbsvfx$linesAutoOpen || this.bbsvfx$linesManualOpen)
        {
            this.bbsvfx$linesBlock.add(this.bbsvfx$linesRow1, this.bbsvfx$linesRow2);

            if (this.bbsvfx$linesAutoOpen)
            {
                this.bbsvfx$linesBlock.add(this.bbsvfx$linesAutoRow);
            }
        }
    }

    /** Mutually exclusive Auto/Manual for the lines; the mode is stored in the enable value (1 = auto, 2 = manual). */
    @Unique
    private void bbsvfx$selectLinesMode(boolean auto, boolean on)
    {
        if (auto)
        {
            this.bbsvfx$linesAutoOpen = on;

            if (on)
            {
                this.bbsvfx$linesManualOpen = false;
            }
        }
        else
        {
            this.bbsvfx$linesManualOpen = on;

            if (on)
            {
                this.bbsvfx$linesAutoOpen = false;
            }
        }

        float mode = this.bbsvfx$linesAutoOpen ? 1F : (this.bbsvfx$linesManualOpen ? 2F : 0F);
        this.bbsvfx$forEach((s) -> s.bbsvfx$setSmearLines(mode));

        this.bbsvfx$linesAuto.setValue(this.bbsvfx$linesAutoOpen);
        this.bbsvfx$linesManual.setValue(this.bbsvfx$linesManualOpen);
        this.bbsvfx$rebuildLines();
        this.bbsvfx$reflowFrom(this.bbsvfx$linesBlock);
    }

    /** Rebuild the smear block to show only the expanded mode's rows + the shared row. */
    @Unique
    private void bbsvfx$rebuildSmear()
    {
        this.bbsvfx$smearBlock.removeAll();
        this.bbsvfx$smearBlock.add(this.bbsvfx$smearManual.marginTop(6));

        if (this.bbsvfx$manualOpen)
        {
            this.bbsvfx$smearBlock.add(this.bbsvfx$manualRow, this.bbsvfx$manualRow2);
        }

        this.bbsvfx$smearBlock.add(this.bbsvfx$smearArc);

        if (this.bbsvfx$autoOpen)
        {
            this.bbsvfx$smearBlock.add(this.bbsvfx$autoRow1, this.bbsvfx$autoRow2);
        }

        this.bbsvfx$smearBlock.add(this.bbsvfx$commonRow);
    }

    /** Mutually exclusive Manual/Auto: opening one closes the other; Auto also drives the bone's arc flag. */
    @Unique
    private void bbsvfx$selectMode(boolean auto, boolean on)
    {
        if (auto)
        {
            this.bbsvfx$autoOpen = on;

            if (on)
            {
                this.bbsvfx$manualOpen = false;
            }

            this.bbsvfx$forEach((s) ->
            {
                s.bbsvfx$setSmearArc(on ? 1F : 0F);

                if (on)
                {
                    s.bbsvfx$setSmearManual(0F);
                }
            });
        }
        else
        {
            this.bbsvfx$manualOpen = on;

            if (on)
            {
                this.bbsvfx$autoOpen = false;
            }

            this.bbsvfx$forEach((s) ->
            {
                s.bbsvfx$setSmearManual(on ? 1F : 0F);

                if (on)
                {
                    s.bbsvfx$setSmearArc(0F);
                }
            });
        }

        this.bbsvfx$smearManual.setValue(this.bbsvfx$manualOpen);
        this.bbsvfx$smearArc.setValue(this.bbsvfx$autoOpen);
        this.bbsvfx$rebuildSmear();
        this.bbsvfx$reflow();
    }

    @Unique
    private void bbsvfx$reflow()
    {
        this.bbsvfx$reflowFrom(this.bbsvfx$smearBlock);
    }

    /** Re-flow the whole tree from the given block — the smear track has smearBlock in the tree, the lines
     *  track has linesBlock; starting from the wrong one resizes nothing and the panel doesn't refresh. */
    @Unique
    private void bbsvfx$reflowFrom(UIElement start)
    {
        UIElement root = start;

        while (root.getParent() != null)
        {
            root = root.getParent();
        }

        root.resize();
    }

    @Unique
    private void bbsvfx$forEach(Consumer<ISmearBone> action)
    {
        this.forEachSelectedPose((pt) -> action.accept((ISmearBone) pt));
    }

    @Unique
    private void bbsvfx$apply(int axis, float value)
    {
        this.bbsvfx$forEach((s) ->
        {
            float x = s.bbsvfx$smearX();
            float y = s.bbsvfx$smearY();
            float z = s.bbsvfx$smearZ();

            if (axis == 0)
            {
                x = value;
            }
            else if (axis == 1)
            {
                y = value;
            }
            else
            {
                z = value;
            }

            s.bbsvfx$setSmear(x, y, z);
        });
    }

    @Inject(method = "pickBones", at = @At("TAIL"))
    private void bbsvfx$sync(List<String> bones, CallbackInfo ci)
    {
        if (bones.isEmpty() || this.pose == null)
        {
            this.bbsvfx$smearX.setValue(0F);
            this.bbsvfx$smearY.setValue(0F);
            this.bbsvfx$smearZ.setValue(0F);
            this.bbsvfx$smearCount.setValue(0F);
            this.bbsvfx$smearFalloff.setValue(0F);
            this.bbsvfx$smearDissolve.setValue(0F);
            this.bbsvfx$smearTime.setValue(0F);
            this.bbsvfx$smearStretch.setValue(0F);
            this.bbsvfx$smearDensity.setValue(0F);
            this.bbsvfx$smearOpacity.setValue(0F);
            this.bbsvfx$smearLinesCount.setValue(0F);
            this.bbsvfx$smearLinesWidth.setValue(0F);
            this.bbsvfx$smearLinesSpread.setValue(0F);
            this.bbsvfx$smearLinesReach.setValue(0F);
            this.bbsvfx$smearLinesLength.setValue(0F);
            this.bbsvfx$smearLinesBlunt.setValue(false);
            this.bbsvfx$smearLinesTexture.setValue(false);
            this.bbsvfx$manualOpen = false;
            this.bbsvfx$autoOpen = false;
            this.bbsvfx$smearManual.setValue(false);
            this.bbsvfx$smearArc.setValue(false);
            this.bbsvfx$rebuildSmear();
            this.bbsvfx$linesAutoOpen = false;
            this.bbsvfx$linesManualOpen = false;
            this.bbsvfx$linesAuto.setValue(false);
            this.bbsvfx$linesManual.setValue(false);
            this.bbsvfx$rebuildLines();

            return;
        }

        ISmearBone s = (ISmearBone) this.pose.getOrCreate(bones.get(0));

        this.bbsvfx$smearX.setValue(s.bbsvfx$smearX());
        this.bbsvfx$smearY.setValue(s.bbsvfx$smearY());
        this.bbsvfx$smearZ.setValue(s.bbsvfx$smearZ());
        this.bbsvfx$smearCount.setValue(s.bbsvfx$smearCount());
        this.bbsvfx$smearFalloff.setValue(s.bbsvfx$smearFalloff());
        this.bbsvfx$smearDissolve.setValue(s.bbsvfx$smearDissolve());
        this.bbsvfx$smearTime.setValue(s.bbsvfx$smearTime());
        this.bbsvfx$smearStretch.setValue(s.bbsvfx$smearStretch());
        this.bbsvfx$smearDensity.setValue(s.bbsvfx$smearDensity());
        this.bbsvfx$smearOpacity.setValue(s.bbsvfx$smearOpacity());
        this.bbsvfx$smearLinesCount.setValue(s.bbsvfx$smearLinesCount());
        this.bbsvfx$smearLinesWidth.setValue(s.bbsvfx$smearLinesWidth());
        this.bbsvfx$smearLinesSpread.setValue(s.bbsvfx$smearLinesSpread());
        this.bbsvfx$smearLinesReach.setValue(s.bbsvfx$smearLinesOffsetX());
        this.bbsvfx$smearLinesLength.setValue(s.bbsvfx$smearLinesOffsetY());
        this.bbsvfx$smearLinesBlunt.setValue(s.bbsvfx$smearLinesOffsetZ() > 0F);
        this.bbsvfx$smearLinesTexture.setValue(s.bbsvfx$smearLinesTexture() > 0F);

        /* Reflect the bone's mode (Auto = arc flag, Manual = manual flag). */
        this.bbsvfx$autoOpen = s.bbsvfx$smearArc() > 0F;
        this.bbsvfx$manualOpen = !this.bbsvfx$autoOpen && s.bbsvfx$smearManual() > 0F;
        this.bbsvfx$smearManual.setValue(this.bbsvfx$manualOpen);
        this.bbsvfx$smearArc.setValue(this.bbsvfx$autoOpen);
        this.bbsvfx$rebuildSmear();

        /* Lines mode: 1 = Auto, 2 = Manual. */
        this.bbsvfx$linesAutoOpen = Math.abs(s.bbsvfx$smearLines() - 1F) < 0.5F;
        this.bbsvfx$linesManualOpen = s.bbsvfx$smearLines() >= 1.5F;
        this.bbsvfx$linesAuto.setValue(this.bbsvfx$linesAutoOpen);
        this.bbsvfx$linesManual.setValue(this.bbsvfx$linesManualOpen);
        this.bbsvfx$rebuildLines();
    }
}
