package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;

import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import com.bbsvfx.bbsvfx.forms.DestructionBoxForm;

/**
 * Editor panel for {@link DestructionBoxForm}: the animatable destruction amount, the blast direction
 * / radial / random scatter sliders, and (v1, until the wand exists) debug buttons to fill or clear a
 * test structure.
 */
public class UIDestructionBoxFormPanel extends UIFormPanel<DestructionBoxForm>
{
    public final UITrackpad destruction;
    public final UIToggle pointMode;
    public final UIToggle physicsMode;
    public final UITrackpad physDuration;
    public final UITrackpad physMaxBodies;
    public final UITrackpad physGravity;
    public final UITrackpad physFriction;
    public final UITrackpad physBounciness;
    public final UIToggle physGround;
    public final UIToggle physWorld;
    public final UITrackpad physWorldMargin;
    public final UIButton physRebake;
    public final UITrackpad physExplosionStrength;
    public final UITrackpad physExplosionRadius;
    public final UITrackpad physExplosionCone;
    public final UIToggle physWave;
    public final UITrackpad physWaveTime;
    public final UIToggle physInvertOrder;
    public final UIToggle physSupport;
    public final UITrackpad physClusterSize;
    public final UITrackpad physClusterStrength;
    public final UITrackpad physShatter;
    public final UITrackpad physShatterStrength;
    public final UITrackpad dirYaw;
    public final UITrackpad dirPitch;
    public final UITrackpad dirStrength;
    public final UITrackpad radialStrength;
    public final UITrackpad randomAmount;
    public final UITrackpad seed;
    public final UITrackpad rotationAmount;
    public final UITrackpad pointStrength;
    public final UITrackpad stagger;
    public final UIToggle pointAway;
    public final UIToggle invertOrder;
    public final UITrackpad fillSize;
    public final UIButton fillTest;
    public final UIButton clear;
    public final UILabel count;
    public final UILabel physStatus;

    /* Pre-built sections, added/removed per mode (the inactive mode's section is left out entirely).
     * Built ONCE and re-added (not rebuilt) so the contained widgets are never re-parented. */
    public BbsVfxSection destructionSection;
    public BbsVfxSection directionSection;
    public BbsVfxSection pointSection;
    public BbsVfxSection physicsSection;
    public BbsVfxSection explosionSection;
    public BbsVfxSection waveSection;
    public BbsVfxSection fractureSection;
    public BbsVfxSection randomSection;
    public BbsVfxSection rotationSection;
    public BbsVfxSection blocksSection;

