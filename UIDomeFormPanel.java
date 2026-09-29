package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Color;
import com.bbsvfx.bbsvfx.forms.DomeForm;

/**
 * Editor panel for the {@code bbsvfx:dome} form: master timeline / fade / expansion, the energy colours
 * and rim look, surface turbulence, and the ground shockwave ring. {@code progress} and {@code opacity}
 * are normally keyframed on the replay track; the sliders here double as a static preview.
 */
public class UIDomeFormPanel extends UIFormPanel<DomeForm>
{
    public final UITrackpad progress;
    public final UITrackpad opacity;
    public final UITrackpad duration;
    public final UITrackpad maxRadius;
    public final UITrackpad expandAt;
    public final UITrackpad impactAt;
    public final UITrackpad lull;
    public final UITrackpad heightScale;

    public final UIToggle beamTail;
    public final UITrackpad beamHeight;
    public final UITrackpad beamRadius;
    public final UITrackpad beamFade;
    public final UIToggle impactBurst;

    public final UIColor color;
    public final UIColor rimColor;
    public final UITrackpad rimPower;
    public final UITrackpad fill;
    public final UITrackpad turbulence;
    public final UITrackpad swirlSpeed;

    public final UITrackpad segments;
    public final UITrackpad ringsRes;
    public final UITrackpad lightning;
    public final UITrackpad dust;
    public final UITrackpad flash;
    public final UITrackpad flashFade;

    public final UIToggle baseRing;
    public final UITrackpad ringWidth;

    public final UIToggle cracks;
    public final UIColor crackColor;
    public final UITrackpad crackReach;
    public final UITrackpad crackScale;
    public final UITrackpad crackGlow;
    public final UITrackpad crackDepth;

    public final UIToggle smoke;
    public final UIColor smokeColor;
    public final UITrackpad smokeHeight;
    public final UITrackpad smokeDensity;
    public final UITrackpad smokeScale;
    public final UITrackpad smokeRise;

    public final UIToggle destruction;
    public final UITrackpad clearSpan;

