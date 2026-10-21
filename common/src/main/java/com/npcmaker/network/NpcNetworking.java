package com.npcmaker.network;

import com.npcmaker.dialogue.DialogueManager;
import com.npcmaker.faction.PlayerFactions;
import com.npcmaker.platform.Services;
import com.npcmaker.quest.QuestManager;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * The glue between loaders and NPC Maker's packets. Loaders register the payload types and route received packets
 * here. Everything client-side (handling server packets, sending to the server) goes through hooks that the
 * loader's client entrypoint sets, so nothing here touches client classes and dedicated servers never load them.
 */
public final class NpcNetworking {

    /** What the client does with each server packet; set by the client at startup. */
    public record ClientHandlers(Consumer<OpenDialoguePayload> openDialogue, Runnable closeDialogue,
                                 Consumer<ReputationNoticePayload> reputation, Consumer<FactionStandingsPayload> standings,
                                 Consumer<QuestSyncPayload> questSync, Consumer<QuestNoticePayload> questNotice,
                                 Consumer<QuestOfferPayload> questOffer, Consumer<RewardChoicePayload> rewardChoice,
                                 Consumer<QuestMarkersPayload> questMarkers, Consumer<OpenEditorPayload> openEditor,
                                 Consumer<EditorResultPayload> editorResult, Consumer<SkinPayloads.Data> skinData,
                                 Consumer<SkinPayloads.Library> skinLibrary, Consumer<DialoguePayloads.Data> dialogueData,
                                 Consumer<ContentPayloads.Index> contentIndex, Consumer<ContentPayloads.File> contentFile,
                                 Consumer<ContentPayloads.Saved> contentSaved, Consumer<CustomPayloads.Manifest> customManifest,
                                 Consumer<CustomPayloads.FilePart> customFile) {
    }

    private static ClientHandlers client = new ClientHandlers(p -> { }, () -> { }, p -> { }, p -> { }, p -> { }, p -> { },
            p -> { }, p -> { }, p -> { }, p -> { }, p -> { }, p -> { }, p -> { }, p -> { }, p -> { }, p -> { }, p -> { },
            p -> { }, p -> { });
    private static Consumer<CustomPacketPayload> clientSender = payload -> {
        throw new IllegalStateException("sendToServer called before the client set up networking");
    };

    private NpcNetworking() {
    }

    public static void setClientHandlers(ClientHandlers handlers) {
        client = handlers;
    }

    public static void setClientSender(Consumer<CustomPacketPayload> sender) {
        clientSender = sender;
    }

    // ---- received on the client ---------------------------------------------------------------------------

    public static void handleOpen(OpenDialoguePayload payload) {
        client.openDialogue().accept(payload);
    }

    public static void handleClose() {
        client.closeDialogue().run();
    }

    public static void handleReputation(ReputationNoticePayload payload) {
        client.reputation().accept(payload);
    }

    public static void handleStandings(FactionStandingsPayload payload) {
        client.standings().accept(payload);
    }

    public static void handleQuestSync(QuestSyncPayload payload) {
        client.questSync().accept(payload);
    }

    public static void handleQuestNotice(QuestNoticePayload payload) {
        client.questNotice().accept(payload);
    }

    public static void handleQuestOffer(QuestOfferPayload payload) {
        client.questOffer().accept(payload);
    }

    public static void handleRewardChoice(RewardChoicePayload payload) {
        client.rewardChoice().accept(payload);
    }

    public static void handleQuestMarkers(QuestMarkersPayload payload) {
        client.questMarkers().accept(payload);
    }

    public static void handleOpenEditor(OpenEditorPayload payload) {
        client.openEditor().accept(payload);
    }

    public static void handleEditorResult(EditorResultPayload payload) {
        client.editorResult().accept(payload);
    }

    public static void handleSkinData(SkinPayloads.Data payload) {
        client.skinData().accept(payload);
    }

    public static void handleSkinLibrary(SkinPayloads.Library payload) {
        client.skinLibrary().accept(payload);
    }

