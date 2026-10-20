package com.npcmaker.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.npcmaker.NpcPermissions;
import com.npcmaker.content.ContentFolder;
import com.npcmaker.content.NpcContent;
import com.npcmaker.faction.PlayerFactions;
import com.npcmaker.network.ContentPayloads;
import com.npcmaker.network.EditorResultPayload;
import com.npcmaker.network.NpcNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The server side of the quest builder and faction editor: an index of what exists (for pickers), sending a file,
 * and saving one. Quests and factions are world-wide, so saving has no "only this NPC" choice. Only builders.
 */
public final class ContentEditing {

    private ContentEditing() {
    }

    public static void sendIndex(ServerPlayer player) {
        if (!NpcPermissions.canEdit(player)) {
            return;
        }
        List<ContentPayloads.Entry> npcs = new ArrayList<>();
        NpcContent.NPCS.all().forEach((id, def) -> npcs.add(new ContentPayloads.Entry(id, def.displayName(), "")));
        List<ContentPayloads.Entry> factions = new ArrayList<>();
        NpcContent.FACTIONS.all().forEach((id, def) -> factions.add(new ContentPayloads.Entry(id, def.name(), def.color())));
        List<ContentPayloads.Entry> quests = new ArrayList<>();
        NpcContent.QUESTS.all().forEach((id, def) -> quests.add(new ContentPayloads.Entry(id, def.title(), def.giver().orElse(""))));
        NpcNetworking.sendToPlayer(player, new ContentPayloads.Index(npcs, factions, quests));
    }

    public static void request(ServerPlayer player, ContentPayloads.Request request) {
        ContentFolder<?> folder = folder(request.kind());
        if (!NpcPermissions.canEdit(player) || folder == null) {
            return;
        }
        String raw = folder.readRaw(request.id()).map(JsonElement::toString).orElse("");
        NpcNetworking.sendToPlayer(player, new ContentPayloads.File(request.kind(), request.id(), raw));
    }

    public static void save(ServerPlayer player, ContentPayloads.Save save) {
        ContentFolder<?> folder = folder(save.kind());
        if (!NpcPermissions.canEdit(player) || folder == null) {
            return;
        }
        JsonElement raw;
        try {
            raw = JsonParser.parseString(save.rawJson());
        } catch (Exception e) {
            reply(player, false, "Couldn't read the editor's data: " + e.getMessage());
            return;
        }
        String id = save.create() ? freeId(folder, slug(save.id())) : save.id();
        try {
            folder.saveRaw(id, raw);
        } catch (IOException e) {
            reply(player, false, "Not saved: " + e.getMessage());
            return;
        }
        String what = save.kind().equals(ContentPayloads.QUEST) ? "quest" : "faction";
        reply(player, true, (save.create() ? "Created " : "Saved ") + what + " '" + id + "'.");
        NpcNetworking.sendToPlayer(player, new ContentPayloads.Saved(save.kind(), id));
        sendIndex(player);
        if (save.kind().equals(ContentPayloads.FACTION)) {
            // names, colours and standings show on everyone's Factions screen
            player.level().getServer().getPlayerList().getPlayers().forEach(PlayerFactions::sendStandings);
        }
    }

    private static @Nullable ContentFolder<?> folder(String kind) {
        return switch (kind) {
            case ContentPayloads.QUEST -> NpcContent.QUESTS;
            case ContentPayloads.FACTION -> NpcContent.FACTIONS;
            default -> null;
        };
    }

    /** "The Baker's Errand" → "the_baker_s_errand". */
    private static String slug(String wanted) {
        String s = wanted.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.length() > 40) {
            s = s.substring(0, 40).replaceAll("_+$", "");
        }
        return s.isEmpty() ? "new" : s;
    }

    private static String freeId(ContentFolder<?> folder, String base) {
        if (!folder.exists(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            if (!folder.exists(base + "_" + n)) {
                return base + "_" + n;
            }
        }
    }

    private static void reply(ServerPlayer player, boolean ok, String message) {
        NpcNetworking.sendToPlayer(player, new EditorResultPayload(ok, message));
    }
}
