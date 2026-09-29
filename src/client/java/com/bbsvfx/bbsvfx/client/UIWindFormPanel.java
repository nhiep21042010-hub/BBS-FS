package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Color;
import com.bbsvfx.bbsvfx.forms.WindForm;

/**
 * Editor panel for {@code bbsvfx:wind}: the scan range + Scan button, the wind strength and visible-wind
 * densities, the direction handle (shown once scanned), and the master timeline. Pressing Scan flips the
 * form's {@code scanned} flag, which reveals the direction gizmo and (phase 0b) enables the wind FX.
 */
public class UIWindFormPanel extends UIFormPanel<WindForm>
{
    public final UIButton scan;
    public final UITrackpad scanRadius;
    public final UITrackpad strength;
    public final UITrackpad streaks;
    public final UITrackpad leaves;
    public final UITrackpad dust;
    public final UIColor color;

    public final mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate windType;
    public final UIButton preset;
    public final UITrackpad coreRadius;
    public final UITrackpad funnelFlare;
    public final UITrackpad funnelHeight;
    public final UITrackpad swirl;
    public final UITrackpad updraft;
    public final UITrackpad suction;
    public BbsVfxSection vortexSection;
    public final UITrackpad progress;
    public final UITrackpad opacity;
    public final UITrackpad duration;
    public final mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle sway;
    public final UITrackpad swayAmount;
    public final mchorse.bbs_mod.ui.framework.elements.buttons.UIButton restore;

    public BbsVfxSection windSection;
    public BbsVfxSection directionSection;
    public BbsVfxSection swaySection;
    public BbsVfxSection timelineSection;

    public UIWindFormPanel(UIForm editor)
    {
        super(editor);

        this.scan = new UIButton(IKey.constant("Scan territory"), (b) ->
        {
            this.form.scanned.set(true);
            ((UIWindForm) this.editor).syncPointWidget();
            this.bbsvfx$updateVisibility();
        });

        this.scanRadius = new UITrackpad((v) -> this.form.scanRadius.set(v.floatValue()));
        this.scanRadius.limit(1F, 512F).increment(1);
        this.strength = new UITrackpad((v) -> this.form.strength.set(v.floatValue()));
        this.strength.limit(0F, Float.POSITIVE_INFINITY).values(0.5F);
        this.streaks = new UITrackpad((v) -> this.form.streaks.set(v.floatValue()));
        this.streaks.limit(0F, 1F).values(0.05F);
        this.leaves = new UITrackpad((v) -> this.form.leaves.set(v.floatValue()));
        this.leaves.limit(0F, 1F).values(0.05F);
        this.dust = new UITrackpad((v) -> this.form.dust.set(v.floatValue()));
        this.dust.limit(0F, 1F).values(0.05F);
        this.color = new UIColor((c) -> this.form.color.set(Color.rgba(c)));

        /* Field type — flipping to Tornado reveals the funnel controls. */
        this.windType = new mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate((b) ->
        {
            if (this.form != null)
            {
                this.form.windType.set(b.getValue());
                this.bbsvfx$rebuild();
            }
        });
        this.windType.addLabel(IKey.constant("Directional"));
        this.windType.addLabel(IKey.constant("Storm"));
        this.windType.addLabel(IKey.constant("Tornado"));

        this.coreRadius = new UITrackpad((v) -> this.form.coreRadius.set(v.floatValue()));
        this.coreRadius.limit(0.5F, 128F).values(0.5F);
        this.funnelFlare = new UITrackpad((v) -> this.form.funnelFlare.set(v.floatValue()));
        this.funnelFlare.limit(0F, 8F).values(0.1F);
        this.funnelHeight = new UITrackpad((v) -> this.form.funnelHeight.set(v.floatValue()));
        this.funnelHeight.limit(2F, 320F).values(1F);
        this.swirl = new UITrackpad((v) -> this.form.swirl.set(v.floatValue()));
        this.swirl.limit(0F, 120F).values(0.5F);
        this.updraft = new UITrackpad((v) -> this.form.updraft.set(v.floatValue()));
        this.updraft.limit(0F, 60F).values(0.5F);
        this.suction = new UITrackpad((v) -> this.form.suction.set(v.floatValue()));
        this.suction.limit(0F, 60F).values(0.5F);

        /* Loads a tuned starting point for the selected type. Separate from the type switch on purpose, so
         * flipping types to look around never destroys hand-dialled values. */
        this.preset = new UIButton(IKey.constant("Apply preset"), (b) ->
        {
            if (this.form != null)
            {
                this.form.applyPreset(this.form.windType.get());
                this.bbsvfx$syncWidgets(this.form);
                this.bbsvfx$rebuild();
            }
        });

        this.vortexSection = BbsVfxUI.section("Vortex (funnel)",
            UI.label(IKey.constant("Core radius / flare")), UI.row(this.coreRadius, this.funnelFlare),
            UI.label(IKey.constant("Funnel height")), this.funnelHeight,
            UI.label(IKey.constant("Swirl / updraft / suction")), UI.row(this.swirl, this.updraft, this.suction));

        this.progress = new UITrackpad((v) -> this.form.progress.set(v.floatValue()));
        this.progress.limit(0F, 1F).values(0.01F);
        this.opacity = new UITrackpad((v) -> this.form.opacity.set(v.floatValue()));
        this.opacity.limit(0F, 1F).values(0.01F);
        this.duration = new UITrackpad((v) -> this.form.duration.set(v.floatValue()));
        this.duration.limit(0.1F, 600F).values(0.1F);

        this.windSection = BbsVfxUI.section("Wind",
            this.scan,
            UI.label(IKey.constant("Scan range (blocks)")), this.scanRadius,
            UI.label(IKey.constant("Field type")), this.windType, this.preset,
            UI.label(IKey.constant("Strength")), this.strength,
            UI.label(IKey.constant("Streaks / leaves / dust")), UI.row(this.streaks, this.leaves, this.dust),
            UI.label(IKey.constant("Wind colour")), this.color);

        /* The direction editor (X/Y/Z + the gizmo's drag handler) must be in the tree to render — a
         * detached UIPropTransform never processes its drag, so the direction handle wouldn't move. */
        this.directionSection = BbsVfxUI.section("Direction",
            ((UIWindForm) this.editor).pointGizmo());

        this.sway = new mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle(
            IKey.constant("Sway foliage (film only)"), (b) ->
        {
            if (this.form != null)
            {
                this.form.sway.set(b.getValue());
            }
        });
        this.swayAmount = new UITrackpad((v) -> this.form.swayAmount.set(v.floatValue()));
        this.swayAmount.limit(0F, 45F).values(0.5F);

        /* Explicit "put it back now": turns sway off (the renderer restores) and restores immediately, so it
         * works even when the form isn't currently rendering. Client-only, so nothing to lose. */
        this.restore = new UIButton(IKey.constant("Restore foliage"), (b) ->
        {
            if (this.form != null)
            {
                this.form.sway.set(false);
                this.sway.setValue(false);
            }

            com.bbsvfx.bbsvfx.client.WindFoliage.restore();
        });

        this.swaySection = BbsVfxUI.section("Foliage sway",
            this.sway,
            UI.label(IKey.constant("Sway amount (deg)")), this.swayAmount,
            this.restore);

        this.timelineSection = BbsVfxUI.section("Timeline",
            UI.label(IKey.constant("Progress / opacity")), UI.row(this.progress, this.opacity),
            UI.label(IKey.constant("Duration (s)")), this.duration);

        this.bbsvfx$rebuild();
    }

