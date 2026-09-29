package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIStringList;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.forms.editors.panels.widgets.UIBlockStateEditor;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Color;
import org.joml.Vector3f;
import com.bbsvfx.bbsvfx.forms.CurveForm;
import com.bbsvfx.bbsvfx.forms.CurvePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor panel for {@link CurveForm}: colour / width / closed / resolution, two independent extrude
 * outputs (solid tube, swept block), taper and trim, plus a <b>point list</b> you switch between (like
 * the pose editor's bone list). The selected point's position / rotation are edited through a
 * {@link mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform} that also drives the viewport
 * <b>gizmo</b> (see {@link UICurveForm}); its width has its own slider.
 */
public class UICurveFormPanel extends UIFormPanel<CurveForm>
{
    public final UIColor color;
    public final UITrackpad width;
    public final UIToggle closed;
    public final UITrackpad resolution;
    public final UIToggle extrudeSolid;
    public final UIToggle extrudeBlocks;
    public final UIBlockStateEditor extrudeBlock;
    public final UITrackpad extrudeSpacing;
    public final UITrackpad extrudeScale;
    public final UIToggle extrudeAlign;
    public final UIToggle extrudeConnect;
    public final UIToggle taperEnabled;
    public final UITrackpad taperStart;
    public final UITrackpad taperEnd;
    public UIButton taperCurve;
    public final UITrackpad trimStart;
    public final UITrackpad trimEnd;

    /** Selectable list of points ("Point 0", "Point 1", ...) — picking one targets it for editing + gizmo. */
    public final UIStringList pointsList;
    public final UIButton addPoint;
    public final UIButton removePoint;
    public final UITrackpad pointWidth;
    public final UIElement pointWidthLabel;

    private static final String[] TAPER_NAMES = {"Linear", "Ease In", "Ease Out", "Smooth"};

    public UICurveFormPanel(UIForm editor)
    {
        super(editor);

        this.color = new UIColor((c) -> this.form.color.set(Color.rgba(c))).withAlpha();
        this.width = new UITrackpad((v) -> this.form.width.set(v.floatValue()));
        this.width.limit(0.001F, Float.POSITIVE_INFINITY).values(0.01F).increment(0.05F);
        this.closed = new UIToggle(IKey.constant("Closed"), (b) -> this.form.closed.set(b.getValue()));
        this.resolution = new UITrackpad((v) -> this.form.resolution.set(v.intValue()));
        this.resolution.limit(1, 256, true).increment(1);

        this.extrudeSolid = new UIToggle(IKey.constant("Extrude Solid"), (b) -> this.form.extrudeSolid.set(b.getValue()));
        this.extrudeBlocks = new UIToggle(IKey.constant("Extrude Block"), (b) -> this.form.extrudeBlocks.set(b.getValue()));
        this.extrudeBlock = new UIBlockStateEditor((s) -> this.form.extrudeBlock.set(s));
        this.extrudeSpacing = new UITrackpad((v) -> this.form.extrudeSpacing.set(v.floatValue()));
        this.extrudeSpacing.limit(0.05F, Float.POSITIVE_INFINITY).values(0.1F);
        this.extrudeScale = new UITrackpad((v) -> this.form.extrudeScale.set(v.floatValue()));
        this.extrudeScale.limit(0.01F, Float.POSITIVE_INFINITY).values(0.05F);
        this.extrudeAlign = new UIToggle(IKey.constant("Align to curve"), (b) -> this.form.extrudeAlign.set(b.getValue()));
        this.extrudeConnect = new UIToggle(IKey.constant("Connect (no gaps)"), (b) -> this.form.extrudeConnect.set(b.getValue()));

        this.taperEnabled = new UIToggle(IKey.constant("Enable taper"), (b) -> this.form.taperEnabled.set(b.getValue()));
        this.taperStart = new UITrackpad((v) -> this.form.taperStart.set(v.floatValue()));
        this.taperStart.limit(0F, 1F).values(0.05F);
        this.taperEnd = new UITrackpad((v) -> this.form.taperEnd.set(v.floatValue()));
        this.taperEnd.limit(0F, 1F).values(0.05F);
        this.taperCurve = new UIButton(IKey.constant("Linear"), (b) ->
        {
            int next = (this.form.taperCurve.get() + 1) % TAPER_NAMES.length;

            this.form.taperCurve.set(next);
            this.taperCurve.label = IKey.constant(TAPER_NAMES[next]);
        });

        this.trimStart = new UITrackpad((v) -> this.form.trimStart.set(v.floatValue()));
        this.trimStart.limit(0F, 1F).values(0.05F);
        this.trimEnd = new UITrackpad((v) -> this.form.trimEnd.set(v.floatValue()));
        this.trimEnd.limit(0F, 1F).values(0.05F);

        this.pointsList = new UIStringList((l) -> this.onPickPoint());
        this.pointsList.background().h(UIStringList.DEFAULT_HEIGHT * 6);
        this.addPoint = new UIButton(IKey.constant("Add point"), (b) -> this.addPoint());
        this.removePoint = new UIButton(IKey.constant("Remove"), (b) -> this.removePoint());
        this.pointWidth = new UITrackpad((v) ->
        {
            CurvePoint point = this.selectedPoint();

            if (point != null)
            {
                point.width.set(v.floatValue());
            }
        });
        this.pointWidth.limit(0.001F, Float.POSITIVE_INFINITY).values(0.05F);
        this.pointWidthLabel = UI.label(IKey.constant("Point width"));

        this.options.add(BbsVfxUI.section("Curve", this.color, this.width, this.closed, this.resolution));
        this.options.add(BbsVfxUI.section("Extrude", this.extrudeSolid, this.extrudeBlocks, this.extrudeBlock,
            UI.row(this.extrudeSpacing, this.extrudeScale), UI.row(this.extrudeAlign, this.extrudeConnect)));
        this.options.add(BbsVfxUI.section("Taper", this.taperEnabled, UI.row(this.taperStart, this.taperEnd), this.taperCurve));
        this.options.add(BbsVfxUI.section("Trim", UI.row(this.trimStart, this.trimEnd)));
        /* The point transform editor (X/Y/Z + the gizmo's drag handler) must be in the tree to render —
         * a detached UIPropTransform never processes its drag, so the gizmo wouldn't move. */
        this.options.add(BbsVfxUI.section("Points", UI.row(this.addPoint, this.removePoint), this.pointsList,
            ((UICurveForm) this.editor).pointGizmo(), this.pointWidthLabel, this.pointWidth));
    }

    /** List-selection callback: retarget the per-point widgets + gizmo onto the clicked point. */
    private void onPickPoint()
    {
        this.selectPoint(this.pointsList.getIndex());
    }

    /** The point backing the current list selection, or null when nothing is selected. */
    private CurvePoint selectedPoint()
    {
        List<CurvePoint> pts = this.form.points.getAllTyped();
        int index = this.pointsList.getIndex();

        return index >= 0 && index < pts.size() ? pts.get(index) : null;
    }

    /** Refill the list with one entry per point, preserving the natural (insertion) order. */
    private void rebuildList()
    {
        List<CurvePoint> pts = this.form.points.getAllTyped();
        List<String> names = new ArrayList<>();

        for (int i = 0; i < pts.size(); i++)
        {
            names.add("Point " + i);
        }

        this.pointsList.clear();
        this.pointsList.add(names);
    }

    /** Bind the per-point widgets + gizmo onto the point at the given index (clamped; -1 selects nothing). */
    private void selectPoint(int index)
    {
        List<CurvePoint> pts = this.form.points.getAllTyped();

        if (index < 0 || index >= pts.size())
        {
            this.pointsList.deselect();
            ((UICurveForm) this.editor).selectPoint(null);

            return;
        }

        this.pointsList.setIndex(index);

        CurvePoint point = pts.get(index);

        ((UICurveForm) this.editor).selectPoint(point);
        this.pointWidth.setValue(point.width.get());
    }

    private void addPoint()
    {
        List<CurvePoint> pts = this.form.points.getAllTyped();

        if (pts.isEmpty())
        {
            this.form.addPoint(0F, 0F, 0F, 0.2F);
        }
        else
        {
            /* Offset from the last point so consecutive adds make a visible curve. */
            Vector3f last = this.form.pointPosition(pts.get(pts.size() - 1));

            this.form.addPoint(last.x + 0.5F, last.y, last.z, 0.2F);
        }

        this.rebuildList();
        this.selectPoint(this.form.points.getAllTyped().size() - 1);
    }

    private void removePoint()
    {
        int index = this.pointsList.getIndex();

        if (index < 0)
        {
            return;
        }

        this.form.removePoint(index);
        this.rebuildList();

        /* Keep a sensible neighbour selected so the gizmo always has a target. */
        List<CurvePoint> pts = this.form.points.getAllTyped();

        this.selectPoint(pts.isEmpty() ? -1 : Math.min(index, pts.size() - 1));
    }

    @Override
    public void finishEdit()
    {
        super.finishEdit();

        /* BBS 2.6 dropped gizmo display modes: the gizmo always carries every element, and a real
         * restriction is a Gizmo.HandleMask handed to the capture call. Nothing to restore here. */
    }

    @Override
    public void startEdit(CurveForm form)
    {
        super.startEdit(form);

        this.color.setColor(form.color.get().getARGBColor());
        this.width.setValue(form.width.get());
        this.closed.setValue(form.closed.get());
        this.resolution.setValue(form.resolution.get());
        this.extrudeSolid.setValue(form.extrudeSolid.get());
        this.extrudeBlocks.setValue(form.extrudeBlocks.get());
        this.extrudeBlock.setBlockState(form.extrudeBlock.get());
        this.extrudeSpacing.setValue(form.extrudeSpacing.get());
        this.extrudeScale.setValue(form.extrudeScale.get());
        this.extrudeAlign.setValue(form.extrudeAlign.get());
        this.extrudeConnect.setValue(form.extrudeConnect.get());
        this.taperEnabled.setValue(form.taperEnabled.get());
        this.taperStart.setValue(form.taperStart.get());
        this.taperEnd.setValue(form.taperEnd.get());
        this.taperCurve.label = IKey.constant(TAPER_NAMES[Math.max(0, Math.min(TAPER_NAMES.length - 1, form.taperCurve.get()))]);
        this.trimStart.setValue(form.trimStart.get());
        this.trimEnd.setValue(form.trimEnd.get());

        this.rebuildList();
        this.selectPoint(this.form.points.getAllTyped().isEmpty() ? -1 : 0);
    }
}