    public UIDestructionBoxFormPanel(UIForm editor)
    {
        super(editor);

        this.destruction = new UITrackpad((v) -> this.form.destruction.set(v.floatValue()));
        this.destruction.limit(0F, 1F).values(0.01F);
        this.pointMode = new UIToggle(IKey.constant("Point destruction"), (b) ->
        {
            this.form.pointMode.set(b.getValue());

            if (b.getValue())
            {
                this.form.physicsMode.set(false);
            }

            this.bbsvfx$syncModeToggles();
            this.bbsvfx$updateModeVisibility();
        });
        this.physicsMode = new UIToggle(IKey.constant("Physics"), (b) ->
        {
            this.form.physicsMode.set(b.getValue());

            if (b.getValue())
            {
                this.form.pointMode.set(false);
            }

            this.bbsvfx$syncModeToggles();
            this.bbsvfx$updateModeVisibility();
        });
        this.physDuration = new UITrackpad((v) -> this.form.physDuration.set(v.floatValue()));
        this.physDuration.limit(0.5F, 60F).values(0.1F);
        this.physMaxBodies = new UITrackpad((v) -> this.form.physMaxBodies.set(v.intValue()));
        this.physMaxBodies.limit(0, 200_000, true).increment(1000);
        this.physGravity = new UITrackpad((v) -> this.form.physGravity.set(v.floatValue()));
        this.physGravity.values(0.5F);
        this.physFriction = new UITrackpad((v) -> this.form.physFriction.set(v.floatValue()));
        this.physFriction.limit(0F, 2F).values(0.05F);
        this.physBounciness = new UITrackpad((v) -> this.form.physBounciness.set(v.floatValue()));
        this.physBounciness.limit(0F, 1F).values(0.05F);
        this.physGround = new UIToggle(IKey.constant("Ground plane"), (b) -> this.form.physGround.set(b.getValue()));
        this.physWorld = new UIToggle(IKey.constant("World collision"), (b) -> this.form.physWorld.set(b.getValue()));
        this.physWorldMargin = new UITrackpad((v) -> this.form.physWorldMargin.set(v.floatValue()));
        this.physWorldMargin.limit(0F, 64F, true).increment(1);
        /* World blocks aren't hashed (too expensive), so terrain edits need a manual re-simulation.
         * Also wipes the PERSISTED bakes — they'd otherwise resurrect the stale sim after a restart. */
        this.physRebake = new UIButton(IKey.constant("Rebake"), (b) ->
        {
            DestructionPhysics.rebakeNonce++;
            DestructionPhysics.clearPersisted();
        });
        this.physExplosionStrength = new UITrackpad((v) -> this.form.physExplosionStrength.set(v.floatValue()));
        this.physExplosionStrength.limit(0F, Float.POSITIVE_INFINITY).values(0.5F);
        this.physExplosionRadius = new UITrackpad((v) -> this.form.physExplosionRadius.set(v.floatValue()));
        this.physExplosionRadius.limit(1F, Float.POSITIVE_INFINITY).values(0.5F);
        this.physExplosionCone = new UITrackpad((v) -> this.form.physExplosionCone.set(v.floatValue()));
        this.physExplosionCone.limit(0F, 1F).values(0.01F);
        this.physWave = new UIToggle(IKey.constant("Release wave"), (b) -> this.form.physWave.set(b.getValue()));
        this.physWaveTime = new UITrackpad((v) -> this.form.physWaveTime.set(v.floatValue()));
        this.physWaveTime.limit(0F, Float.POSITIVE_INFINITY).values(0.1F);
        /* Second widget over the SAME form value — the point-mode section owns the other instance. */
        this.physInvertOrder = new UIToggle(IKey.constant("Far blocks first"), (b) -> this.form.invertOrder.set(b.getValue()));
        this.physSupport = new UIToggle(IKey.constant("Support integrity"), (b) -> this.form.physSupport.set(b.getValue()));
        this.physClusterSize = new UITrackpad((v) -> this.form.physClusterSize.set(v.intValue()));
        this.physClusterSize.limit(1, 8, true).increment(1);
        this.physClusterStrength = new UITrackpad((v) -> this.form.physClusterStrength.set(v.floatValue()));
        this.physClusterStrength.limit(0F, Float.POSITIVE_INFINITY).values(10F);
        this.physShatter = new UITrackpad((v) -> this.form.physShatter.set(v.floatValue()));
        this.physShatter.limit(0F, 1F).values(0.05F);
        this.physShatterStrength = new UITrackpad((v) -> this.form.physShatterStrength.set(v.floatValue()));
        this.physShatterStrength.limit(0F, Float.POSITIVE_INFINITY).values(10F);
        this.dirYaw = new UITrackpad((v) -> this.form.dirYaw.set(v.floatValue()));
        this.dirPitch = new UITrackpad((v) -> this.form.dirPitch.set(v.floatValue()));
        this.dirStrength = new UITrackpad((v) -> this.form.dirStrength.set(v.floatValue()));
        this.dirStrength.limit(0F, Float.POSITIVE_INFINITY).values(0.1F);
        this.radialStrength = new UITrackpad((v) -> this.form.radialStrength.set(v.floatValue()));
        this.radialStrength.limit(0F, Float.POSITIVE_INFINITY).values(0.1F);
        this.randomAmount = new UITrackpad((v) -> this.form.randomAmount.set(v.floatValue()));
        this.randomAmount.limit(0F, Float.POSITIVE_INFINITY).values(0.1F);
        this.seed = new UITrackpad((v) -> this.form.seed.set(v.intValue()));
        this.seed.limit(0, Integer.MAX_VALUE, true).increment(1);
        this.rotationAmount = new UITrackpad((v) -> this.form.rotationAmount.set(v.floatValue()));
        this.rotationAmount.limit(0F, Float.POSITIVE_INFINITY).values(0.1F);

        this.pointStrength = new UITrackpad((v) -> this.form.pointStrength.set(v.floatValue()));
        this.pointStrength.limit(0F, Float.POSITIVE_INFINITY).values(0.1F);
        this.stagger = new UITrackpad((v) -> this.form.stagger.set(v.floatValue()));
        this.stagger.limit(0F, 1F).values(0.01F);
        this.pointAway = new UIToggle(IKey.constant("Away from point"), (b) -> this.form.pointAway.set(b.getValue()));
        this.invertOrder = new UIToggle(IKey.constant("Far blocks first"), (b) -> this.form.invertOrder.set(b.getValue()));

        this.fillSize = new UITrackpad((v) -> {});
        this.fillSize.limit(1, 64, true).increment(1);
        this.fillSize.setValue(20);
        this.fillTest = new UIButton(IKey.constant("Fill test cube"), (b) ->
        {
            this.form.fillTestStructure((int) this.fillSize.getValue());
            this.bbsvfx$updateCount();
        });
        this.clear = new UIButton(IKey.constant("Clear"), (b) ->
        {
            this.form.blocks.clearBlocks();
            this.bbsvfx$updateCount();
        });
        this.count = UI.label(IKey.constant(""));
        this.physStatus = UI.label(IKey.constant(""));

        this.destructionSection = BbsVfxUI.section("Destruction", this.destruction, this.pointMode, this.physicsMode);
        this.physicsSection = BbsVfxUI.section("Physics",
            this.physStatus,
            UI.label(IKey.constant("Max duration (s)")), this.physDuration,
            UI.label(IKey.constant("Max bodies (0 = all, rest fly ballistic)")), this.physMaxBodies,
            UI.label(IKey.constant("Gravity")), this.physGravity,
            UI.row(this.physFriction, this.physBounciness),
            this.physGround,
            this.physWorld,
            UI.label(IKey.constant("World margin (0 = auto)")), this.physWorldMargin,
            this.physRebake);
        /* The epicenter transform editor must be in the tree for the gizmo ray-drag (its own instance —
         * the point-mode section owns the other one; a widget can't have two parents). */
        this.explosionSection = BbsVfxUI.section("Explosion",
            ((UIDestructionBoxForm) this.editor).explosionGizmo(),
            UI.label(IKey.constant("Strength")), this.physExplosionStrength,
            UI.row(this.physExplosionRadius, this.physExplosionCone),
            UI.label(IKey.constant("Radius / Cone (0 = sphere, 1 = directed)")));
        this.waveSection = BbsVfxUI.section("Staged collapse",
            this.physWave,
            UI.label(IKey.constant("Wave time (s)")), this.physWaveTime,
            this.physInvertOrder,
            this.physSupport);
        this.fractureSection = BbsVfxUI.section("Fracture",
            UI.label(IKey.constant("Cluster size (1 = off)")), this.physClusterSize,
            UI.label(IKey.constant("Cluster strength (0 = unbreakable)")), this.physClusterStrength,
            UI.label(IKey.constant("Sub-block shatter (0..1 of radius)")), this.physShatter,
            UI.label(IKey.constant("Shatter strength")), this.physShatterStrength);
        this.directionSection = BbsVfxUI.section("Direction / radial",
            UI.row(this.dirYaw, this.dirPitch), this.dirStrength, this.radialStrength);
        /* The point's transform editor (X/Y/Z + the gizmo's drag handler) must be in the tree to render —
         * a detached UIPropTransform never processes its drag, so the gizmo wouldn't move. */
        this.pointSection = BbsVfxUI.section("Point", ((UIDestructionBoxForm) this.editor).pointGizmo(),
            this.pointStrength, this.stagger, this.pointAway, this.invertOrder);
        this.randomSection = BbsVfxUI.section("Random", UI.row(this.randomAmount, this.seed));
        this.rotationSection = BbsVfxUI.section("Rotation", this.rotationAmount);
        this.blocksSection = BbsVfxUI.section("Blocks (debug)", this.count,
            UI.label(IKey.constant("Cube size")), this.fillSize, this.fillTest, this.clear);

        this.bbsvfx$rebuildOptions();
    }

