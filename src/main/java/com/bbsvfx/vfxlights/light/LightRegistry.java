package com.bbsvfx.vfxlights.light;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every light in the frame, gathered in one place before anything is shaded.
 *
 * <p><b>The whole design of this addon rests on one decision: a light does not have to be a form.</b>
 * Authored lamps are collected by walking the form tree, but effects submit their own — an explosion's
 * fireball, a beam's core, burning debris. Those are born and die by the hundred inside a single effect,
 * and nobody is going to place them by hand. So the registry accepts both on equal terms and neither
 * path knows the other exists.</p>
 *
 * <p><b>Frame lifecycle.</b> {@link #beginFrame()} clears, contributors call {@link #submit()} and fill in
 * the returned instance, then a backend reads {@link #getLights()}. Lights are pooled and reused across
 * frames, which is what keeps a few hundred ephemeral sources per frame from turning into GC pressure —
 * the failure mode that made smear stutter on testers' machines.</p>
 *
 * <p><b>Deduplication.</b> BBS renders a form more than once per frame (shadow pass, deferred replays,
 * the picking pass). Without a key, a single lamp would be counted several times and read as too bright.
 * Anything with a non-null {@code key} is accepted once per frame; the first submission wins.</p>
 *
 * <p>Client-side state, single-threaded: everything here runs on the render thread.</p>
 */
public final class LightRegistry
{
    /**
     * Hard ceiling on lights kept for one frame. Well above what any backend will shade — the point is a
     * runaway effect cannot grow the pool without bound, not a quality limit. Backends apply their own,
     * tighter budget via {@link #getLights(double, double, double, int)}.
     */
    public static final int MAX_LIGHTS = 4096;

    private static final List<Light> POOL = new ArrayList<>();
    private static final List<Light> ACTIVE = new ArrayList<>();
    private static final Set<Object> SEEN = new HashSet<>();

    /**
     * Keyed lights kept alive briefly after they were last collected, so a lamp whose FORM leaves the
     * view frustum does not blink out. Collection happens during form rendering (see FormLightCollector),
     * which the renderer frustum-culls — pan the camera so the lamp's source goes off-screen and it stops
     * being submitted, even though its light still falls on what stayed on screen. Without this, a
     * frustum-edge crossing drops the whole light for a frame and the scene flashes dark (worst on packs
     * that temporally accumulate, e.g. IterationRP's TAA). The key is stable across frames by design (see
     * {@link Light#key}), so a re-collected lamp matches its own persisted copy and never doubles up.
     *
     * <p>Only KEYED lights persist. Anonymous ones (effect sparks, submitted without a key) are ephemeral
     * by nature and must die with their frame.</p>
     */
    private static final Map<Object, Light> PERSIST = new LinkedHashMap<>();
    private static final Map<Object, Integer> PERSIST_TTL = new java.util.HashMap<>();
    private static final List<Light> MERGED = new ArrayList<>();

    /**
     * Frames a culled light lingers before it is dropped. Long enough to bridge any real camera pan (a
     * lamp off-screen for a couple of seconds still lights the shot), short enough that a genuinely
     * deleted lamp clears quickly. Culling and deletion are indistinguishable from here — both simply
     * stop being collected — so this is the one knob that trades flicker immunity against how long a
     * removed lamp lingers.
     */
    private static final int PERSIST_GRACE = 120;

    private static int poolCursor;
    private static int dropped;

    /**
     * Change stamp for the light set: bumped by anything that alters ACTIVE or PERSIST membership.
     * {@link #merged()} is O(active × persisted) because of the same-position dedup, and it used to
     * run ~8 times per frame (both compositors, the volumetric pass twice, the masks, bounce, water,
     * dispersion) — recomputing identical output each time. The stamp turns all but the first call
     * per state into a cache hit.
     */
    private static int mutation;
    private static int mergedStamp = -1;
    private static List<Light> mergedCache = ACTIVE;

    /**
     * Frames to skip refreshing {@link #PERSIST} from {@link #ACTIVE} after {@link #clearAllPersisted()}.
     * The editor form can render for a couple of frames after the screen closes; skipping the refresh
     * for a short window prevents that stale editor light from being re-persisted and merged with the
     * real world lights during the transition.
     */
    private static int clearPendingFrames;

    /**
     * Kill-cooldown after a block break. The broken block's forms keep rendering for a few frames
     * (stale chunk-section contents), and every such frame re-collects and re-persists the just-killed
     * lamp — the "blinked off, came back, faded over the grace window" bug. While the cooldown runs,
     * the collector drops submissions stamped with this block.
     */
    private static net.minecraft.util.math.BlockPos killedBlock;
    private static int killedFrames;

    private static final boolean DEBUG = System.getProperty("vfxlights.persist.debug") != null;

    private LightRegistry()
    {
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("vfxlights");

    private static void log(String message)
    {
        LOG.info(message);
    }

    private static String keyName(Object key)
    {
        if (key == null)
        {
            return "null";
        }

        /* Value-keyed lamps print by VALUE (the logical lamp), instance keys by identity —
         * anything else is unreadable in the churn logs. */
        if (key instanceof com.bbsvfx.vfxlights.light.LampKey)
        {
            return key + " (" + key.hashCode() + ")";
        }

        return key.getClass().getSimpleName() + "@" + System.identityHashCode(key);
    }

    /** Drop last frame's lights and start gathering. Call once per frame, before anything renders. */
    public static void beginFrame()
    {
        if (killedFrames > 0 && --killedFrames == 0)
        {
            killedBlock = null;
        }

        if (DEBUG)
        {
            log("beginFrame start: persist=" + PERSIST.size() + ", active(prev)=" + ACTIVE.size());
        }

        /* Age the persisted lights and drop the expired. ACTIVE still holds the frame that just ended,
         * fully filled, so it is the moment to refresh each keyed light's snapshot and reset its grace.
         * Order matters: age FIRST (using the previous TTLs), then refresh what was collected — a lamp
         * seen every frame therefore nets a steady grace, a culled one counts down from its last sighting. */
        for (Iterator<Map.Entry<Object, Integer>> it = PERSIST_TTL.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<Object, Integer> entry = it.next();
            int ttl = entry.getValue() - 1;

            if (ttl <= 0)
            {
                if (DEBUG)
                {
                    log("persist expired: " + keyName(entry.getKey()));
                }

                PERSIST.remove(entry.getKey());
                it.remove();
            }
            else
            {
                entry.setValue(ttl);
            }
        }

        if (clearPendingFrames > 0)
        {
            if (DEBUG)
            {
                log("beginFrame: skipping persist refresh after clearAllPersisted (active=" + ACTIVE.size() + ", remaining=" + clearPendingFrames + ")");
            }

            clearPendingFrames--;
        }
        else
        {
            /* Key adoption, before the refresh: a lamp submitted with a VOLATILE key (stub wrapper
             * recreated per pass, fresh form instance — BBS recreates forms on edits, pauses and
             * scrubs) but sitting where a persisted lamp of the same type already lives IS that
             * lamp — it inherits the stable key instead of orphaning its shadow state into a
             * full rebake and its old entry into the ghost churn (the pause/scrub/drag flicker,
             * proven by the persist log: 25 keys for 4 lamps). Position is the adoption proof:
             * a lamp that actually MOVED (a real drag past the window) legitimately rebakes. */
            for (int i = 0; i < ACTIVE.size(); i++)
            {
                Light light = ACTIVE.get(i);

                if (light.key == null
                    || (light.key instanceof com.bbsvfx.vfxlights.light.LampKey lampKey
                        && lampKey.stableHost()))
                {
                    continue;
                }

                java.util.Iterator<java.util.Map.Entry<Object, Light>> adoptIt =
                    PERSIST.entrySet().iterator();

                while (adoptIt.hasNext())
                {
                    java.util.Map.Entry<Object, Light> entry = adoptIt.next();
                    Light persisted = entry.getValue();

                    if (entry.getKey().equals(light.key)
                        || (entry.getKey() instanceof com.bbsvfx.vfxlights.light.LampKey lampKey
                            && lampKey.stableHost())
                        || persisted.type != light.type
                        || Math.abs(persisted.x - light.x) >= 0.5
                        || Math.abs(persisted.y - light.y) >= 0.5
                        || Math.abs(persisted.z - light.z) >= 0.5)
                    {
                        continue;
                    }

                    Object adopted = entry.getKey();

                    PERSIST_TTL.remove(adopted);
                    adoptIt.remove();
                    light.key = adopted;

                    if (DEBUG)
                    {
                        log("persist key adopted: " + keyName(adopted));
                    }

                    break;
                }
            }

            for (int i = 0; i < ACTIVE.size(); i++)
            {
                Light light = ACTIVE.get(i);

                if (light.key == null)
                {
                    continue;
                }

                Light copy = PERSIST.get(light.key);
                boolean created = copy == null;

                if (created)
                {
                    copy = new Light();
                    PERSIST.put(light.key, copy);
                }

                copy.copyFrom(light);
                PERSIST_TTL.put(light.key, PERSIST_GRACE);

                if (DEBUG)
                {
                    log("persist " + (created ? "created" : "refreshed") + ": " + keyName(light.key)
                        + " pos=[" + light.x + ", " + light.y + ", " + light.z + "]"
                        + " (active=" + ACTIVE.size() + ", persist=" + PERSIST.size() + ")");
                }
            }

            /* Prune the recreated forms' ghosts: BBS recreates a light's form on edits (new
             * identity), and the old instances linger here — the same-position dedup in merged()
             * hides them while the lamp is static, but during a drag the active lamp moves on and
             * the ghost at its old position is no longer deduped: TWO lights shine ("ярче при
             * движении", normal again on stop once the positions re-converge). An active keyed
             * light at the same spot (different key) IS the proof the ghost is dead weight — a
             * genuinely culled lamp has no active twin and is left alone.
             *
             * The stronger, position-free proof: the active lamp carries the same SERIALISED form
             * id under a different key, and the candidate was not collected this frame — that is
             * the old instance of a form BBS recreated, full stop (the volatile-host LampKeys the
             * adoption above could not prove by position once the drag passed ±0.5). The ±0.5 rule
             * alone let the ghost trail a whole drag at its start position for the grace window
             * ("мерцает при драге": two lights, merged()'s 0.001 position dedup toggling). */
            for (int i = 0; i < ACTIVE.size(); i++)
            {
                Light light = ACTIVE.get(i);

                if (light.key == null)
                {
                    continue;
                }

                Iterator<Map.Entry<Object, Light>> it = PERSIST.entrySet().iterator();

                while (it.hasNext())
                {
                    Map.Entry<Object, Light> entry = it.next();
                    Light persisted = entry.getValue();

                    /* Value semantics, not reference: LampKey instances are re-created every
                     * frame, and a reference test read the lamp's OWN persist as a ghost and
                     * pruned it per frame (the 7000-prune churn). equals() is the same test the
                     * instance keys passed by identity. */
                    if (entry.getKey().equals(light.key))
                    {
                        continue;
                    }

                    boolean sameSpot = Math.abs(persisted.x - light.x) < 0.5
                        && Math.abs(persisted.y - light.y) < 0.5
                        && Math.abs(persisted.z - light.z) < 0.5;
                    boolean recreated = light.formId != null && !light.formId.isEmpty()
                        && light.formId.equals(persisted.formId)
                        && !SEEN.contains(entry.getKey());

                    if (sameSpot || recreated)
                    {
                        if (DEBUG)
                        {
                            log("persist ghost pruned: " + keyName(entry.getKey()));
                        }

                        PERSIST_TTL.remove(entry.getKey());
                        it.remove();
                        mutation++;
                    }
                }
            }
        }

        ACTIVE.clear();
        SEEN.clear();
        poolCursor = 0;
        dropped = 0;
        mutation++;

        if (DEBUG)
        {
            log("beginFrame end: persist=" + PERSIST.size() + ", pool=" + POOL.size());
        }
    }

    /**
     * Take a pooled light to fill in. It is already registered for this frame, so a caller that abandons
     * it halfway must call {@link #discard(Light)} rather than simply dropping the reference.
     *
     * @return a light reset to defaults, or {@code null} once the frame is full
     */
    public static Light submit()
    {
        if (ACTIVE.size() >= MAX_LIGHTS)
        {
            dropped++;

            return null;
        }

        Light light;

        if (poolCursor < POOL.size())
        {
            light = POOL.get(poolCursor).reset();
        }
        else
        {
            light = new Light();
            POOL.add(light);
        }

        poolCursor++;
        ACTIVE.add(light);
        mutation++;

        return light;
    }

    /**
     * Submit a light identified by {@code key}, or {@code null} if one with that key is already in this
     * frame. Use for anything rendered more than once per frame — that is, every authored form.
     */
    public static Light submit(Object key)
    {
        if (key != null && !SEEN.add(key))
        {
            if (DEBUG)
            {
                log("submit duplicate skipped: " + keyName(key));
            }

            return null;
        }

        Light light = submit();

        if (light != null)
        {
            light.key = key;

            if (DEBUG)
            {
                log("submit: " + keyName(key) + " pos=[" + light.x + ", " + light.y + ", " + light.z + "]");
            }
        }

        return light;
    }

    /** Give back a light taken from {@link #submit()} without filling it in. */
    public static void discard(Light light)
    {
        if (light == null)
        {
            return;
        }

        int last = ACTIVE.size() - 1;

        /* Only the most recent one can be withdrawn cheaply; anything else would shift the list. In
         * practice a caller discards what it just took, so this is the only case that occurs. */
        if (last >= 0 && ACTIVE.get(last) == light)
        {
            ACTIVE.remove(last);
            poolCursor--;
            mutation++;

            if (light.key != null)
            {
                SEEN.remove(light.key);
            }
        }
    }

    /** Every light gathered this frame, in submission order. Do not retain past the frame. */
    public static List<Light> getLights()
    {
        return merged();
    }

    /**
     * ACTIVE plus any persisted light whose form was not collected this frame — the lamps currently
     * frustum-culled but still within their grace window. A light collected this frame is in {@link #SEEN}
     * and its persisted copy is skipped, so nothing is counted twice.
     */
    private static List<Light> merged()
    {
        if (mergedStamp == mutation)
        {
            return mergedCache;
        }

        mergedStamp = mutation;

        if (PERSIST.isEmpty())
        {
            mergedCache = ACTIVE;

            return ACTIVE;
        }

        mergedCache = MERGED;
        MERGED.clear();
        MERGED.addAll(ACTIVE);

        int added = 0;

        for (Map.Entry<Object, Light> entry : PERSIST.entrySet())
        {
            if (SEEN.contains(entry.getKey()))
            {
                continue;
            }

            Light persisted = entry.getValue();

            /* When the editor closes, BBS can recreate the light's form (new identity) while the old
             * form instance still renders for a few frames. Both end up in the registry keyed by their
             * own form object, so the normal key-based deduplication misses them. They share the same
             * world position, though, so skipping persisted duplicates by position prevents the bright
             * double-light flash without breaking flicker immunity for genuinely frustum-culled lamps. */
            if (hasActiveAtSamePosition(persisted))
            {
                if (DEBUG)
                {
                    log("merged persisted skipped (same position): " + keyName(entry.getKey())
                        + " pos=[" + persisted.x + ", " + persisted.y + ", " + persisted.z + "]");
                }

                continue;
            }

            MERGED.add(persisted);
            added++;

            if (DEBUG)
            {
                log("merged persisted: " + keyName(entry.getKey())
                    + " pos=[" + persisted.x + ", " + persisted.y + ", " + persisted.z + "]");
            }
        }

        if (DEBUG && added > 0)
        {
            log("merged totals: active=" + ACTIVE.size() + ", persisted-added=" + added + ", total=" + MERGED.size());
        }

        return MERGED;
    }

    private static boolean hasActiveAtSamePosition(Light light)
    {
        double epsilon = 0.001D;

        for (int i = 0; i < ACTIVE.size(); i++)
        {
            Light other = ACTIVE.get(i);

            if (Math.abs(light.x - other.x) < epsilon
                && Math.abs(light.y - other.y) < epsilon
                && Math.abs(light.z - other.z) < epsilon)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * The {@code limit} most important lights for a camera at the given position, strongest first.
     *
     * <p>Ranking by intensity over distance squared — the light's own falloff — so when a backend cannot
     * take them all, what it loses is what contributed least to the image anyway. Ambient never ranks
     * last: it has no falloff and dropping it changes the whole look of the shot.</p>
     */
    public static List<Light> getLights(double camX, double camY, double camZ, int limit)
    {
        List<Light> all = merged();

        if (all.size() <= limit)
        {
            return all;
        }

        List<Light> sorted = new ArrayList<>(all);

        sorted.sort((a, b) -> Float.compare(b.importance(camX, camY, camZ), a.importance(camX, camY, camZ)));

        return sorted.subList(0, limit);
    }

    /** How many submissions were refused this frame because the ceiling was hit. */
    public static int getDropped()
    {
        return dropped;
    }

    public static int getCount()
    {
        return ACTIVE.size();
    }

    /** The persisted snapshot for {@code key}, or null. */
    public static Light getPersisted(Object key)
    {
        return PERSIST.get(key);
    }

    /** The persisted light collected from a form with this id — the editor live-refresh fallback:
     * BBS recreates the form instance on edits, so the identity-keyed lookup misses, while the id
     * survives. Null when nothing matches (or the id is shared and therefore ambiguous). */
    public static Light findPersistedByFormId(String formId)
    {
        if (formId == null || formId.isEmpty())
        {
            return null;
        }

        Light found = null;

        for (Light light : PERSIST.values())
        {
            if (formId.equals(light.formId))
            {
                if (found != null)
                {
                    return null;
                }

                found = light;
            }
        }

        return found;
    }

    /** The ACTIVE-light twin of {@link #findPersistedByFormId(String)} — same rule, this frame's
     * collection: the editor's tweaks overlay onto it when the world keeps rendering the lamp. */
    public static Light findActiveByFormId(String formId)
    {
        if (formId == null || formId.isEmpty())
        {
            return null;
        }

        Light found = null;

        for (Light light : ACTIVE)
        {
            if (formId.equals(light.formId))
            {
                if (found != null)
                {
                    return null;
                }

                found = light;
            }
        }

        return found;
    }

    /**
     * Refresh a persisted light's grace without touching its snapshot. Called from editor/preview
     * renders that can't place a light (dummy matrices) but prove the form still exists — the
     * world position collected before the edit session must not expire mid-edit. A deleted light
     * (no persisted entry) stays dead, so this cannot resurrect anything.
     */
    public static void touch(Object key)
    {
        if (PERSIST_TTL.containsKey(key))
        {
            PERSIST_TTL.put(key, PERSIST_GRACE);
        }
    }

    /**
     * Refresh EVERY persisted light's grace. Called from editor/preview renders: a rejected
     * collection proves an editor session is open, and the form being previewed is not reliably
     * the same instance the world collected (the editor's binding is its own business), so the
     * per-key {@link #touch(Object)} misses and the lamp expired ~2 s into every edit session —
     * "свет пропадает из редактора". A lamp deleted mid-session still clears on the editor's
     * close ({@link #clearAllPersisted()}), so holding the whole set costs nothing.
     */
    public static void touchAll()
    {
        for (Map.Entry<Object, Integer> entry : PERSIST_TTL.entrySet())
        {
            entry.setValue(PERSIST_GRACE);
        }
    }

    /**
     * Clear a keyed light everywhere. Called when the light's source is removed (e.g. a form is
     * deleted or the editor preview closes) so it does not linger for the rest of its grace window.
     * ACTIVE and MERGED are purged too: {@link #beginFrame()} re-persists keyed lights from ACTIVE,
     * which would otherwise resurrect the removed light with a fresh grace.
     */
    public static void clearPersisted(Object key)
    {
        if (DEBUG)
        {
            log("clearPersisted: " + keyName(key));
        }

        PERSIST.remove(key);
        PERSIST_TTL.remove(key);
        ACTIVE.removeIf(light -> light.key == key);
        MERGED.removeIf(light -> light.key == key);
        mutation++;
    }

    /**
     * Clear every light collected from the form with this serialised vfx id — the "Enabled OFF in
     * the replay/form panel" path: a disabled source simply stops rendering, and without this the
     * lamp kept burning on its grace window (or indefinitely while an editor session renewed every
     * grace via {@link #touchAll()}). Matches by id, not key: the panel-side caller knows only the
     * form, and BBS may have recreated the instance since collection.
     */
    public static void clearByFormId(String formId)
    {
        if (formId == null || formId.isEmpty())
        {
            return;
        }

        java.util.List<Object> keys = new java.util.ArrayList<>();

        for (Map.Entry<Object, Light> entry : PERSIST.entrySet())
        {
            if (formId.equals(entry.getValue().formId))
            {
                keys.add(entry.getKey());
            }
        }

        if (DEBUG)
        {
            log("clearByFormId: " + formId + " -> " + keys.size() + " persisted, active matched too");
        }

        /* ★Sweep-diagnostic twin (vfxlights.sweep.debug): the registry's own DEBUG is per-frame
           spam; this one prints only on an explicit kill call. */
        if (System.getProperty("vfxlights.sweep.debug") != null)
        {
            log("clearByFormId: " + formId + " -> " + keys.size() + " persisted keys");
        }

        for (Object key : keys)
        {
            clearPersisted(key);
        }

        /* Never-persisted matches (first frame after collection) sit only in ACTIVE. */
        ACTIVE.removeIf(light -> formId.equals(light.formId));
        MERGED.removeIf(light -> formId.equals(light.formId));
        mutation++;
    }

    /** True while {@code pos}'s lights are in the post-break kill-cooldown — do not re-collect them. */
    public static boolean isKilled(net.minecraft.util.math.BlockPos pos)
    {
        return killedFrames > 0 && pos.equals(killedBlock);
    }

    /**
     * Clear lights whose form lives in the given block (stamped at collection time from the
     * model-block render tracker). Called on a block break so the block's lamps die immediately
     * instead of lingering for their grace window — exact association, no radius to get wrong.
     * ACTIVE and MERGED are purged for the same re-persist reason as {@link #clearPersisted}.
     */
    public static void clearPersistedAtBlock(net.minecraft.util.math.BlockPos pos)
    {
        for (Iterator<Map.Entry<Object, Light>> it = PERSIST.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<Object, Light> entry = it.next();

            if (pos.equals(entry.getValue().sourceBlock))
            {
                if (DEBUG)
                {
                    log("clearPersistedAtBlock: " + keyName(entry.getKey()));
                }

                PERSIST_TTL.remove(entry.getKey());
                it.remove();
            }
        }

        ACTIVE.removeIf(light -> pos.equals(light.sourceBlock));
        MERGED.removeIf(light -> pos.equals(light.sourceBlock));
        mutation++;

        killedBlock = pos;
        killedFrames = 10;
    }

    /** Clear every persisted light. Useful on world change or when the editor session ends. */
    public static void clearAllPersisted()
    {
        if (DEBUG)
        {
            log("clearAllPersisted: removed " + PERSIST.size() + " persisted lights");
        }

        PERSIST.clear();
        PERSIST_TTL.clear();
        clearPendingFrames = 3;
        mutation++;
    }
}
