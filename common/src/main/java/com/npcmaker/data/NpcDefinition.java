package com.npcmaker.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.npcmaker.ai.AiRule;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * An NPC template, loaded from {@code <world>/npcmaker/npcs/<id>.json}. The id is the file name and is not
 * stored inside the file. Every placed NPC points at one template; editing the template updates them all.
 *
 * @param displayName name shown above the head
 * @param aiTemplate  ready-made AI setup ("guard", "townsfolk", "bandit", "companion") whose settings fill in
 *                    anything this file doesn't set itself
 * @param inventory   starting inventory (up to 27 stacks) the NPC uses items from: potions, food, milk, spare
 *                    weapons, ammunition
 * @param factions    faction ids this NPC belongs to
 * @param dialogue    dialogue tree id opened on interact
 * @param greeting    lines shown in the dialogue box on interact when there is no dialogue file
 * @param speech      speech bubbles: approach lines, idle lines, hit warnings, and switches to turn them off
 * @param aiRules     "when → if → then" rules, checked top to bottom (see AiRule)
 * @param effects     animations (idle pose, emotes) and particles (aura, event effects)
 */
public record NpcDefinition(String displayName, Optional<String> aiTemplate, Appearance appearance, Stats stats,
                            Behavior behavior, Equipment equipment, List<ItemStack> inventory, Sounds sounds,
                            List<String> factions, Optional<String> dialogue, List<String> greeting, Speech speech,
                            List<AiRule> aiRules, Effects effects) {

    public static final int INVENTORY_SIZE = 27;

    public static final Codec<NpcDefinition> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(NpcDefinition::displayName),
            Codec.STRING.optionalFieldOf("ai_template").forGetter(NpcDefinition::aiTemplate),
            Appearance.CODEC.optionalFieldOf("appearance", Appearance.DEFAULT).forGetter(NpcDefinition::appearance),
            Stats.CODEC.optionalFieldOf("stats", Stats.DEFAULT).forGetter(NpcDefinition::stats),
            Behavior.CODEC.optionalFieldOf("behavior", Behavior.DEFAULT).forGetter(NpcDefinition::behavior),
            Equipment.CODEC.optionalFieldOf("equipment", Equipment.NONE).forGetter(NpcDefinition::equipment),
            ItemStack.CODEC.listOf(0, INVENTORY_SIZE).optionalFieldOf("inventory", List.of()).forGetter(NpcDefinition::inventory),
            Sounds.CODEC.optionalFieldOf("sounds", Sounds.NONE).forGetter(NpcDefinition::sounds),
            Codec.STRING.listOf().optionalFieldOf("factions", List.of()).forGetter(NpcDefinition::factions),
            Codec.STRING.optionalFieldOf("dialogue").forGetter(NpcDefinition::dialogue),
            Codec.STRING.listOf().optionalFieldOf("greeting", List.of()).forGetter(NpcDefinition::greeting),
            Speech.CODEC.optionalFieldOf("speech", Speech.DEFAULT).forGetter(NpcDefinition::speech),
            AiRule.CODEC.listOf().optionalFieldOf("ai_rules", List.of()).forGetter(NpcDefinition::aiRules),
            Effects.CODEC.optionalFieldOf("effects", Effects.NONE).forGetter(NpcDefinition::effects)
    ).apply(i, NpcDefinition::new));

    public static NpcDefinition basic(String name) {
        return new NpcDefinition(name, Optional.empty(), Appearance.DEFAULT, Stats.DEFAULT, Behavior.DEFAULT,
                Equipment.NONE, List.of(), Sounds.NONE, List.of(), Optional.empty(), List.of(), Speech.DEFAULT, List.of(),
                Effects.NONE);
    }
}
