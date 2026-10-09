package mchorse.bbs_mod.ui.dashboard.panels.landing;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * Edits the landing screen banner in game: the artwork slideshow, or a two colour gradient.
 * Changes apply to the banner behind this panel right away and are kept in the settings.
 */
public class UIBannerEditorPanel extends UIOverlayPanel
{
    private final UICirculate style;
    private final UIColor start;
    private final UIColor end;

    public UIBannerEditorPanel()
    {
        super(IKey.constant("Chỉnh banner"));

        this.style = new UICirculate((b) -> BBSSettings.bannerStyle.set(b.getValue()));
        this.style.addLabel(IKey.constant("Kiểu: Ảnh"));
        this.style.addLabel(IKey.constant("Kiểu: Gradient dọc"));
        this.style.addLabel(IKey.constant("Kiểu: Gradient ngang"));
        this.style.setValue(BBSSettings.bannerStyle.get());

        this.start = new UIColor((c) -> BBSSettings.bannerColorStart.set(c & Colors.RGB));
        this.start.setColor(BBSSettings.bannerColorStart.get() & Colors.RGB);

        this.end = new UIColor((c) -> BBSSettings.bannerColorEnd.set(c & Colors.RGB));
        this.end.setColor(BBSSettings.bannerColorEnd.get() & Colors.RGB);

        UILabel startLabel = UI.label(IKey.constant("Màu đầu"));
        UILabel endLabel = UI.label(IKey.constant("Màu cuối"));
        UILabel hint = UI.label(IKey.constant("Gradient chỉ hiện khi chọn kiểu Gradient."));

        UIElement column = UI.column(5, 10, this.style, startLabel, this.start, endLabel, this.end, hint);

        column.relative(this.content).xy(0, 0).w(1F).h(1F);
        this.content.add(column);
    }
}
