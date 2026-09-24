package mchorse.bbs_mod.camera.clips.screen;

import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;
import mchorse.bbs_mod.utils.colors.Colors;

public class LetterboxClip extends CameraClip
{
    /** Aspect ratio of the picture that is left visible; the rest is covered with bars. */
    public ValueFloat ratio = new ValueFloat("ratio", 2.39F, 0.1F, 10F);
    public ValueInt color = new ValueInt("color", Colors.A100);

    private ScreenEffect effect = new ScreenEffect();

    public LetterboxClip()
    {
        this.add(this.ratio);
        this.add(this.color);
    }

    @Override
    protected void applyClip(ClipContext context, Position position)
    {
        float factor = this.envelope.factorEnabled(this.duration.get(), context.relativeTick + context.transition);

        this.effect.type = ScreenEffect.LETTERBOX;
        this.effect.factor = factor;
        this.effect.color = this.color.get();
        this.effect.value = this.ratio.get();

        ScreenEffect.getEffects(context).add(this.effect);
    }

    @Override
    protected Clip create()
    {
        return new LetterboxClip();
    }
}
