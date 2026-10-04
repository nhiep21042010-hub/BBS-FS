package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropertiesPanel;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIStringList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.vfxlights.forms.LightForm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Group selection for lights, where the gaffer actually thinks about it: in the REPLAY's own
 * properties panel, shown only when the replay's form is a light. Built eagerly at panel
 * construction (a lazily-added section never got laid out until some other panel forced a resize
 * — the "button appears only after entering the editor" bug).
 */
@Mixin(value = UIReplayPropertiesPanel.class, remap = false)
public abstract class UIReplayPropertiesPanelMixin
{
    @Shadow @org.spongepowered.asm.mixin.Final
    private UIFilmPanel filmPanel;

    @Unique
    private UISection vfx$section;

    @Unique
    private UIStringList vfx$list;

    @Unique
    private LightForm vfx$form;

    @Unique
    private mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle vfx$filterToggle;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vfx$build(UIFilmPanel panel, CallbackInfo ci)
    {
        this.vfx$list = new UIStringList((selected) ->
        {
            if (this.vfx$form != null)
            {
                this.vfx$form.groups.set(new HashSet<>(selected));
                com.bbsvfx.vfxlights.light.LightRegistry.clearPersisted(this.vfx$form);
            }

            /* Apply on every selection change: exactly what dragging the replay does — recreate the
             * controller's entities so collection, masks and uploads refresh against the new
             * selection right now. */
            if (filmPanel != null && filmPanel.getController() != null)
            {
                filmPanel.getController().createEntities();
            }
        });
        this.vfx$list.multi();
        this.vfx$list.h(UIStringList.DEFAULT_HEIGHT);
        this.vfx$list.tooltip(com.bbsvfx.vfxlights.client.LightKeys.GROUPS_TOOLTIP);

        this.vfx$section = new UISection(com.bbsvfx.vfxlights.client.LightKeys.GROUPS);
        this.vfx$section.fields.add(this.vfx$list);

        this.vfx$filterToggle = new mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle(
            com.bbsvfx.vfxlights.client.LightKeys.GROUPS_FILTER, (t) ->
            {
                if (this.vfx$form != null)
                {
                    this.vfx$form.groupFilter.set(t.getValue());
                    com.bbsvfx.vfxlights.light.LightRegistry.clearPersisted(this.vfx$form);

                    if (filmPanel != null && filmPanel.getController() != null)
                    {
                        filmPanel.getController().createEntities();
                    }
                }
            });
        this.vfx$filterToggle.tooltip(com.bbsvfx.vfxlights.client.LightKeys.GROUPS_FILTER_TOOLTIP);
        this.vfx$section.fields.add(this.vfx$filterToggle);
        this.vfx$section.setVisible(false);

        UIReplayPropertiesPanel self = (UIReplayPropertiesPanel) (Object) this;

        self.properties.add(this.vfx$section);
        self.properties.resize();
    }

    @Inject(method = "setReplay", at = @At("RETURN"))
    private void vfx$lightGroups(Replay replay, CallbackInfo ci)
    {
        UIReplayPropertiesPanel self = (UIReplayPropertiesPanel) (Object) this;

        this.vfx$form = replay != null && replay.form.get() instanceof LightForm light ? light : null;

        /* The panel's own constructor calls setReplay(null) before our <init> RETURN inject runs —
         * the section may not exist yet; the state pass runs on the next selection. */
        if (this.vfx$section == null)
        {
            return;
        }

        this.vfx$section.setVisible(this.vfx$form != null);

        if (this.vfx$form != null)
        {
            this.vfx$refillGroups();
        }

        self.properties.resize();
    }

    @Unique
    private void vfx$refillGroups()
    {
        Set<String> categories = new LinkedHashSet<>();

        if (filmPanel.getData() != null)
        {
            for (String name : filmPanel.getData().replayCategories.getPaths())
            {
                vfx$addCategory(categories, name);
            }

            for (Replay replay : filmPanel.getData().replays.getList())
            {
                vfx$addCategory(categories, replay.category.get());
            }
        }

        this.vfx$list.clear();
        this.vfx$list.add(new ArrayList<>(categories));
        /* setCurrent does not fire the callback — the form's value is never clobbered here. */
        this.vfx$list.setCurrent(new ArrayList<>(this.vfx$form.groups.get()));
        this.vfx$filterToggle.setValue(this.vfx$form.groupFilter.get());
    }

    @Unique
    private static void vfx$addCategory(Set<String> categories, String name)
    {
        String normalized = Replay.normalizeCategory(name);

        if (!normalized.isEmpty())
        {
            categories.add(normalized);
        }
    }
}
