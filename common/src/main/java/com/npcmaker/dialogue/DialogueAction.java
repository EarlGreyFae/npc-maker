package com.npcmaker.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.npcmaker.data.SoundSpec;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.faction.PlayerFactions;
import com.npcmaker.player.PlayerData;
import com.npcmaker.quest.QuestManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;

/**
 * Something that happens when the player picks a reply. In JSON: {@code {"type": "give_item", "item": {...}}}.
 * Actions run in order, before moving to the reply's next node.
 */
public sealed interface DialogueAction {

    String type();

    void run(ServerPlayer player, NpcEntity npc);

    record SetFlag(String flag, boolean value) implements DialogueAction {
        static final MapCodec<SetFlag> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("flag").forGetter(SetFlag::flag),
                Codec.BOOL.optionalFieldOf("value", true).forGetter(SetFlag::value)
        ).apply(i, SetFlag::new));

        public String type() {
            return "set_flag";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            PlayerData.get(player).setFlag(flag, value);
        }
    }

    /** Gives an item stack (any item from any mod, with components); drops it at the player's feet if full. */
    record GiveItem(ItemStack item) implements DialogueAction {
        static final MapCodec<GiveItem> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                ItemStack.CODEC.fieldOf("item").forGetter(GiveItem::item)
        ).apply(i, GiveItem::new));

        public String type() {
            return "give_item";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            DialogueActions.give(player, item.copy());
        }
    }

    /** Takes up to {@code count} of an item. Pair it with a has_item condition so the reply only shows when possible. */
    record TakeItem(Item item, int count) implements DialogueAction {
        static final MapCodec<TakeItem> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(TakeItem::item),
                Codec.intRange(1, 10000).optionalFieldOf("count", 1).forGetter(TakeItem::count)
        ).apply(i, TakeItem::new));

        public String type() {
            return "take_item";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            DialogueActions.take(player, item, count);
        }
    }

    /**
     * Runs a server command as the NPC, with full permissions. Dialogue files live in the world folder, so only
     * people with access to the server files (or the op-only editor) can write these.
     */
    record RunCommand(String command) implements DialogueAction {
        static final MapCodec<RunCommand> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("command").forGetter(RunCommand::command)
        ).apply(i, RunCommand::new));

        public String type() {
            return "run_command";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            DialogueActions.runCommand(player, npc, command);
        }
    }

    record PlaySound(SoundSpec sound) implements DialogueAction {
        static final MapCodec<PlaySound> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                SoundSpec.CODEC.fieldOf("sound").forGetter(PlaySound::sound)
        ).apply(i, PlaySound::new));

        public String type() {
            return "play_sound";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            npc.playSound(sound.event(), sound.volume(), sound.pitch());
        }
    }

    /** The NPC joins the player as a companion ("Join me?"). */
    record BecomeCompanion() implements DialogueAction {
        static final MapCodec<BecomeCompanion> CODEC = MapCodec.unit(new BecomeCompanion());

        public String type() {
            return "become_companion";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            npc.setOwner(Optional.of(player.getUUID()));
        }
    }

    /** The NPC stops following the player and stays where it is. */
    record DismissCompanion() implements DialogueAction {
        static final MapCodec<DismissCompanion> CODEC = MapCodec.unit(new DismissCompanion());

        public String type() {
            return "dismiss_companion";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            if (npc.isCompanionOf(player)) {
                npc.setOwner(Optional.empty());
            }
        }
    }

    /** The player joins a faction (refused with a message if they're in a faction it excludes). */
    record JoinFaction(String faction) implements DialogueAction {
        static final MapCodec<JoinFaction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("faction").forGetter(JoinFaction::faction)
        ).apply(i, JoinFaction::new));

        public String type() {
            return "join_faction";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            PlayerFactions.join(player, faction);
        }
    }

    record LeaveFaction(String faction) implements DialogueAction {
        static final MapCodec<LeaveFaction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("faction").forGetter(LeaveFaction::faction)
        ).apply(i, LeaveFaction::new));

        public String type() {
            return "leave_faction";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            PlayerFactions.leave(player, faction);
        }
    }

    /** Adds (or with a negative amount, removes) reputation with a faction. */
    record ChangeReputation(String faction, int amount) implements DialogueAction {
        static final MapCodec<ChangeReputation> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("faction").forGetter(ChangeReputation::faction),
                Codec.intRange(-200, 200).fieldOf("amount").forGetter(ChangeReputation::amount)
        ).apply(i, ChangeReputation::new));

        public String type() {
            return "change_reputation";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            PlayerFactions.changeReputation(player, faction, amount);
        }
    }

    /** Gives the player a quest (only if they could take it: see the "quest" condition's can_start). */
    record StartQuest(String quest) implements DialogueAction {
        static final MapCodec<StartQuest> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("quest").forGetter(StartQuest::quest)
        ).apply(i, StartQuest::new));

        public String type() {
            return "start_quest";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            QuestManager.start(player, quest, npc, false);
        }
    }

    /**
     * Shows the quest offer card (title, story, first step, rewards) with Accept / Decline, if the player could take
     * the quest. Usually the last action of a reply with no "goto", so the card replaces the conversation.
     */
    record OfferQuest(String quest) implements DialogueAction {
        static final MapCodec<OfferQuest> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("quest").forGetter(OfferQuest::quest)
        ).apply(i, OfferQuest::new));

        public String type() {
            return "offer_quest";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            QuestManager.offer(player, quest, npc);
        }
    }

    /** Ticks off a quest's custom objective (all custom objectives of the current step when no objective is given). */
    record CompleteObjective(String quest, Optional<String> objective) implements DialogueAction {
        static final MapCodec<CompleteObjective> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("quest").forGetter(CompleteObjective::quest),
                Codec.STRING.optionalFieldOf("objective").forGetter(CompleteObjective::objective)
        ).apply(i, CompleteObjective::new));

        public String type() {
            return "complete_objective";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            QuestManager.completeCustom(player, quest, objective);
        }
    }

    /** Drops a quest the player is on (even one they couldn't abandon from the journal). */
    record AbandonQuest(String quest) implements DialogueAction {
        static final MapCodec<AbandonQuest> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("quest").forGetter(AbandonQuest::quest)
        ).apply(i, AbandonQuest::new));

        public String type() {
            return "abandon_quest";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            QuestManager.abandon(player, quest, true);
        }
    }

    /** The NPC plays one of the built-in animations (wave, bow, cheer, point, salute, sit, sleep, read, work). */
    record PlayAnimation(String animation) implements DialogueAction {
        static final MapCodec<PlayAnimation> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("animation").forGetter(PlayAnimation::animation)
        ).apply(i, PlayAnimation::new));

        public String type() {
            return "play_animation";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            npc.playAnimation(animation);
        }
    }

    /** A burst of particles around the NPC. */
    record Particles(com.npcmaker.data.ParticleSpec particle) implements DialogueAction {
        static final MapCodec<Particles> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                com.npcmaker.data.ParticleSpec.CODEC.fieldOf("particle").forGetter(Particles::particle)
        ).apply(i, Particles::new));

        public String type() {
            return "particles";
        }

        public void run(ServerPlayer player, NpcEntity npc) {
            particle.spawn(player.level(), npc);
        }
    }

    Map<String, MapCodec<? extends DialogueAction>> TYPES = Map.ofEntries(
            Map.entry("play_animation", PlayAnimation.CODEC),
            Map.entry("particles", Particles.CODEC),
            Map.entry("start_quest", StartQuest.CODEC),
            Map.entry("offer_quest", OfferQuest.CODEC),
            Map.entry("complete_objective", CompleteObjective.CODEC),
            Map.entry("abandon_quest", AbandonQuest.CODEC),
            Map.entry("set_flag", SetFlag.CODEC),
            Map.entry("give_item", GiveItem.CODEC),
            Map.entry("take_item", TakeItem.CODEC),
            Map.entry("run_command", RunCommand.CODEC),
            Map.entry("play_sound", PlaySound.CODEC),
            Map.entry("become_companion", BecomeCompanion.CODEC),
            Map.entry("dismiss_companion", DismissCompanion.CODEC),
            Map.entry("join_faction", JoinFaction.CODEC),
            Map.entry("leave_faction", LeaveFaction.CODEC),
            Map.entry("change_reputation", ChangeReputation.CODEC));

    Codec<String> TYPE_CODEC = Codec.STRING.validate(t -> TYPES.containsKey(t)
            ? DataResult.success(t)
            : DataResult.error(() -> "Unknown action type '" + t + "'. Known: " + TYPES.keySet()));

    Codec<DialogueAction> CODEC = TYPE_CODEC.dispatch("type", DialogueAction::type, TYPES::get);
}
