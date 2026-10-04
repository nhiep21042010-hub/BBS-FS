package com.bbsvfx.vfxlights.client.light;

import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.replays.Replay;

import java.util.List;
import java.util.Set;

/**
 * Replay-category membership for actors, shared by every mask/filter pass. Category = the film's
 * replay category string (BBS "groups"); a lamp with an empty group set touches everything.
 */
public final class ActorCategories
{
    private ActorCategories()
    {
    }

    /** The replay with this id in the controller's film, or null. (BBS 2.7 keys replays by id, not index.) */
    public static Replay replayById(BaseFilmController controller, String id)
    {
        if (controller == null || controller.film == null || id == null)
        {
            return null;
        }

        for (Replay replay : controller.film.replays.getList())
        {
            if (id.equals(replay.getId()))
            {
                return replay;
            }
        }

        return null;
    }

    /** The normalized category of the replay with this id, or "". */
    public static String categoryOf(BaseFilmController controller, String id)
    {
        Replay replay = replayById(controller, id);

        return replay == null ? "" : Replay.normalizeCategory(replay.category.get());
    }

    /** The normalized category of the replay at {@code index} in the controller's film, or "". */
    public static String categoryOf(BaseFilmController controller, int index)
    {
        if (controller == null || controller.film == null)
        {
            return "";
        }

        List<Replay> replays = controller.film.replays.getList();

        if (index < 0 || index >= replays.size())
        {
            return "";
        }

        return Replay.normalizeCategory(replays.get(index).category.get());
    }

    /** True when the lamp's group set is empty or contains the category. */
    public static boolean allowed(Set<String> groups, String category)
    {
        return groups.isEmpty() || groups.contains(category);
    }
}
