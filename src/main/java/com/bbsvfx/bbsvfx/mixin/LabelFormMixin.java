package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.forms.forms.LabelForm;
import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.colors.Color;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.forms.ILabelExtras;

/**
 * Adds the addon label-overhaul properties to BBS's {@link LabelForm}. Each is a regular form
 * {@code Value*} child added in the constructor, so it serialises and is keyframable in the replay
 * editor automatically (same approach as {@code CurveForm}/{@code DestructionBoxForm}).
 *
 * <p>Stage 0/1: {@code tracking} (letter spacing). More properties (stroke, blend mode, font,
 * projection) are added here as the overhaul progresses.
 */
@Mixin(value = LabelForm.class, remap = false)
public abstract class LabelFormMixin implements ILabelExtras
{
    @Unique
    private ValueFloat bbsvfx$tracking;

    @Unique
    private ValueFloat bbsvfx$strokeWidth;

    @Unique
    private ValueColor bbsvfx$strokeColor;

    @Unique
    private ValueBoolean bbsvfx$strokeOnly;

    @Unique
    private ValueInt bbsvfx$blendMode;

    @Unique
    private ValueString bbsvfx$font;

    @Unique
    private ValueFloat bbsvfx$fontSize;

    @Unique
    private ValueBoolean bbsvfx$projection;

    @Unique
    private ValueFloat bbsvfx$projRange;

    @Unique
    private ValueFloat bbsvfx$projFade;

    @Unique
    private ValueBoolean bbsvfx$gradient;

    @Unique
    private ValueColor bbsvfx$gradientStart;

    @Unique
    private ValueColor bbsvfx$gradientEnd;

    @Unique
    private ValueFloat bbsvfx$gradientAngle;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$addProperties(CallbackInfo ci)
    {
        this.bbsvfx$tracking = new ValueFloat("tracking", 0F);
        this.bbsvfx$strokeWidth = new ValueFloat("stroke_width", 0F);
        this.bbsvfx$strokeColor = new ValueColor("stroke_color", new Color(0F, 0F, 0F, 1F));
        this.bbsvfx$strokeOnly = new ValueBoolean("stroke_only", false);
        this.bbsvfx$blendMode = new ValueInt("blend_mode", 0);
        this.bbsvfx$font = new ValueString("font", "");
        this.bbsvfx$fontSize = new ValueFloat("font_size", 8F);
        this.bbsvfx$projection = new ValueBoolean("projection", false);
        this.bbsvfx$projRange = new ValueFloat("proj_range", 32F);
        this.bbsvfx$projFade = new ValueFloat("proj_fade", 0F);
        this.bbsvfx$gradient = new ValueBoolean("gradient", false);
        this.bbsvfx$gradientStart = new ValueColor("gradient_start", Color.white());
        this.bbsvfx$gradientEnd = new ValueColor("gradient_end", Color.white());
        this.bbsvfx$gradientAngle = new ValueFloat("gradient_angle", 0F);

        ValueGroup self = (ValueGroup) (Object) this;

        self.add(this.bbsvfx$tracking);
        self.add(this.bbsvfx$strokeWidth);
        self.add(this.bbsvfx$strokeColor);
        self.add(this.bbsvfx$strokeOnly);
        self.add(this.bbsvfx$blendMode);
        self.add(this.bbsvfx$font);
        self.add(this.bbsvfx$fontSize);
        self.add(this.bbsvfx$projection);
        self.add(this.bbsvfx$projRange);
        self.add(this.bbsvfx$projFade);
        self.add(this.bbsvfx$gradient);
        self.add(this.bbsvfx$gradientStart);
        self.add(this.bbsvfx$gradientEnd);
        self.add(this.bbsvfx$gradientAngle);
    }

    @Override
    public ValueFloat bbsvfx$tracking()
    {
        return this.bbsvfx$tracking;
    }

    @Override
    public ValueFloat bbsvfx$strokeWidth()
    {
        return this.bbsvfx$strokeWidth;
    }

    @Override
    public ValueColor bbsvfx$strokeColor()
    {
        return this.bbsvfx$strokeColor;
    }

    @Override
    public ValueBoolean bbsvfx$strokeOnly()
    {
        return this.bbsvfx$strokeOnly;
    }

    @Override
    public ValueInt bbsvfx$blendMode()
    {
        return this.bbsvfx$blendMode;
    }

    @Override
    public ValueString bbsvfx$font()
    {
        return this.bbsvfx$font;
    }

    @Override
    public ValueFloat bbsvfx$fontSize()
    {
        return this.bbsvfx$fontSize;
    }

    @Override
    public ValueBoolean bbsvfx$projection()
    {
        return this.bbsvfx$projection;
    }

    @Override
    public ValueFloat bbsvfx$projRange()
    {
        return this.bbsvfx$projRange;
    }

    @Override
    public ValueFloat bbsvfx$projFade()
    {
        return this.bbsvfx$projFade;
    }

    @Override
    public ValueBoolean bbsvfx$gradient()
    {
        return this.bbsvfx$gradient;
    }

    @Override
    public ValueColor bbsvfx$gradientStart()
    {
        return this.bbsvfx$gradientStart;
    }

    @Override
    public ValueColor bbsvfx$gradientEnd()
    {
        return this.bbsvfx$gradientEnd;
    }

    @Override
    public ValueFloat bbsvfx$gradientAngle()
    {
        return this.bbsvfx$gradientAngle;
    }
}
