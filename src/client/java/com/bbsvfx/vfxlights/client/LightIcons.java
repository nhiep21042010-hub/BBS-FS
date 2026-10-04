package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.icons.Icon;

/**
 * The four lights' own glyphs, served from a 64×16 atlas of 16×16 icons (BBS's native icon size) at
 * {@code assets/vfxlights/bbs_override/textures/vfxlights_icons.png} through
 * {@link com.bbsvfx.vfxlights.resources.VfxLightsAssetsSourcePack}.
 *
 * <p>Order in the atlas is the order the lights are thought about: point, spot, area, ambient.</p>
 */
public final class LightIcons
{
    private static final Link ATLAS = Link.assets("textures/vfxlights_icons.png");

    public static final Icon POINT = at("point", 0);
    public static final Icon SPOT = at("spot", 16);
    public static final Icon AREA = at("area", 32);
    public static final Icon AMBIENT = at("ambient", 48);

    private LightIcons()
    {}

    private static Icon at(String id, int x)
    {
        return new Icon(ATLAS, "vfxlights_" + id, x, 0, 16, 16, 64, 16);
    }
}
