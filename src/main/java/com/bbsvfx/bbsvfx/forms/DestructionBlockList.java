package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.settings.values.core.ValueList;
import net.minecraft.block.BlockState;

/**
 * The captured blocks of a {@link DestructionBoxForm}.
 */
public class DestructionBlockList extends ValueList<DestructionBlock>
{
    public DestructionBlockList(String id)
    {
        super(id);
    }

    @Override
    protected DestructionBlock create(String id)
    {
        return new DestructionBlock(id);
    }

    public DestructionBlock addBlock(int x, int y, int z, BlockState state)
    {
        return this.addBlock(x, y, z, state, -1);
    }

    public DestructionBlock addBlock(int x, int y, int z, BlockState state, int tint)
    {
        return this.addBlock(x, y, z, state, tint, -1);
    }

    public DestructionBlock addBlock(int x, int y, int z, BlockState state, int tint, int light)
    {
        DestructionBlock block = this.create(String.valueOf(this.getList().size()));

        block.x.set(x);
        block.y.set(y);
        block.z.set(z);
        block.state.set(state);
        block.tint.set(tint);
        block.light.set(light);
        this.add(block);

        return block;
    }

    public void clearBlocks()
    {
        this.getAllTyped().clear();
        this.sync();
    }
}
