package com.npcmaker.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Gear worn and held by the NPC. Items use the vanilla item stack format, so components (enchantments, dyes,
 * custom names) work: {@code {"id": "minecraft:iron_sword", "components": {...}}}.
 *
 * @param dropChance chance (0–1) that each item drops when the NPC is killed; 0 keeps NPC gear unfarmable
 */
public record Equipment(ItemStack mainHand, ItemStack offHand, ItemStack head, ItemStack chest,
                        ItemStack legs, ItemStack feet, float dropChance) {

    public static final Equipment NONE = new Equipment(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
            ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, 0.0F);

    public static final Codec<Equipment> CODEC = RecordCodecBuilder.create(i -> i.group(
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("main_hand", ItemStack.EMPTY).forGetter(Equipment::mainHand),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("off_hand", ItemStack.EMPTY).forGetter(Equipment::offHand),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("head", ItemStack.EMPTY).forGetter(Equipment::head),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("chest", ItemStack.EMPTY).forGetter(Equipment::chest),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("legs", ItemStack.EMPTY).forGetter(Equipment::legs),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("feet", ItemStack.EMPTY).forGetter(Equipment::feet),
            Codec.floatRange(0.0F, 1.0F).optionalFieldOf("drop_chance", 0.0F).forGetter(Equipment::dropChance)
    ).apply(i, Equipment::new));

    public ItemStack get(EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> mainHand;
            case OFFHAND -> offHand;
            case HEAD -> head;
            case CHEST -> chest;
            case LEGS -> legs;
            case FEET -> feet;
            default -> ItemStack.EMPTY;
        };
    }

    /** The slots an NPC can wear gear in, in display order. */
    public static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
}
