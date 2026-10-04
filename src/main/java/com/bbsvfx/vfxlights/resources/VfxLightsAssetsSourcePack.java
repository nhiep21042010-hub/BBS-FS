package com.bbsvfx.vfxlights.resources;

import mchorse.bbs_mod.resources.ISourcePack;
import mchorse.bbs_mod.resources.Link;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Set;

/**
 * Serves this addon's own assets to BBS's {@link mchorse.bbs_mod.resources.AssetProvider} — currently
 * the icon atlas the light form editors wear in their tab strip.
 *
 * <p>The internal prefix ({@code assets/vfxlights/bbs_override/…}) is unique to this jar on purpose:
 * BBS serves its own assets from {@code assets/bbs/assets/…}, and a shared path would make classpath
 * resolution depend on jar order. Registered with {@code registerFirst} so it precedes BBS's internal
 * pack, which is what an override needs (an addition would not care).</p>
 */
public class VfxLightsAssetsSourcePack implements ISourcePack
{
    private static final Class<?> ANCHOR = VfxLightsAssetsSourcePack.class;
    private static final String INTERNAL = "assets/vfxlights/bbs_override";

    /** Asset paths (under the {@link Link#ASSETS} source) this pack serves. */
    private static final Set<String> ASSETS = Set.of(
        "textures/vfxlights_icons.png"
    );

    @Override
    public String getPrefix()
    {
        return Link.ASSETS;
    }

    @Override
    public boolean hasAsset(Link link)
    {
        if (!Link.ASSETS.equals(link.source) || !ASSETS.contains(link.path))
        {
            return false;
        }

        return ANCHOR.getResource("/" + INTERNAL + "/" + link.path) != null;
    }

    @Override
    public InputStream getAsset(Link link) throws IOException
    {
        InputStream stream = ANCHOR.getResourceAsStream("/" + INTERNAL + "/" + link.path);

        if (stream == null)
        {
            throw new FileNotFoundException("Asset " + link + " couldn't be found!");
        }

        return stream;
    }

    @Override
    public File getFile(Link link)
    {
        return null;
    }

    @Override
    public Link getLink(File file)
    {
        return null;
    }

    @Override
    public void getLinksFromPath(Collection<Link> links, Link link, boolean recursive)
    {}
}
