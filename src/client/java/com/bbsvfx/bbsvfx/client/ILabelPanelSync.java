package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.forms.forms.LabelForm;

/**
 * The addon's label panel, told that a new form is being edited.
 *
 * <p>BBS 2.6 dropped {@code UILabelFormPanel#startEdit} — the stock label widgets are bound to their
 * values now, so the panel has nothing of its own to sync and stopped overriding it. The addon's
 * extra widgets are not value-bound, so they still need the moment. Mixin resolves an injection
 * point only against the target class itself, never an inherited method, so the hook is taken on
 * {@link mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel} and handed here.</p>
 */
public interface ILabelPanelSync
{
    void bbsvfx$syncLabelPanel(LabelForm form);
}