    public static void handleDialogueData(DialoguePayloads.Data payload) {
        client.dialogueData().accept(payload);
    }

    public static void handleContentIndex(ContentPayloads.Index payload) {
        client.contentIndex().accept(payload);
    }

    public static void handleContentFile(ContentPayloads.File payload) {
        client.contentFile().accept(payload);
    }

    public static void handleContentSaved(ContentPayloads.Saved payload) {
        client.contentSaved().accept(payload);
    }

    public static void handleCustomManifest(CustomPayloads.Manifest payload) {
        client.customManifest().accept(payload);
    }

    public static void handleCustomFile(CustomPayloads.FilePart payload) {
        client.customFile().accept(payload);
    }

    public static void handleCustomRequest(ServerPlayer player, CustomPayloads.RequestFiles payload) {
        com.npcmaker.custom.CustomContent.sendFiles(player, payload.sha1s());
    }

    public static void handleCustomUpload(ServerPlayer player, CustomPayloads.UploadPart payload) {
        com.npcmaker.custom.CustomContent.receivePart(player, payload);
    }

    public static void handleCustomDelete(ServerPlayer player, CustomPayloads.Delete payload) {
        com.npcmaker.custom.CustomContent.delete(player, payload.path());
    }

    public static void handleRequestContent(ServerPlayer player, ContentPayloads.Request payload) {
        com.npcmaker.editor.ContentEditing.request(player, payload);
    }

    public static void handleSaveContent(ServerPlayer player, ContentPayloads.Save payload) {
        com.npcmaker.editor.ContentEditing.save(player, payload);
    }

    public static void handleRequestDialogue(ServerPlayer player, DialoguePayloads.Request payload) {
        if (com.npcmaker.editor.NpcEditing.findNpc(player.level().getServer(), payload.entityId()) instanceof com.npcmaker.entity.NpcEntity npc) {
            com.npcmaker.editor.NpcEditing.openDialogue(player, npc);
        }
    }

    public static void handleSaveDialogue(ServerPlayer player, DialoguePayloads.Save payload) {
        com.npcmaker.editor.NpcEditing.saveDialogue(player, payload);
    }

    public static void handleUploadSkin(ServerPlayer player, SkinPayloads.Upload payload) {
        com.npcmaker.editor.NpcEditing.uploadSkin(player, payload);
    }

    public static void handleRequestSkin(ServerPlayer player, SkinPayloads.Request payload) {
        com.npcmaker.editor.NpcEditing.requestSkin(player, payload.name());
    }

    public static void handleSaveNpc(ServerPlayer player, SaveNpcPayload payload) {
        com.npcmaker.editor.NpcEditing.save(player, payload);
    }

    public static void handleLiveAction(ServerPlayer player, LivePayloads.Action payload) {
        com.npcmaker.editor.LiveNpcs.handle(player, payload);
    }

    /** Where the Live list goes on the client; set by the client at startup (kept out of ClientHandlers on purpose). */
    private static Consumer<LivePayloads.Snapshot> liveHandler = p -> { };

    public static void setLiveHandler(Consumer<LivePayloads.Snapshot> handler) {
        liveHandler = handler;
    }

    public static void handleLiveSnapshot(LivePayloads.Snapshot payload) {
        liveHandler.accept(payload);
    }

    // ---- received on the server ---------------------------------------------------------------------------

    public static void handleQuestAction(ServerPlayer player, QuestActionPayload payload) {
        QuestManager.handleAction(player, payload);
    }

    public static void handleChoice(ServerPlayer player, DialogueChoicePayload payload) {
        DialogueManager.choose(player, payload.entityId(), payload.choice());
    }

    public static void handleRequestFactions(ServerPlayer player) {
        PlayerFactions.sendStandings(player);
    }

    // ---- sending ------------------------------------------------------------------------------------------

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        Services.PLATFORM.sendToPlayer(player, payload);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        clientSender.accept(payload);
    }
}
