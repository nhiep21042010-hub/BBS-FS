package com.bbsvfx.vfxlights.client.light;

import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import com.bbsvfx.vfxlights.forms.LightForm;
import com.bbsvfx.vfxlights.light.LightRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a film's lamps off when the film ends.
 *
 * <p>A collected light lingers in the registry for its grace window, because collection stops both when
 * a lamp is frustum-culled and when it is deleted, and from inside the registry those look identical.
 * A film ending is the case where they are NOT identical: the controller is gone from BBS, so every
 * lamp it carried is deleted, not merely off-screen. Waiting out the grace leaves the set lit for two
 * seconds after the last frame — long enough to read as "the film ended and the light stayed".</p>
 *
 * <p>Departure is read from the same controller list every actor pass shares, so it covers a film
 * played in the world (right Ctrl), one stopped by hand, the outside recorder, and the editor's own
 * controller — including the one BBS rebuilds on edits, whose forms are new objects and whose old
 * lamps would otherwise sit out their grace as ghosts.</p>
 *
 * <p>Must run BEFORE {@link LightRegistry#beginFrame()}: that re-persists keyed lights from the frame
 * that just ended, and {@link LightRegistry#clearPersisted(Object)} purges the active list precisely so
 * the refresh cannot resurrect what was just killed.</p>
 */
public final class FilmLightTracker
{
    /** Guard against a malformed form graph — a tree in practice, and nothing needs this depth. */
    private static final int MAX_DEPTH = 16;

    private static final List<BaseFilmController> LAST = new ArrayList<>();

    private FilmLightTracker()
    {
    }

    /** Once per frame, before the registry starts gathering. */
    public static void beginFrame()
    {
        List<BaseFilmController> current =
            com.bbsvfx.vfxlights.client.render.CharacterMask.controllers();

        for (int i = 0; i < LAST.size(); i++)
        {
            BaseFilmController gone = LAST.get(i);

            if (!contains(current, gone))
            {
                dropLights(gone);
            }
        }

        LAST.clear();
        LAST.addAll(current);
    }

    /** Identity, not equality: two controllers over the same film are still two films' worth of lamps. */
    private static boolean contains(List<BaseFilmController> controllers, BaseFilmController controller)
    {
        for (int i = 0; i < controllers.size(); i++)
        {
            if (controllers.get(i) == controller)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * BBS leaves the departed controller's entities in place — only its camera context is shut down —
     * so the actors it built are still walkable here, and their form objects are the very keys the
     * registry persisted them under.
     */
    private static void dropLights(BaseFilmController controller)
    {
        try
        {
            for (IEntity actor : controller.getEntities().values())
            {
                if (actor != null)
                {
                    dropForm(actor.getForm(), 0);
                }
            }
        }
        catch (Throwable ignored)
        {
            /* A film that tears itself down oddly costs its lamps their early exit, not the frame —
             * they still expire on the grace window. */
        }
    }

    private static void dropForm(Form form, int depth)
    {
        if (form == null || depth > MAX_DEPTH)
        {
            return;
        }

        if (form instanceof LightForm)
        {
            LightRegistry.clearPersisted(form);
        }

        /* A lamp is usually a body part of the actor's model — the bone attachment that makes torches
         * and eye glows work — so the whole tree has to be walked, not just the root form. */
        for (BodyPart part : form.parts.getList())
        {
            dropForm(part.getForm(), depth + 1);
        }
    }
}
