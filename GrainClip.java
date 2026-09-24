package mchorse.bbs_mod.camera.clips.screen;

import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;
import mchorse.bbs_mod.utils.colors.Colors;

public class GrainClip extends CameraClip
{
    public ValueFloat strength = new ValueFloat("strength", 0.15F, 0F, 1F);
    /** Size of a single speck, in frame units (the frame is 1080 units high). */
    public ValueFloat size = new ValueFloat("size", 2F, 0.5F, 10F);

    private ScreenEffect effect = new ScreenEffect();

    public GrainClip()
    {
        this.add(this.strength);
        this.add(this.size);
    }

    @Override
    protected void applyClip(ClipContext context, Position position)
    {
        float factor = this.envelope.factorEnabled(this.duration.get(), context.relativeTick + context.transition);

        this.effect.type = ScreenEffect.GRAIN;
        this.effect.factor = factor;
        this.effect.color = Colors.WHITE;
        this.effect.value = this.strength.get();
        this.effect.extra = this.size.get();

        ScreenEffect.getEffects(context).add(this.effect);
    }

    @Override
    protected Clip create()
    {
        return new GrainClip();
    }
}
