package com.npcmaker.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.npcmaker.NpcPermissions;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcEntityTypes;
import com.npcmaker.network.EditorResultPayload;
import com.npcmaker.network.LivePayloads;
import com.npcmaker.network.NpcNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The server side of the Live list: every NPC loaded right now, in every dimension, with edit, teleport and remove.
 * Works like Enemy Maker's Live list (your dimension first, nearest first). Only operators and creative players.
 */
public final class LiveNpcs {

    private LiveNpcs() {
    }

    public static void handle(ServerPlayer player, LivePayloads.Action action) {
        if (!NpcPermissions.canEdit(player)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        switch (action.action()) {
            case LivePayloads.KEY -> {
                NpcEntity looked = NpcEditing.lookedAt(player);
                if (looked != null) {
                    NpcEditing.open(player, looked);
                } else {
                    send(player, true);
                }
            }
            case LivePayloads.LIST -> send(player, false);
            case LivePayloads.EDIT -> find(server, action.uuid()).ifPresentOrElse(npc -> NpcEditing.open(player, npc), () -> gone(player));
            case LivePayloads.TELEPORT -> find(server, action.uuid()).ifPresentOrElse(npc -> {
                player.teleportTo((ServerLevel) npc.level(), npc.getX(), npc.getY(), npc.getZ(), Set.of(),
                        player.getYRot(), player.getXRot(), true);
                reply(player, true, "Teleported to " + npc.getName().getString() + ".");
                send(player, false);
            }, () -> gone(player));
            case LivePayloads.REMOVE -> find(server, action.uuid()).ifPresentOrElse(npc -> {
                String name = npc.getName().getString();
                npc.discard();
                reply(player, true, "Removed " + name + ".");
                send(player, false);
            }, () -> gone(player));
            default -> reply(player, false, "Unknown action '" + action.action() + "'.");
        }
    }

    /** Every loaded NPC in every dimension: the player's dimension first, nearest first. */
    public static void send(ServerPlayer player, boolean open) {
        List<NpcEntity> all = new ArrayList<>();
        for (ServerLevel level : player.level().getServer().getAllLevels()) {
            all.addAll(level.getEntities(NpcEntityTypes.npc(), npc -> npc.isAlive() && npc.instance() != null));
        }
        all.sort(Comparator.comparing((NpcEntity npc) -> npc.level() != player.level())
                .thenComparingDouble(npc -> npc.level() == player.level() ? npc.distanceToSqr(player) : 0));
        JsonArray list = new JsonArray();
        for (NpcEntity npc : all) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", npc.getUUID().toString());
            o.addProperty("id", npc.getId());
            o.addProperty("template", npc.instance().template());
            o.addProperty("name", npc.getName().getString());
            o.addProperty("dimension", npc.level().dimension().identifier().toString());
            o.addProperty("x", npc.getBlockX());
            o.addProperty("y", npc.getBlockY());
            o.addProperty("z", npc.getBlockZ());
            o.addProperty("distance", npc.level() == player.level() ? (int) Math.sqrt(npc.distanceToSqr(player)) : -1);
            o.addProperty("health", Math.round(npc.getHealth()));
            o.addProperty("max_health", Math.round(npc.getMaxHealth()));
            list.add(o);
        }
        NpcNetworking.sendToPlayer(player, new LivePayloads.Snapshot(list.toString(), open));
    }

    private static Optional<NpcEntity> find(MinecraftServer server, String uuid) {
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof NpcEntity npc && npc.isAlive() && npc.instance() != null) {
                return Optional.of(npc);
            }
        }
        return Optional.empty();
    }

    private static void gone(ServerPlayer player) {
        reply(player, false, "That NPC isn't there any more.");
        send(player, false);
    }

    private static void reply(ServerPlayer player, boolean ok, String message) {
        NpcNetworking.sendToPlayer(player, new EditorResultPayload(ok, message));
    }
}
