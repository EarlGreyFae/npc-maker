package com.npcmaker.client;

import com.npcmaker.network.ContentPayloads;
import com.npcmaker.network.NpcNetworking;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What the server says exists (NPC templates, factions, quests) for the editors' pickers, plus fetching single
 * quest or faction files. The index arrives each time the NPC editor opens and after every quest/faction save.
 */
public final class ClientContent {

    /** Screens that show save results and want to know the id a file was saved under. */
    public interface Listener {
        void showResult(boolean ok, String message);

        default void contentSaved(String kind, String id) {
        }
    }

    private static List<ContentPayloads.Entry> npcs = List.of();
    private static List<ContentPayloads.Entry> factions = List.of();
    private static List<ContentPayloads.Entry> quests = List.of();
    private static final Map<String, Consumer<String>> PENDING = new HashMap<>();

    private ClientContent() {
    }

    public static List<ContentPayloads.Entry> npcs() {
        return npcs;
    }

    public static List<ContentPayloads.Entry> factions() {
        return factions;
    }

    public static List<ContentPayloads.Entry> quests() {
        return quests;
    }

    /** The display label for an id in a list, or the id itself if it isn't known. */
    public static String label(List<ContentPayloads.Entry> list, String id) {
        return list.stream().filter(e -> e.id().equals(id)).map(ContentPayloads.Entry::label).findFirst().orElse(id);
    }

    /** Asks the server for a file; {@code onRaw} gets its JSON text ("" if missing) when it arrives. */
    public static void request(String kind, String id, Consumer<String> onRaw) {
        PENDING.put(kind + "/" + id, onRaw);
        NpcNetworking.sendToServer(new ContentPayloads.Request(kind, id));
    }

    public static void onIndex(ContentPayloads.Index index) {
        npcs = List.copyOf(index.npcs());
        factions = List.copyOf(index.factions());
        quests = List.copyOf(index.quests());
    }

    public static void onFile(ContentPayloads.File file) {
        Consumer<String> callback = PENDING.remove(file.kind() + "/" + file.id());
        if (callback != null) {
            callback.accept(file.rawJson());
        }
    }

    public static void onSaved(ContentPayloads.Saved saved) {
        if (Minecraft.getInstance().gui.screen() instanceof Listener listener) {
            listener.contentSaved(saved.kind(), saved.id());
        }
    }
}
