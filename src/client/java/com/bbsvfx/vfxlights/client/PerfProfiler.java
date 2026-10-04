package com.bbsvfx.vfxlights.client;

import org.lwjgl.opengl.GL33C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;

/**
 * Frame profiler answering one question: what does the light actually cost, pass by pass.
 *
 * GPU segments are GL_TIME_ELAPSED queries wrapped around each pass (nesting is impossible — only
 * one elapsed query may be active at a time — so segments are strictly sequential siblings), plus
 * a pair of GL_TIMESTAMP probes bracketing the whole world render: the difference is the total GPU
 * frame, which is the only number that also sees the patched pack's own surface shading (the pack
 * lights inside Iris's internal passes, outside any segment we can wrap). Every section also
 * accumulates its CPU time in nanoseconds.
 *
 * Two modes, both compile-out to a static-final no-op when off:
 *
 * - `-Dvfxlights.perf` — accumulate and LOG every {@link #REPORT_FRAMES} frames: per-section GPU /
 *   CPU averages, total GPU frame, FPS, lamp count, and a fingerprint of the active kill-switches
 *   so numbers from different launches can be compared without guessing which flags were set.
 *
 * - `-Dvfxlights.perf.sweep` (implies the above) — differential attribution: each
 *   {@link #SWEEP_CONFIG_FRAMES}-frame window runs with exactly one pass forcibly skipped
 *   ({@link #skip(Feature)}), cycling ALL_ON → minus one pass each. The difference between ALL_ON
 *   and a NO_* window is that pass's true cost, measured end-to-end instead of inferred. Visuals
 *   flicker while sweeping (passes really do not draw); it is a measurement mode, not a toy.
 *
 * Results are read back non-blocking ({@link GL33C#GL_QUERY_RESULT_AVAILABLE}), so profiling never
 * stalls the pipeline; query ids are recycled through a free list instead of gen/delete churn.
 */
public final class PerfProfiler
{
    private static final Logger LOG = LoggerFactory.getLogger("vfxlights");

    private static final boolean ENABLED =
        Boolean.getBoolean("vfxlights.perf") || Boolean.getBoolean("vfxlights.perf.sweep");
    private static final boolean SWEEP = Boolean.getBoolean("vfxlights.perf.sweep");

    /** Frames between periodic reports in plain (non-sweep) mode. */
    private static final int REPORT_FRAMES = 300;
    /** Frames each sweep configuration holds before rotating to the next. */
    private static final int SWEEP_CONFIG_FRAMES = 120;

    /** A measured pass. gpu=true sections get an elapsed query; all sections get CPU nanos. */
    public enum Section
    {
        SHADOWS(true),
        PACK_UPLOAD(true),
        MASKS(true),
        COMPOSITE(true),
        VOLUMETRIC(true),
        /* Pure-CPU bookkeeping passes: no query, nanos only. */
        REGISTRY(false),
        EFFECTS(false);

        final boolean gpu;

        Section(boolean gpu) { this.gpu = gpu; }
    }

    /** A whole pass the sweep can suppress for a window; call sites must honour {@link #skip}. */
    public enum Feature { SHADOWS, MASKS, COMPOSITE, VOLUMETRIC, PACK_LIGHTS }

    private enum SweepConfig
    {
        ALL_ON(null),
        NO_SHADOWS(Feature.SHADOWS),
        NO_PACK(Feature.PACK_LIGHTS),
        NO_MASKS(Feature.MASKS),
        NO_COMPOSITE(Feature.COMPOSITE),
        NO_VOLUMETRIC(Feature.VOLUMETRIC);

        final Feature off;

        SweepConfig(Feature off) { this.off = off; }
    }

    /* ---- query plumbing ---- */

    private static final int SECTIONS = Section.values().length;

    /** Completed-in-order query ids awaiting readback, per section. */
    @SuppressWarnings("unchecked")
    private static final ArrayDeque<Integer>[] pending = new ArrayDeque[SECTIONS];
    /* Query objects are typed by first use — an id that ran as GL_TIME_ELAPSED can never serve a
     * GL_TIMESTAMP counter (the 1282 spam proved it), so each target recycles from its own pool. */
    private static final ArrayDeque<Integer> freeElapsed = new ArrayDeque<>();
    private static final ArrayDeque<Integer> freeTimestamp = new ArrayDeque<>();
    /** Start timestamp ids of frames whose end probe has not been issued yet (≤1 in practice). */
    private static final ArrayDeque<long[]> framePairs = new ArrayDeque<>();
    private static final int[] activeQuery = new int[SECTIONS];
    private static final long[] cpuStart = new long[SECTIONS];

    /* ---- accumulators ---- */

