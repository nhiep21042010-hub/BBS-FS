package com.bbsvfx.vfxlights.forms.values;

import mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

/**
 * The addon's grouped light properties, registered into BBS's factory table.
 *
 * <p><b>Ids are namespaced and permanent.</b> A keyframe channel stores the factory's KEY
 * ({@code KeyframeChannel.toData} writes it, {@code fromData} looks it up), so renaming one of these
 * strings orphans every animated light in every saved film. Add, never rename.</p>
 *
 * <p>Registration happens in this class's own initialiser, so it cannot be forgotten: every light form
 * builds its values from the factories below and therefore loads this class first. The mod's entry
 * point also calls {@link #register()} outright, for the case of a film loading before any light form
 * has ever been constructed.</p>
 */
public final class LightFactories
{
    public static final LightStructFactory<LightAir> AIR = new LightStructFactory<>(LightAir::new);
    public static final LightStructFactory<LightStyle> STYLE = new LightStructFactory<>(LightStyle::new);
    public static final LightStructFactory<LightToon> TOON = new LightStructFactory<>(LightToon::new);
    public static final LightStructFactory<LightBarn> BARN = new LightStructFactory<>(LightBarn::new);

    static
    {
        KeyframeFactories.FACTORIES.put("vfxlights:air", AIR);
        KeyframeFactories.FACTORIES.put("vfxlights:style", STYLE);
        KeyframeFactories.FACTORIES.put("vfxlights:toon", TOON);
        KeyframeFactories.FACTORIES.put("vfxlights:barn", BARN);
    }

    private LightFactories()
    {}

    /** Force this class to initialise, and with it the registration above. */
    public static void register()
    {}

    /* Form property builders — the value objects the forms hold. */

    public static BaseKeyframeFactoryValue<LightAir> air()
    {
        return new BaseKeyframeFactoryValue<>("air", AIR, new LightAir());
    }

    public static BaseKeyframeFactoryValue<LightStyle> style()
    {
        return new BaseKeyframeFactoryValue<>("style", STYLE, new LightStyle());
    }

    public static BaseKeyframeFactoryValue<LightToon> toon()
    {
        return new BaseKeyframeFactoryValue<>("toon", TOON, new LightToon());
    }

    public static BaseKeyframeFactoryValue<LightBarn> barn()
    {
        return new BaseKeyframeFactoryValue<>("barn", BARN, new LightBarn());
    }
}
