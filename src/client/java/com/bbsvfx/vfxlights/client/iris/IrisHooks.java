package com.bbsvfx.vfxlights.client.iris;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;

/** Direct Iris calls. Never load this class without checking that Iris is present. */
final class IrisHooks
{
    private IrisHooks()
    {
    }

    static boolean packInUse()
    {
        try
        {
            return IrisApi.getInstance().isShaderPackInUse();
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    static String packName()
    {
        try
        {
            String name = Iris.getCurrentPackName();

            return name == null ? "" : name;
        }
        catch (Throwable t)
        {
            return "";
        }
    }
}
