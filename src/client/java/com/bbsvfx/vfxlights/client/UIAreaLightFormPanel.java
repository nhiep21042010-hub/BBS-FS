package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import com.bbsvfx.vfxlights.forms.AreaLightForm;
import com.bbsvfx.vfxlights.forms.LightForm;
import com.bbsvfx.vfxlights.forms.values.LightBarn;

/** Editor for the area light: the shape and size of the emitting surface, then the flaps that trim it. */
public class UIAreaLightFormPanel extends UILightFormPanel<AreaLightForm> implements LightGuideDrag.GuideSync
{
    public final UICirculate shape;
    public final UITrackpad width;
    public final UITrackpad height;
    public final UITrackpad thickness;
    public final UIToggle twoSided;
    public final UITrackpad spread;

    public final UITrackpad barnTop;
    public final UITrackpad barnBottom;
    public final UITrackpad barnLeft;
    public final UITrackpad barnRight;
    public final UITrackpad barnSoftness;

    /** The first dimension is a width, a diameter or a length depending on the shape. */
    private final UILabel widthLabel;

    public UIAreaLightFormPanel(UIForm editor)
    {
        super(editor);

        this.shape = new UICirculate(this.dial((b) ->
        {
            this.form.shape.set(b.getValue());
            this.updateShapeLabel();
        }));
        this.shape.addLabel(LightKeys.SHAPE_RECT);
        this.shape.addLabel(LightKeys.SHAPE_DISC);
        this.shape.addLabel(LightKeys.SHAPE_TUBE);
        this.shape.addLabel(LightKeys.SHAPE_SPHERE);
        this.shape.tooltip(LightKeys.SHAPE_TOOLTIP);

        this.widthLabel = label(LightKeys.WIDTH);

        this.width = new UITrackpad(this.dial((v) -> this.form.width.set(v.floatValue())));
        this.width.limit(0.01F, 128F).values(0.05F);
        this.width.tooltip(LightKeys.WIDTH_TOOLTIP);

        this.height = new UITrackpad(this.dial((v) -> this.form.height.set(v.floatValue())));
        this.height.limit(0.01F, 128F).values(0.05F);
        this.height.tooltip(LightKeys.HEIGHT_TOOLTIP);

        this.thickness = new UITrackpad(this.dial((v) -> this.form.thickness.set(v.floatValue())));
        this.thickness.limit(0.001F, 16F).values(0.01F);
        this.thickness.tooltip(LightKeys.TUBE_RADIUS_TOOLTIP);

        this.twoSided = new UIToggle(LightKeys.TWO_SIDED, this.dial((t) -> this.form.twoSided.set(t.getValue())));
        this.twoSided.tooltip(LightKeys.TWO_SIDED_TOOLTIP);

        this.spread = new UITrackpad(this.dial((v) -> this.form.spread.set(v.floatValue())));
        this.spread.limit(0.01F, 1F).values(0.01F);
        this.spread.tooltip(LightKeys.SPREAD_TOOLTIP);

        this.barnTop = new UITrackpad(this.dial((f) -> f.barn, (v) -> this.form.barn.getOriginalValue().top = v.floatValue()));
        this.barnTop.limit(0F, 1F).values(0.01F);
        this.barnTop.tooltip(LightKeys.BARN_TOP_TOOLTIP);

        this.barnBottom = new UITrackpad(this.dial((f) -> f.barn, (v) -> this.form.barn.getOriginalValue().bottom = v.floatValue()));
        this.barnBottom.limit(0F, 1F).values(0.01F);
        this.barnBottom.tooltip(LightKeys.BARN_BOTTOM_TOOLTIP);

        this.barnLeft = new UITrackpad(this.dial((f) -> f.barn, (v) -> this.form.barn.getOriginalValue().left = v.floatValue()));
        this.barnLeft.limit(0F, 1F).values(0.01F);
        this.barnLeft.tooltip(LightKeys.BARN_LEFT_TOOLTIP);

        this.barnRight = new UITrackpad(this.dial((f) -> f.barn, (v) -> this.form.barn.getOriginalValue().right = v.floatValue()));
        this.barnRight.limit(0F, 1F).values(0.01F);
        this.barnRight.tooltip(LightKeys.BARN_RIGHT_TOOLTIP);

        this.barnSoftness = new UITrackpad(this.dial((f) -> f.barn, (v) -> this.form.barn.getOriginalValue().softness = v.floatValue()));
        this.barnSoftness.limit(0F, 8F).values(0.05F);
        this.barnSoftness.tooltip(LightKeys.BARN_SOFTNESS_TOOLTIP);

        UILightSection emitter = this.section(LightKeys.SECTION_EMITTER);

        emitter.row(this.shape);
        emitter.row(row(this.widthLabel, this.width));
        emitter.row(() -> this.form.getShape() == AreaLightForm.Shape.RECT, row(LightKeys.HEIGHT, this.height));
        emitter.row(() -> this.form.getShape() == AreaLightForm.Shape.TUBE, row(LightKeys.TUBE_RADIUS, this.thickness));
        emitter.row(advancedOr(() -> this.form.twoSided.get()), this.twoSided);
        emitter.row(advancedOr(() -> this.form.spread.get() != 1F), row(LightKeys.SPREAD, this.spread));

        /* Barn doors: the flaps a gaffer swings in to keep light off the background. Folded by
         * default — a lamp is aimed before it is trimmed. */
        UILightSection barnDoors = this.section(LightKeys.SECTION_BARN).collapsed();

        barnDoors.row(row(LightKeys.BARN_TOP, this.barnTop));
        barnDoors.row(row(LightKeys.BARN_BOTTOM, this.barnBottom));
        barnDoors.row(row(LightKeys.BARN_LEFT, this.barnLeft));
        barnDoors.row(row(LightKeys.BARN_RIGHT, this.barnRight));
        barnDoors.row(row(LightKeys.SOFTNESS, this.barnSoftness));

        this.buildLightTab(emitter, barnDoors);
    }

    /** Area lights are always physical (analytic form factors) — the falloff switch would do nothing. */
    @Override
    protected boolean supportsFalloffToggle()
    {
        return false;
    }

    private void updateShapeLabel()
    {
        this.widthLabel.label = switch (this.form.getShape())
        {
            case RECT -> LightKeys.WIDTH;
            case TUBE -> LightKeys.LENGTH;
            default -> LightKeys.DIAMETER;
        };
    }

    @Override
    public void startEdit(AreaLightForm form)
    {
        /* The gizmo drag syncs its trackpads back into whichever panel is showing the lamp. */
        LightGuideDrag.bindPanel(this);

        this.shape.setValue(form.shape.get());
        this.width.setValue(form.width.get());
        this.height.setValue(form.height.get());
        this.thickness.setValue(form.thickness.get());
        this.twoSided.setValue(form.twoSided.get());
        this.spread.setValue(form.spread.get());
        LightBarn barn = form.barn.getOriginalValue();

        this.barnTop.setValue(barn.top);
        this.barnBottom.setValue(barn.bottom);
        this.barnLeft.setValue(barn.left);
        this.barnRight.setValue(barn.right);
        this.barnSoftness.setValue(barn.softness);

        super.startEdit(form);

        this.updateShapeLabel();
    }

    /** A gizmo drag wrote the form: catch the trackpads up (setValue fires no callback — safe). */
    @Override
    public void syncShape(LightForm form)
    {
        if (form != this.form)
        {
            return;
        }

        this.width.setValue(this.form.width.get());
        this.height.setValue(this.form.height.get());
        this.range.setValue(this.form.range.get());
    }
}
