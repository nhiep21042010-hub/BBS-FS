package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix4f;
import com.bbsvfx.bbsvfx.forms.DestructionBoxForm;

/**
 * Form editor wrapper for {@link DestructionBoxForm}.
 *
 * <p>Besides the value panel it adds a "point" gizmo mode: while {@code pointMode} is on, the editor's
 * existing viewport gizmo is retargeted from the actor's transform onto the form's attractor point.
 * This is done by returning a {@link UIPropTransform} whose translation is the point
 * ({@link #getEditableTransform()}) and by offsetting the gizmo origin to the point
 * ({@link #getOrigin}/{@link #getOriginMatrix}) — so BBS's full arrow gizmo (handles, stencil picking,
 * ray drag) moves the point with no gizmo reimplementation. The gizmo simply follows the Point
 * destruction toggle, so there is no separate "edit point" switch.</p>
 */
public class UIDestructionBoxForm extends UIForm<DestructionBoxForm>
{
    private final Transform pointTransform = new Transform();
    private UIPropTransform pointGizmo;
    private UIPropTransform explosionGizmo;

    public UIDestructionBoxForm()
    {
        super();

        this.defaultPanel = this.bbsvfx$createPanel();

        this.registerPanel(this.defaultPanel, this.bbsvfx$panelTitle(), this.bbsvfx$panelIcon());
        this.registerDefaultPanels();
    }

    /* Overridden by UIExplosionForm — same gizmo machinery, explosion-flavoured panel. */
    protected UIDestructionBoxFormPanel bbsvfx$createPanel()
    {
        return new UIDestructionBoxFormPanel(this);
    }

    protected IKey bbsvfx$panelTitle()
    {
        return IKey.constant("Destruction box");
    }

    protected mchorse.bbs_mod.ui.utils.icons.Icon bbsvfx$panelIcon()
    {
        return BbsVfxIcons.DESTRUCTION;
    }

    private boolean editingPoint()
    {
        return this.form != null && (this.form.pointMode.get() || this.form.physicsMode.get());
    }

    /**
     * The point's transform editor (lazily built). It doubles as the position editor (numeric X/Y/Z)
     * and as the gizmo's drag handler — the ray drag is processed in {@link UIPropTransform#render},
     * so this widget MUST live in the panel tree (see {@link UIDestructionBoxFormPanel}); a detached
     * instance never renders and the gizmo would not move.
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

    /**
     * The explosion epicenter editor for PHYSICS mode — a SECOND widget over the SAME point values,
     * because a widget has one parent and the point section (point mode) and the explosion section
     * (physics mode) each need one in their tree for the gizmo ray-drag to run.
     */
    public UIPropTransform explosionGizmo()
    {
        if (this.explosionGizmo == null)
        {
            this.explosionGizmo = new UIPropTransform().callbacks(null, this::bbsvfx$writePointBack).barBackground();
            this.explosionGizmo.setTransform(this.pointTransform);
        }

        return this.explosionGizmo;
    }

    /** Refresh the point editors from the stored point values (call when starting to edit a form). */
    public void syncPointWidget()
    {
        this.pointTransform.translate.set(
            this.form.pointX.get(), this.form.pointY.get(), this.form.pointZ.get());
        this.pointGizmo().setTransform(this.pointTransform);
        this.explosionGizmo().setTransform(this.pointTransform);
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
            /* The instance that is actually in the panel tree for the current mode. */
            return this.form.physicsMode.get() ? this.explosionGizmo() : this.pointGizmo();
        }

        return super.getEditableTransform();
    }

    /** The raw ACTOR origin (world placement), bypassing the point-gizmo offset override. */
    public Matrix4f bbsvfx$actorOrigin(float transition)
    {
        return super.getOriginMatrix(transition);
    }

    @Override
    public Matrix4f getOrigin(float transition)
    {
        if (this.editingPoint())
        {
            /* Place the gizmo on the point. Translate the full local matrix so it lands on the same
             * spot as the rendered marker even if the actor is rotated. */
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
