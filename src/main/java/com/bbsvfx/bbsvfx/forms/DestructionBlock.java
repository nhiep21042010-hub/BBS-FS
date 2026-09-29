package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.mc.ValueBlockState;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import net.minecraft.block.BlockState;
import org.joml.Vector3f;

/**
 * One captured block of a {@link DestructionBoxForm}: its position relative to the structure origin
 * plus its block state. A {@link ValueGroup} so it (de)serialises with the form.
 */
public class DestructionBlock extends ValueGroup
{
    public final ValueInt x = new ValueInt("x", 0);
    public final ValueInt y = new ValueInt("y", 0);
    public final ValueInt z = new ValueInt("z", 0);
    public final ValueBlockState state = new ValueBlockState("state");
    /** Biome-resolved tint (tintIndex 0) captured at cut time; -1 (= white) for untinted blocks. */
    public final ValueInt tint = new ValueInt("tint", -1);
    /** Packed lightmap coordinates sampled at capture time (PRE-cut — under-canopy trunks stay dark,
     *  cave debris stays black); -1 = not captured (old saves) → the renderer's single sample. */
    public final ValueInt light = new ValueInt("light", -1);

    public DestructionBlock(String id)
    {
        super(id);

        this.add(this.x);
        this.add(this.y);
        this.add(this.z);
        this.add(this.state);
        this.add(this.tint);
        this.add(this.light);
    }

    public Vector3f position()
    {
        return new Vector3f(this.x.get(), this.y.get(), this.z.get());
    }

    public BlockState blockState()
    {
        return this.state.get();
    }
}
