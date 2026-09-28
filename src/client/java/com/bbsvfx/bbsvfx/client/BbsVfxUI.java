package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;


/**
 * Small UI helper for the addon: builds a BBS {@link BbsVfxSection} (the collapsible header-card BBS 2.3.1
 * uses to group panel controls) from a title and its fields, so all addon feature panels group their
 * controls the same idiomatic way as stock BBS panels.
 */
public final class BbsVfxUI
{
    private BbsVfxUI()
    {
    }

    /** A collapsible section titled {@code title} containing {@code fields}. */
    public static BbsVfxSection section(IKey title, IUIElement... fields)
    {
        BbsVfxSection section = new BbsVfxSection(title);

        section.fields.add(fields);

        return section;
    }

    /** Convenience overload taking a plain string title. */
    public static BbsVfxSection section(String title, IUIElement... fields)
    {
        return section(IKey.constant(title), fields);
    }
}
