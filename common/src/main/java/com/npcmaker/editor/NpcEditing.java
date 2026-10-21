package com.npcmaker.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.npcmaker.NpcPermissions;
import com.npcmaker.content.NpcContent;
import com.npcmaker.content.SkinLibrary;
import com.npcmaker.network.SkinPayloads;
import com.npcmaker.network.DialoguePayloads;
import com.npcmaker.data.NpcDefinition;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcEntityTypes;
import com.npcmaker.entity.NpcInstance;
import com.npcmaker.network.EditorResultPayload;
import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.OpenEditorPayload;
import com.npcmaker.network.SaveNpcPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Optional;

/**
 * The server side of the in-game NPC editor: sends an NPC's template to an editor, and saves what comes back, either
 * to the template (every NPC using it) or to a new copy for just this NPC. Only operators and creative players.
 */
public final class NpcEditing {

    /** How far {@code /npcmaker edit} looks for the NPC you're facing. */
    private static final double LOOK_RANGE = 24.0;

    private NpcEditing() {
    }

    public static void open(ServerPlayer player, NpcEntity npc) {
        NpcInstance instance = npc.instance();
        if (!NpcPermissions.canEdit(player) || instance == null) {
            return;
        }
        String template = instance.template();
        Optional<NpcDefinition> def = NpcContent.NPCS.get(template);
        JsonElement effective = def.flatMap(NpcContent.NPCS::encode).orElseGet(JsonObject::new);
        JsonElement raw = NpcContent.NPCS.readRaw(template).orElse(effective);
        NpcNetworking.sendToPlayer(player, new SkinPayloads.Library(SkinLibrary.names(), ""));
        ContentEditing.sendIndex(player);
        NpcNetworking.sendToPlayer(player, new OpenEditorPayload(npc.getId(), template, raw.toString(), effective.toString(),
                usersOf(player.level().getServer(), template), instance.home(), instance.yaw(), instance.pitch()));
    }

    // ---- dialogue graph -----------------------------------------------------------------------------------

    /** Sends the NPC's conversation (or what's needed to start one) to the graph editor. */
    public static void openDialogue(ServerPlayer player, NpcEntity npc) {
        NpcInstance instance = npc.instance();
        if (!NpcPermissions.canEdit(player) || instance == null) {
            return;
        }
        Optional<NpcDefinition> def = NpcContent.NPCS.get(instance.template());
        String dialogueId = def.flatMap(NpcDefinition::dialogue).orElse("");
        String raw = dialogueId.isEmpty() ? "" : NpcContent.DIALOGUES.readRaw(dialogueId).map(JsonElement::toString).orElse("");
        String greeting = def.map(d -> String.join("\n", d.greeting())).orElse("");
        int users = dialogueId.isEmpty() ? 0 : dialogueUsers(player.level().getServer(), dialogueId);
        NpcNetworking.sendToPlayer(player, new DialoguePayloads.Data(npc.getId(), dialogueId, raw, greeting, users));
    }

