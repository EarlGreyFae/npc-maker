package com.npcmaker.player;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.npcmaker.Constants;
import com.npcmaker.content.NpcContent;
import com.npcmaker.faction.FactionDefinition;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * What NPC Maker remembers about each player: dialogue flags, faction memberships, faction reputation and quests
 * (active, finished, tracked). One file per player in {@code <world>/npcmaker/players/<uuid>.json}, saved whenever it changes.
 */
public final class PlayerData {

    /** An active quest: which stage it's on and the count for each of that stage's objectives. */
    public record QuestProgress(int stage, List<Integer> progress) {
        public static final Codec<QuestProgress> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("stage").forGetter(QuestProgress::stage),
                Codec.INT.listOf().fieldOf("progress").forGetter(QuestProgress::progress)
        ).apply(i, QuestProgress::new));

        public int get(int objective) {
            return objective < progress.size() ? progress.get(objective) : 0;
        }
    }

    private static final Codec<PlayerData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.listOf().optionalFieldOf("flags", List.of()).forGetter(d -> List.copyOf(d.flags)),
            Codec.STRING.listOf().optionalFieldOf("factions", List.of()).forGetter(d -> List.copyOf(d.factions)),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("reputation", Map.of()).forGetter(d -> d.reputation),
            Codec.unboundedMap(Codec.STRING, QuestProgress.CODEC).optionalFieldOf("quests", Map.of()).forGetter(d -> d.quests),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("completed_quests", Map.of()).forGetter(d -> d.completedQuests),
            Codec.STRING.optionalFieldOf("tracked_quest", "").forGetter(d -> d.trackedQuest),
            Codec.STRING.listOf().optionalFieldOf("pending_rewards", List.of()).forGetter(d -> List.copyOf(d.pendingRewards))
    ).apply(i, PlayerData::new));
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<UUID, PlayerData> CACHE = new HashMap<>();
    private static Path dir;

    private final TreeSet<String> flags;
    private final TreeSet<String> factions;
    private final TreeMap<String, Integer> reputation;
    /** Active quests in the order they were started. */
    private final LinkedHashMap<String, QuestProgress> quests;
    /** Finished quests and when they were last finished (epoch millis, for repeat cooldowns). */
    private final TreeMap<String, Long> completedQuests;
    private String trackedQuest;
    /** Finished quests whose "choose one" reward hasn't been picked yet. */
    private final LinkedHashSet<String> pendingRewards;
    private UUID owner;

    private PlayerData(List<String> flags, List<String> factions, Map<String, Integer> reputation,
                       Map<String, QuestProgress> quests, Map<String, Long> completedQuests, String trackedQuest,
                       List<String> pendingRewards) {
        this.pendingRewards = new LinkedHashSet<>(pendingRewards);
        this.flags = new TreeSet<>(flags);
        this.factions = new TreeSet<>(factions);
        this.reputation = new TreeMap<>(reputation);
        this.quests = new LinkedHashMap<>(quests);
        this.completedQuests = new TreeMap<>(completedQuests);
        this.trackedQuest = trackedQuest;
    }

    public static void init(MinecraftServer server) {
        CACHE.clear();
        dir = NpcContent.root(server).resolve("players");
    }

    public static PlayerData get(ServerPlayer player) {
        return CACHE.computeIfAbsent(player.getUUID(), PlayerData::load);
    }

    private static PlayerData load(UUID id) {
        PlayerData data = new PlayerData(List.of(), List.of(), Map.of(), Map.of(), Map.of(), "", List.of());
        Path file = dir.resolve(id + ".json");
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                data = CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader))
                        .resultOrPartial(err -> Constants.LOG.error("Invalid player data {}: {}", file, err))
                        .orElse(data);
            } catch (Exception e) {
                Constants.LOG.error("Could not read {}", file, e);
            }
        }
        data.owner = id;
        return data;
    }

    // ---- flags --------------------------------------------------------------------------------------------

    public boolean hasFlag(String flag) {
        return flags.contains(flag);
    }

    public void setFlag(String flag, boolean value) {
        if (value ? flags.add(flag) : flags.remove(flag)) {
            save();
        }
    }

    // ---- factions -----------------------------------------------------------------------------------------

    public Set<String> factions() {
        return Collections.unmodifiableSet(factions);
    }

    public boolean isMember(String faction) {
        return factions.contains(faction);
    }

    /** Adds a membership without any checks; use {@link com.npcmaker.faction.PlayerFactions#join} for rules. */
    public boolean addFaction(String faction) {
        boolean added = factions.add(faction);
        if (added) {
            save();
        }
        return added;
    }

    public boolean removeFaction(String faction) {
        boolean removed = factions.remove(faction);
        if (removed) {
            save();
        }
        return removed;
    }

    public int reputation(String faction) {
        return reputation.getOrDefault(faction, 0);
    }

    /** Sets reputation, clamped to -100..100. Returns the new value. */
    public int setReputation(String faction, int value) {
        int clamped = Mth.clamp(value, FactionDefinition.MIN_REPUTATION, FactionDefinition.MAX_REPUTATION);
        Integer old = clamped == 0 ? reputation.remove(faction) : reputation.put(faction, clamped);
        if (old == null ? clamped != 0 : old != clamped) {
            save();
        }
        return clamped;
    }

    public Map<String, Integer> allReputation() {
        return Collections.unmodifiableMap(reputation);
    }

    // ---- quests -------------------------------------------------------------------------------------------
    // Raw storage only; QuestManager applies the rules (stages, rewards, syncing to the client).

    public Map<String, QuestProgress> activeQuests() {
        return Collections.unmodifiableMap(quests);
    }

    public Optional<QuestProgress> quest(String id) {
        return Optional.ofNullable(quests.get(id));
    }

    public void setQuest(String id, QuestProgress progress) {
        quests.put(id, progress);
        save();
    }

    public void removeQuest(String id) {
        if (quests.remove(id) != null) {
            save();
        }
    }

    public Map<String, Long> completedQuests() {
        return Collections.unmodifiableMap(completedQuests);
    }

    public void markCompleted(String id, long when) {
        completedQuests.put(id, when);
        save();
    }

    public void forgetCompleted(String id) {
        if (completedQuests.remove(id) != null) {
            save();
        }
    }

    public String trackedQuest() {
        return trackedQuest;
    }

    public void setTrackedQuest(String id) {
        if (!trackedQuest.equals(id)) {
            trackedQuest = id;
            save();
        }
    }

    public Set<String> pendingRewards() {
        return Collections.unmodifiableSet(pendingRewards);
    }

    public void setPendingReward(String quest, boolean pending) {
        if (pending ? pendingRewards.add(quest) : pendingRewards.remove(quest)) {
            save();
        }
    }

    private void save() {
        try {
            JsonElement json = CODEC.encodeStart(JsonOps.INSTANCE, this).getOrThrow(IOException::new);
            Files.createDirectories(dir);
            try (Writer writer = Files.newBufferedWriter(dir.resolve(owner + ".json"), StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
        } catch (IOException e) {
            Constants.LOG.error("Could not save player data for {}", owner, e);
        }
    }
}
