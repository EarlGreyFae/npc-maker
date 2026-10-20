package com.npcmaker.client;

import com.npcmaker.Constants;
import com.npcmaker.network.NpcNetworking;
import net.minecraft.resources.Identifier;

/** Client-only setup shared by both loaders. Only ever called from client entrypoints. */
public final class NpcMakerClient {

    /** HUD layer id each loader registers {@link ReputationCard#render} under. */
    public static final Identifier REPUTATION_CARD_LAYER = Constants.id("reputation_card");
    /** HUD layer id for {@link QuestHud#render} (tracker and banners). */
    public static final Identifier QUEST_HUD_LAYER = Constants.id("quest_hud");

    private NpcMakerClient() {
    }

    public static void init() {
        NpcNetworking.setClientHandlers(new NpcNetworking.ClientHandlers(
                DialogueScreen::open, DialogueScreen::closeFromServer, ReputationCard::show, FactionsScreen::open,
                ClientQuests::onSync, QuestHud::notice, QuestOfferScreen::open, ClientQuests::onRewardChoice,
                ClientQuests::onMarkers, NpcEditorScreen::open, NpcEditorScreen::result, ClientSkins::onData,
                ClientSkins::onLibrary, DialogueGraphScreen::open, ClientContent::onIndex, ClientContent::onFile,
                ClientContent::onSaved, ClientCustom::onManifest, ClientCustom::onFilePart));
    }
}
