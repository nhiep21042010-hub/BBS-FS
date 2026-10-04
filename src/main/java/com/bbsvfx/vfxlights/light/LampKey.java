package com.bbsvfx.vfxlights.light;

/**
 * The STABLE identity of an authored lamp — the answer to the identity-churn flicker
 * (2026-08-09, proven by the persist debug log: ~25 keys for ~4 lamps over three minutes of
 * pause/drag). The registry used to key lamps by the FORM INSTANCE, and BBS recreates forms on
 * edits, drags and pause refreshes: each recreation was a "new" lamp — the old one went to the
 * ghost prune while its shadow lease invalidated into a full rebake, and the churn read as the
 * pause/drag light flicker.
 *
 * <p>The key is (host, formId): the form's SERIALISED vfx id ({@code LightForm.vfxId}, minted at
 * collection) survives recreation, and the host discriminates the same id across films. Host
 * semantics by kind: an MC-backed actor contributes its entity
 * UUID (value equality — survives form recreation, wrapper recreation and world reload); other
 * IEntity wrappers contribute the wrapper itself (identity — stable when the wrapper IS the
 * host, e.g. a model block entity); anonymous effect lamps contribute the form instance, the
 * pre-LampKey behaviour (they never persist anyway).</p>
 */
public final class LampKey
{
    /** UUID, an IEntity wrapper, or a Form instance — see the class javadoc. */
    private final Object host;
    private final String formId;
    private final int hash;

    private LampKey(Object host, String formId)
    {
        this.host = host;
        this.formId = formId;
        this.hash = 31 * host.hashCode() + formId.hashCode();
    }

    /** Actor/entity lamp: the MC entity's UUID anchors it (value equality across recreations). */
    public static LampKey ofUuid(java.util.UUID uuid, String formId)
    {
        return new LampKey(uuid, formId);
    }

    /** Lamp on another IEntity implementation: the wrapper object anchors it (identity). */
    public static LampKey ofEntity(Object wrapper, String formId)
    {
        return new LampKey(wrapper, formId);
    }

    /** Anonymous lamp (no host in context): the form instance, the pre-LampKey behaviour. */
    public static LampKey ofForm(Object form, String formId)
    {
        return new LampKey(form, formId);
    }

    /** Whether the host is a value-identity UUID (survives form/wrapper recreation). */
    public boolean stableHost()
    {
        return this.host instanceof java.util.UUID;
    }

    @Override
    public boolean equals(Object other)
    {
        return other instanceof LampKey key
            && key.formId.equals(this.formId)
            && key.host.equals(this.host);
    }

    @Override
    public int hashCode()
    {
        return this.hash;
    }

    @Override
    public String toString()
    {
        return "LampKey[" + this.host.getClass().getSimpleName() + "#" + this.formId + "]";
    }
}
