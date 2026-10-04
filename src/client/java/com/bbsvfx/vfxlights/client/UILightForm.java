package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import com.bbsvfx.vfxlights.forms.LightForm;

import java.util.function.Function;

/**
 * Form-editor shell shared by all four lights: the light's own tabs plus BBS's stock ones.
 *
 * <p><b>Three tabs, not one panel.</b> Everything a light can do used to live in a single scrolling
 * column — 45 controls on an area light, against at most 18 in the largest stock BBS panel. The domain
 * is split the way BBS splits its own big editors ({@code UIModelForm}: pose, IK, physics, constraints,
 * actions): the lamp itself, what it does to the air, and how surfaces answer it. The tab strip, its
 * cycling hotkey and the memory of which tab was open all come from {@code UIPanelBase} for free.</p>
 *
 * <p>One generic shell rather than four near-identical subclasses — what varies is which panel to
 * build, its title, its icon, and whether the lamp has any presence in the air at all.</p>
 */
public class UILightForm <T extends LightForm> extends UIForm<T>
{
    private final UILightFormPanel<T> lightPanel;

    /**
     * @param atmosphere whether this light gets the Air and Style tabs. An ambient fill has no beam,
     *                   no direction and no specular, so for it they would be tabs of dead dials.
     */
    public UILightForm(Function<UIForm, UILightFormPanel<T>> panelFactory, IKey title, Icon icon, boolean atmosphere)
    {
        super();

        this.lightPanel = panelFactory.apply(this);
        this.defaultPanel = this.lightPanel;

        this.registerPanel(this.defaultPanel, title, icon);

        if (atmosphere)
        {
            this.registerPanel(new UILightAirPanel<T>(this), LightKeys.TAB_AIR, Icons.SPRAY);
            this.registerPanel(new UILightStylePanel<T>(this), LightKeys.TAB_STYLE, Icons.SHAPES);
        }

        this.registerDefaultPanels();
    }

    /** A dial moved on another tab: the Light tab's picker has to stop claiming an untouched look. */
    public void refreshPreset()
    {
        this.lightPanel.refreshPreset();
    }
}
