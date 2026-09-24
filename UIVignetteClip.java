package mchorse.bbs_mod.ui.film.clips;

import mchorse.bbs_mod.camera.clips.screen.VignetteClip;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;

public class UIVignetteClip extends UIClip<VignetteClip>
{
    public UIColor color;
    public UITrackpad strength;
    public UITrackpad size;

    public UIVignetteClip(VignetteClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.color = this.color(this.clip.color).withAlpha();
        this.strength = this.trackpad(this.clip.strength);
        this.strength.limit(0, 1);
        this.strength.tooltip(IKey.raw("Strength (0 - 1)"));
        this.size = this.trackpad(this.clip.size);
        this.size.limit(0.01, 1);
        this.size.tooltip(IKey.raw("How far the darkening reaches into the frame (0 - 1)"));
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(this.section(IKey.raw("Vignette"), this.color, this.strength, this.size));
    }
}
