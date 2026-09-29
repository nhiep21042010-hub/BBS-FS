package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import org.joml.Matrix4f;
import com.bbsvfx.bbsvfx.forms.CurveForm;
import com.bbsvfx.bbsvfx.forms.CurvePoint;

/**
 * Form editor wrapper for {@link CurveForm}: registers the custom {@link UICurveFormPanel} as the
 * default panel plus the standard form panels (transform, etc.).
 *
 * <p>The points are edited through a selectable list (see {@link UICurveFormPanel}); the currently
 * selected point gets <b>gizmo support</b>: the editor's viewport gizmo is retargeted from the actor's
 * transform onto that point's pose bone. This reuses the same mechanism as the destruction-box point
 * gizmo — {@link #getEditableTransform()} returns a {@link UIPropTransform} bound to the point's live
 * {@code PoseTransform}, and {@link #getOrigin}/{@link #getOriginMatrix} offset the gizmo origin to the
 * point — so BBS's full arrow gizmo (handles, stencil picking, ray drag) moves the point with no gizmo
 * reimplementation.</p>
 */
public class UICurveForm extends UIForm<CurveForm>
{
    private UIPropTransform pointGizmo;
    private CurvePoint selectedPoint;

    public UICurveForm()
    {
        super();

        this.defaultPanel = new UICurveFormPanel(this);

        this.registerPanel(this.defaultPanel, IKey.constant("Curve"), Icons.CURVES);
        this.registerDefaultPanels();
    }

    /**
     * The selected point's transform editor (lazily built). It doubles as the numeric position/rotation
     * editor and as the gizmo's drag handler — the ray drag is processed in {@link UIPropTransform#render},
     * so this widget MUST live in the panel tree (see {@link UICurveFormPanel}); a detached instance
     * never renders and the gizmo would not move.
     */
    public UIPropTransform pointGizmo()
    {
        if (this.pointGizmo == null)
        {
            this.pointGizmo = new UIPropTransform().callbacks(() -> this.form.pose).barBackground();
        }

        return this.pointGizmo;
    }

    /**
     * Bind the point editor / gizmo onto the given point's live pose-bone transform (or detach it when
     * {@code point} is null). Called by the panel when the list selection changes.
     */
    public void selectPoint(CurvePoint point)
    {
        this.selectedPoint = point;
        this.pointGizmo().setTransform(point == null ? null : this.form.pointTransformOf(point));
        this.updateGizmoMode();
    }

    /** Whether the viewport gizmo currently edits a curve point (the Curve panel is open + a point picked). */
    private boolean editingPoint()
    {
        return this.form != null && this.selectedPoint != null && this.view == this.defaultPanel;
    }

    /**
     * Used to narrow the viewport gizmo to the translate arrows while a point was selected. BBS 2.6
     * dropped display modes from the gizmo entirely — it always carries every element now, and which
     * ones are drawn is the user's setting. The restriction a target genuinely needs is expressed as a
     * {@link Gizmo.HandleMask} passed at capture time by whoever renders the gizmo, and the form editor
     * viewport renders it unmasked, so there is nothing for a panel to set. Kept as a no-op hook so the
     * call sites still read as "the selection changed".
     */
    private void updateGizmoMode()
    {
    }

    @Override
    public void setPanel(UIFormPanel<CurveForm> panel)
    {
        super.setPanel(panel);

        /* React to tab switches: only the Curve panel drives the point gizmo, every other tab gets the
         * normal combined actor gizmo back. */
        this.updateGizmoMode();
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
            /* Place the gizmo on the point — translate the full local matrix so it lands on the same spot
             * as the rendered point even if the actor is moved/rotated. */
            return new Matrix4f(super.getOriginMatrix(transition)).translate(this.form.pointPosition(this.selectedPoint));
        }

        return super.getOrigin(transition);
    }

    @Override
    public Matrix4f getOriginMatrix(float transition)
    {
        if (this.editingPoint())
        {
            return new Matrix4f(super.getOriginMatrix(transition)).translate(this.form.pointPosition(this.selectedPoint));
        }

        return super.getOriginMatrix(transition);
    }
}
