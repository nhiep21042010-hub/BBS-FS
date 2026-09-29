package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import com.bbsvfx.bbsvfx.forms.ExplosionForm;

/**
 * Editor panel for the {@code bbsvfx:explosion} form. Same widgets as the Destruction Box panel (the
 * form IS the destruction engine underneath) but with an explosion's own shape: the epicenter blast
 * front and centre, the global WIND section, physics always on (the mode toggles are gone), and the
 * destruction-box-specific sections (point mode, direction/radial) hidden — their values keep the
 * blast-friendly defaults.
 */
public class UIExplosionFormPanel extends UIDestructionBoxFormPanel
{
    public UITrackpad windStrength;
    public UITrackpad windFrontSpeed;
    public UITrackpad windDecay;
    public UITrackpad windAmbientYaw;
    public UITrackpad windAmbientStrength;
    public UITrackpad windSuction;
    public UITrackpad windHold;

    public UITrackpad fireCount;
    public UITrackpad fireDuration;
    public UITrackpad fireSize;
    public UITrackpad smokeScale;
    public UITrackpad dustScale;
    public UITrackpad mushroomScale;
    public UITrackpad groundWave;
    public UITrackpad hazeScale;
    public UITrackpad birdCount;
    public UITrackpad worldReach;
    public UITrackpad windStreaksUI;
    public UITrackpad bendScanRadius;
    public UITrackpad bendRadius;
    public UITrackpad bendAmount;
    public UITrackpad bendFell;
    public UITrackpad bendGusts;
    public UITrackpad bendLeaves;

    public UICirculate shape;
    public UITrackpad sphereRadius;
    public UITrackpad sphereExpand;
    public UITrackpad sphereHeat;
    public UITrackpad sphereScale;
    public UITrackpad sparkCount;
    public UITrackpad sparkSize;
    public UITrackpad sparkSpeed;
    public UITrackpad sparkGravity;
    public UITrackpad sparkLife;
    public UITrackpad sparkSpread;
    public UITrackpad sparkColorR;
    public UITrackpad sparkColorG;
    public UITrackpad sparkColorB;

    public BbsVfxSection windSection;
    public BbsVfxSection fireSection;
    public BbsVfxSection shapeSection;
    public BbsVfxSection sphereSection;
    public BbsVfxSection sparksSection;
    public BbsVfxSection worldSection;
    public BbsVfxSection bendSection;