    /** (Re)build the option list for the current mode — the inactive modes' sections are left out entirely. */
    public void bbsvfx$rebuildOptions()
    {
        boolean point = this.form != null && this.form.pointMode.get();
        boolean physics = this.form != null && this.form.physicsMode.get();

        this.options.removeAll();
        this.options.add(this.destructionSection);

        if (physics)
        {
            /* Direction/Radial/Random become initial velocities, Rotation the initial tumble speed. */
            this.options.add(this.physicsSection);
            this.options.add(this.explosionSection);
            this.options.add(this.waveSection);
            this.options.add(this.fractureSection);
            this.options.add(this.directionSection);
        }
        else
        {
            this.options.add(point ? this.pointSection : this.directionSection);
        }

        /* Shared by all modes. */
        this.options.add(this.randomSection);
        this.options.add(this.rotationSection);
        this.options.add(this.blocksSection);
        this.options.resize();
    }

    /** The two mode toggles are mutually exclusive; mirror the form state back into both widgets. */
    private void bbsvfx$syncModeToggles()
    {
        this.pointMode.setValue(this.form.pointMode.get());
        this.physicsMode.setValue(this.form.physicsMode.get());
    }

    private void bbsvfx$updateCount()
    {
        this.count.label = IKey.constant(this.form.blocks.getList().size() + " blocks");
    }

    /**
     * Live physics status line at the top of the Physics section — the tester feedback was that a failed
     * PhysX init / a pending bake looked like "the settings do nothing", with the only trace in the log.
     */
    @Override
    public void render(mchorse.bbs_mod.ui.framework.UIContext context)
    {
        this.bbsvfx$updatePhysStatus();

        super.render(context);
    }

