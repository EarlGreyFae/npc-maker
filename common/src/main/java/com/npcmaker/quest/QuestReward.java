package com.npcmaker.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.npcmaker.dialogue.DialogueActions;
import com.npcmaker.faction.PlayerFactions;
import com.npcmaker.player.PlayerData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;

/**
 * Something a player gets for finishing a quest. In JSON: {@code {"type": "item", "item": {"id": "minecraft:diamond",
 * "count": 2}}}. Items use the vanilla stack format, so any mod's items (with enchantments, names and other
 * components) work.
 */
public sealed interface QuestReward {

    String type();

    void give(ServerPlayer player);

    /** One line for the "Quest complete" card, or empty to show nothing. */
    Optional<Component> describe();

    record Item(ItemStack item) implements QuestReward {
        static final MapCodec<Item> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                ItemStack.CODEC.fieldOf("item").forGetter(Item::item)
        ).apply(i, Item::new));

        public String type() {
            return "item";
        }

        public void give(ServerPlayer player) {
            DialogueActions.give(player, item.copy());
        }

        public Optional<Component> describe() {
            return Optional.of(Component.literal(item.getCount() + "× ").append(item.getHoverName()));
        }
    }

    /** Experience points, or levels with {@code "levels": true}. */
    record Xp(int amount, boolean levels) implements QuestReward {
        static final MapCodec<Xp> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.intRange(1, 1000000).fieldOf("amount").forGetter(Xp::amount),
                Codec.BOOL.optionalFieldOf("levels", false).forGetter(Xp::levels)
        ).apply(i, Xp::new));

        public String type() {
            return "xp";
        }

        public void give(ServerPlayer player) {
            if (levels) {
                player.giveExperienceLevels(amount);
            } else {
                player.giveExperiencePoints(amount);
            }
        }

        public Optional<Component> describe() {
            return Optional.of(Component.literal("+" + amount + (levels ? " levels" : " XP")).withStyle(ChatFormatting.GREEN));
        }
    }

    record Reputation(String faction, int amount) implements QuestReward {
        static final MapCodec<Reputation> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("faction").forGetter(Reputation::faction),
                Codec.intRange(-200, 200).fieldOf("amount").forGetter(Reputation::amount)
        ).apply(i, Reputation::new));

        public String type() {
            return "reputation";
        }

        public void give(ServerPlayer player) {
            PlayerFactions.changeReputation(player, faction, amount);
        }

        public Optional<Component> describe() {
            return Optional.empty(); // the reputation card already shows it
        }
    }

    record JoinFaction(String faction) implements QuestReward {
        static final MapCodec<JoinFaction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("faction").forGetter(JoinFaction::faction)
        ).apply(i, JoinFaction::new));

        public String type() {
            return "join_faction";
        }

        public void give(ServerPlayer player) {
            PlayerFactions.join(player, faction);
        }

        public Optional<Component> describe() {
            return Optional.of(Component.literal("Joined ").append(PlayerFactions.name(faction)));
        }
    }

    record SetFlag(String flag, boolean value) implements QuestReward {
        static final MapCodec<SetFlag> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("flag").forGetter(SetFlag::flag),
                Codec.BOOL.optionalFieldOf("value", true).forGetter(SetFlag::value)
        ).apply(i, SetFlag::new));

        public String type() {
            return "set_flag";
        }

        public void give(ServerPlayer player) {
            PlayerData.get(player).setFlag(flag, value);
        }

        public Optional<Component> describe() {
            return Optional.empty();
        }
    }

    /** Starts a follow-up quest. */
    record StartQuest(String quest) implements QuestReward {
        static final MapCodec<StartQuest> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("quest").forGetter(StartQuest::quest)
        ).apply(i, StartQuest::new));

        public String type() {
            return "start_quest";
        }

        public void give(ServerPlayer player) {
            QuestManager.start(player, quest, null, true);
        }

        public Optional<Component> describe() {
            return Optional.empty(); // gets its own "Quest started" card
        }
    }

    /** Runs a server command at the player, with full permissions ({player} is replaced with their name). */
    record Command(String command) implements QuestReward {
        static final MapCodec<Command> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("command").forGetter(Command::command)
        ).apply(i, Command::new));

        public String type() {
            return "command";
        }

        public void give(ServerPlayer player) {
            ServerLevel level = player.level();
            var source = level.getServer().createCommandSourceStack()
                    .withEntity(player)
                    .withLevel(level)
                    .withPosition(player.position())
                    .withPermission(PermissionSet.ALL_PERMISSIONS)
                    .withSuppressedOutput();
            level.getServer().getCommands().performPrefixedCommand(source,
                    command.replace("{player}", player.getGameProfile().name()));
        }

        public Optional<Component> describe() {
            return Optional.empty();
        }
    }

    Map<String, MapCodec<? extends QuestReward>> TYPES = Map.of(
            "item", Item.CODEC,
            "xp", Xp.CODEC,
            "reputation", Reputation.CODEC,
            "join_faction", JoinFaction.CODEC,
            "set_flag", SetFlag.CODEC,
            "start_quest", StartQuest.CODEC,
            "command", Command.CODEC);

    Codec<String> TYPE_CODEC = Codec.STRING.validate(t -> TYPES.containsKey(t)
            ? DataResult.success(t)
            : DataResult.error(() -> "Unknown reward type '" + t + "'. Known: " + TYPES.keySet()));

    Codec<QuestReward> CODEC = TYPE_CODEC.dispatch("type", QuestReward::type, TYPES::get);
}