    public UIExplosionFormPanel(UIForm editor)
    {
        super(editor);

        /* An explosion is always the physics sim — no parametric/point modes to toggle. */
        this.pointMode.removeFromParent();
        this.physicsMode.removeFromParent();

        this.windStrength = new UITrackpad((v) -> this.explosion().windStrength.set(v.floatValue()));
        this.windStrength.limit(0F, Float.POSITIVE_INFINITY).values(0.5F);
        this.windFrontSpeed = new UITrackpad((v) -> this.explosion().windFrontSpeed.set(v.floatValue()));
        this.windFrontSpeed.limit(1F, Float.POSITIVE_INFINITY).values(1F);
        this.windDecay = new UITrackpad((v) -> this.explosion().windDecay.set(v.floatValue()));
        this.windDecay.limit(0.05F, 10F).values(0.05F);
        this.windAmbientYaw = new UITrackpad((v) -> this.explosion().windAmbientYaw.set(v.floatValue()));
        this.windAmbientStrength = new UITrackpad((v) -> this.explosion().windAmbientStrength.set(v.floatValue()));
        this.windAmbientStrength.limit(0F, Float.POSITIVE_INFINITY).values(0.5F);

        this.windSuction = new UITrackpad((v) -> this.explosion().windSuction.set(v.floatValue()));
        this.windSuction.limit(0F, 2F).values(0.05F);
        this.windHold = new UITrackpad((v) -> this.explosion().windHold.set(v.floatValue()));
        this.windHold.limit(0F, 15F).values(0.25F);

        this.windSection = BbsVfxUI.section("Wind",
            UI.label(IKey.constant("Blast shove / front speed (bl/s)")),
            UI.row(this.windStrength, this.windFrontSpeed),
            UI.label(IKey.constant("Punch decay (s) / blast wind hold (s)")),
            UI.row(this.windDecay, this.windHold),
            UI.label(IKey.constant("Negative phase (suction)")), this.windSuction,
            UI.label(IKey.constant("Ambient yaw / strength")),
            UI.row(this.windAmbientYaw, this.windAmbientStrength));

        this.fireCount = new UITrackpad((v) -> this.explosion().fireCount.set(v.intValue()));
        this.fireCount.limit(0, 5000, true).increment(50);
        this.fireDuration = new UITrackpad((v) -> this.explosion().fireDuration.set(v.floatValue()));
        this.fireDuration.limit(0.5F, 60F).values(0.5F);
        this.fireSize = new UITrackpad((v) -> this.explosion().fireSize.set(v.floatValue()));
        this.fireSize.limit(0.1F, 10F).values(0.1F);
        this.smokeScale = new UITrackpad((v) -> this.explosion().smokeScale.set(v.floatValue()));
        this.smokeScale.limit(0F, 5F).values(0.1F);
        this.dustScale = new UITrackpad((v) -> this.explosion().dustScale.set(v.floatValue()));
        this.dustScale.limit(0F, 5F).values(0.1F);
        this.mushroomScale = new UITrackpad((v) -> this.explosion().mushroomScale.set(v.floatValue()));
        this.mushroomScale.limit(0F, 5F).values(0.1F);

        this.fireSection = BbsVfxUI.section("Fire & smoke",
            UI.label(IKey.constant("Ignited blocks (0 = off) / burn time (s)")),
            UI.row(this.fireCount, this.fireDuration),
            UI.label(IKey.constant("Flame size / smoke scale")),
            UI.row(this.fireSize, this.smokeScale),
            UI.label(IKey.constant("Dust wave / mushroom cloud")),
            UI.row(this.dustScale, this.mushroomScale));

        /* Hero cloud shape: switching to Sphere rebuilds the options so the Sphere section appears
         * (and the mushroom-only sections stay — they still govern wind/fire around the ball). */
        this.shape = new UICirculate((c) ->
        {
            this.explosion().shape.set(c.getValue());
            this.bbsvfx$rebuildOptions();
        });
        this.shape.addLabel(IKey.constant("Mushroom"));
        this.shape.addLabel(IKey.constant("Sphere"));

        this.shapeSection = BbsVfxUI.section("Shape",
            UI.label(IKey.constant("Hero cloud shape")), this.shape);

        this.sphereRadius = new UITrackpad((v) -> this.explosion().sphereRadius.set(v.floatValue()));
        this.sphereRadius.limit(1F, 64F).values(1F);
        this.sphereExpand = new UITrackpad((v) -> this.explosion().sphereExpand.set(v.floatValue()));
        this.sphereExpand.limit(0.1F, 10F).values(0.1F);
        this.sphereHeat = new UITrackpad((v) -> this.explosion().sphereHeat.set(v.floatValue()));
        this.sphereHeat.limit(0F, 1F).values(0.05F);
        this.sphereScale = new UITrackpad((v) -> this.explosion().sphereScale.set(v.floatValue()));
        this.sphereScale.limit(0F, 5F).values(0.1F);

        this.sphereSection = BbsVfxUI.section("Sphere",
            UI.label(IKey.constant("Radius (blocks) / expand speed")),
            UI.row(this.sphereRadius, this.sphereExpand),
            UI.label(IKey.constant("Heat (0 = smoke only) / scale")),
            UI.row(this.sphereHeat, this.sphereScale));

        this.sparkCount = new UITrackpad((v) -> this.explosion().sparkCount.set(v.intValue()));
        this.sparkCount.limit(0, 500, true).increment(10);
        this.sparkSize = new UITrackpad((v) -> this.explosion().sparkSize.set(v.floatValue()));
        this.sparkSize.limit(0.1F, 5F).values(0.1F);
        this.sparkSpeed = new UITrackpad((v) -> this.explosion().sparkSpeed.set(v.floatValue()));
        this.sparkSpeed.limit(0F, 100F).values(1F);
        this.sparkGravity = new UITrackpad((v) -> this.explosion().sparkGravity.set(v.floatValue()));
        this.sparkGravity.limit(0F, 100F).values(1F);
        this.sparkLife = new UITrackpad((v) -> this.explosion().sparkLife.set(v.floatValue()));
        this.sparkLife.limit(0.1F, 30F).values(0.1F);
        this.sparkSpread = new UITrackpad((v) -> this.explosion().sparkSpread.set(v.floatValue()));
        this.sparkSpread.limit(0F, 1F).values(0.05F);
        this.sparkColorR = new UITrackpad((v) -> this.explosion().sparkColorR.set(v.floatValue()));
        this.sparkColorR.limit(0F, 1F).values(0.05F);
        this.sparkColorG = new UITrackpad((v) -> this.explosion().sparkColorG.set(v.floatValue()));
        this.sparkColorG.limit(0F, 1F).values(0.05F);
        this.sparkColorB = new UITrackpad((v) -> this.explosion().sparkColorB.set(v.floatValue()));
        this.sparkColorB.limit(0F, 1F).values(0.05F);

        this.sparksSection = BbsVfxUI.section("Sparks",
            UI.label(IKey.constant("Count (0 = off) / size")),
            UI.row(this.sparkCount, this.sparkSize),
            UI.label(IKey.constant("Speed / gravity")),
            UI.row(this.sparkSpeed, this.sparkGravity),
            UI.label(IKey.constant("Life (s) / spread (0 = jet, 1 = spray)")),
            UI.row(this.sparkLife, this.sparkSpread),
            UI.label(IKey.constant("Color R / G / B")),
            UI.row(this.sparkColorR, this.sparkColorG, this.sparkColorB));

        this.groundWave = new UITrackpad((v) -> this.explosion().groundWave.set(v.floatValue()));
        this.groundWave.limit(0F, 5F).values(0.1F);
        this.hazeScale = new UITrackpad((v) -> this.explosion().hazeScale.set(v.floatValue()));
        this.hazeScale.limit(0F, 5F).values(0.1F);
        this.birdCount = new UITrackpad((v) -> this.explosion().birdCount.set(v.intValue()));
        this.birdCount.limit(0, 40, true).increment(1);
        this.worldReach = new UITrackpad((v) -> this.explosion().worldReach.set(v.floatValue()));
        this.worldReach.limit(16F, 512F).increment(16);
        this.windStreaksUI = new UITrackpad((v) -> this.explosion().windStreaks.set(v.floatValue()));
        this.windStreaksUI.limit(0F, 5F).values(0.1F);

        this.worldSection = BbsVfxUI.section("World reaction",
            UI.label(IKey.constant("World reach (blocks)")), this.worldReach,
            UI.label(IKey.constant("Ground wave / lingering haze")),
            UI.row(this.groundWave, this.hazeScale),
            UI.label(IKey.constant("Wind streaks / birds (real trees)")),
            UI.row(this.windStreaksUI, this.birdCount));

        this.bendScanRadius = new UITrackpad((v) -> this.explosion().bendScanRadius.set(v.floatValue()));
        this.bendScanRadius.limit(0F, 320F).increment(8);
        this.bendRadius = new UITrackpad((v) -> this.explosion().bendRadius.set(v.floatValue()));
        this.bendRadius.limit(0F, 320F).increment(4);
        this.bendAmount = new UITrackpad((v) -> this.explosion().bendAmount.set(v.floatValue()));
        this.bendAmount.limit(0F, 80F).values(1F);
        this.bendFell = new UITrackpad((v) -> this.explosion().bendFell.set(v.floatValue()));
        this.bendFell.limit(0F, 1F).values(0.05F);

        /* Re-run the foliage capture around the actor at the SCAN range above: restores the currently
         * captured foliage to the world, scans afresh, cuts again. The actor origin comes from the
         * renderer's per-frame note — the editor's own origin matrix is preview-space. */
        UIButton rescanFoliage = new UIButton(IKey.constant("Rescan foliage"), (b) ->
            DestructionCapture.rescanFoliage(this.explosion()));

        this.bendGusts = new UITrackpad((v) -> this.explosion().bendGusts.set(v.floatValue()));
        this.bendGusts.limit(0F, 1F).values(0.05F);
        this.bendLeaves = new UITrackpad((v) -> this.explosion().bendLeaves.set(v.floatValue()));
        this.bendLeaves.limit(0F, 3F).values(0.1F);

        this.bendSection = BbsVfxUI.section("Environment bend",
            UI.label(IKey.constant("Scan range (rescan to apply)")), this.bendScanRadius,
            rescanFoliage,
            UI.label(IKey.constant("Sway reach / amount (deg)")),
            UI.row(this.bendRadius, this.bendAmount),
            UI.label(IKey.constant("Felled trees (0 = all spring back)")), this.bendFell,
            UI.label(IKey.constant("Gusts after the front / leaf shed")),
            UI.row(this.bendGusts, this.bendLeaves));

        this.bbsvfx$rebuildOptions();
    }

