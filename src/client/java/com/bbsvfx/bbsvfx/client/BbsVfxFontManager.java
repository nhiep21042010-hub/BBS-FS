package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.BBSMod;
import net.fabricmc.loader.api.FabricLoader;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Discovers the fonts available to {@code LabelForm}: TTF/OTF files dropped into {@code config/bbsvfx/fonts}
 * plus every font family installed on the machine ({@link GraphicsEnvironment}). The combined, sorted list
 * (with {@link #DEFAULT} first = the vanilla MC font) feeds the editor's font picker; {@link #resolve}
 * turns a stored name back into an AWT {@link Font} for baking ({@link BbsVfxFontTexture}).
 *
 * <p>Fonts are stored on the form by NAME (not index) so a film opened on another machine degrades
 * gracefully (an unknown name resolves to the default). Scanning is lazy + cached; the system-font scan
 * triggers AWT init, so it only happens on first use (or an explicit {@link #rescan}).
 */
public final class BbsVfxFontManager
{
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("bbsvfx");

    /** Sentinel display name meaning "use the vanilla Minecraft font" (the stock render path). */
    public static final String DEFAULT = "Default";

    /** Folder fonts by display name → base AWT font (size 1, derived per request). */
    private static final Map<String, Font> FOLDER = new LinkedHashMap<>();

    /** Picker entries: DEFAULT, then folder fonts, then system families. */
    private static final List<String> NAMES = new ArrayList<>();

    private static boolean scanned;

    private BbsVfxFontManager()
    {}

    public static Path fontsDir()
    {
        /* Live in BBS's assets folder (config/bbs/assets/fonts) alongside the rest of the user content. */
        File assets = BBSMod.getAssetsFolder();

        if (assets != null)
        {
            return assets.toPath().resolve("fonts");
        }

        return FabricLoader.getInstance().getConfigDir().resolve("bbsvfx").resolve("fonts");
    }

    /** The picker list (lazily scanned). */
    public static List<String> names()
    {
        if (!scanned)
        {
            scan();
        }

        return NAMES;
    }

    public static void rescan()
    {
        scanned = false;
        scan();
    }

    private static void scan()
    {
        FOLDER.clear();
        NAMES.clear();
        NAMES.add(DEFAULT);

        try
        {
            Path dir = fontsDir();

            Files.createDirectories(dir);

            try (Stream<Path> stream = Files.list(dir))
            {
                stream.filter(BbsVfxFontManager::isFontFile).sorted().forEach(BbsVfxFontManager::loadFolderFont);
            }
        }
        catch (Exception e)
        {
            LOG.warn("[fonts] could not scan {}", fontsDir(), e);
        }

        try
        {
            String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();

            for (String family : families)
            {
                if (!NAMES.contains(family))
                {
                    NAMES.add(family);
                }
            }
        }
        catch (Exception e)
        {
            LOG.warn("[fonts] could not list system fonts", e);
        }

        scanned = true;
    }

    private static boolean isFontFile(Path path)
    {
        String name = path.getFileName().toString().toLowerCase();

        return name.endsWith(".ttf") || name.endsWith(".otf");
    }

    private static void loadFolderFont(Path path)
    {
        try
        {
            Font font = Font.createFont(Font.TRUETYPE_FONT, path.toFile());
            String name = font.getFontName();

            FOLDER.put(name, font);

            if (!NAMES.contains(name))
            {
                NAMES.add(name);
            }
        }
        catch (Exception e)
        {
            LOG.warn("[fonts] failed to load font {}", path, e);
        }
    }

    /**
     * Resolves a stored font name to an AWT font at the given pixel size, or {@code null} for the default
     * (vanilla MC font) — empty/{@link #DEFAULT}/unknown names fall back to default.
     */
    public static Font resolve(String name, float size)
    {
        if (name == null || name.isEmpty() || name.equals(DEFAULT))
        {
            return null;
        }

        if (!scanned)
        {
            scan();
        }

        Font base = FOLDER.get(name);

        if (base != null)
        {
            return base.deriveFont(size);
        }

        /* System family. new Font with an unknown name silently maps to the logical "Dialog" font; treat
         * that as "unknown" so the label falls back to the MC font instead of rendering in Dialog. */
        Font font = new Font(name, Font.PLAIN, 1);

        if (font.getFamily().equalsIgnoreCase("Dialog") && !name.equalsIgnoreCase("Dialog"))
        {
            return null;
        }

        return font.deriveFont(size);
    }
}
