package com.npcmaker.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.npcmaker.network.LivePayloads;
import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.RequestFactionsPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** NPC Maker's key bindings (rebindable under Controls → Miscellaneous). Each loader registers them. */
public final class NpcKeys {

    public static final KeyMapping FACTIONS = new KeyMapping("key.npcmaker.factions",
            InputConstants.Type.KEYBOARD, InputConstants.KEY_J, KeyMapping.Category.MISC);
    public static final KeyMapping QUESTS = new KeyMapping("key.npcmaker.quests",
            InputConstants.Type.KEYBOARD, InputConstants.KEY_K, KeyMapping.Category.MISC);
    /** Edits the NPC you're looking at, or opens the Live list when there's none (operators and creative players). */
    public static final KeyMapping EDITOR = new KeyMapping("key.npcmaker.editor",
            InputConstants.Type.KEYBOARD, InputConstants.KEY_I, KeyMapping.Category.MISC);

    private NpcKeys() {
    }

    /** Called every client tick by each loader. */
    public static void onClientTick(Minecraft mc) {
        while (FACTIONS.consumeClick()) {
            if (mc.player != null && mc.gui.screen() == null) {
                NpcNetworking.sendToServer(RequestFactionsPayload.INSTANCE);
            }
        }
        while (QUESTS.consumeClick()) {
            if (mc.player != null && mc.gui.screen() == null) {
                QuestJournalScreen.open();
            }
        }
        while (EDITOR.consumeClick()) {
            if (mc.player != null && mc.gui.screen() == null) {
                NpcNetworking.sendToServer(new LivePayloads.Action(LivePayloads.KEY, ""));
            }
        }
        ClientQuests.tick(mc);
        com.npcmaker.entity.LocateGlow.prune();
    }
}