    private void bbsvfx$updatePhysStatus()
    {
        if (this.form == null || !this.form.physicsMode.get())
        {
            return;
        }

        String line = "";
        int status = mchorse.bbs_mod.forms.FormUtilsClient.getRenderer(this.form) instanceof DestructionBoxFormRenderer renderer
            ? renderer.physStatus
            : DestructionBoxFormRenderer.PHYS_STATUS_NONE;

        if (DestructionPhysics.failed() || status == DestructionBoxFormRenderer.PHYS_STATUS_UNAVAILABLE)
        {
            line = "PhysX failed to load - physics disabled: " + bbsvfx$shortError();
        }
        else if (DestructionPhysics.lastError != null)
        {
            line = "Simulation failed: " + bbsvfx$shortError();
        }
        else if (status == DestructionBoxFormRenderer.PHYS_STATUS_BAKING
            || status == DestructionBoxFormRenderer.PHYS_STATUS_BAKING_PREVIOUS)
        {
            line = "Simulating...";
        }
        else if (status == DestructionBoxFormRenderer.PHYS_STATUS_READY
            && mchorse.bbs_mod.forms.FormUtilsClient.getRenderer(this.form) instanceof DestructionBoxFormRenderer renderer)
        {
            line = "Simulated: " + renderer.physStatusUnits + " bodies, "
                + String.format("%.1f", renderer.physStatusDuration) + "s - scrub Destruction to play";
        }

        this.physStatus.label = IKey.constant(line);
    }

    private static String bbsvfx$shortError()
    {
        String error = DestructionPhysics.lastError;

        if (error == null)
        {
            return "see log";
        }

        return error.length() > 90 ? error.substring(0, 90) + "..." : error;
    }

    /**
     * React to a mode switch: rebuild the list. The point (attractor / explosion epicenter) used to
     * narrow the gizmo to translate-only, but BBS 2.6 dropped display modes from the gizmo — it always
     * carries every element, and a genuine restriction travels as a Gizmo.HandleMask with the capture
     * call, which the form editor viewport makes unmasked.
     */
    public void bbsvfx$updateModeVisibility()
    {
        this.bbsvfx$rebuildOptions();
    }

    @Override
    public void finishEdit()
    {
        super.finishEdit();

        /* BBS 2.6 dropped gizmo display modes: the gizmo always carries every element, and a real
         * restriction is a Gizmo.HandleMask handed to the capture call. Nothing to restore here. */
    }

    @Override
    public void startEdit(DestructionBoxForm form)
    {
        super.startEdit(form);

        this.destruction.setValue(form.destruction.get());
        this.pointMode.setValue(form.pointMode.get());
        this.physicsMode.setValue(form.physicsMode.get());
        this.physDuration.setValue(form.physDuration.get());
        this.physMaxBodies.setValue(form.physMaxBodies.get());
        this.physGravity.setValue(form.physGravity.get());
        this.physFriction.setValue(form.physFriction.get());
        this.physBounciness.setValue(form.physBounciness.get());
        this.physGround.setValue(form.physGround.get());
        this.physWorld.setValue(form.physWorld.get());
        this.physWorldMargin.setValue(form.physWorldMargin.get());
        this.physExplosionStrength.setValue(form.physExplosionStrength.get());
        this.physExplosionRadius.setValue(form.physExplosionRadius.get());
        this.physExplosionCone.setValue(form.physExplosionCone.get());
        this.physWave.setValue(form.physWave.get());
        this.physWaveTime.setValue(form.physWaveTime.get());
        this.physInvertOrder.setValue(form.invertOrder.get());
        this.physSupport.setValue(form.physSupport.get());
        this.physClusterSize.setValue(form.physClusterSize.get());
        this.physClusterStrength.setValue(form.physClusterStrength.get());
        this.physShatter.setValue(form.physShatter.get());
        this.physShatterStrength.setValue(form.physShatterStrength.get());
        this.dirYaw.setValue(form.dirYaw.get());
        this.dirPitch.setValue(form.dirPitch.get());
        this.dirStrength.setValue(form.dirStrength.get());
        this.radialStrength.setValue(form.radialStrength.get());
        this.randomAmount.setValue(form.randomAmount.get());
        this.seed.setValue(form.seed.get());
        this.rotationAmount.setValue(form.rotationAmount.get());
        this.pointStrength.setValue(form.pointStrength.get());
        this.stagger.setValue(form.stagger.get());
        this.pointAway.setValue(form.pointAway.get());
        this.invertOrder.setValue(form.invertOrder.get());

        ((UIDestructionBoxForm) this.editor).syncPointWidget();

        this.bbsvfx$updateModeVisibility();
        this.bbsvfx$updateCount();
    }
}
