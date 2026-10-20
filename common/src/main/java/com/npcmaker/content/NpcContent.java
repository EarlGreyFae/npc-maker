package com.npcmaker.content;

import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.npcmaker.Constants;
import com.npcmaker.data.Appearance;
import com.npcmaker.data.ArmStyle;
import com.npcmaker.data.Behavior;
import com.npcmaker.data.CombatStance;
import com.npcmaker.data.Equipment;
import com.npcmaker.data.ItemUse;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import com.npcmaker.data.Speech;
import com.npcmaker.data.MovementMode;
import com.npcmaker.data.NpcDefinition;
import com.npcmaker.data.SoundSpec;
import com.npcmaker.data.Sounds;
import com.npcmaker.data.Stats;
import com.npcmaker.data.Voice;
import com.npcmaker.faction.FactionDefinition;
import com.npcmaker.faction.Relation;
import com.npcmaker.faction.StandingTier;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

import com.npcmaker.dialogue.DialogueTree;
import com.npcmaker.quest.QuestDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** All NPC Maker content for the running server, loaded from {@code <world>/npcmaker/}. */
public final class NpcContent {

    public static final ContentFolder<NpcDefinition> NPCS = new ContentFolder<>("npc", NpcDefinition.CODEC, NpcPresets::applyTemplate);
    public static final ContentFolder<FactionDefinition> FACTIONS = new ContentFolder<>("faction", FactionDefinition.CODEC);
    public static final ContentFolder<DialogueTree> DIALOGUES = new ContentFolder<>("dialogue", DialogueTree.CODEC);
    public static final ContentFolder<QuestDefinition> QUESTS = new ContentFolder<>("quest", QuestDefinition.CODEC);

    /** Example files bundled in the jar, copied into a world's empty folders. */
    private static final String[] EXAMPLE_DIALOGUES = {"dawnguard_sentry"};
    private static final String[] EXAMPLE_QUESTS = {"restless_dead"};

    private NpcContent() {
    }

    public static Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(Constants.MOD_ID);
    }

    public static void load(MinecraftServer server) {
        Path root = root(server);
        DynamicOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        copyExamplesIfEmpty(root.resolve("dialogues"), "dialogues", EXAMPLE_DIALOGUES);
        DIALOGUES.load(root.resolve("dialogues"), ops);
        copyExamplesIfEmpty(root.resolve("quests"), "quests", EXAMPLE_QUESTS);
        QUESTS.load(root.resolve("quests"), ops);
        FACTIONS.load(root.resolve("factions"), ops);
        NPCS.load(root.resolve("npcs"), ops);
        if (NPCS.isEmpty() && FACTIONS.isEmpty()) {
            writeExamples();
        }
    }

    private static void copyExamplesIfEmpty(Path dir, String kind, String[] names) {
        try {
            Files.createDirectories(dir);
            try (Stream<Path> existing = Files.list(dir)) {
                if (existing.findAny().isPresent()) {
                    return;
                }
            }
            for (String name : names) {
                try (InputStream in = NpcContent.class.getResourceAsStream("/npcmaker_examples/" + kind + "/" + name + ".json")) {
                    if (in == null) {
                        Constants.LOG.warn("Bundled example {}/{} is missing from the jar", kind, name);
                        continue;
                    }
                    Files.copy(in, dir.resolve(name + ".json"));
                }
            }
        } catch (IOException e) {
            Constants.LOG.error("Could not write example {}", kind, e);
        }
    }

    /** First run in a world: write a small example set so admins have something to copy from. */
    private static void writeExamples() {
        try {
            FACTIONS.save("dawnguard", FactionDefinition.of("Dawnguard", "#C8A24A", List.of(),
                    Map.of("undead", Relation.HOSTILE, "vampire_court", Relation.HOSTILE), Relation.NEUTRAL,
                    List.of("vampire_court")));
            FactionDefinition undead = FactionDefinition.of("Undead", "#5A8A3C", List.of("minecraft:undead"),
                    Map.of("dawnguard", Relation.HOSTILE, "vampire_court", Relation.ALLIED), Relation.HOSTILE, List.of());
            FACTIONS.save("undead", new FactionDefinition(undead.name(), undead.color(), undead.entityTypes(), undead.entityTags(),
                    undead.relations(), undead.defaultPlayerRelation(), undead.hostileBelow(), undead.alliedAbove(),
                    undead.excludes(), undead.reputationChanges(), undead.standings(), true));
            FactionDefinition court = FactionDefinition.of("Vampire Court", "#8B1E3F", List.of(),
                    Map.of("dawnguard", Relation.HOSTILE, "undead", Relation.ALLIED), Relation.NEUTRAL, List.of("dawnguard"));
            FACTIONS.save("vampire_court", new FactionDefinition(court.name(), court.color(), court.entityTypes(), court.entityTags(),
                    court.relations(), court.defaultPlayerRelation(), court.hostileBelow(), court.alliedAbove(),
                    court.excludes(), court.reputationChanges(), List.of(
                    new StandingTier("Elder", 75, "#E8C46A"),
                    new StandingTier("Kin", 50, "#C0397A"),
                    new StandingTier("Fledgling", 20, "#B05C8A"),
                    new StandingTier("Guest", -19, "#BBBBBB"),
                    new StandingTier("Thrall", -49, "#8A6A7A"),
                    new StandingTier("Prey", FactionDefinition.MIN_REPUTATION, "#E04848")), false));
            NPCS.save("example_guard", new NpcDefinition("Dawnguard Sentry", Optional.empty(),
                    new Appearance(Optional.empty(), Optional.empty(), Optional.empty(), ArmStyle.AUTO, "#C8A24A", true, 1.0F),
                    new Stats(40.0, 6.0, 8.0, 0.3, 0.2),
                    new Behavior(MovementMode.STATIONARY, 8, CombatStance.GUARD, false, true, true, 60, ItemUse.DEFAULT, true),
                    new Equipment(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.SHIELD),
                            new ItemStack(Items.IRON_HELMET), new ItemStack(Items.IRON_CHESTPLATE),
                            ItemStack.EMPTY, new ItemStack(Items.IRON_BOOTS), 0.0F),
                    List.of(new ItemStack(Items.BOW), new ItemStack(Items.ARROW, 16),
                            PotionContents.createItemStack(Items.POTION, Potions.HEALING),
                            new ItemStack(Items.BREAD, 8), new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.TORCH, 16)),
                    new Sounds(Optional.empty(),
                            Optional.of(new SoundSpec(Identifier.withDefaultNamespace("entity.villager.ambient"), 1.0F, 0.8F)),
                            Optional.empty(), Optional.empty(),
                            new Voice(Identifier.withDefaultNamespace("block.note_block.bass"), 0.8F, 0.25F, true)),
                    List.of("dawnguard"), Optional.of("dawnguard_sentry"),
                    List.of("Halt, traveller.", "The night is full of the restless dead. Stay close to the torches."),
                    new Speech(true,
                            List.of("Halt, traveller.", "Stay where I can see you."),
                            List.of("<i>Quiet tonight...</i>", "Keep your torch lit.", "I smell grave dirt on the wind."),
                            List.of("Hey! Watch it!", "Careful with that blade."),
                            true, 8, 25, 120),
                    List.of(), com.npcmaker.data.Effects.NONE));
            Constants.LOG.info("Wrote example NPC Maker content");
        } catch (IOException e) {
            Constants.LOG.error("Could not write example content", e);
        }
    }
}
