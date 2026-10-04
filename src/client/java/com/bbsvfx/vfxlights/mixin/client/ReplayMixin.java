package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.vfxlights.forms.LightForm;
import com.bbsvfx.vfxlights.light.LightRegistry;

/**
 * "Enabled" OFF on the replay panel must kill its lamps NOW. A disabled replay stops rendering its
 * forms, so collection silently stops — and the lamp kept burning on its persist grace, or forever
 * while an editor session renewed every grace via touchAll ("выключил свет, а он не пропадает").
 * The hook sits on the VALUE, not the panel: every edit path fires it. The tree walk covers light
 * forms hanging on bones (BodyPart children), not just the replay's root form.
 */
@Mixin(value = Replay.class, remap = false)
public abstract class ReplayMixin
{
    @Shadow
    public mchorse.bbs_mod.settings.values.core.ValueForm form;

    @Shadow
    public ValueBoolean enabled;

    @Inject(method = "<init>(Ljava/lang/String;)V", at = @At("RETURN"))
    private void vfx$killLightsOnDisable(CallbackInfo ci)
    {
        this.enabled.postCallback((value, flag) ->
        {
            if (!this.enabled.get())
            {
                vfx$clearFormTree(this.form.get());
            }
        });
    }

    @Unique
    private static void vfx$clearFormTree(Form form)
    {
        if (form == null)
        {
            return;
        }

        if (form instanceof LightForm lightForm)
        {
            LightRegistry.clearByFormId(lightForm.ensureVfxId());
        }

        for (BodyPart part : form.parts.getList())
        {
            vfx$clearFormTree(part.getForm());
        }
    }
}