    public UIDomeFormPanel(UIForm editor)
    {
        super(editor);

        this.progress = new UITrackpad((v) -> this.form.progress.set(v.floatValue()));
        this.progress.limit(0F, 1F).values(0.01F);
        this.opacity = new UITrackpad((v) -> this.form.opacity.set(v.floatValue()));
        this.opacity.limit(0F, 1F).values(0.01F);
        this.duration = new UITrackpad((v) -> this.form.duration.set(v.floatValue()));
        this.duration.limit(0.1F, 120F).values(0.1F);
        this.maxRadius = new UITrackpad((v) -> this.form.maxRadius.set(v.floatValue()));
        this.maxRadius.limit(0.5F, Float.POSITIVE_INFINITY).values(0.5F);
        this.expandAt = new UITrackpad((v) -> this.form.expandAt.set(v.floatValue()));
        this.expandAt.limit(0.01F, 1F).values(0.01F);
        this.impactAt = new UITrackpad((v) -> this.form.impactAt.set(v.floatValue()));
        this.impactAt.limit(0F, 1F).values(0.01F);
        this.lull = new UITrackpad((v) -> this.form.lull.set(v.floatValue()));
        this.lull.limit(0F, 1F).values(0.01F);
        this.heightScale = new UITrackpad((v) -> this.form.heightScale.set(v.floatValue()));
        this.heightScale.limit(0.05F, 4F).values(0.05F);

        this.beamTail = new UIToggle(IKey.constant("Beam tail (strike lead-in)"), (b) -> this.form.beamTail.set(b.getValue()));
        this.beamHeight = new UITrackpad((v) -> this.form.beamHeight.set(v.floatValue()));
        this.beamHeight.limit(0.1F, Float.POSITIVE_INFINITY).values(0.5F);
        this.beamRadius = new UITrackpad((v) -> this.form.beamRadius.set(v.floatValue()));
        this.beamRadius.limit(0.01F, Float.POSITIVE_INFINITY).values(0.05F);
        this.beamFade = new UITrackpad((v) -> this.form.beamFade.set(v.floatValue()));
        this.beamFade.limit(0.01F, 1F).values(0.01F);
        this.impactBurst = new UIToggle(IKey.constant("Impact mini-explosion"), (b) -> this.form.impactBurst.set(b.getValue()));

        this.color = new UIColor((c) -> this.form.color.set(Color.rgba(c))).withAlpha();
        this.rimColor = new UIColor((c) -> this.form.rimColor.set(Color.rgba(c))).withAlpha();
        this.rimPower = new UITrackpad((v) -> this.form.rimPower.set(v.floatValue()));
        this.rimPower.limit(0.2F, 12F).values(0.1F);
        this.fill = new UITrackpad((v) -> this.form.fill.set(v.floatValue()));
        this.fill.limit(0F, 1F).values(0.01F);
        this.turbulence = new UITrackpad((v) -> this.form.turbulence.set(v.floatValue()));
        this.turbulence.limit(0F, 0.5F).values(0.005F);
        this.swirlSpeed = new UITrackpad((v) -> this.form.swirlSpeed.set(v.floatValue()));
        this.swirlSpeed.limit(0F, Float.POSITIVE_INFINITY).values(0.05F);

        this.segments = new UITrackpad((v) -> this.form.segments.set(v.intValue()));
        this.segments.limit(6, 160, true).increment(2);
        this.ringsRes = new UITrackpad((v) -> this.form.ringsRes.set(v.intValue()));
        this.ringsRes.limit(3, 80, true).increment(1);
        this.lightning = new UITrackpad((v) -> this.form.lightning.set(v.intValue()));
        this.lightning.limit(0, 64, true).increment(1);
        this.dust = new UITrackpad((v) -> this.form.dust.set(v.intValue()));
        this.dust.limit(0, 2000, true).increment(10);
        this.flash = new UITrackpad((v) -> this.form.flash.set(v.floatValue()));
        this.flash.limit(0F, 1F).values(0.01F);
        this.flashFade = new UITrackpad((v) -> this.form.flashFade.set(v.floatValue()));
        this.flashFade.limit(0.01F, 1F).values(0.01F);

        this.baseRing = new UIToggle(IKey.constant("Ground shockwave ring"), (b) -> this.form.baseRing.set(b.getValue()));
        this.ringWidth = new UITrackpad((v) -> this.form.ringWidth.set(v.floatValue()));
        this.ringWidth.limit(0.05F, Float.POSITIVE_INFINITY).values(0.05F);

        this.options.add(BbsVfxUI.section("Dome",
            UI.label(IKey.constant("Progress / opacity")), UI.row(this.progress, this.opacity),
            UI.label(IKey.constant("Duration (s)")), this.duration,
            UI.label(IKey.constant("Max radius / expand span")), UI.row(this.maxRadius, this.expandAt),
            UI.label(IKey.constant("Impact at / lull (progress)")), UI.row(this.impactAt, this.lull),
            UI.label(IKey.constant("Height scale")), this.heightScale));

        this.options.add(BbsVfxUI.section("Beam tail & impact",
            this.beamTail,
            UI.label(IKey.constant("Beam height / radius")), UI.row(this.beamHeight, this.beamRadius),
            UI.label(IKey.constant("Beam fade span")), this.beamFade,
            this.impactBurst));

        this.options.add(BbsVfxUI.section("Energy look",
            UI.label(IKey.constant("Body / rim colour")), UI.row(this.color, this.rimColor),
            UI.label(IKey.constant("Rim power / fill")), UI.row(this.rimPower, this.fill),
            UI.label(IKey.constant("Turbulence / swirl speed")), UI.row(this.turbulence, this.swirlSpeed),
            UI.label(IKey.constant("Lightning arcs / dust")), UI.row(this.lightning, this.dust),
            UI.label(IKey.constant("White-out flash / fade")), UI.row(this.flash, this.flashFade),
            UI.label(IKey.constant("Segments / rings (mesh)")), UI.row(this.segments, this.ringsRes)));

        this.options.add(BbsVfxUI.section("Shockwave ring",
            this.baseRing,
            UI.label(IKey.constant("Ring width (blocks)")), this.ringWidth));

        this.cracks = new UIToggle(IKey.constant("Ground cracks"), (b) -> this.form.cracks.set(b.getValue()));
        this.crackColor = new UIColor((c) -> this.form.crackColor.set(Color.rgba(c)));
        this.crackReach = new UITrackpad((v) -> this.form.crackReach.set(v.floatValue()));
        this.crackReach.limit(0.05F, 4F).values(0.05F);
        this.crackScale = new UITrackpad((v) -> this.form.crackScale.set(v.floatValue()));
        this.crackScale.limit(0.02F, 2F).values(0.01F);
        this.crackGlow = new UITrackpad((v) -> this.form.crackGlow.set(v.floatValue()));
        this.crackGlow.limit(0F, 8F).values(0.05F);
        this.crackDepth = new UITrackpad((v) -> this.form.crackDepth.set(v.floatValue()));
        this.crackDepth.limit(1F, 32F).values(0.5F);

        this.options.add(BbsVfxUI.section("Ground cracks",
            this.cracks,
            UI.label(IKey.constant("Magma colour")), this.crackColor,
            UI.label(IKey.constant("Reach (× radius) / pattern scale")), UI.row(this.crackReach, this.crackScale),
            UI.label(IKey.constant("Glow / depth (blocks)")), UI.row(this.crackGlow, this.crackDepth)));

        this.smoke = new UIToggle(IKey.constant("Rising smoke"), (b) -> this.form.smoke.set(b.getValue()));
        this.smokeColor = new UIColor((c) -> this.form.smokeColor.set(Color.rgba(c)));
        this.smokeHeight = new UITrackpad((v) -> this.form.smokeHeight.set(v.floatValue()));
        this.smokeHeight.limit(1F, 96F).values(0.5F);
        this.smokeDensity = new UITrackpad((v) -> this.form.smokeDensity.set(v.floatValue()));
        this.smokeDensity.limit(0F, 4F).values(0.05F);
        this.smokeScale = new UITrackpad((v) -> this.form.smokeScale.set(v.floatValue()));
        this.smokeScale.limit(0.02F, 1F).values(0.01F);
        this.smokeRise = new UITrackpad((v) -> this.form.smokeRise.set(v.floatValue()));
        this.smokeRise.limit(0F, 4F).values(0.05F);

        this.options.add(BbsVfxUI.section("Rising smoke",
            this.smoke,
            UI.label(IKey.constant("Smoke colour")), this.smokeColor,
            UI.label(IKey.constant("Height / density")), UI.row(this.smokeHeight, this.smokeDensity),
            UI.label(IKey.constant("Wisp scale / rise speed")), UI.row(this.smokeScale, this.smokeRise)));

        this.destruction = new UIToggle(IKey.constant("Level the world"), (b) -> this.form.destruction.set(b.getValue()));
        this.clearSpan = new UITrackpad((v) -> this.form.clearSpan.set(v.floatValue()));
        this.clearSpan.limit(0.5F, 32F).values(0.1F);

        /* The wand only spawns the sequence; the destruction eats a circle of the dome's Max radius.
         * After changing Max radius, "Re-cut" re-applies the destruction to the world at that radius. */
        UIButton recut = new UIButton(IKey.constant("Re-cut world to radius"),
            (b) -> DestructionCapture.recutDome(this.form));

        this.options.add(BbsVfxUI.section("Destruction",
            this.destruction,
            UI.label(IKey.constant("Clear span (blocks past front)")), this.clearSpan,
            recut));
    }