    /**
     * Saves a conversation from the graph editor. "All" writes the shared file (and links it to the NPC's template if
     * it had none); "only this NPC" writes a copy and points just this NPC at it, giving it its own template copy too
     * if other NPCs share the template.
     */
    public static void saveDialogue(ServerPlayer player, DialoguePayloads.Save payload) {
        if (!NpcPermissions.canEdit(player)) {
            return;
        }
        if (!(findNpc(player.level().getServer(), payload.entityId()) instanceof NpcEntity npc) || npc.instance() == null) {
            reply(player, false, "That NPC is gone.");
            return;
        }
        JsonElement raw;
        try {
            raw = JsonParser.parseString(payload.rawJson());
        } catch (Exception e) {
            reply(player, false, "Couldn't read the conversation: " + e.getMessage());
            return;
        }
        MinecraftServer server = player.level().getServer();
        String template = npc.instance().template();
        String current = NpcContent.NPCS.get(template).flatMap(NpcDefinition::dialogue).orElse("");
        String savedAs;
        try {
            if (payload.scope() == SaveNpcPayload.ONLY_THIS || current.isEmpty()) {
                String base = current.isEmpty() ? template : current;
                savedAs = current.isEmpty() && !NpcContent.DIALOGUES.exists(base) ? base : freeDialogueId(base);
                NpcContent.DIALOGUES.saveRaw(savedAs, raw);
                boolean shared = usersOf(server, template) > 1;
                if (payload.scope() == SaveNpcPayload.ONLY_THIS && shared) {
                    String copy = freeIdFor(template);
                    NpcContent.NPCS.saveRaw(copy, withDialogue(template, savedAs));
                    npc.setTemplate(copy);
                } else {
                    NpcContent.NPCS.saveRaw(template, withDialogue(template, savedAs));
                    refreshUsers(server, template, false);
                }
            } else {
                savedAs = current;
                NpcContent.DIALOGUES.saveRaw(savedAs, raw);
            }
        } catch (IOException e) {
            reply(player, false, "Not saved: " + e.getMessage());
            return;
        }
        int users = dialogueUsers(server, savedAs);
        reply(player, true, "Saved conversation '" + savedAs + "' (" + users + " NPC" + (users == 1 ? "" : "s") + " use it).");
        openDialogue(player, npc);
        open(player, npc); // the NPC editor behind the graph may now show a new template
    }

    /** The NPC template file with its "dialogue" set to {@code dialogueId}. */
    private static JsonElement withDialogue(String template, String dialogueId) {
        JsonElement raw = NpcContent.NPCS.readRaw(template).orElseGet(JsonObject::new);
        JsonObject copy = raw.isJsonObject() ? raw.getAsJsonObject().deepCopy() : new JsonObject();
        copy.addProperty("dialogue", dialogueId);
        return copy;
    }

    private static String freeDialogueId(String base) {
        String stem = base.replaceAll("_\\d+$", "");
        for (int n = 2; ; n++) {
            String id = stem + "_" + n;
            if (!NpcContent.DIALOGUES.exists(id)) {
                return id;
            }
        }
    }

