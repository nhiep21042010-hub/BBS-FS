package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix4f;
import com.bbsvfx.bbsvfx.forms.WindForm;

/**
 * Form editor wrapper for {@link WindForm}. After a scan, the wind's DIRECTION handle is the editor's
 * viewport gizmo: it is retargeted from the actor transform onto the form's direction point (the vector
 * from the actor to the point is where the wind blows) with no gizmo reimplementation — the same trick
 * {@link UIDestructionBoxForm} uses for its attractor point, here with a single handle and no mode switch.
 * Before the scan the normal actor gizmo is active (nothing to aim yet).
 */
public class UIWindForm extends UIForm<WindForm>
{
    private final Transform pointTransform = new Transform();
    private UIPropTransform pointGizmo;

    public UIWindForm()
    {
        super();

        this.defaultPanel = new UIWindFormPanel(this);

        this.registerPanel(this.defaultPanel, IKey.constant("Wind"), BbsVfxIcons.EXPLOSION);
        this.registerDefaultPanels();
    }

    /** The direction handle is only aimed once a territory has been scanned. */
    private boolean editingPoint()
    {
        return this.form != null && this.form.scanned.get();
    }

    /**
     * The direction point's transform editor (lazily built). It doubles as the X/Y/Z editor and the gizmo's
     * drag handler — the ray drag runs in {@link UIPropTransform#render}, so this widget MUST live in the
     * panel tree (see {@link UIWindFormPanel}); a detached instance never renders and the gizmo won't move.
     */
    public UIPropTransform pointGizmo()
    {
        if (this.pointGizmo == null)
        {
            this.pointGizmo = new UIPropTransform().callbacks(null, this::bbsvfx$writePointBack).barBackground();
            this.pointGizmo.setTransform(this.pointTransform);
        }

        return this.pointGizmo;
    }

    /** Refresh the direction editor from the stored point values (call when starting to edit a form). */
    public void syncPointWidget()
    {
        if (this.form == null)
        {
            return;
        }

        this.pointTransform.translate.set(this.form.pointX.get(), this.form.pointY.get(), this.form.pointZ.get());
        this.pointGizmo().setTransform(this.pointTransform);
    }

    /** Push the editor's translation back into the form's point values (called live during edits). */
    private void bbsvfx$writePointBack()
    {
        if (this.form == null)
        {
            return;
        }

        this.form.pointX.set(this.pointTransform.translate.x);
        this.form.pointY.set(this.pointTransform.translate.y);
        this.form.pointZ.set(this.pointTransform.translate.z);
    }

    @Override
    public UIPropTransform getEditableTransform()
    {
        if (this.editingPoint())
        {
            return this.pointGizmo();
        }

        return super.getEditableTransform();
    }

    @Override
    public Matrix4f getOrigin(float transition)
    {
        if (this.editingPoint())
        {
            /* Place the gizmo on the direction point (translate the local matrix so it lands on the
             * rendered handle even if the actor is rotated). */
            return new Matrix4f(super.getOriginMatrix(transition)).translate(this.form.point());
        }

        return super.getOrigin(transition);
    }

    @Override
    public Matrix4f getOriginMatrix(float transition)
    {
        if (this.editingPoint())
        {
            return new Matrix4f(super.getOriginMatrix(transition)).translate(this.form.point());
        }

        return super.getOriginMatrix(transition);
    }
}
