package com.npcmaker.item;

import com.npcmaker.Constants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;

/** NPC Maker's items. Each loader registers what {@link #createWand()} builds, when its registry system allows. */
public final class NpcItems {

    public static final ResourceKey<Item> WAND_KEY = ResourceKey.create(Registries.ITEM, Constants.id("npc_wand"));

    /** Vanilla's Tools & Utilities tab (its constant isn't public in 26.3). */
    public static final ResourceKey<CreativeModeTab> TOOLS_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));

    private static Item wand;

    private NpcItems() {
    }

    public static Item createWand() {
        wand = new NpcWandItem(new Item.Properties()
                .setId(WAND_KEY)
                .stacksTo(1)
                .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true));
        return wand;
    }

    public static Item wand() {
        return wand;
    }
}
