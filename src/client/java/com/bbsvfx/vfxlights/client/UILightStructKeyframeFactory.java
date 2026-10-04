package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import com.bbsvfx.vfxlights.forms.values.LightField;
import com.bbsvfx.vfxlights.forms.values.LightStruct;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The panel behind a grouped light track: click a keyframe on Air, Style, Toon or Barn doors and every
 * dial in that group is here, at the value it holds at that keyframe.
 *
 * <p>One editor for all four, built from the group's own schema
 * ({@link LightStruct#schema()}) plus a label per dial — the same table that drives serialisation and
 * interpolation, so a dial can never exist in the group and be missing from its editor.</p>
 *
 * <p>Edits go through {@code UIReplaysEditorUtils.forEachSelectedKeyframe}, so dragging a dial with
 * several keyframes selected moves all of them, exactly like BBS's own composite editors.</p>
 */
public class UILightStructKeyframeFactory <T extends LightStruct<T>> extends UIKeyframeFactory<T>
{
    private final List<UIElement> rows = new ArrayList<>();
    private final List<UITrackpad> dials = new ArrayList<>();
    private final List<UIToggle> flags = new ArrayList<>();

    private final LightField[] schema;

    private boolean syncing;

    public UILightStructKeyframeFactory(Keyframe<T> keyframe, UIKeyframes editor, IKey[] labels)
    {
        super(keyframe, editor);

        T value = keyframe.getValue();

        /* A keyframe always carries a value here, but the schema is the one thing this panel cannot
         * be built without — so it falls back to a fresh group rather than dying on a null. */
        this.schema = (value == null ? keyframe.getFactory().createEmpty() : value).schema();

        for (int i = 0; i < this.schema.length; i++)
        {
            LightField field = this.schema[i];
            int index = i;
            /* The labels array is index-aligned by hand and can lag a growing schema — a missing
             * label must not kill the keyframe editor (the AIOOBE crash report). Fall back to the
             * field's own key as the row title. */
            IKey label = i < labels.length ? labels[i] : IKey.constant(field.key);

            if (field.step)
            {
                UIToggle toggle = new UIToggle(label, (b) ->
                    this.edit(index, b.getValue() ? 1F : 0F));

                this.flags.add(toggle);
                this.dials.add(null);
                this.rows.add(toggle.marginTop(UIConstants.SECTION_GAP));
            }
            else
            {
                UITrackpad trackpad = new UITrackpad((v) -> this.edit(index, v.floatValue()));

                trackpad.limit(field.min, field.max).values(0.02F);

                this.flags.add(null);
                this.dials.add(trackpad);
                this.rows.add(UI.labelRow(label, trackpad).marginTop(UIConstants.SECTION_GAP));
            }
        }

        this.scroll.add(UI.column(this.rows.toArray(new UIElement[0])));

        this.display();
    }

    /** Push the keyframe's values into the widgets without the callbacks writing them straight back. */
    private void display()
    {
        T value = this.keyframe.getValue();

        if (value == null)
        {
            return;
        }

        float[] fields = value.fields();

        this.syncing = true;

        try
        {
            for (int i = 0; i < fields.length; i++)
            {
                UITrackpad trackpad = this.dials.get(i);
                UIToggle toggle = this.flags.get(i);

                if (trackpad != null)
                {
                    trackpad.setValue(fields[i]);
                }
                else if (toggle != null)
                {
                    toggle.setValue(fields[i] > 0.5F);
                }
            }
        }
        finally
        {
            this.syncing = false;
        }
    }

    private void edit(int index, float value)
    {
        if (this.syncing)
        {
            return;
        }

        this.forEach((struct) ->
        {
            float[] fields = struct.fields();

            fields[index] = value;
            struct.setFields(fields);
        });
    }

    @SuppressWarnings("unchecked")
    private void forEach(Consumer<T> consumer)
    {
        UIReplaysEditorUtils.forEachSelectedKeyframe(this.editor, this.keyframe, (selected) ->
        {
            T struct = (T) selected.getValue();

            if (struct == null)
            {
                return;
            }

            selected.preNotify();
            consumer.accept(struct);
            selected.postNotify();
        });
    }
}
