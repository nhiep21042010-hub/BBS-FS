package com.bbsvfx.bbsvfx.forms;

/**
 * Duck interface mixed onto every {@link mchorse.bbs_mod.forms.forms.Form} so its smear settings can be
 * read from the render hook and the editor UI without subclassing each form type.
 */
public interface ISmearHolder
{
    SmearProperties bbsvfx$smear();
}
