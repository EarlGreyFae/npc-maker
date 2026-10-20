package com.npcmaker.fabric;

import com.npcmaker.client.NpcClientSettings;
import com.npcmaker.client.NpcKeys;
import com.npcmaker.network.FactionStandingsPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import com.npcmaker.client.NpcMakerClient;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import com.npcmaker.client.NpcRenderer;
import com.npcmaker.client.ReputationCard;
import com.npcmaker.client.QuestHud;
import com.npcmaker.network.QuestNoticePayload;
import com.npcmaker.network.QuestMarkersPayload;
import com.npcmaker.network.EditorResultPayload;
import com.npcmaker.network.OpenEditorPayload;
import com.npcmaker.network.QuestOfferPayload;
import com.npcmaker.network.RewardChoicePayload;
import com.npcmaker.network.QuestSyncPayload;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import com.npcmaker.entity.NpcEntityTypes;
import com.npcmaker.network.CloseDialoguePayload;
import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.OpenDialoguePayload;
import com.npcmaker.network.ReputationNoticePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class NpcMakerFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        NpcMakerClient.init();
        NpcNetworking.setClientSender(ClientPlayNetworking::send);
        EntityRendererRegistry.register(NpcEntityTypes.npc(), NpcRenderer::new);
        HudElementRegistry.addLast(NpcMakerClient.REPUTATION_CARD_LAYER, ReputationCard::render);
        HudElementRegistry.addLast(NpcMakerClient.QUEST_HUD_LAYER, QuestHud::render);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                ClientCommands.literal("npcbubbles")
                        .executes(ctx -> { ctx.getSource().sendFeedback(NpcClientSettings.setSpeechBubbles(null)); return 1; })
                        .then(ClientCommands.literal("on")
                                .executes(ctx -> { ctx.getSource().sendFeedback(NpcClientSettings.setSpeechBubbles(true)); return 1; }))
                        .then(ClientCommands.literal("off")
                                .executes(ctx -> { ctx.getSource().sendFeedback(NpcClientSettings.setSpeechBubbles(false)); return 1; }))));
        ClientPlayNetworking.registerGlobalReceiver(OpenDialoguePayload.TYPE, (payload, context) -> NpcNetworking.handleOpen(payload));
        ClientPlayNetworking.registerGlobalReceiver(CloseDialoguePayload.TYPE, (payload, context) -> NpcNetworking.handleClose());
        ClientPlayNetworking.registerGlobalReceiver(ReputationNoticePayload.TYPE, (payload, context) -> NpcNetworking.handleReputation(payload));
        ClientPlayNetworking.registerGlobalReceiver(FactionStandingsPayload.TYPE, (payload, context) -> NpcNetworking.handleStandings(payload));
        ClientPlayNetworking.registerGlobalReceiver(QuestSyncPayload.TYPE, (payload, context) -> NpcNetworking.handleQuestSync(payload));
        ClientPlayNetworking.registerGlobalReceiver(QuestNoticePayload.TYPE, (payload, context) -> NpcNetworking.handleQuestNotice(payload));
        ClientPlayNetworking.registerGlobalReceiver(QuestOfferPayload.TYPE, (payload, context) -> NpcNetworking.handleQuestOffer(payload));
        ClientPlayNetworking.registerGlobalReceiver(RewardChoicePayload.TYPE, (payload, context) -> NpcNetworking.handleRewardChoice(payload));
        ClientPlayNetworking.registerGlobalReceiver(QuestMarkersPayload.TYPE, (payload, context) -> NpcNetworking.handleQuestMarkers(payload));
        ClientPlayNetworking.registerGlobalReceiver(OpenEditorPayload.TYPE, (payload, context) -> NpcNetworking.handleOpenEditor(payload));
        ClientPlayNetworking.registerGlobalReceiver(EditorResultPayload.TYPE, (payload, context) -> NpcNetworking.handleEditorResult(payload));
        ClientPlayNetworking.registerGlobalReceiver(com.npcmaker.network.SkinPayloads.Data.TYPE, (payload, context) -> NpcNetworking.handleSkinData(payload));
        ClientPlayNetworking.registerGlobalReceiver(com.npcmaker.network.SkinPayloads.Library.TYPE, (payload, context) -> NpcNetworking.handleSkinLibrary(payload));
        ClientPlayNetworking.registerGlobalReceiver(com.npcmaker.network.DialoguePayloads.Data.TYPE, (payload, context) -> NpcNetworking.handleDialogueData(payload));
        ClientPlayNetworking.registerGlobalReceiver(com.npcmaker.network.ContentPayloads.Index.TYPE, (payload, context) -> NpcNetworking.handleContentIndex(payload));
        ClientPlayNetworking.registerGlobalReceiver(com.npcmaker.network.ContentPayloads.File.TYPE, (payload, context) -> NpcNetworking.handleContentFile(payload));
        ClientPlayNetworking.registerGlobalReceiver(com.npcmaker.network.ContentPayloads.Saved.TYPE, (payload, context) -> NpcNetworking.handleContentSaved(payload));
        ClientPlayNetworking.registerGlobalReceiver(com.npcmaker.network.CustomPayloads.Manifest.TYPE, (payload, context) -> NpcNetworking.handleCustomManifest(payload));
        ClientPlayNetworking.registerGlobalReceiver(com.npcmaker.network.CustomPayloads.FilePart.TYPE, (payload, context) -> NpcNetworking.handleCustomFile(payload));
        for (var type : com.npcmaker.custom.NpcParticles.CUSTOM) {
            net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry.getInstance()
                    .register(type, sprites -> new com.npcmaker.client.CustomParticle.Provider(sprites));
        }
        KeyMappingHelper.registerKeyMapping(NpcKeys.FACTIONS);
        KeyMappingHelper.registerKeyMapping(NpcKeys.QUESTS);
        ClientTickEvents.END_CLIENT_TICK.register(NpcKeys::onClientTick);
    }
}
