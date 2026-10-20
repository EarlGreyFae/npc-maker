package com.npcmaker.client;

import com.npcmaker.network.QuestMarkersPayload;
import com.npcmaker.network.QuestSyncPayload;
import com.npcmaker.network.RewardChoicePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The player's quests as the server last sent them; read by the journal screen and the HUD tracker. */
public final class ClientQuests {

    private static QuestSyncPayload current = new QuestSyncPayload(List.of(), List.of(), "");
    private static Map<Integer, Integer> markers = Map.of();
    /** Reward pickers waiting for the screen to be free (e.g. a quest finished while a conversation opened). */
    private static final ArrayDeque<RewardChoicePayload> REWARD_CHOICES = new ArrayDeque<>();

    private ClientQuests() {
    }

    // ---- markers ------------------------------------------------------------------------------------------

    public static void onMarkers(QuestMarkersPayload payload) {
        markers = Map.copyOf(payload.markers());
    }

    /** The quest marker over this NPC for the local player, or 0. */
    public static int marker(int entityId) {
        return markers.getOrDefault(entityId, 0);
    }

    // ---- reward choices -----------------------------------------------------------------------------------

    public static void onRewardChoice(RewardChoicePayload payload) {
        REWARD_CHOICES.removeIf(p -> p.quest().equals(payload.quest()));
        REWARD_CHOICES.add(payload);
    }

    /** Every client tick: open the next reward picker once no other screen is open. */
    public static void tick(Minecraft mc) {
        if (mc.player != null && mc.gui.screen() == null && !REWARD_CHOICES.isEmpty()) {
            RewardChoiceScreen.open(REWARD_CHOICES.poll());
        }
    }

    public static QuestSyncPayload get() {
        return current;
    }

    public static Optional<QuestSyncPayload.Quest> tracked() {
        return find(current.tracked());
    }

    public static Optional<QuestSyncPayload.Quest> find(String id) {
        return current.active().stream().filter(q -> q.id().equals(id)).findFirst();
    }

    public static void onSync(QuestSyncPayload payload) {
        if (objectiveJustFinished(payload)) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2F, 0.7F));
        }
        current = payload;
        if (Minecraft.getInstance().gui.screen() instanceof QuestJournalScreen journal) {
            journal.refresh();
        }
    }

    /** An objective ticked off without finishing its step (the step and quest banners have their own sounds). */
    private static boolean objectiveJustFinished(QuestSyncPayload next) {
        for (QuestSyncPayload.Quest quest : next.active()) {
            Optional<QuestSyncPayload.Quest> before = find(quest.id());
            if (before.isEmpty() || before.get().stage() != quest.stage()) {
                continue;
            }
            List<QuestSyncPayload.Objective> old = before.get().objectives();
            for (int i = 0; i < quest.objectives().size() && i < old.size(); i++) {
                if (quest.objectives().get(i).done() && !old.get(i).done()) {
                    return true;
                }
            }
        }
        return false;
    }
}
