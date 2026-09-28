package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Color;
import com.bbsvfx.bbsvfx.forms.BeamForm;

/**
 * Editor panel for the {@code bbsvfx:beam} form: the master timeline / fade / geometry, the core
 * colours, and one collapsible section per subsystem (helix ribbons, halo rings, dash sparks, ground
 * glow). {@code progress} and {@code opacity} are normally keyframed on the replay track; the sliders
 * here double as a static preview.
 */
public class UIBeamFormPanel extends UIFormPanel<BeamForm>
{
    public final UITrackpad progress;
    public final UITrackpad opacity;
    public final UITrackpad duration;
    public final UITrackpad height;
    public final UITrackpad radius;
    public final UIToggle fromSky;
    public final UIColor color;
    public final UIColor rimColor;

    public final UITrackpad strands;
    public final UITrackpad softness;

    public final UIToggle helix;
    public final UITrackpad helixCount;
    public final UITrackpad helixRadius;
    public final UITrackpad helixTurns;
    public final UITrackpad helixThickness;
    public final UITrackpad helixSpin;

    public final UITrackpad rings;
    public final UITrackpad ringMax;
    public final UITrackpad ringRise;
    public final UITrackpad ringThickness;

    public final UITrackpad dashes;
    public final UITrackpad dashSpeed;

    public final UITrackpad groundRadius;

    public final UIToggle destruction;
    public final UITrackpad destructRadius;
    public final UITrackpad destructDepth;
    public final UITrackpad impactAt;

    public UIBeamFormPanel(UIForm editor)
    {
        super(editor);

        this.progress = new UITrackpad((v) -> this.form.progress.set(v.floatValue()));
        this.progress.limit(0F, 1F).values(0.01F);
        this.opacity = new UITrackpad((v) -> this.form.opacity.set(v.floatValue()));
        this.opacity.limit(0F, 1F).values(0.01F);
        this.duration = new UITrackpad((v) -> this.form.duration.set(v.floatValue()));
        this.duration.limit(0.1F, 120F).values(0.1F);
        this.height = new UITrackpad((v) -> this.form.height.set(v.floatValue()));
        this.height.limit(0.1F, Float.POSITIVE_INFINITY).values(0.5F);
        this.radius = new UITrackpad((v) -> this.form.radius.set(v.floatValue()));
        this.radius.limit(0.01F, Float.POSITIVE_INFINITY).values(0.05F);
        this.fromSky = new UIToggle(IKey.constant("From sky (else erupt up)"), (b) -> this.form.direction.set(b.getValue() ? 1 : 0));

        this.color = new UIColor((c) -> this.form.color.set(Color.rgba(c))).withAlpha();
        this.rimColor = new UIColor((c) -> this.form.rimColor.set(Color.rgba(c))).withAlpha();

        this.strands = new UITrackpad((v) -> this.form.strands.set(v.intValue()));
        this.strands.limit(0, 64, true).increment(1);
        this.softness = new UITrackpad((v) -> this.form.softness.set(v.floatValue()));
        this.softness.limit(0F, 3F).values(0.1F);

        this.helix = new UIToggle(IKey.constant("Helix ribbons"), (b) -> this.form.helix.set(b.getValue()));
        this.helixCount = new UITrackpad((v) -> this.form.helixCount.set(v.intValue()));
        this.helixCount.limit(0, 8, true).increment(1);
        this.helixRadius = new UITrackpad((v) -> this.form.helixRadius.set(v.floatValue()));
        this.helixRadius.limit(0F, Float.POSITIVE_INFINITY).values(0.1F);
        this.helixTurns = new UITrackpad((v) -> this.form.helixTurns.set(v.floatValue()));
        this.helixTurns.limit(0F, 64F).values(0.25F);
        this.helixThickness = new UITrackpad((v) -> this.form.helixThickness.set(v.floatValue()));
        this.helixThickness.limit(0.001F, Float.POSITIVE_INFINITY).values(0.02F);
        this.helixSpin = new UITrackpad((v) -> this.form.helixSpin.set(v.floatValue()));
        this.helixSpin.values(0.05F);

        this.rings = new UITrackpad((v) -> this.form.rings.set(v.intValue()));
        this.rings.limit(0, 64, true).increment(1);
        this.ringMax = new UITrackpad((v) -> this.form.ringMax.set(v.floatValue()));
        this.ringMax.limit(0F, Float.POSITIVE_INFINITY).values(0.1F);
        this.ringRise = new UITrackpad((v) -> this.form.ringRise.set(v.floatValue()));
        this.ringRise.limit(0F, Float.POSITIVE_INFINITY).values(0.25F);
        this.ringThickness = new UITrackpad((v) -> this.form.ringThickness.set(v.floatValue()));
        this.ringThickness.limit(0.001F, Float.POSITIVE_INFINITY).values(0.02F);

        this.dashes = new UITrackpad((v) -> this.form.dashes.set(v.intValue()));
        this.dashes.limit(0, 2000, true).increment(5);
        this.dashSpeed = new UITrackpad((v) -> this.form.dashSpeed.set(v.floatValue()));
        this.dashSpeed.limit(0F, Float.POSITIVE_INFINITY).values(0.05F);

        this.groundRadius = new UITrackpad((v) -> this.form.groundRadius.set(v.floatValue()));
        this.groundRadius.limit(0F, Float.POSITIVE_INFINITY).values(0.25F);

        this.options.add(BbsVfxUI.section("Beam",
            UI.label(IKey.constant("Progress / opacity")), UI.row(this.progress, this.opacity),
            UI.label(IKey.constant("Duration (s) / strands")), UI.row(this.duration, this.strands),
            UI.label(IKey.constant("Height / radius")), UI.row(this.height, this.radius),
            this.fromSky,
            UI.label(IKey.constant("Softness (edge blur)")), this.softness,
            UI.label(IKey.constant("Core / rim colour")), UI.row(this.color, this.rimColor)));

        this.options.add(BbsVfxUI.section("Helix", this.helix,
            UI.label(IKey.constant("Count / radius (× beam)")), UI.row(this.helixCount, this.helixRadius),
            UI.label(IKey.constant("Turns / thickness")), UI.row(this.helixTurns, this.helixThickness),
            UI.label(IKey.constant("Spin (turns/s)")), this.helixSpin));

        this.options.add(BbsVfxUI.section("Rings",
            UI.label(IKey.constant("Count / max radius (× beam)")), UI.row(this.rings, this.ringMax),
            UI.label(IKey.constant("Rise (bl/s) / thickness")), UI.row(this.ringRise, this.ringThickness)));

        this.options.add(BbsVfxUI.section("Sparks & glow",
            UI.label(IKey.constant("Dashes / rise speed")), UI.row(this.dashes, this.dashSpeed),
            UI.label(IKey.constant("Ground glow radius")), this.groundRadius));

        this.destruction = new UIToggle(IKey.constant("Devour terrain"), (b) -> this.form.destruction.set(b.getValue()));
        this.destructRadius = new UITrackpad((v) -> this.form.destructRadius.set(v.floatValue()));
        this.destructRadius.limit(1F, 64F).increment(1);
        this.destructDepth = new UITrackpad((v) -> this.form.destructDepth.set(v.floatValue()));
        this.destructDepth.limit(1F, 32F).increment(1);
        this.impactAt = new UITrackpad((v) -> this.form.impactAt.set(v.floatValue()));
        this.impactAt.limit(0F, 1F).values(0.01F);

        /* The devour bowl is captured with the destruction wand (the "Capture Beam" icon in the replay
         * bar, next to the explosion one) — these are just the params. */
        this.options.add(BbsVfxUI.section("Destruction",
            this.destruction,
            UI.label(IKey.constant("Radius / crater depth")), UI.row(this.destructRadius, this.destructDepth),
            UI.label(IKey.constant("Impact at (progress)")), this.impactAt));
    }