    @Override
    public void startEdit(DomeForm form)
    {
        super.startEdit(form);

        this.progress.setValue(form.progress.get());
        this.opacity.setValue(form.opacity.get());
        this.duration.setValue(form.duration.get());
        this.maxRadius.setValue(form.maxRadius.get());
        this.expandAt.setValue(form.expandAt.get());
        this.impactAt.setValue(form.impactAt.get());
        this.lull.setValue(form.lull.get());
        this.heightScale.setValue(form.heightScale.get());
        this.beamTail.setValue(form.beamTail.get());
        this.beamHeight.setValue(form.beamHeight.get());
        this.beamRadius.setValue(form.beamRadius.get());
        this.beamFade.setValue(form.beamFade.get());
        this.impactBurst.setValue(form.impactBurst.get());
        this.color.setColor(form.color.get().getARGBColor());
        this.rimColor.setColor(form.rimColor.get().getARGBColor());
        this.rimPower.setValue(form.rimPower.get());
        this.fill.setValue(form.fill.get());
        this.turbulence.setValue(form.turbulence.get());
        this.swirlSpeed.setValue(form.swirlSpeed.get());
        this.segments.setValue(form.segments.get());
        this.ringsRes.setValue(form.ringsRes.get());
        this.lightning.setValue(form.lightning.get());
        this.dust.setValue(form.dust.get());
        this.flash.setValue(form.flash.get());
        this.flashFade.setValue(form.flashFade.get());
        this.baseRing.setValue(form.baseRing.get());
        this.ringWidth.setValue(form.ringWidth.get());
        this.cracks.setValue(form.cracks.get());
        this.crackColor.setColor(form.crackColor.get().getARGBColor());
        this.crackReach.setValue(form.crackReach.get());
        this.crackScale.setValue(form.crackScale.get());
        this.crackGlow.setValue(form.crackGlow.get());
        this.crackDepth.setValue(form.crackDepth.get());
        this.smoke.setValue(form.smoke.get());
        this.smokeColor.setColor(form.smokeColor.get().getARGBColor());
        this.smokeHeight.setValue(form.smokeHeight.get());
        this.smokeDensity.setValue(form.smokeDensity.get());
        this.smokeScale.setValue(form.smokeScale.get());
        this.smokeRise.setValue(form.smokeRise.get());
        this.destruction.setValue(form.destruction.get());
        this.clearSpan.setValue(form.clearSpan.get());
    }
}