    /** Rebuild the option list — the Direction section only appears once a territory has been scanned. */
    private void bbsvfx$rebuild()
    {
        boolean scanned = this.form != null && this.form.scanned.get();

        this.options.removeAll();
        this.options.add(this.windSection);

        if (this.form != null && this.form.isVortex())
        {
            this.options.add(this.vortexSection);
        }

        if (scanned)
        {
            this.options.add(this.directionSection);
        }

        this.options.add(this.swaySection);
        this.options.add(this.timelineSection);
        this.options.resize();
    }

    /**
     * Rebuild the list. Aiming the direction point used to narrow the gizmo to translate-only; BBS 2.6
     * dropped gizmo display modes, so the restriction has no setter any more (a Gizmo.HandleMask is
     * passed at capture time, and the form editor viewport captures unmasked).
     */
    private void bbsvfx$updateVisibility()
    {
        this.bbsvfx$rebuild();
    }

    @Override
    public void finishEdit()
    {
        super.finishEdit();

        /* BBS 2.6 dropped gizmo display modes: the gizmo always carries every element, and a real
         * restriction is a Gizmo.HandleMask handed to the capture call. Nothing to restore here. */
    }

    @Override
    public void startEdit(WindForm form)
    {
        super.startEdit(form);

        this.bbsvfx$syncWidgets(form);

        ((UIWindForm) this.editor).syncPointWidget();

        this.bbsvfx$updateVisibility();
    }

    /** Push the form's values into every widget (used on open and after a preset is applied). */
    private void bbsvfx$syncWidgets(WindForm form)
    {
        this.scanRadius.setValue(form.scanRadius.get());
        this.strength.setValue(form.strength.get());
        this.streaks.setValue(form.streaks.get());
        this.leaves.setValue(form.leaves.get());
        this.dust.setValue(form.dust.get());
        this.color.setColor(form.color.get().getARGBColor());
        this.windType.setValue(form.windType.get());
        this.coreRadius.setValue(form.coreRadius.get());
        this.funnelFlare.setValue(form.funnelFlare.get());
        this.funnelHeight.setValue(form.funnelHeight.get());
        this.swirl.setValue(form.swirl.get());
        this.updraft.setValue(form.updraft.get());
        this.suction.setValue(form.suction.get());
        this.progress.setValue(form.progress.get());
        this.opacity.setValue(form.opacity.get());
        this.duration.setValue(form.duration.get());
        this.sway.setValue(form.sway.get());
        this.swayAmount.setValue(form.swayAmount.get());
    }
}
