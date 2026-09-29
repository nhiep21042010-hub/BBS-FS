package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;

/**
 * Duck interface mixed onto BBS's {@code LabelForm} (via {@code LabelFormMixin}) exposing the extra,
 * addon-added label properties. The stock {@code LabelForm} is minimal (text/billboard/color/anchor/
 * shadow/background); everything for the label overhaul — tracking, outline, blend mode, custom fonts,
 * text projection — is added as new {@code Value*} children so it serialises and animates in the replay
 * editor like any other form property.
 *
 * <p>Grows one accessor per feature as the overhaul progresses.
 */
public interface ILabelExtras
{
    /** Letter spacing, in font pixels, added between characters. 0 = stock spacing. */
    ValueFloat bbsvfx$tracking();

    /** Outline width in font pixels. 0 = no outline. */
    ValueFloat bbsvfx$strokeWidth();

    /** Outline colour. */
    ValueColor bbsvfx$strokeColor();

    /** When true, draw only the outline (skip the fill). */
    ValueBoolean bbsvfx$strokeOnly();

    /** Compositing blend mode, as an index into {@link BlendMode}. 0 = Normal. */
    ValueInt bbsvfx$blendMode();

    /** Custom font name (folder font or system family). Empty / "Default" = vanilla MC font. */
    ValueString bbsvfx$font();

    /** Custom-font size in label pixels (only used when a custom font is selected). */
    ValueFloat bbsvfx$fontSize();

    /** Text Projection: project the text onto world geometry like a slide projector. */
    ValueBoolean bbsvfx$projection();

    /** Projector throw distance (how far in front the projection reaches), in blocks. */
    ValueFloat bbsvfx$projRange();

    /** Soft edge fade of the projection spot, 0..1 (0 = hard edge). */
    ValueFloat bbsvfx$projFade();

    /** Gradient fill: interpolate the text colour from {@link #bbsvfx$gradientStart()} to {@link #bbsvfx$gradientEnd()}. */
    ValueBoolean bbsvfx$gradient();

    /** Gradient start colour. */
    ValueColor bbsvfx$gradientStart();

    /** Gradient end colour. */
    ValueColor bbsvfx$gradientEnd();

    /** Gradient direction in degrees (0 = left→right, 90 = top→bottom). */
    ValueFloat bbsvfx$gradientAngle();
}
