package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;

/**
 * Smear-frame settings attached to every {@link mchorse.bbs_mod.forms.forms.Form} (via the
 * {@code SmearFormMixin} duck-typed onto {@link ISmearHolder}). When {@link #enabled}, the form's
 * render is multiplied/stretched along its motion so fast movement reads as a smear:
 *
 * <ul>
 *   <li><b>echo</b> ({@link #stretch} = false): several fading copies trailing the motion;</li>
 *   <li><b>stretch</b> ({@link #stretch} = true): the model stretched along the motion direction.</li>
 * </ul>
 *
 * <p>The motion is taken automatically from the actor's per-frame movement, or from a keyframable
 * manual vector when {@link #manual} is on (for staged smears).</p>
 */
public class SmearProperties extends ValueGroup
{
    public final ValueBoolean enabled = new ValueBoolean("enabled", false);

    /** false = echo (trailing copies), true = stretch (elongate the model). */
    public final ValueBoolean stretch = new ValueBoolean("stretch", false);

    /** false = derive the smear from the actor's motion, true = use the manual vector below. */
    public final ValueBoolean manual = new ValueBoolean("manual", false);

    /** Echo copies (ignored in stretch mode). */
    public final ValueInt count = new ValueInt("count", 4);

    /** Per-copy opacity falloff for echo (0 = copies vanish fast, 1 = stay solid). */
    public final ValueFloat falloff = new ValueFloat("falloff", 0.6F, 0F, 1F);

    /** Overall smear strength: echo spread / stretch length as a multiple of the motion. */
    public final ValueFloat amount = new ValueFloat("amount", 1F, 0F, Float.POSITIVE_INFINITY);

    /** Below this per-frame speed nothing is smeared (auto mode only). */
    public final ValueFloat minSpeed = new ValueFloat("min_speed", 0.03F, 0F, Float.POSITIVE_INFINITY);

    /** Manual smear vector (world units / frame) used when {@link #manual} is on. */
    public final ValueFloat manualX = new ValueFloat("manual_x", 0F);
    public final ValueFloat manualY = new ValueFloat("manual_y", 0F);
    public final ValueFloat manualZ = new ValueFloat("manual_z", 0F);

    public SmearProperties(String id)
    {
        super(id);

        this.add(this.enabled);
        this.add(this.stretch);
        this.add(this.manual);
        this.add(this.count);
        this.add(this.falloff);
        this.add(this.amount);
        this.add(this.minSpeed);
        this.add(this.manualX);
        this.add(this.manualY);
        this.add(this.manualZ);
    }
}