    private static final long[] gpuNanos = new long[SECTIONS];
    private static final long[] cpuNanos = new long[SECTIONS];
    private static final int[] samples = new int[SECTIONS];
    private static long frameTotalNanos;
    private static int frameTotalSamples;
    private static long fpsSum;
    private static int frames;

    private static final long[] sweepFrameNanos = new long[SweepConfig.values().length];
    private static final long[] sweepFps = new long[SweepConfig.values().length];
    private static final int[] sweepFrames = new int[SweepConfig.values().length];

    private static int sweepIndex = -1;
    private static int lastSweepIndex = -1;
    private static boolean announced;

    static
    {
        for (int i = 0; i < SECTIONS; i++)
        {
            pending[i] = new ArrayDeque<>();
        }
    }

    private PerfProfiler() {}

    /** First call of the world render (START handler): poll readbacks, rotate sweep, stamp begin. */
    public static void frame()
    {
        if (!ENABLED)
        {
            return;
        }

        if (!announced)
        {
            announced = true;
            LOG.info("[perf] profiler active (sweep={})", SWEEP);
        }

        poll();

        if (SWEEP)
        {
            int index = (frames / SWEEP_CONFIG_FRAMES) % SweepConfig.values().length;

            /* Log each window the moment it closes: a heavy scene at ~10 fps needs over a minute
             * per full cycle, and nobody should have to hold a camera still through that blind. */
            if (index != lastSweepIndex)
            {
                if (lastSweepIndex >= 0 && sweepFrames[lastSweepIndex] > 0)
                {
                    logWindow(SweepConfig.values()[lastSweepIndex], lastSweepIndex);
                }

                lastSweepIndex = index;
            }

            sweepIndex = index;
        }

        fpsSum += net.minecraft.client.MinecraftClient.getInstance().getCurrentFps();
        frames++;

        int id = allocQuery(true);
        GL33C.glQueryCounter(id, GL33C.GL_TIMESTAMP);
        framePairs.addLast(new long[] {id, -1});
    }

    /** Last call of the world render (LAST handler, after every measured pass): stamp end. */
    public static void endFrame()
    {
        if (!ENABLED || framePairs.isEmpty())
        {
            return;
        }

        long[] pair = framePairs.peekLast();

        if (pair[1] == -1)
        {
            int id = allocQuery(true);
            GL33C.glQueryCounter(id, GL33C.GL_TIMESTAMP);
            pair[1] = id;
        }
    }

    public static void begin(Section section)
    {
        if (!ENABLED)
        {
            return;
        }

        cpuStart[section.ordinal()] = System.nanoTime();

        if (section.gpu)
        {
            /* Coexistence, not etiquette: Iris runs its own GL_TIME_ELAPSED profiler, and only one
             * query may be active per target. Beginning over theirs corrupts both measurements
             * (and our end() would close THEIR query — the 1282 spam). A busy target just drops
             * the section this frame. */
            if (currentElapsedQuery() != 0)
            {
                activeQuery[section.ordinal()] = 0;

                return;
            }

            int id = allocQuery(false);
            GL33C.glBeginQuery(GL33C.GL_TIME_ELAPSED, id);
            activeQuery[section.ordinal()] = id;
        }
    }

