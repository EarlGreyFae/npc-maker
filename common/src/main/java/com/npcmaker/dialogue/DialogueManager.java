package com.npcmaker.dialogue;

import com.npcmaker.Constants;
import com.npcmaker.content.NpcContent;
import com.npcmaker.data.NpcDefinition;
import com.npcmaker.data.Voice;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.network.CloseDialoguePayload;
import com.npcmaker.network.DialogueChoicePayload;
import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.OpenDialoguePayload;
import com.npcmaker.quest.QuestManager;
import com.npcmaker.text.TextTags;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs conversations on the server. The server decides which replies a player sees and checks every choice
 * again when it comes back, so a modified client can't pick a reply it doesn't qualify for.
 */
public final class DialogueManager {

    /** Walking further than this from the NPC ends the conversation. */
    private static final double MAX_DISTANCE = 8.0;

    private static final class Session {
        final NpcEntity npc;
        final DialogueTree tree;
        String node;
        List<Integer> shownReplies = List.of();

        Session(NpcEntity npc, DialogueTree tree, String node) {
            this.npc = npc;
            this.tree = tree;
            this.node = node;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private DialogueManager() {
    }

    /** The conversation an NPC would have, if any: its dialogue file, or its greeting lines as a single page set. */
    public static Optional<DialogueTree> treeFor(NpcDefinition def) {
        if (def.dialogue().isPresent()) {
            Optional<DialogueTree> tree = NpcContent.DIALOGUES.get(def.dialogue().get());
            if (tree.isEmpty()) {
                Constants.LOG.warn("NPC '{}' uses missing dialogue '{}'", def.displayName(), def.dialogue().get());
            }
            if (tree.isPresent() || def.greeting().isEmpty()) {
                return tree;
            }
        }
        return def.greeting().isEmpty() ? Optional.empty() : Optional.of(DialogueTree.fromGreeting(def.greeting()));
    }

    /** Starts a conversation. Returns false if this NPC has nothing to say. */
    public static boolean start(ServerPlayer player, NpcEntity npc) {
        Optional<DialogueTree> tree = npc.definition().flatMap(DialogueManager::treeFor);
        if (tree.isEmpty()) {
            return false;
        }
        Session previous = SESSIONS.remove(player.getUUID());
        if (previous != null && previous.npc != npc) {
            previous.npc.endConversation(player);
        }
        String first = tree.get().openers().stream()
                .filter(o -> o.conditions().stream().allMatch(c -> c.test(player, npc)))
                .map(DialogueTree.Opener::node)
                .findFirst()
                .orElse(tree.get().start());
        Session session = new Session(npc, tree.get(), first);
        SESSIONS.put(player.getUUID(), session);
        npc.startConversation(player);
        show(player, session);
        return true;
    }

    private static void show(ServerPlayer player, Session session) {
        DialogueTree.Node node = session.tree.nodes().get(session.node);
        List<Integer> shown = new ArrayList<>();
        List<Component> texts = new ArrayList<>();
        for (int i = 0; i < node.replies().size(); i++) {
            DialogueTree.Reply reply = node.replies().get(i);
            if (reply.conditions().stream().allMatch(c -> c.test(player, session.npc))) {
                shown.add(i);
                texts.add(fill(reply.text(), player, session.npc));
            }
        }
        session.shownReplies = shown;
        List<Component> pages = node.pages().stream().map(p -> fill(p, player, session.npc)).toList();
        Voice voice = session.npc.definition().map(d -> d.sounds().voice()).orElse(Voice.DEFAULT);
        NpcNetworking.sendToPlayer(player, new OpenDialoguePayload(session.npc.getId(), session.npc.label(), pages, texts, voice));
    }

    /** Fills in {player} / {npc}, then turns styling tags into formatted text. */
    private static Component fill(String text, ServerPlayer player, NpcEntity npc) {
        return TextTags.parse(text.replace("{player}", player.getGameProfile().name()).replace("{npc}", npc.label().getString()));
    }

    public static void choose(ServerPlayer player, int entityId, int choice) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.npc.getId() != entityId) {
            return;
        }
        if (choice == DialogueChoicePayload.CLOSE || choice < 0 || choice >= session.shownReplies.size()) {
            end(player, false);
            return;
        }
        DialogueTree.Reply reply = session.tree.nodes().get(session.node).replies().get(session.shownReplies.get(choice));
        // Things may have changed since the replies were shown (items dropped, flags set elsewhere).
        if (!reply.conditions().stream().allMatch(c -> c.test(player, session.npc))) {
            show(player, session);
            return;
        }
        for (DialogueAction action : reply.actions()) {
            action.run(player, session.npc);
        }
        if (SESSIONS.get(player.getUUID()) != session) {
            return; // an action ended or replaced the conversation
        }
        if (reply.next().isPresent()) {
            session.node = reply.next().get();
            show(player, session);
        } else {
            end(player, true);
        }
    }

    /** @param notifyClient false when the client closed the box itself */
    public static void end(ServerPlayer player, boolean notifyClient) {
        Session session = SESSIONS.remove(player.getUUID());
        // An open quest card from this NPC keeps it standing with the player until they answer it.
        if (session != null && !QuestManager.holdsConversation(player, session.npc)) {
            session.npc.endConversation(player);
        }
        if (notifyClient) {
            NpcNetworking.sendToPlayer(player, CloseDialoguePayload.INSTANCE);
        }
    }

    public static boolean isTalking(ServerPlayer player, Entity npc) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null && session.npc == npc;
    }

    /** Called once a second: drop conversations whose player left, walked away, or whose NPC is gone. */
    public static void tick(MinecraftServer server) {
        for (Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Session> e = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            NpcEntity npc = e.getValue().npc;
            boolean gone = player == null || !npc.isAlive() || npc.isRemoved() || player.level() != npc.level()
                    || player.distanceToSqr(npc) > MAX_DISTANCE * MAX_DISTANCE;
            if (gone) {
                it.remove();
                if (player != null) {
                    npc.endConversation(player);
                    NpcNetworking.sendToPlayer(player, CloseDialoguePayload.INSTANCE);
                } else {
                    npc.endConversation(null);
                }
            }
        }
    }

    public static void clear() {
        SESSIONS.clear();
    }
}
