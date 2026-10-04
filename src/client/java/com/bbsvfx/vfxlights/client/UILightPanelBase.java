package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import com.bbsvfx.vfxlights.forms.LightForm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What every light tab shares: dials that keep the preset honest, sections that drop the rows they do
 * not need, and the Simple/Advanced switch.
 *
 * <p>The lights used to live in one panel of 45 controls; they now sit in three tabs (Light, Air,
 * Style), which is how BBS itself splits a big editor — see {@code UIModelForm}, five tabs of at most
 * eighteen controls each. This class is the part all three of them need.</p>
 */
public abstract class UILightPanelBase <T extends LightForm> extends UIFormPanel<T>
{
    /**
     * Whether the rarely-touched dials are shown. Static because it is a statement about the USER, not
     * about a lamp — the same reasoning (and the same lifetime) as the options column width that
     * {@link UIFormPanel} keeps per panel class.
     */
    private static boolean advanced;

    private final List<UILightSection> sections = new ArrayList<>();

    /**
     * Whether the sections have to be re-evaluated, deferred to the start of this panel's own frame.
     *
     * <p>★A dial CANNOT rebuild the tree from its callback. {@link mchorse.bbs_mod.ui.framework.elements.input.UITrackpad}
     * drags the value inside its own {@code render()}, so the callback fires while the row list that
     * holds the trackpad is being iterated — taking a row out of it there threw
     * {@code ConcurrentModificationException} the moment a drag crossed a threshold that adds a row
     * (dust past 0 revealing the mote size). Marking dirty and rebuilding before this panel renders
     * its children touches no list anybody is walking.</p>
     */
    private boolean dirty = true;

    public UILightPanelBase(UIForm editor)
    {
        super(editor);
    }

    public static boolean isAdvanced()
    {
        return advanced;
    }

    /* Building blocks */

    /** A section owned by this panel: it will be refreshed with the rest. */
    protected UILightSection section(IKey title)
    {
        UILightSection section = new UILightSection(title);

        this.sections.add(section);

        return section;
    }

    /** "Name — control" on one line, the way {@link UI#labelRow} prescribes: ONE control per row. */
    protected static UIElement row(IKey label, UIElement element)
    {
        return UI.labelRow(label, element);
    }

    /**
     * The same row with a name that changes with the lamp (an area emitter's first dimension is a
     * width, a diameter or a length depending on its shape), so keep the label to rename it.
     */
    protected static UIElement row(UILabel label, UIElement element)
    {
        UIElement row = new UIElement();

        row.row(UIConstants.MARGIN).preferred(0).height(UIConstants.CONTROL_HEIGHT);
        row.add(label, element.w(UIConstants.VALUE_WIDTH));

        return row;
    }

    protected static UILabel label(IKey text)
    {
        return UI.label(text, UIConstants.CONTROL_HEIGHT).labelAnchor(0, 0.5F);
    }

    /**
     * A row for a dial that is off the beaten path: present in Advanced, and present in Simple too
     * whenever it is actually doing something ({@code set}). A dial that has been moved never
     * disappears — hiding a value that is in effect would be lying about the lamp.
     */
    protected static BooleanSupplier advancedOr(BooleanSupplier set)
    {
        return () -> advanced || set.getAsBoolean();
    }

    /* Behaviour */

    /**
     * Wrap a control callback so any hand tweak marks the stamped look as modified. Safe because
     * {@code setValue()} on stock BBS widgets never fires the callback, so {@link #startEdit} and
     * preset stamping don't trip it.
     *
     * <p>The push at the end is what makes a drag SHOW: the light being edited usually lives on the
     * persisted snapshot (culled block, in-UI preview) or is re-collected next frame from values
     * that may sit behind a keyframe runtime copy — neither path applies the tweak the moment it
     * happens. Pushing the form into the registry straight from the dial lands it for the very
     * next frame in every backend (see FormLightCollector.refreshFromForm).</p>
     */
    protected <V> Consumer<V> dial(Consumer<V> callback)
    {
        return (v) ->
        {
            callback.accept(v);
            this.markCustom();
            this.dirty = true;

            if (this.form != null)
            {
                com.bbsvfx.vfxlights.client.light.FormLightCollector.refreshFromForm(this.form);
            }
        };
    }

