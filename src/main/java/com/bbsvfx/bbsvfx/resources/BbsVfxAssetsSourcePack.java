package com.bbsvfx.bbsvfx.resources;

import mchorse.bbs_mod.resources.ISourcePack;
import mchorse.bbs_mod.resources.Link;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Set;

/**
 * Serves the addon's assets to BBS's {@link mchorse.bbs_mod.resources.AssetProvider}. Registered by
 * {@link com.bbsvfx.bbsvfx.BbsVfxAddon#registerSourcePacks}.
 *
 * <p>The template ships an EMPTY {@link #OVERRIDES} set, so it serves nothing and falls through to
 * BBS for everything — it is wired and inert, ready for you to fill in. To override one of BBS's own
 * {@code bbs}-namespace assets (icon atlas, banners, …), drop your copy under
 * {@code resources/assets/bbsvfx/bbs_override/&lt;path&gt;}, add {@code &lt;path&gt;} to
 * {@link #OVERRIDES}, and make sure the addon registers this pack with {@code registerFirst} (it
 * does) so it precedes BBS's internal pack.</p>
 *
 * <p>The unique internal prefix ({@code assets/bbsvfx/bbs_override/…}) avoids colliding with
 * BBS's own {@code assets/bbs/assets/…} path, which would make classpath resolution
 * non-deterministic across the two jars.</p>
 */
public class BbsVfxAssetsSourcePack implements ISourcePack
{
    private static final Class<?> ANCHOR = BbsVfxAssetsSourcePack.class;
    private static final String INTERNAL = "assets/bbsvfx/bbs_override";

    /** Asset paths (under the {@link Link#ASSETS} source) this pack overrides. Empty by default. */
    private static final Set<String> OVERRIDES = Set.of(
        "textures/bbsvfx_icons.png"
    );

    @Override
    public String getPrefix()
    {
        return Link.ASSETS;
    }

    @Override
    public boolean hasAsset(Link link)
    {
        if (!Link.ASSETS.equals(link.source) || !OVERRIDES.contains(link.path))
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
    {
    }
}