    @Override
    public void startEdit(BeamForm form)
    {
        super.startEdit(form);

        this.progress.setValue(form.progress.get());
        this.opacity.setValue(form.opacity.get());
        this.duration.setValue(form.duration.get());
        this.height.setValue(form.height.get());
        this.radius.setValue(form.radius.get());
        this.fromSky.setValue(form.direction.get() == 1);
        this.color.setColor(form.color.get().getARGBColor());
        this.rimColor.setColor(form.rimColor.get().getARGBColor());
        this.strands.setValue(form.strands.get());
        this.softness.setValue(form.softness.get());
        this.helix.setValue(form.helix.get());
        this.helixCount.setValue(form.helixCount.get());
        this.helixRadius.setValue(form.helixRadius.get());
        this.helixTurns.setValue(form.helixTurns.get());
        this.helixThickness.setValue(form.helixThickness.get());
        this.helixSpin.setValue(form.helixSpin.get());
        this.rings.setValue(form.rings.get());
        this.ringMax.setValue(form.ringMax.get());
        this.ringRise.setValue(form.ringRise.get());
        this.ringThickness.setValue(form.ringThickness.get());
        this.dashes.setValue(form.dashes.get());
        this.dashSpeed.setValue(form.dashSpeed.get());
        this.groundRadius.setValue(form.groundRadius.get());
        this.destruction.setValue(form.destruction.get());
        this.destructRadius.setValue(form.destructRadius.get());
        this.destructDepth.setValue(form.destructDepth.get());
        this.impactAt.setValue(form.impactAt.get());
    }
}