    private ExplosionForm explosion()
    {
        return (ExplosionForm) this.form;
    }

    @Override
    public void bbsvfx$rebuildOptions()
    {
        /* Called from the super constructor too (fields may be mid-construction) — the base pass is
         * harmless, the final layout is applied by our constructor's second call. */
        if (this.windSection == null)
        {
            super.bbsvfx$rebuildOptions();

            return;
        }

        this.options.removeAll();
        this.options.add(this.destructionSection);
        this.options.add(this.explosionSection);
        this.options.add(this.windSection);
        this.options.add(this.fireSection);
        this.options.add(this.shapeSection);

        /* The Sphere section only makes sense for the sphere shape — it comes and goes with the
         * Mushroom/Sphere toggle. */
        if (this.form instanceof ExplosionForm explosion && explosion.shape.get() == 1)
        {
            this.options.add(this.sphereSection);
        }

        this.options.add(this.sparksSection);
        this.options.add(this.worldSection);
        this.options.add(this.bendSection);
        this.options.add(this.physicsSection);
        this.options.add(this.waveSection);
        this.options.add(this.fractureSection);
        this.options.add(this.randomSection);
        this.options.add(this.rotationSection);
        this.options.add(this.blocksSection);
        this.options.resize();
    }

    @Override
    public void startEdit(com.bbsvfx.bbsvfx.forms.DestructionBoxForm form)
    {
        super.startEdit(form);

        if (form instanceof ExplosionForm explosion)
        {
            this.windStrength.setValue(explosion.windStrength.get());
            this.windFrontSpeed.setValue(explosion.windFrontSpeed.get());
            this.windDecay.setValue(explosion.windDecay.get());
            this.windAmbientYaw.setValue(explosion.windAmbientYaw.get());
            this.windAmbientStrength.setValue(explosion.windAmbientStrength.get());
            this.windSuction.setValue(explosion.windSuction.get());
            this.windHold.setValue(explosion.windHold.get());
            this.fireCount.setValue(explosion.fireCount.get());
            this.fireDuration.setValue(explosion.fireDuration.get());
            this.fireSize.setValue(explosion.fireSize.get());
            this.smokeScale.setValue(explosion.smokeScale.get());
            this.dustScale.setValue(explosion.dustScale.get());
            this.mushroomScale.setValue(explosion.mushroomScale.get());
            this.groundWave.setValue(explosion.groundWave.get());
            this.hazeScale.setValue(explosion.hazeScale.get());
            this.birdCount.setValue(explosion.birdCount.get());
            this.worldReach.setValue(explosion.worldReach.get());
            this.windStreaksUI.setValue(explosion.windStreaks.get());
            this.bendScanRadius.setValue(explosion.bendScanRadius.get());
            this.bendRadius.setValue(explosion.bendRadius.get());
            this.bendAmount.setValue(explosion.bendAmount.get());
            this.bendFell.setValue(explosion.bendFell.get());
            this.bendGusts.setValue(explosion.bendGusts.get());
            this.bendLeaves.setValue(explosion.bendLeaves.get());
            this.shape.setValue(explosion.shape.get());
            this.sphereRadius.setValue(explosion.sphereRadius.get());
            this.sphereExpand.setValue(explosion.sphereExpand.get());
            this.sphereHeat.setValue(explosion.sphereHeat.get());
            this.sphereScale.setValue(explosion.sphereScale.get());
            this.sparkCount.setValue(explosion.sparkCount.get());
            this.sparkSize.setValue(explosion.sparkSize.get());
            this.sparkSpeed.setValue(explosion.sparkSpeed.get());
            this.sparkGravity.setValue(explosion.sparkGravity.get());
            this.sparkLife.setValue(explosion.sparkLife.get());
            this.sparkSpread.setValue(explosion.sparkSpread.get());
            this.sparkColorR.setValue(explosion.sparkColorR.get());
            this.sparkColorG.setValue(explosion.sparkColorG.get());
            this.sparkColorB.setValue(explosion.sparkColorB.get());

            /* Rebuild so the Sphere section matches the loaded shape value. */
            this.bbsvfx$rebuildOptions();
        }
    }
}
