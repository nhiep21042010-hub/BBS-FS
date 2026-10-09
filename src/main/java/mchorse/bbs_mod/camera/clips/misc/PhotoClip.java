package mchorse.bbs_mod.camera.clips.misc;

import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.data.Placement;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.ArrayList;
import java.util.List;

/**
 * An image laid over the finished frame, like {@link ImageClip}, but every property is a keyframe
 * channel, so the picture can fade, slide, grow, spin and flip over the clip's duration.
 *
 * <p>It draws nothing itself: each frame it works out where the picture is and hands an
 * {@link ImageOverlay} to the same list the image clips use, so the existing image renderer
 * (game and editor preview alike) does the drawing.</p>
 *
 * <p>Channels (an empty channel keeps its default):</p>
 * <ul>
 *     <li>{@code opacity}: 0..1, default 1</li>
 *     <li>{@code x}, {@code y}: offset from the frame centre in frame units (the frame is
 *     1080 units tall), default 0</li>
 *     <li>{@code scale}: size multiplier, default 1</li>
 *     <li>{@code stretch_x}, {@code stretch_y}: extra width / height multiplier, default 1</li>
 *     <li>{@code rotate}: degrees around the picture's centre, default 0</li>
 *     <li>{@code flip}: 0.5 or more mirrors the picture horizontally, default 0</li>
 * </ul>
 */
public class PhotoClip extends CameraClip
{
    public final ValueLink texture = new ValueLink("texture", null);
    public final ValueBoolean smooth = new ValueBoolean("smooth", true);

    public final KeyframeChannel<Double> opacity = new KeyframeChannel<>("opacity", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> x = new KeyframeChannel<>("x", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> y = new KeyframeChannel<>("y", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> scale = new KeyframeChannel<>("scale", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> stretchX = new KeyframeChannel<>("stretch_x", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> stretchY = new KeyframeChannel<>("stretch_y", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> rotate = new KeyframeChannel<>("rotate", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> flip = new KeyframeChannel<>("flip", KeyframeFactories.DOUBLE);

    private final ImageOverlay image = new ImageOverlay();
    private final Placement placement = new Placement();
    private final Transform transform = new Transform();

    public PhotoClip()
    {
        this.add(this.texture);
        this.add(this.smooth);

        for (KeyframeChannel<Double> channel : this.getChannels())
        {
            this.add(channel);
        }
    }

    /** The channels in the order the editor lists them. */
    public List<KeyframeChannel<Double>> getChannels()
    {
        List<KeyframeChannel<Double>> channels = new ArrayList<>();

        channels.add(this.opacity);
        channels.add(this.x);
        channels.add(this.y);
        channels.add(this.scale);
        channels.add(this.stretchX);
        channels.add(this.stretchY);
        channels.add(this.rotate);
        channels.add(this.flip);

        return channels;
    }

    private static double value(KeyframeChannel<Double> channel, float tick, double defaultValue)
    {
        if (channel.isEmpty())
        {
            return defaultValue;
        }

        Double value = channel.interpolate(tick);

        return value == null ? defaultValue : value;
    }

    @Override
    protected void applyClip(ClipContext context, Position position)
    {
        if (this.texture.get() == null)
        {
            return;
        }

        float tick = context.relativeTick + context.transition;
        float factor = this.envelope.factorEnabled(this.duration.get(), tick);

        float opacity = (float) Math.max(0D, Math.min(1D, value(this.opacity, tick, 1D)));
        float scale = (float) value(this.scale, tick, 1D);

        this.placement.windowX = 0.5F;
        this.placement.windowY = 0.5F;
        this.placement.anchorX = 0.5F;
        this.placement.anchorY = 0.5F;
        this.placement.offsetX = (float) value(this.x, tick, 0D);
        this.placement.offsetY = (float) value(this.y, tick, 0D);
        this.placement.scaleX = scale * (float) value(this.stretchX, tick, 1D);
        this.placement.scaleY = scale * (float) value(this.stretchY, tick, 1D);

        /* Rotation and flip go through the overlay's transform. The renderer blends from
         * "no transform" towards it by (1 - factor), so factor 0 means "fully applied". */
        this.transform.translate.set(0F, 0F, 0F);
        this.transform.scale.set(value(this.flip, tick, 0D) >= 0.5D ? -1F : 1F, 1F, 1F);
        this.transform.rotate.set(0F, 0F, (float) Math.toRadians(value(this.rotate, tick, 0D)));

        int color = Colors.setA(Colors.WHITE, opacity * factor);

        this.image.update(this.texture.get(), this.placement, color, false, this.smooth.get());
        this.image.updateTransform(this.transform, 0F);

        ImageClip.getImages(context).add(this.image);
    }

    @Override
    protected Clip create()
    {
        return new PhotoClip();
    }
}
