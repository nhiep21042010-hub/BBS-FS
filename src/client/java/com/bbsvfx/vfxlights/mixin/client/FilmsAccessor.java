package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Films;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The playing films' controllers — the only road to actors of films running OUTSIDE the editor. */
@Mixin(value = Films.class, remap = false)
public interface FilmsAccessor
{
    @Accessor("controllers")
    List<BaseFilmController> vfxlights$getControllers();
}
