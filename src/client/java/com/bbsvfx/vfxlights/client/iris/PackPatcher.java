package com.bbsvfx.vfxlights.client.iris;

import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Patches a shaderpack's sources IN MEMORY as Iris loads them.
 *
 * <p><b>Nothing on disk is ever touched.</b> The alternative — writing a modified copy of the pack next
 * to the original — leaves users with two confusingly-named packs, breaks silently when the original
 * updates, and needs UI to manage. Intercepting the read (see {@code IrisSourceMixin}) means the user
 * selects their ordinary pack and the lights are simply there; disable the mod and the pack is exactly
 * as it was, because it never changed.</p>
 *
 * <p><b>Patch format</b> ({@code assets/vfxlights/patches/*.vfxpatch}): {@code @target <substring>}
 * matches the selected pack's name; {@code @virtual <path>} plus a {@code <<< >>>} block serves a file
 * that does not exist in the pack; {@code @patch <path-suffix>} selects a real file and each
 * {@code @after <anchor>} block is inserted after the first line CONTAINING the anchor.</p>
 *
 * <p><b>Anchors fail loudly.</b> The known weakness of anchored patching is a pack update moving a
 * line: the patch must then say exactly which anchor died, not dissolve into a half-applied shader
 * that miscompiles somewhere else.</p>
 */
public final class PackPatcher
{
    private static final Logger LOG = LoggerFactory.getLogger("vfxlights");

    /** The line in vfxlights_lights.glsl the shared model file is spliced over. */
    private static final String MODEL_MARKER = "//@VFX_MODEL@";

    /** Bundled patches, loaded once. */
    private static final List<Patch> PATCHES = new ArrayList<>();
    private static boolean loaded;
    private static Boolean ssboSupported;

    private PackPatcher()
    {
    }

    private static class Insertion
    {
        String fileSuffix;
        String anchor;
        String body;
    }

    private static class Patch
    {
        String target = "";
        final Map<String, String> virtualFiles = new HashMap<>();
        final List<Insertion> insertions = new ArrayList<>();
    }

    private static boolean loggedFirstHook;

    /* Whether the CURRENTLY loaded pack actually received any patch — the switch between "lights
     * live inside the pack" and "the fallback composite must paint them over the finished frame".
     * Tracked from the read paths themselves: a new pack identity resets the flag, a successful
     * insertion sets it. */
    private static String lastPackId = "";
    private static boolean packPatched;

    public static boolean isCurrentPackPatched()
    {
        return packPatched;
    }

    private static void trackPack(Path path)
    {
        /* Zip packs hand us per-FILE relative paths ("shaders/..."), so the substring heuristic
         * below turns every file into its own "pack": each read resets packPatched, and the last
         * file Iris reads at load (not a patch target) leaves it false forever — the fallback
         * composite then double-lights every frame on top of the patched pack (the zip-only
         * overbright/flicker bug). A zip filesystem instance IS the pack identity: Iris opens one
         * per pack load, constant for every file inside it. Disk paths keep the old behaviour. */
        if (path.getFileSystem() != java.nio.file.FileSystems.getDefault())
        {
            String zipId = String.valueOf(path.getFileSystem());

            if (!zipId.equals(lastPackId))
            {
                lastPackId = zipId;
                packPatched = false;
                LOG.info("[vfxlights] pack change detected (zip fs {}), patch state reset", zipId);
            }

            return;
        }

        String key = normalise(path);
        int at = key.indexOf("shaders");
        String id = String.valueOf(path.getFileSystem()) + "|" + (at > 0 ? key.substring(0, at) : key);

        if (!id.equals(lastPackId))
        {
            lastPackId = id;
            packPatched = false;
        }
    }

    /** Content for a file that exists only in memory, or null when this path is not ours. */
    public static String serveVirtual(Path path)
    {
        ensureLoaded();
        logFirstHook(path);
        trackPack(path);

        if (!enabled())
        {
            return null;
        }

        String key = normalise(path);

        for (Patch patch : PATCHES)
        {
            if (!appliesTo(patch, path))
            {
                continue;
            }

            for (Map.Entry<String, String> virtual : patch.virtualFiles.entrySet())
            {
                if (key.endsWith(virtual.getKey()))
                {
                    return virtual.getValue();
                }
            }
        }

        return null;
    }

    /** One line, once — so "the hook never fired" and "the pack never matched" stop looking alike. */
    private static void logFirstHook(Path path)
    {
        if (!loggedFirstHook)
        {
            loggedFirstHook = true;

            LOG.info("[vfxlights] iris source hook active — first read: fs [{}] path [{}]",
                path.getFileSystem(), path);
        }
    }

    /** The pack file's source, patched if any rule targets it. */
    public static String transform(Path path, String source)
    {
        ensureLoaded();
        trackPack(path);

        if (source == null || !enabled())
        {
            return source;
        }

        String key = normalise(path);
        String result = source;

        for (Patch patch : PATCHES)
        {
            if (!appliesTo(patch, path))
            {
                continue;
            }

            for (Insertion insertion : patch.insertions)
            {
                if (!key.endsWith(insertion.fileSuffix))
                {
                    continue;
                }

                int at = result.indexOf(insertion.anchor);

                if (at < 0)
                {
                    /* The one failure mode this system has — say it plainly. */
                    LOG.warn("[vfxlights] pack patch anchor NOT FOUND in {}: \"{}\" — the pack version "
                        + "probably differs from the one the patch was written for; lights will be "
                        + "missing from this file", key, insertion.anchor);

                    continue;
                }

                int lineEnd = result.indexOf('\n', at);

                if (lineEnd < 0)
                {
                    lineEnd = result.length();
                }

                result = result.substring(0, lineEnd + 1) + insertion.body + result.substring(lineEnd + 1);
                packPatched = true;

                LOG.info("[vfxlights] patched {} (anchor \"{}\")", key,
                    insertion.anchor.length() > 40 ? insertion.anchor.substring(0, 40) + "…" : insertion.anchor);
            }
        }

        /* Dev dump (-Dvfxlights.patch.dump): write the served patched source out, so a zip-vs-
         * folder diff of what Iris actually compiles is a file comparison, not a guess. */
        if (packPatched && System.getProperty("vfxlights.patch.dump") != null)
        {
            try
            {
                String tag = IrisCompat.packName().replaceAll("[^A-Za-z0-9._-]", "_");
                String fileTag = key.replaceAll("[^A-Za-z0-9._-]", "_");
                java.nio.file.Path out = java.nio.file.Paths.get("run",
                    "vfxlights_dump_" + tag + "__" + fileTag + ".glsl");

                java.nio.file.Files.createDirectories(out.getParent());
                java.nio.file.Files.write(out, result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                LOG.info("[vfxlights] dumped patched source to {}", out);
            }
            catch (Throwable t)
            {
                LOG.warn("[vfxlights] patch dump failed: {}", t.toString());
            }
        }

        return result;
    }

    /**
     * Does this patch target the pack the file belongs to?
     *
     * <p>Identified from the PATH, not from Iris's current-pack field: that field is set only after the
     * pack finishes constructing, which is exactly when these reads happen — matching against it made
     * every patch silently miss (measured: pack loaded, zero patch lines, zero missing-anchor lines).
     * A zip pack's filesystem string is the archive path; a folder pack's path contains the folder
     * name. Both carry the pack's identity at the moment we actually need it.</p>
     */
    private static boolean appliesTo(Patch patch, Path path)
    {
        String fs;

        try
        {
            fs = String.valueOf(path.getFileSystem());
        }
        catch (Throwable t)
        {
            fs = "";
        }

        if (fs.contains(patch.target) || path.toString().contains(patch.target)
            || fs.toLowerCase(java.util.Locale.ROOT).contains(patch.target.toLowerCase(java.util.Locale.ROOT))
            || path.toString().toLowerCase(java.util.Locale.ROOT).contains(patch.target.toLowerCase(java.util.Locale.ROOT)))
        {
            return true;
        }

        String name = IrisCompat.packName();

        return !name.isEmpty() && (name.contains(patch.target)
            || name.toLowerCase(java.util.Locale.ROOT).contains(patch.target.toLowerCase(java.util.Locale.ROOT)));
    }

    /**
     * The patched GLSL needs shader storage buffers; on a driver without them the patch would turn the
     * whole pack into a compile error. Better no extra lights than a broken pack.
     */
    private static boolean enabled()
    {
        if (ssboSupported == null)
        {
            try
            {
                ssboSupported = GL.getCapabilities().GL_ARB_shader_storage_buffer_object
                    || GL.getCapabilities().OpenGL43;
            }
            catch (Throwable t)
            {
                ssboSupported = false;
            }

            if (!ssboSupported)
            {
                LOG.warn("[vfxlights] driver has no shader storage buffers — pack lighting disabled, "
                    + "the pack itself is untouched");
            }
        }

        return ssboSupported;
    }

    private static String normalise(Path path)
    {
        return path.toString().replace('\\', '/');
    }

    private static void ensureLoaded()
    {
        if (loaded)
        {
            return;
        }

        loaded = true;

        /* The light math is ONE file shared by every pack — each pack's patch differs only in where it
         * hooks and how it hands us normals, not in the lighting itself. Since the merge it is TWO
         * bundled resources: the pack-side adapter (SSBO layout + the slot->struct loop) and the model
         * itself (shaders/include/vfxlights_model.glsl — the same file the fallback and the beams
         * #moj_import), spliced here over the //@VFX_MODEL@ marker and served as each patch's
         * /lib/vfxlights_lights.glsl. A change to the light model is a single edit for all backends. */
        String sharedBody = null;
        String prelude = readResource("/assets/vfxlights/patches/vfxlights_lights.glsl");
        String model = readResource("/assets/vfxlights/shaders/include/vfxlights_model.glsl");

        if (prelude == null || model == null)
        {
            LOG.error("[vfxlights] shared light source missing (adapter {}, model {}) — pack lighting disabled",
                prelude == null ? "MISSING" : "ok", model == null ? "MISSING" : "ok");
        }
        else
        {
            int at = prelude.indexOf(MODEL_MARKER);

            if (at < 0)
            {
                /* Fail loudly, not with a half-assembled shader that miscompiles somewhere else. */
                LOG.error("[vfxlights] {} marker missing from vfxlights_lights.glsl — pack lighting disabled",
                    MODEL_MARKER);
            }
            else
            {
                int lineEnd = prelude.indexOf('\n', at);

                sharedBody = prelude.substring(0, at) + model
                    + (lineEnd < 0 ? "" : prelude.substring(lineEnd + 1));
            }

            /* ★TEMP diagnostic (-Dvfxlights.outline.debug=1): define-gated paint of the pack hook's
             * outline inputs (see the VFX_OUTLINE_DEBUG block at the end of vfxlights_run). */
            if (sharedBody != null && System.getProperty("vfxlights.outline.debug") != null)
            {
                sharedBody = "#define VFX_OUTLINE_DEBUG 1\n" + sharedBody;
            }

            /* ★TEMP diagnostic (-Dvfxlights.shadow.gatedebug): paints the actor map's gates
             * (see the VFX_ACTOR_GATE_DEBUG block at the end of vfxActorShadow). */
            if (sharedBody != null && System.getProperty("vfxlights.shadow.gatedebug") != null)
            {
                sharedBody = "#define VFX_ACTOR_GATE_DEBUG 1\n" + sharedBody;
            }
        }

        for (String name : new String[] { "complementary.vfxpatch", "iterationrp.vfxpatch", "eclipse.vfxpatch",
            "photon.vfxpatch" })
        {
            try (InputStream in = PackPatcher.class.getResourceAsStream("/assets/vfxlights/patches/" + name))
            {
                if (in == null)
                {
                    LOG.warn("[vfxlights] bundled patch missing: {}", name);

                    continue;
                }

                Patch patch = parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));

                if (sharedBody != null)
                {
                    patch.virtualFiles.put("/lib/vfxlights_lights.glsl", sharedBody);
                }

                PATCHES.add(patch);
                LOG.info("[vfxlights] loaded pack patch {} (target \"{}\")", name, patch.target);
            }
            catch (Throwable t)
            {
                LOG.error("[vfxlights] failed to load pack patch " + name, t);
            }
        }
    }

    /** A bundled text resource as a string, or null if it is not on the classpath. */
    private static String readResource(String path)
    {
        try (InputStream in = PackPatcher.class.getResourceAsStream(path))
        {
            if (in == null)
            {
                return null;
            }

            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;

            while ((line = reader.readLine()) != null)
            {
                out.append(line).append('\n');
            }

            return out.toString();
        }
        catch (Throwable t)
        {
            LOG.error("[vfxlights] failed to read resource " + path, t);

            return null;
        }
    }

    private static Patch parse(BufferedReader reader) throws Exception
    {
        Patch patch = new Patch();
        String currentFile = null;
        String line;

        while ((line = reader.readLine()) != null)
        {
            if (line.startsWith("#") || line.isBlank())
            {
                continue;
            }

            if (line.startsWith("@target "))
            {
                patch.target = line.substring(8).trim();
            }
            else if (line.startsWith("@virtual "))
            {
                patch.virtualFiles.put(line.substring(9).trim(), readBlock(reader));
            }
            else if (line.startsWith("@patch "))
            {
                currentFile = line.substring(7).trim();
            }
            else if (line.startsWith("@after "))
            {
                Insertion insertion = new Insertion();

                insertion.fileSuffix = currentFile;
                insertion.anchor = line.substring(7);
                insertion.body = readBlock(reader);
                patch.insertions.add(insertion);
            }
        }

        return patch;
    }

    private static String readBlock(BufferedReader reader) throws Exception
    {
        StringBuilder body = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null && !line.equals("<<<"))
        {
            /* Skip until the block opens. */
        }

        while ((line = reader.readLine()) != null && !line.equals(">>>"))
        {
            body.append(line).append('\n');
        }

        return body.toString();
    }
}
