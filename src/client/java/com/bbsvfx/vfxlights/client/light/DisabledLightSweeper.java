package com.bbsvfx.vfxlights.client.light;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIScreen;
import com.bbsvfx.vfxlights.forms.LightForm;
import com.bbsvfx.vfxlights.light.LightRegistry;

/**
 * Kills lamps whose REPLAY was disabled in the film's replay list. The value-level hooks
 * (ReplayMixin, the form's visible callback) only fire when a UI path notifies — and the replay
 * LIST toggle demonstrably does not (persist log: clearByFormId never fired, the lamp burned on).
 * So this sweeps the ground truth instead: every few frames, walk the open editor film and every
 * playing film, and clear the lights of every disabled replay by their forms' serialised vfx ids.
 * In the editor the world light lives purely on the persisted snapshot (collection is gated out of
 * the editor's render passes), so this is the only moment the light can die at all.
 */
public final class DisabledLightSweeper
{
    /** Frames between sweeps — a handful of frames of lag is invisible next to a mouse click. */
    private static final int INTERVAL = 10;

    private static final boolean DEBUG = System.getProperty("vfxlights.sweep.debug") != null;

    private static int cooldown;

    private DisabledLightSweeper()
    {
    }

    /** Called once per frame from the world-render START handler, after the registry's beginFrame. */
    public static void sweep()
    {
        if (cooldown > 0)
        {
            cooldown--;

            return;
        }

        cooldown = INTERVAL;

        UIFilmPanel panel = editorPanel();
        Film editor = panel == null ? null : panelFilm(panel);
        int cleared = sweepFilm(editor);
        int controllers =
            ((com.bbsvfx.vfxlights.mixin.client.FilmsAccessor) (Object) BBSModClient.getFilms())
                .vfxlights$getControllers().size();

        for (BaseFilmController controller :
            ((com.bbsvfx.vfxlights.mixin.client.FilmsAccessor) (Object) BBSModClient.getFilms())
                .vfxlights$getControllers())
        {
            cleared += sweepFilm(controller.film);
        }

        if (DEBUG)
        {
            StringBuilder map = new StringBuilder();

            if (panel != null && panel.getController() != null
                && panel.getController().editorController != null)
            {
                BaseFilmController ec = panel.getController().editorController;

                for (String key : ec.entities.keySet())
                {
                    Object entity = ec.entities.get(key);

                    map.append('[').append(key).append(' ')
                        .append(entity == null ? "null" : entity.getClass().getSimpleName())
                        .append('@').append(System.identityHashCode(entity)).append(']');
                }
            }

            org.slf4j.LoggerFactory.getLogger("vfxlights").info(
                "sweep: editorFilm=" + (editor == null ? "null" : editor.replays.getList().size())
                    + " controllers=" + controllers + " cleared=" + cleared
                    + " entities=" + map);
        }
    }

    /** The editor's film panel, or null when the film editor is not up. */
    private static UIFilmPanel editorPanel()
    {
        UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

        if (dashboard == null || UIScreen.getCurrentMenu() != dashboard)
        {
            return null;
        }

        return dashboard.getPanel(UIFilmPanel.class);
    }

    /** The film open in the editor's film panel. */
    private static Film panelFilm(UIFilmPanel panel)
    {
        Object data = ((com.bbsvfx.vfxlights.mixin.client.UIDataDashboardPanelAccessor) panel)
            .vfxlights$getData();

        return data instanceof Film film ? film : null;
    }

    private static int sweepFilm(Film film)
    {
        if (film == null)
        {
            return 0;
        }

        int cleared = 0;

        for (Replay replay : film.replays.getList())
        {
            /* Mint the canonical ids EAGERLY, on the film's own form instances, enabled or not:
               render-side copies carry the id with the data from then on, so a later disable
               matches the burning registry light by clearByFormId. */
            mintTree(replay.form.get());

            if (!replay.enabled.get())
            {
                cleared += clearTree(replay.form.get());
            }
        }

        return cleared;
    }

    private static void mintTree(Form form)
    {
        if (form == null)
        {
            return;
        }

        if (form instanceof LightForm lightForm)
        {
            lightForm.ensureVfxId();
        }

        for (BodyPart part : form.parts.getList())
        {
            mintTree(part.getForm());
        }
    }

    private static int clearTree(Form form)
    {
        if (form == null)
        {
            return 0;
        }

        int cleared = 0;

        if (form instanceof LightForm lightForm)
        {
            if (DEBUG)
            {
                org.slf4j.LoggerFactory.getLogger("vfxlights").info(
                    "sweep clearTree: vfxId='" + lightForm.ensureVfxId() + "'");
            }

            LightRegistry.clearByFormId(lightForm.ensureVfxId());

            cleared++;
        }

        for (BodyPart part : form.parts.getList())
        {
            cleared += clearTree(part.getForm());
        }

        return cleared;
    }
}
