package com.npcmaker.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.npcmaker.dialogue.DialogueCondition;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * A quest, from {@code <world>/npcmaker/quests/<id>.json}. Stages run in order; a stage is done when all its
 * objectives are, and finishing the last stage completes the quest and hands out the rewards.
 *
 * @param description     shown in the journal (styling tags work)
 * @param giver           NPC template id of whoever hands it out (shown in the journal)
 * @param requires        conditions (same as dialogue) for the quest to be offered and started from dialogue
 * @param repeatable      can be done again once finished
 * @param cooldownMinutes real minutes before a repeatable quest can be started again
 * @param abandonable     players can drop it from their journal
 * @param chooseOne       items the player picks one of when the quest is done (on top of the fixed rewards)
 */
public record QuestDefinition(String title, String description, Optional<String> giver, List<DialogueCondition> requires,
                              List<Stage> stages, List<QuestReward> rewards, List<ItemStack> chooseOne, boolean repeatable,
                              int cooldownMinutes, boolean abandonable) {

    /** @param text what to do now, shown above the objectives (optional) */
    public record Stage(Optional<String> text, List<QuestObjective> objectives) {
        public static final Codec<Stage> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("text").forGetter(Stage::text),
                QuestObjective.CODEC.listOf(1, 16).fieldOf("objectives").forGetter(Stage::objectives)
        ).apply(i, Stage::new));
    }

    public static final Codec<QuestDefinition> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("title").forGetter(QuestDefinition::title),
            Codec.STRING.optionalFieldOf("description", "").forGetter(QuestDefinition::description),
            Codec.STRING.optionalFieldOf("giver").forGetter(QuestDefinition::giver),
            DialogueCondition.CODEC.listOf().optionalFieldOf("requires", List.of()).forGetter(QuestDefinition::requires),
            Stage.CODEC.listOf(1, 64).fieldOf("stages").forGetter(QuestDefinition::stages),
            QuestReward.CODEC.listOf().optionalFieldOf("rewards", List.of()).forGetter(QuestDefinition::rewards),
            ItemStack.CODEC.listOf(0, 9).optionalFieldOf("choose_one", List.of()).forGetter(QuestDefinition::chooseOne),
            Codec.BOOL.optionalFieldOf("repeatable", false).forGetter(QuestDefinition::repeatable),
            Codec.intRange(0, 525600).optionalFieldOf("cooldown_minutes", 0).forGetter(QuestDefinition::cooldownMinutes),
            Codec.BOOL.optionalFieldOf("abandonable", true).forGetter(QuestDefinition::abandonable)
    ).apply(i, QuestDefinition::new));
}
