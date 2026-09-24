package mchorse.bbs_mod.ui.film.clips;

import mchorse.bbs_mod.camera.clips.screen.GrainClip;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;

public class UIGrainClip extends UIClip<GrainClip>
{
    public UITrackpad strength;
    public UITrackpad size;

    public UIGrainClip(GrainClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.strength = this.trackpad(this.clip.strength);
        this.strength.limit(0, 1);
        this.strength.tooltip(IKey.raw("Strength (0 - 1)"));
        this.size = this.trackpad(this.clip.size);
        this.size.limit(0.5, 10);
        this.size.tooltip(IKey.raw("Size of one speck"));
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(this.section(IKey.raw("Grain"), this.strength, this.size));
    }
}