    public static void end(Section section)
    {
        if (!ENABLED)
        {
            return;
        }

        cpuNanos[section.ordinal()] += System.nanoTime() - cpuStart[section.ordinal()];

        if (section.gpu)
        {
            int id = activeQuery[section.ordinal()];

            /* End only our own live query: if the target's current query is not the one begin()
             * opened (a foreign profiler's, or none because begin() backed off), leave it alone. */
            if (id != 0 && currentElapsedQuery() == id)
            {
                GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED);
                pending[section.ordinal()].addLast(id);
            }

            activeQuery[section.ordinal()] = 0;
        }
        else
        {
            samples[section.ordinal()]++;
        }
    }

    /** Whether the current sweep window suppresses this pass. Always false outside sweep mode. */
    public static boolean skip(Feature feature)
    {
        if (!SWEEP)
        {
            return false;
        }

        return SweepConfig.values()[sweepIndex].off == feature;
    }

    /* ---- internals ---- */

    /** One-int scratch for the GL_CURRENT_QUERY probe (LWJGL has no scalar glGetQueryiv). */
    private static final java.nio.IntBuffer queryScratch = org.lwjgl.BufferUtils.createIntBuffer(1);

    /** The id of the GL_TIME_ELAPSED query currently active on the target, 0 when none. */
    private static int currentElapsedQuery()
    {
        queryScratch.clear();
        GL33C.glGetQueryiv(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY, queryScratch);

        return queryScratch.get(0);
    }

    private static int allocQuery(boolean timestamp)
    {
        Integer id = timestamp ? freeTimestamp.poll() : freeElapsed.poll();

        return id != null ? id : GL33C.glGenQueries();
    }

    private static void poll()
    {
        for (int s = 0; s < SECTIONS; s++)
        {
            if (!Section.values()[s].gpu)
            {
                continue;
            }

            while (!pending[s].isEmpty())
            {
                int id = pending[s].peek();

                if (GL33C.glGetQueryObjecti(id, GL33C.GL_QUERY_RESULT_AVAILABLE) != GL33C.GL_TRUE)
                {
                    break;
                }

                gpuNanos[s] += GL33C.glGetQueryObjecti64(id, GL33C.GL_QUERY_RESULT);
                samples[s]++;
                pending[s].poll();
                freeElapsed.add(id);
            }
        }

        while (!framePairs.isEmpty())
        {
            long[] pair = framePairs.peek();

            if (pair[1] == -1
                || GL33C.glGetQueryObjecti((int) pair[0], GL33C.GL_QUERY_RESULT_AVAILABLE) != GL33C.GL_TRUE
                || GL33C.glGetQueryObjecti((int) pair[1], GL33C.GL_QUERY_RESULT_AVAILABLE) != GL33C.GL_TRUE)
            {
                break;
            }

            long total = GL33C.glGetQueryObjecti64((int) pair[1], GL33C.GL_QUERY_RESULT)
                - GL33C.glGetQueryObjecti64((int) pair[0], GL33C.GL_QUERY_RESULT);

            if (total > 0)
            {
                frameTotalNanos += total;
                frameTotalSamples++;

                if (SWEEP)
                {
                    sweepFrameNanos[sweepIndex] += total;
                }
            }

            if (SWEEP)
            {
                sweepFps[sweepIndex] += net.minecraft.client.MinecraftClient.getInstance().getCurrentFps();
                sweepFrames[sweepIndex]++;
            }

            freeTimestamp.add((int) pair[0]);
            freeTimestamp.add((int) pair[1]);
            framePairs.poll();
        }

        if (!SWEEP && frames >= REPORT_FRAMES)
        {
            logReport();
        }
    }

    private static void logReport()
    {
        StringBuilder sb = new StringBuilder("[perf] frames=").append(frames)
            .append(" fps=").append(fpsSum / frames)
            .append(" lamps=").append(com.bbsvfx.vfxlights.light.LightRegistry.getLights().size())
            .append(" frameGPU=").append(ms(frameTotalNanos, frameTotalSamples)).append(" ms |");

        for (Section section : Section.values())
        {
            int i = section.ordinal();

            sb.append(' ').append(section.name()).append('=');

            if (section.gpu)
            {
                sb.append(ms(gpuNanos[i], samples[i])).append("ms GPU / ");
            }

            sb.append(ms(cpuNanos[i], samples[i])).append("ms CPU |");
        }

        sb.append(" flags: ").append(fingerprint());
        LOG.info(sb.toString());
        reset();
    }

    private static void logWindow(SweepConfig config, int i)
    {
        LOG.info("[perf-sweep] {} = {} ms GPU @ {} fps ({} frames)",
            config.name(), ms(sweepFrameNanos[i], sweepFrames[i]),
            sweepFps[i] / sweepFrames[i], sweepFrames[i]);

        sweepFrameNanos[i] = 0;
        sweepFps[i] = 0;
        sweepFrames[i] = 0;
    }

    private static String fingerprint()
    {
        StringBuilder sb = new StringBuilder();

        appendFlag(sb, "nocull", "vfxlights.opt.nocull");
        appendFlag(sb, "nostaticlive", "vfxlights.opt.nostaticlive");
        appendFlag(sb, "nobudget", "vfxlights.opt.nobudget");
        appendFlag(sb, "noskip", "vfxlights.shadow.noskip");
        appendFlag(sb, "noactor", "vfxlights.shadow.noactor");
        appendFlag(sb, "nooffset", "vfxlights.shadow.nooffset");
        appendFlag(sb, "noscissor", "vfxlights.noscissor");

        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static void appendFlag(StringBuilder sb, String label, String property)
    {
        if (Boolean.getBoolean(property))
        {
            if (sb.length() > 0)
            {
                sb.append(',');
            }

            sb.append(label);
        }
    }

    private static String ms(long nanos, int samples)
    {
        if (samples == 0)
        {
            return "0.00";
        }

        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0 / samples);
    }

    private static void reset()
    {
        for (int i = 0; i < SECTIONS; i++)
        {
            gpuNanos[i] = 0;
            cpuNanos[i] = 0;
            samples[i] = 0;
        }

        frameTotalNanos = 0;
        frameTotalSamples = 0;
        fpsSum = 0;
        frames = 0;
    }
}
