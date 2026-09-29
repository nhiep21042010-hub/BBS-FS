package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropertiesPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.utils.UI;
import com.bbsvfx.bbsvfx.client.BbsVfxUI;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.forms.CurveForm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Adds a "Follow curve" section to the per-actor Replay settings panel: an enable toggle, a picker
 * that cycles through the curve actors in the scene (only replays whose form is a {@link CurveForm}),
 * and a tangent-align toggle. Drives the config stored on the {@link Replay} by {@code ReplayFollowMixin};
 * the travel itself is animated via the {@code follow_offset} form property in the replay editor.
 */
@Mixin(UIReplayPropertiesPanel.class)
public abstract class UIReplayPropertiesPanelMixin
{
    @Shadow public UIElement properties;
    @Shadow @Final private UIFilmPanel filmPanel;

    @Shadow private void edit(Consumer<Replay> consumer)
    {
    }

    @Unique private UIToggle bbsvfx$followEnabled;
    @Unique private UIButton bbsvfx$followCurve;
    @Unique private UIToggle bbsvfx$followAlign;
    @Unique private int bbsvfx$target = -1;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$addFollowSection(UIFilmPanel filmPanel, CallbackInfo ci)
    {
        this.bbsvfx$followEnabled = new UIToggle(IKey.constant("Enabled"),
            (b) -> this.edit((replay) -> this.bbsvfx$setBool(replay, "xavin_follow_enabled", b.getValue())));
        this.bbsvfx$followCurve = new UIButton(IKey.constant("(no curve)"), (b) -> this.bbsvfx$cycleCurve());
        this.bbsvfx$followCurve.tooltip(IKey.constant("Curve actor to follow (cycles through curve actors)"));
        this.bbsvfx$followAlign = new UIToggle(IKey.constant("Align to curve"),
            (b) -> this.edit((replay) -> this.bbsvfx$setBool(replay, "xavin_follow_align", b.getValue())));

        this.properties.add(BbsVfxUI.section(IKey.constant("Follow curve"),
            this.bbsvfx$followEnabled, this.bbsvfx$followCurve, this.bbsvfx$followAlign));
    }

    @Inject(method = "setReplay", at = @At("TAIL"))
    private void bbsvfx$syncFollowSection(Replay replay, CallbackInfo ci)
    {
        if (replay == null)
        {
            return;
        }

        if (replay.get("xavin_follow_enabled") instanceof ValueBoolean enabled)
        {
            this.bbsvfx$followEnabled.setValue(enabled.get());
        }

        if (replay.get("xavin_follow_align") instanceof ValueBoolean align)
        {
            this.bbsvfx$followAlign.setValue(align.get());
        }

        this.bbsvfx$target = replay.get("xavin_follow_target") instanceof ValueInt target ? target.get() : -1;
        this.bbsvfx$followCurve.label = IKey.constant(this.bbsvfx$curveLabel(this.bbsvfx$target));
    }

    @Unique
    private void bbsvfx$cycleCurve()
    {
        List<Integer> curves = this.bbsvfx$curveActors();

        if (curves.isEmpty())
        {
            return;
        }

        int idx = curves.indexOf(this.bbsvfx$target);
        int next = curves.get((idx + 1) % curves.size());

        this.bbsvfx$target = next;
        this.edit((replay) ->
        {
            if (replay.get("xavin_follow_target") instanceof ValueInt target)
            {
                target.set(next);
            }
        });
        this.bbsvfx$followCurve.label = IKey.constant(this.bbsvfx$curveLabel(next));
    }

    /** Indices (in the film's replay list) of every actor whose form is a curve. */
    @Unique
    private List<Integer> bbsvfx$curveActors()
    {
        List<Integer> out = new ArrayList<>();
        Film film = this.filmPanel.getData();

        if (film != null)
        {
            List<Replay> replays = film.replays.getList();

            for (int i = 0; i < replays.size(); i++)
            {
                if (replays.get(i).form.get() instanceof CurveForm)
                {
                    out.add(i);
                }
            }
        }

        return out;
    }

    @Unique
    private String bbsvfx$curveLabel(int index)
    {
        Film film = this.filmPanel.getData();

        if (film == null || index < 0 || index >= film.replays.getList().size())
        {
            return "(no curve)";
        }

        String label = film.replays.getList().get(index).label.get();

        return (label == null || label.isEmpty() ? "Curve" : label) + " #" + index;
    }

    @Unique
    private void bbsvfx$setBool(Replay replay, String key, boolean value)
    {
        if (replay.get(key) instanceof ValueBoolean v)
        {
            v.set(value);
        }
    }
}
