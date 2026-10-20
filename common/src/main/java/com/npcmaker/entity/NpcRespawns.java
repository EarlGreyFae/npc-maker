package com.npcmaker.entity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.npcmaker.Constants;
import com.npcmaker.content.NpcContent;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * NPCs that were killed and will come back. Kept in {@code <world>/npcmaker/respawns.json} so a server restart
 * doesn't lose them. Respawn time counts in overworld game ticks.
 */
public final class NpcRespawns {

    private record Pending(NpcInstance instance, ResourceKey<Level> dimension, long dueTick) {
        static final Codec<Pending> CODEC = RecordCodecBuilder.create(i -> i.group(
                NpcInstance.CODEC.fieldOf("npc").forGetter(Pending::instance),
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Pending::dimension),
                Codec.LONG.fieldOf("due_tick").forGetter(Pending::dueTick)
        ).apply(i, Pending::new));
    }

    private static final Codec<List<Pending>> LIST_CODEC = Pending.CODEC.listOf();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final List<Pending> PENDING = new ArrayList<>();
    private static Path file;

    private NpcRespawns() {
    }

    public static void load(MinecraftServer server) {
        PENDING.clear();
        file = NpcContent.root(server).resolve("respawns.json");
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            LIST_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader))
                    .resultOrPartial(err -> Constants.LOG.error("Invalid respawns.json: {}", err))
                    .ifPresent(PENDING::addAll);
        } catch (Exception e) {
            Constants.LOG.error("Could not read {}", file, e);
        }
    }

    public static void schedule(ServerLevel level, NpcInstance instance, int seconds) {
        // Companions respawn as normal NPCs at their last home.
        NpcInstance clean = instance.withOwner(Optional.empty());
        long due = level.getServer().overworld().getGameTime() + seconds * 20L;
        PENDING.add(new Pending(clean, level.dimension(), due));
        save();
    }

    /** Called once a second. NPCs whose home chunk isn't loaded wait until it is. */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        boolean changed = false;
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.dueTick() > now) {
                continue;
            }
            ServerLevel level = server.getLevel(p.dimension());
            if (level == null) {
                Constants.LOG.warn("Dropping respawn of '{}': dimension {} no longer exists", p.instance().template(), p.dimension().identifier());
                it.remove();
                changed = true;
                continue;
            }
            if (!level.isLoaded(BlockPos.containing(p.instance().home()))) {
                continue;
            }
            NpcEntity.place(level, p.instance());
            it.remove();
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    public static void save() {
        if (file == null) {
            return;
        }
        try {
            JsonElement json = LIST_CODEC.encodeStart(JsonOps.INSTANCE, PENDING).getOrThrow(IOException::new);
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
        } catch (IOException e) {
            Constants.LOG.error("Could not save {}", file, e);
        }
    }
}
