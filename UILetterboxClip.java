package mchorse.bbs_mod.ui.film.clips;

import mchorse.bbs_mod.camera.clips.screen.LetterboxClip;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;

public class UILetterboxClip extends UIClip<LetterboxClip>
{
    public UITrackpad ratio;
    public UIColor color;

    public UILetterboxClip(LetterboxClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.ratio = this.trackpad(this.clip.ratio);
        this.ratio.limit(0.1, 10);
        this.ratio.tooltip(IKey.raw("Aspect ratio of the picture that stays visible (2.39 = widescreen cinema)"));
        this.color = this.color(this.clip.color).withAlpha();
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(this.section(IKey.raw("Letterbox"), this.ratio, this.color));
    }
}