    /**
     * A dial that edits a GROUPED property in place.
     *
     * <p>{@code value.set(...)} raises the notification the undo stack and the film listen for, but a
     * group is edited by writing a field on the object {@code get()} handed back, which nothing would
     * hear. So the mutation is bracketed by the value's own pre/post notifications — the same idiom
     * {@code UIPropTransform} uses for a transform and {@code UIWindKeyframeFactory} for wind.</p>
     */
    /**
     * A dial that edits a GROUPED property in place.
     *
     * <p>{@code value.set(...)} raises the notification the undo stack and the film listen for, but a
     * group is edited by writing a field on the object {@code get()} handed back, which nothing would
     * hear. So the mutation is bracketed by the value's own pre/post notifications — the same idiom
     * {@code UIPropTransform} uses for a transform and {@code UIWindKeyframeFactory} for wind.</p>
     *
     * <p>★The runtime mirror: every reader (collector, gizmos) uses {@code get()}, which prefers the
     * RUNTIME value once a keyframe channel has evaluated one in — and a paused editor keeps that
     * stale snapshot forever, so a struct dial edit never showed anywhere ("в редакторе не видно
     * изменений", until some re-render dropped the runtime copy). Mirror the mutation onto the
     * runtime struct when one exists; live playback re-evaluates every frame anyway, so this only
     * ever repaints a paused editor's stale snapshot.</p>
     */
    protected <V> Consumer<V> dial(Function<T, ? extends BaseValue> group, Consumer<V> callback)
    {
        return this.dial((v) ->
        {
            BaseValue value = group.apply(this.form);

            value.preNotify();
            callback.accept(v);

            if (value instanceof mchorse.bbs_mod.settings.values.base.BaseValueBasic<?> basic
                && basic.getRuntimeValue() instanceof com.bbsvfx.vfxlights.forms.values.LightStruct<?> runtime)
            {
                runtime.setFields(
                    ((com.bbsvfx.vfxlights.forms.values.LightStruct<?>) basic.getOriginalValue()).fields());
            }

            value.postNotify();
        });
    }

    /** Note that the lamp no longer matches the look it was stamped from. */
    protected void markCustom()
    {
        if (this.form == null || this.form.preset.get().isEmpty() || this.form.presetTweaked.get())
        {
            return;
        }

        this.form.presetTweaked.set(true);

        if (this.editor instanceof UILightForm<?> light)
        {
            light.refreshPreset();
        }
    }

    /**
     * Re-evaluate every section, resizing the column once if anything actually moved. Called from
     * {@link #render(UIContext)} only — see {@link #dirty}.
     */
    private void refreshSections()
    {
        if (this.form == null)
        {
            /* Panels are built before the editor points them at a lamp; the conditions read the form. */
            return;
        }

        boolean changed = false;

        for (UILightSection section : this.sections)
        {
            changed |= section.refresh();
        }

        if (changed)
        {
            this.options.resize();
        }
    }

    /**
     * Rebuild every tab of this editor against the current form, keeping the user on this tab. Used by
     * the two commands that change what the OTHER tabs should be showing: stamping a look and flipping
     * Simple/Advanced.
     */
    @SuppressWarnings("unchecked")
    protected void refreshEditor()
    {
        if (this.editor != null && this.form != null)
        {
            this.editor.startEdit(this.form, this.getClass());
        }
    }

    protected void setAdvanced(boolean value)
    {
        advanced = value;

        this.refreshEditor();
    }

    @Override
    public void startEdit(T form)
    {
        super.startEdit(form);

        this.dirty = true;
    }

    @Override
    public void render(UIContext context)
    {
        if (this.dirty)
        {
            this.dirty = false;

            this.refreshSections();
        }

        super.render(context);
    }
}
