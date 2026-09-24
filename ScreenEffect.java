package mchorse.bbs_mod.camera.clips.screen;

import mchorse.bbs_mod.utils.clips.ClipContext;

import java.util.ArrayList;
import java.util.List;

/**
 * One screen effect (letterbox, vignette, grain) a clip asks the frame overlay renderer to draw
 * on top of the finished frame. Ported in spirit from BBS CML Edition's screen clips, but drawn
 * with plain 2D shapes through the frame overlay list instead of a post-processing pass.
 */
public class ScreenEffect
{
    public static final int LETTERBOX = 0;
    public static final int VIGNETTE = 1;
    public static final int GRAIN = 2;

    public int type;

    /** ARGB colour of the effect (its alpha is the effect's own opacity). */
    public int color;

    /** How much of the effect is on right now (0-1), from the clip's envelope. */
    public float factor;

    /** Letterbox: aspect ratio. Vignette and grain: strength (0-1). */
    public float value;

    /** Vignette: size of the fade as a part of the frame (0-1). Grain: size of one speck. */
    public float extra;

    public static List<ScreenEffect> getEffects(ClipContext context)
    {
        return context.clipData.get("screenEffects", ArrayList::new);
    }
}
