package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.icons.Icon;

/**
 * Custom BBS-style icons for the addon's VFX, served from a 96×16 atlas of six 16×16 glyphs (matching
 * BBS's native icon size) at {@code assets/bbsvfx/bbs_override/textures/bbsvfx_icons.png}, exposed to
 * BBS's AssetProvider via {@link com.bbsvfx.bbsvfx.resources.BbsVfxAssetsSourcePack}. World-destroying
 * effects wear a hazard triangle; cosmetic overlays are a bare glyph.
 */
public final class BbsVfxIcons
{
    private static final Link ATLAS = Link.assets("textures/bbsvfx_icons.png");

    public static final Icon BEAM = at("beam", 0);
    public static final Icon DOME = at("dome", 16);
    public static final Icon EXPLOSION = at("explosion", 32);
    public static final Icon DESTRUCTION = at("destruction", 48);
    public static final Icon CRACKS = at("cracks", 64);
    public static final Icon SMOKE = at("smoke", 80);

    private BbsVfxIcons()
    {}

    private static Icon at(String id, int x)
    {
        return new Icon(ATLAS, "bbsvfx_" + id, x, 0, 16, 16, 96, 16);
    }
}