    /** Loaded NPCs whose template uses this conversation. */
    public static int dialogueUsers(MinecraftServer server, String dialogueId) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            count += level.getEntities(NpcEntityTypes.npc(), npc -> npc.instance() != null
                    && NpcContent.NPCS.get(npc.instance().template()).flatMap(NpcDefinition::dialogue)
                    .map(dialogueId::equals).orElse(false)).size();
        }
        return count;
    }

    // ---- skin library -------------------------------------------------------------------------------------

    /** A skin uploaded from the editor: store it, send the picture to everyone, and select it in the editor. */
    public static void uploadSkin(ServerPlayer player, SkinPayloads.Upload upload) {
        if (!NpcPermissions.canEdit(player)) {
            return;
        }
        String name;
        try {
            name = SkinLibrary.save(upload.fileName(), upload.png());
        } catch (IOException e) {
            reply(player, false, e.getMessage());
            return;
        }
        byte[] png = SkinLibrary.read(name).orElse(upload.png());
        for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
            NpcNetworking.sendToPlayer(other, new SkinPayloads.Data(name, png));
        }
        NpcNetworking.sendToPlayer(player, new SkinPayloads.Library(SkinLibrary.names(), name));
        reply(player, true, "Skin '" + name + "' is in the library and selected. Save to keep it.");
    }

    /** Any player: the picture for a library skin an NPC near them wears. */
    public static void requestSkin(ServerPlayer player, String name) {
        NpcNetworking.sendToPlayer(player, new SkinPayloads.Data(name, SkinLibrary.read(name).orElse(new byte[0])));
    }

    public static void save(ServerPlayer player, SaveNpcPayload payload) {
        if (!NpcPermissions.canEdit(player)) {
            return;
        }
        if (!(findNpc(player.level().getServer(), payload.entityId()) instanceof NpcEntity npc) || npc.instance() == null
                || !npc.instance().template().equals(payload.template())) {
            reply(player, false, "That NPC is gone or has changed since the editor opened.");
            return;
        }
        JsonElement raw;
        try {
            raw = JsonParser.parseString(payload.rawJson());
        } catch (Exception e) {
            reply(player, false, "Couldn't read the editor's data: " + e.getMessage());
            return;
        }
        String template = payload.template();
        String savedAs;
        try {
            if (payload.scope() == SaveNpcPayload.ONLY_THIS) {
                savedAs = freeIdFor(template);
                NpcContent.NPCS.saveRaw(savedAs, raw);
                npc.setTemplate(savedAs);
                if (payload.resetGear()) {
                    npc.applyTemplate(true);
                    npc.restock();
                }
            } else {
                NpcContent.NPCS.saveRaw(template, raw);
                savedAs = template;
                refreshUsers(player.level().getServer(), template, payload.resetGear());
            }
        } catch (IOException e) {
            reply(player, false, "Not saved: " + e.getMessage());
            return;
        }
        Vec3 home = payload.home();
        NpcInstance instance = npc.instance();
        if (!home.equals(instance.home()) || payload.yaw() != instance.yaw() || payload.pitch() != instance.pitch()) {
            npc.moveHome(home, payload.yaw(), payload.pitch());
        }
        int users = usersOf(player.level().getServer(), savedAs);
        reply(player, true, payload.scope() == SaveNpcPayload.ONLY_THIS
                ? "Saved as its own template '" + savedAs + "'."
                : "Saved '" + savedAs + "' (" + users + " NPC" + (users == 1 ? "" : "s") + " updated).");
        open(player, npc); // refresh the editor with what was saved (and the new template id)
    }

    private static void reply(ServerPlayer player, boolean ok, String message) {
        NpcNetworking.sendToPlayer(player, new EditorResultPayload(ok, message));
    }

    /** "baker" → "baker_2", "baker_3"… whichever isn't taken. */
    private static String freeIdFor(String template) {
        String base = template.replaceAll("_\\d+$", "");
        for (int n = 2; ; n++) {
            String id = base + "_" + n;
            if (!NpcContent.NPCS.exists(id)) {
                return id;
            }
        }
    }

    /** Loaded NPCs using a template, across all worlds. */
    public static int usersOf(MinecraftServer server, String template) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            count += level.getEntities(NpcEntityTypes.npc(), npc -> npc.instance() != null
                    && npc.instance().template().equals(template)).size();
        }
        return count;
    }

    /** @param resetGear re-equip and restock too (only when gear or inventory was edited, so swaps aren't undone) */
    private static void refreshUsers(MinecraftServer server, String template, boolean resetGear) {
        for (ServerLevel level : server.getAllLevels()) {
            for (NpcEntity npc : level.getEntities(NpcEntityTypes.npc(), n -> n.instance() != null
                    && n.instance().template().equals(template))) {
                npc.applyTemplate(resetGear);
                if (resetGear) {
                    npc.restock();
                }
            }
        }
    }

    /** A loaded NPC by entity id in any dimension (entity ids are unique server-wide), so the Live list can edit far NPCs. */
    public static @Nullable NpcEntity findNpc(MinecraftServer server, int entityId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(entityId) instanceof NpcEntity npc) {
                return npc;
            }
        }
        return null;
    }

    /** The NPC the player is looking at (within 24 blocks), for {@code /npcmaker edit} without a target. */
    public static @Nullable NpcEntity lookedAt(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(LOOK_RANGE));
        NpcEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (NpcEntity npc : player.level().getEntities(NpcEntityTypes.npc(), new AABB(eye, end).inflate(1.0), NpcEntity::isAlive)) {
            Optional<Vec3> hit = npc.getBoundingBox().inflate(0.3).clip(eye, end);
            if (hit.isPresent() && eye.distanceToSqr(hit.get()) < bestDistance) {
                bestDistance = eye.distanceToSqr(hit.get());
                best = npc;
            }
        }
        return best;
    }

    /** Message for when {@code /npcmaker edit} finds nothing. */
    public static Component nothingLookedAt() {
        return Component.literal("Look at an NPC (within 24 blocks), or name one: /npcmaker edit <npc>").withStyle(ChatFormatting.GRAY);
    }
}
