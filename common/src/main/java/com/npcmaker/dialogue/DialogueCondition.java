package com.npcmaker.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.npcmaker.NpcPermissions;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.faction.FactionDefinition;
import com.npcmaker.faction.PlayerFactions;
import com.npcmaker.player.PlayerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import com.npcmaker.content.NpcContent;
import com.npcmaker.quest.QuestManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Decides whether a reply (or an opener) is offered. In JSON: {@code {"type": "flag", "flag": "met_guard"}}.
 */
public sealed interface DialogueCondition {

    String type();

    boolean test(ServerPlayer player, NpcEntity npc);

    /** The player has (or, with {@code "value": false}, hasn't) got a flag set by an earlier dialogue. */
    record Flag(String flag, boolean value) implements DialogueCondition {
        static final MapCodec<Flag> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("flag").forGetter(Flag::flag),
                Codec.BOOL.optionalFieldOf("value", true).forGetter(Flag::value)
        ).apply(i, Flag::new));

        public String type() {
            return "flag";
        }

        public boolean test(ServerPlayer player, NpcEntity npc) {
            return PlayerData.get(player).hasFlag(flag) == value;
        }
    }

    /** The player carries at least {@code count} of an item. */
    record HasItem(Item item, int count) implements DialogueCondition {
        static final MapCodec<HasItem> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(HasItem::item),
                Codec.intRange(1, 10000).optionalFieldOf("count", 1).forGetter(HasItem::count)
        ).apply(i, HasItem::new));

        public String type() {
            return "has_item";
        }

        public boolean test(ServerPlayer player, NpcEntity npc) {
            return DialogueActions.countItem(player, item) >= count;
        }
    }

    /** Only operators and creative-mode builders see this reply (handy for admin/debug options). */
    record CanEdit() implements DialogueCondition {
        static final MapCodec<CanEdit> CODEC = MapCodec.unit(new CanEdit());

        public String type() {
            return "can_edit";
        }

        public boolean test(ServerPlayer player, NpcEntity npc) {
            return NpcPermissions.canEdit(player);
        }
    }

    /** Offered only some of the time, re-rolled each time the node is shown. */
    record Chance(float chance) implements DialogueCondition {
        static final MapCodec<Chance> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.floatRange(0.0F, 1.0F).fieldOf("chance").forGetter(Chance::chance)
        ).apply(i, Chance::new));

        public String type() {
            return "chance";
        }

        public boolean test(ServerPlayer player, NpcEntity npc) {
            return player.getRandom().nextFloat() < chance;
        }
    }

    /** Only while this NPC is the player's companion (or, with false, while it isn't). */
    record IsCompanion(boolean value) implements DialogueCondition {
        static final MapCodec<IsCompanion> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.BOOL.optionalFieldOf("value", true).forGetter(IsCompanion::value)
        ).apply(i, IsCompanion::new));

        public String type() {
            return "is_companion";
        }

        public boolean test(ServerPlayer player, NpcEntity npc) {
            return npc.isCompanionOf(player) == value;
        }
    }

    /** The player is (or, with false, isn't) a member of a faction. */
    record FactionMember(String faction, boolean value) implements DialogueCondition {
        static final MapCodec<FactionMember> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("faction").forGetter(FactionMember::faction),
                Codec.BOOL.optionalFieldOf("value", true).forGetter(FactionMember::value)
        ).apply(i, FactionMember::new));

        public String type() {
            return "faction_member";
        }

        public boolean test(ServerPlayer player, NpcEntity npc) {
            return PlayerData.get(player).isMember(faction) == value;
        }
    }

    /** The player's reputation with a faction is within a range (both ends inclusive, either optional). */
    record Reputation(String faction, int min, int max) implements DialogueCondition {
        static final MapCodec<Reputation> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("faction").forGetter(Reputation::faction),
                Codec.INT.optionalFieldOf("min", FactionDefinition.MIN_REPUTATION).forGetter(Reputation::min),
                Codec.INT.optionalFieldOf("max", FactionDefinition.MAX_REPUTATION).forGetter(Reputation::max)
        ).apply(i, Reputation::new));

        public String type() {
            return "reputation";
        }

        public boolean test(ServerPlayer player, NpcEntity npc) {
            int rep = PlayerData.get(player).reputation(faction);
            return rep >= min && rep <= max;
        }
    }

    /** The player could join a faction right now: not already a member, and not in a faction it excludes. */
    record CanJoinFaction(String faction) implements DialogueCondition {
        static final MapCodec<CanJoinFaction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("faction").forGetter(CanJoinFaction::faction)
        ).apply(i, CanJoinFaction::new));

        public String type() {
            return "can_join_faction";
        }

        public boolean test(ServerPlayer player, NpcEntity npc) {
            return PlayerFactions.canJoin(player, faction);
        }
    }

    /**
     * The player's standing with a faction is at least and/or at most a named tier, e.g.
     * {@code {"type": "standing", "faction": "dawnguard", "at_least": "Friendly"}}.
     */
    record Standing(String faction, Optional<String> atLeast, Optional<String> atMost) implements DialogueCondition {
        static final MapCodec<Standing> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("faction").forGetter(Standing::faction),
                Codec.STRING.optionalFieldOf("at_least").forGetter(Standing::atLeast),
                Codec.STRING.optionalFieldOf("at_most").forGetter(Standing::atMost)
        ).apply(i, Standing::new));

        public String type() {
            return "standing";
        }

        public boolean test(ServerPlayer player, NpcEntity npc) {
            Optional<FactionDefinition> def = NpcContent.FACTIONS.get(faction);
            if (def.isEmpty()) {
                return false;
            }
            int playerTierMin = def.get().standingFor(PlayerData.get(player).reputation(faction)).min();
            if (atLeast.isPresent()) {
                OptionalInt min = def.get().minFor(atLeast.get());
                if (min.isEmpty() || playerTierMin < min.getAsInt()) {
                    return false;
                }
            }
            if (atMost.isPresent()) {
                OptionalInt max = def.get().minFor(atMost.get());
                if (max.isEmpty() || playerTierMin > max.getAsInt()) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Where the player is with a quest: {@code "state"} is "not_started" (never taken), "active", "completed"
     * (finished at least once and not active now) or "can_start" (could take it now: requirements met, not active,
     * and not finished unless it's repeatable and off cooldown). {@code "stage"} (1-based) narrows "active" to a step.
     */
    record Quest(String quest, String state, Optional<Integer> stage) implements DialogueCondition {
        static final MapCodec<Quest> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("quest").forGetter(Quest::quest),
                Codec.STRING.validate(s -> List.of("not_started", "active", "completed", "can_start").contains(s)
                                ? DataResult.success(s)
                                : DataResult.error(() -> "Quest state must be not_started, active, completed or can_start"))
                        .optionalFieldOf("state", "active").forGetter(Quest::state),
                Codec.intRange(1, 64).optionalFieldOf("stage").forGetter(Quest::stage)
        ).apply(i, Quest::new));

        public String type() {
            return "quest";
        }

        public boolean test(ServerPlayer player, NpcEntity npc) {
            return switch (state) {
                case "not_started" -> !QuestManager.isActive(player, quest) && !QuestManager.hasCompleted(player, quest);
                case "completed" -> !QuestManager.isActive(player, quest) && QuestManager.hasCompleted(player, quest);
                case "can_start" -> QuestManager.canStart(player, quest, npc);
                default -> QuestManager.isActive(player, quest)
                        && stage.map(s -> QuestManager.stageOf(player, quest) == s).orElse(true);
            };
        }
    }

    Map<String, MapCodec<? extends DialogueCondition>> TYPES = Map.of(
            "quest", Quest.CODEC,
            "flag", Flag.CODEC,
            "has_item", HasItem.CODEC,
            "can_edit", CanEdit.CODEC,
            "chance", Chance.CODEC,
            "is_companion", IsCompanion.CODEC,
            "faction_member", FactionMember.CODEC,
            "reputation", Reputation.CODEC,
            "can_join_faction", CanJoinFaction.CODEC,
            "standing", Standing.CODEC);

    Codec<String> TYPE_CODEC = Codec.STRING.validate(t -> TYPES.containsKey(t)
            ? DataResult.success(t)
            : DataResult.error(() -> "Unknown condition type '" + t + "'. Known: " + TYPES.keySet()));

    Codec<DialogueCondition> CODEC = TYPE_CODEC.dispatch("type", DialogueCondition::type, TYPES::get);
}
