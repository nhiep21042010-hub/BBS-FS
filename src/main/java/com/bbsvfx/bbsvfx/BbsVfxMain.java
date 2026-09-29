package com.bbsvfx.bbsvfx;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Common mod entry point. Registers the Destruction Box wand item (must happen during mod init, before
 * the registries freeze — hence a dedicated {@code main} entrypoint rather than the late BBS/client
 * init). Right-clicking a block with the wand sets a selection corner (sneak = corner 1, otherwise
 * corner 2); the selection is stored client-side in {@link DestructionSelection}.
 */
public class BbsVfxMain implements ModInitializer
{
    public static final Item DESTRUCTION_WAND = new Item(new Item.Settings().maxCount(1));

    @Override
    public void onInitialize()
    {
        Registry.register(Registries.ITEM, Identifier.of(BbsVfxAddon.MOD_ID, "destruction_wand"), DESTRUCTION_WAND);

        /* Put the wand in BBS's own creative tab (registered by BBS as "bbs:main"). */
        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of("bbs", "main")))
            .register((entries) -> entries.add(DESTRUCTION_WAND));

        UseBlockCallback.EVENT.register((player, world, hand, hit) ->
        {
            if (player.getStackInHand(hand).getItem() != DESTRUCTION_WAND)
            {
                return ActionResult.PASS;
            }

            if (world.isClient)
            {
                BlockPos pos = hit.getBlockPos();

                if (player.isSneaking())
                {
                    DestructionSelection.pos1 = pos;
                }
                else
                {
                    DestructionSelection.pos2 = pos;
                }
            }

            return ActionResult.SUCCESS;
        });
    }
}
