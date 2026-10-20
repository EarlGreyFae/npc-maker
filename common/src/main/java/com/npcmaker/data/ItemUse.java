package com.npcmaker.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Built-in ways an NPC uses items from its inventory. All on by default; turn any off per template.
 *
 * @param heal          drink a healing potion (or eat a golden apple) when badly hurt
 * @param cure          drink milk to clear harmful effects such as poison or wither
 * @param eat           eat food to recover health when hurt and out of combat
 * @param switchWeapons swap to a bow or crossbow for distant targets and back to a melee weapon up close
 * @param useAmmo       ranged weapons use arrows from the inventory (off = unlimited arrows)
 * @param lightUp       place torches it carries on dark spots near home (anywhere, for companions)
 */
public record ItemUse(boolean heal, boolean cure, boolean eat, boolean switchWeapons, boolean useAmmo, boolean lightUp) {

    public static final ItemUse DEFAULT = new ItemUse(true, true, true, true, true, true);

    public static final Codec<ItemUse> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("heal", true).forGetter(ItemUse::heal),
            Codec.BOOL.optionalFieldOf("cure", true).forGetter(ItemUse::cure),
            Codec.BOOL.optionalFieldOf("eat", true).forGetter(ItemUse::eat),
            Codec.BOOL.optionalFieldOf("switch_weapons", true).forGetter(ItemUse::switchWeapons),
            Codec.BOOL.optionalFieldOf("use_ammo", true).forGetter(ItemUse::useAmmo),
            Codec.BOOL.optionalFieldOf("light_up", true).forGetter(ItemUse::lightUp)
    ).apply(i, ItemUse::new));
}
