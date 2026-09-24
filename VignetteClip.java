package mchorse.bbs_mod.camera.clips.screen;

import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;
import mchorse.bbs_mod.utils.colors.Colors;

public class VignetteClip extends CameraClip
{
    public ValueInt color = new ValueInt("color", Colors.A100);
    public ValueFloat strength = new ValueFloat("strength", 0.6F, 0F, 1F);
    /** How far the fade reaches into the frame, as a part of it. */
    public ValueFloat size = new ValueFloat("size", 0.35F, 0.01F, 1F);

    private ScreenEffect effect = new ScreenEffect();

    public VignetteClip()
    {
        this.add(this.color);
        this.add(this.strength);
        this.add(this.size);
    }

    @Override
    protected void applyClip(ClipContext context, Position position)
    {
        float factor = this.envelope.factorEnabled(this.duration.get(), context.relativeTick + context.transition);

        this.effect.type = ScreenEffect.VIGNETTE;
        this.effect.factor = factor;
        this.effect.color = this.color.get();
        this.effect.value = this.strength.get();
        this.effect.extra = this.size.get();

        ScreenEffect.getEffects(context).add(this.effect);
    }

    @Override
    protected Clip create()
    {
        return new VignetteClip();
    }
}
