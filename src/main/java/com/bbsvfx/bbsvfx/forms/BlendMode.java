package com.bbsvfx.bbsvfx.forms;

import java.util.ArrayList;
import java.util.List;

/**
 * Label compositing blend modes. Stored on the form as an int index ({@code blend_mode}); the order is
 * stable, so new modes must only be appended.
 *
 * <p>{@link #fixedFunction} modes are expressible with {@code glBlendFunc}/{@code glBlendEquation} alone.
 * The rest need a shader that reads the destination framebuffer (added in a later stage); until then the
 * renderer treats them as {@link #NORMAL}.
 */
public enum BlendMode
{
    NORMAL("Normal", true),
    ADD("Add", true),
    SCREEN("Screen", true),
    MULTIPLY("Multiply", true),
    DARKEN("Darken", true),
    LIGHTEN("Lighten", true),
    OVERLAY("Overlay", false),
    SOFT_LIGHT("Soft Light", false),
    COLOR_DODGE("Color Dodge", false),
    DIFFERENCE("Difference", false),
    EXCLUSION("Exclusion", false);

    public final String display;
    public final boolean fixedFunction;

    public static final List<String> DISPLAY_NAMES = new ArrayList<>();

    static
    {
        for (BlendMode mode : values())
        {
            DISPLAY_NAMES.add(mode.display);
        }
    }

    BlendMode(String display, boolean fixedFunction)
    {
        this.display = display;
        this.fixedFunction = fixedFunction;
    }

    public static BlendMode byIndex(int index)
    {
        BlendMode[] values = values();

        if (index < 0 || index >= values.length)
        {
            return NORMAL;
        }

        return values[index];
    }
}
