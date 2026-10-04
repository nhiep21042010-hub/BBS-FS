package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UISection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A {@link UISection} whose rows are present only while they mean something: the softness dial exists
 * while shadows are on, the mote size while there is dust, and so on.
 *
 * <p><b>Why rows are re-added rather than hidden.</b> {@code setVisible(false)} keeps the element in the
 * column resizer, so a hidden row leaves a hole exactly its own height (see
 * {@code ColumnResizer.getResizers} — it walks every child, visible or not). The only way to make a row
 * cost nothing is to take it out of the parent, which is what {@link UISection#setExpanded} does with its
 * whole body. So this rebuilds the field list from the rows whose condition currently holds.</p>
 *
 * <p>The rebuild happens only when the set actually changes (a bitmask comparison), so dragging a
 * trackpad does not thrash the layout on every callback — and a row's dependants are always declared
 * AFTER it, so the control under the cursor never moves while the rows below it appear.</p>
 *
 * <p>At least one row must be unconditional: a section that empties itself would leave its header
 * floating over nothing.</p>
 */
public class UILightSection
{
    private static final BooleanSupplier ALWAYS = () -> true;

    /** The section itself — add this to the panel's scroll view. */
    public final UISection section;

    private final List<UIElement> rows = new ArrayList<>();
    private final List<BooleanSupplier> conditions = new ArrayList<>();

    /** Bitmask of the rows present right now; -1 until the first refresh. */
    private long mask = -1L;

    public UILightSection(IKey title)
    {
        this.section = new UISection(title);
    }

    /** A row that is always there. */
    public UILightSection row(UIElement row)
    {
        return this.row(ALWAYS, row);
    }

    /** A row that is there while {@code when} holds. */
    public UILightSection row(BooleanSupplier when, UIElement row)
    {
        if (this.rows.size() >= 64)
        {
            throw new IllegalStateException("A light section is limited to 64 rows by its dirty mask");
        }

        this.rows.add(row);
        this.conditions.add(when);

        return this;
    }

    /** Start folded — for the seasoning, not the meal. */
    public UILightSection collapsed()
    {
        this.section.setExpanded(false);

        return this;
    }

    /**
     * Re-evaluate every row's condition.
     *
     * @return whether the visible set changed, so the caller can resize the scroll view once for all
     *         of its sections instead of once per section.
     */
    public boolean refresh()
    {
        long current = 0L;

        for (int i = 0; i < this.conditions.size(); i++)
        {
            if (this.conditions.get(i).getAsBoolean())
            {
                current |= 1L << i;
            }
        }

        if (current == this.mask)
        {
            return false;
        }

        this.mask = current;
        this.section.fields.removeAll();

        for (int i = 0; i < this.rows.size(); i++)
        {
            if ((current & (1L << i)) != 0L)
            {
                this.section.fields.add(this.rows.get(i));
            }
        }

        return true;
    }
}
