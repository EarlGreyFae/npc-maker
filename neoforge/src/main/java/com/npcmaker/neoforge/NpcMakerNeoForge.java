package com.npcmaker.neoforge;

import com.npcmaker.Constants;
import com.npcmaker.NpcMaker;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcEntityTypes;
import com.npcmaker.item.NpcItems;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import com.npcmaker.network.CloseDialoguePayload;
import com.npcmaker.network.DialogueChoicePayload;
import com.npcmaker.network.FactionStandingsPayload;
import com.npcmaker.network.RequestFactionsPayload;
import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.OpenDialoguePayload;
import com.npcmaker.network.ReputationNoticePayload;
import com.npcmaker.network.QuestActionPayload;
import com.npcmaker.network.QuestNoticePayload;
import com.npcmaker.network.QuestMarkersPayload;
import com.npcmaker.network.EditorResultPayload;
import com.npcmaker.network.OpenEditorPayload;
import com.npcmaker.network.SaveNpcPayload;
import com.npcmaker.network.SkinPayloads;
import com.npcmaker.network.QuestOfferPayload;
import com.npcmaker.network.RewardChoicePayload;
import com.npcmaker.network.QuestSyncPayload;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(Constants.MOD_ID)
public class NpcMakerNeoForge {

    public NpcMakerNeoForge(IEventBus modBus) {
        NpcMaker.init();

        modBus.addListener((RegisterEvent event) -> {
            event.register(Registries.ENTITY_TYPE, helper -> helper.register(NpcEntityTypes.NPC_KEY.identifier(), NpcEntityTypes.create()));
            event.register(Registries.ITEM, helper -> helper.register(NpcItems.WAND_KEY.identifier(), NpcItems.createWand()));
            event.register(Registries.PARTICLE_TYPE, helper -> {
                for (int i = 0; i < com.npcmaker.custom.NpcParticles.CUSTOM.size(); i++) {
                    helper.register(com.npcmaker.custom.NpcParticles.id(i), com.npcmaker.custom.NpcParticles.CUSTOM.get(i));
                }
            });
        });
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey().equals(NpcItems.TOOLS_TAB)) {
                event.accept(new ItemStack(NpcItems.wand()), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            }
        });
        modBus.addListener((EntityAttributeCreationEvent event) ->
                event.put(NpcEntityTypes.npc(), NpcEntity.createAttributes().build()));
        // The client-bound handlers only call into NpcNetworking, which forwards to hooks the client sets up, so
        // no client class is named here and dedicated servers start cleanly. NPC Maker needs the mod on both sides
        // (custom entity renderer), so the payloads stay required.
        modBus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1")
                .playToClient(OpenDialoguePayload.TYPE, OpenDialoguePayload.CODEC, (payload, context) -> NpcNetworking.handleOpen(payload))
                .playToClient(CloseDialoguePayload.TYPE, CloseDialoguePayload.CODEC, (payload, context) -> NpcNetworking.handleClose())
                .playToClient(ReputationNoticePayload.TYPE, ReputationNoticePayload.CODEC, (payload, context) -> NpcNetworking.handleReputation(payload))
                .playToClient(FactionStandingsPayload.TYPE, FactionStandingsPayload.CODEC, (payload, context) -> NpcNetworking.handleStandings(payload))
                .playToClient(QuestSyncPayload.TYPE, QuestSyncPayload.CODEC, (payload, context) -> NpcNetworking.handleQuestSync(payload))
                .playToClient(QuestNoticePayload.TYPE, QuestNoticePayload.CODEC, (payload, context) -> NpcNetworking.handleQuestNotice(payload))
                .playToClient(QuestOfferPayload.TYPE, QuestOfferPayload.CODEC, (payload, context) -> NpcNetworking.handleQuestOffer(payload))
                .playToClient(RewardChoicePayload.TYPE, RewardChoicePayload.CODEC, (payload, context) -> NpcNetworking.handleRewardChoice(payload))
                .playToClient(QuestMarkersPayload.TYPE, QuestMarkersPayload.CODEC, (payload, context) -> NpcNetworking.handleQuestMarkers(payload))
                .playToClient(OpenEditorPayload.TYPE, OpenEditorPayload.CODEC, (payload, context) -> NpcNetworking.handleOpenEditor(payload))
                .playToClient(EditorResultPayload.TYPE, EditorResultPayload.CODEC, (payload, context) -> NpcNetworking.handleEditorResult(payload))
                .playToClient(SkinPayloads.Data.TYPE, SkinPayloads.Data.CODEC, (payload, context) -> NpcNetworking.handleSkinData(payload))
                .playToClient(SkinPayloads.Library.TYPE, SkinPayloads.Library.CODEC, (payload, context) -> NpcNetworking.handleSkinLibrary(payload))
                .playToClient(com.npcmaker.network.DialoguePayloads.Data.TYPE, com.npcmaker.network.DialoguePayloads.Data.CODEC,
                        (payload, context) -> NpcNetworking.handleDialogueData(payload))
                .playToServer(com.npcmaker.network.DialoguePayloads.Request.TYPE, com.npcmaker.network.DialoguePayloads.Request.CODEC,
                        (payload, context) -> NpcNetworking.handleRequestDialogue((ServerPlayer) context.player(), payload))
                .playToServer(com.npcmaker.network.DialoguePayloads.Save.TYPE, com.npcmaker.network.DialoguePayloads.Save.CODEC,
                        (payload, context) -> NpcNetworking.handleSaveDialogue((ServerPlayer) context.player(), payload))
                .playToClient(com.npcmaker.network.ContentPayloads.Index.TYPE, com.npcmaker.network.ContentPayloads.Index.CODEC,
                        (payload, context) -> NpcNetworking.handleContentIndex(payload))
                .playToClient(com.npcmaker.network.ContentPayloads.File.TYPE, com.npcmaker.network.ContentPayloads.File.CODEC,
                        (payload, context) -> NpcNetworking.handleContentFile(payload))
                .playToClient(com.npcmaker.network.ContentPayloads.Saved.TYPE, com.npcmaker.network.ContentPayloads.Saved.CODEC,
                        (payload, context) -> NpcNetworking.handleContentSaved(payload))
                .playToServer(com.npcmaker.network.ContentPayloads.Request.TYPE, com.npcmaker.network.ContentPayloads.Request.CODEC,
                        (payload, context) -> NpcNetworking.handleRequestContent((ServerPlayer) context.player(), payload))
                .playToServer(com.npcmaker.network.ContentPayloads.Save.TYPE, com.npcmaker.network.ContentPayloads.Save.CODEC,
                        (payload, context) -> NpcNetworking.handleSaveContent((ServerPlayer) context.player(), payload))
                .playToClient(com.npcmaker.network.CustomPayloads.Manifest.TYPE, com.npcmaker.network.CustomPayloads.Manifest.CODEC,
                        (payload, context) -> NpcNetworking.handleCustomManifest(payload))
                .playToClient(com.npcmaker.network.CustomPayloads.FilePart.TYPE, com.npcmaker.network.CustomPayloads.FilePart.CODEC,
                        (payload, context) -> NpcNetworking.handleCustomFile(payload))
                .playToServer(com.npcmaker.network.CustomPayloads.RequestFiles.TYPE, com.npcmaker.network.CustomPayloads.RequestFiles.CODEC,
                        (payload, context) -> NpcNetworking.handleCustomRequest((ServerPlayer) context.player(), payload))
                .playToServer(com.npcmaker.network.CustomPayloads.UploadPart.TYPE, com.npcmaker.network.CustomPayloads.UploadPart.CODEC,
                        (payload, context) -> NpcNetworking.handleCustomUpload((ServerPlayer) context.player(), payload))
                .playToServer(com.npcmaker.network.CustomPayloads.Delete.TYPE, com.npcmaker.network.CustomPayloads.Delete.CODEC,
                        (payload, context) -> NpcNetworking.handleCustomDelete((ServerPlayer) context.player(), payload))
                .playToServer(SkinPayloads.Upload.TYPE, SkinPayloads.Upload.CODEC,
                        (payload, context) -> NpcNetworking.handleUploadSkin((ServerPlayer) context.player(), payload))
                .playToServer(SkinPayloads.Request.TYPE, SkinPayloads.Request.CODEC,
                        (payload, context) -> NpcNetworking.handleRequestSkin((ServerPlayer) context.player(), payload))
                .playToServer(SaveNpcPayload.TYPE, SaveNpcPayload.CODEC,
                        (payload, context) -> NpcNetworking.handleSaveNpc((ServerPlayer) context.player(), payload))
                .playToClient(com.npcmaker.network.LivePayloads.Snapshot.TYPE, com.npcmaker.network.LivePayloads.Snapshot.CODEC,
                        (payload, context) -> NpcNetworking.handleLiveSnapshot(payload))
                .playToServer(com.npcmaker.network.LivePayloads.Action.TYPE, com.npcmaker.network.LivePayloads.Action.CODEC,
                        (payload, context) -> NpcNetworking.handleLiveAction((ServerPlayer) context.player(), payload))
                .playToServer(QuestActionPayload.TYPE, QuestActionPayload.CODEC,
                        (payload, context) -> NpcNetworking.handleQuestAction((ServerPlayer) context.player(), payload))
                .playToServer(RequestFactionsPayload.TYPE, RequestFactionsPayload.CODEC,
                        (payload, context) -> NpcNetworking.handleRequestFactions((ServerPlayer) context.player()))
                .playToServer(DialogueChoicePayload.TYPE, DialogueChoicePayload.CODEC,
                        (payload, context) -> NpcNetworking.handleChoice((ServerPlayer) context.player(), payload)));

        // "About to start" runs before the spawn chunks load, so NPCs there find their templates.
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> NpcMaker.onServerStarting(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> NpcMaker.onServerStopping(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> NpcMaker.onServerTick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> NpcMaker.registerCommands(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                NpcMaker.onPlayerJoin(player);
            }
        });
        // Lowest priority so deaths another mod cancels (totems, revive mods) don't count as kills.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (LivingDeathEvent event) -> {
            if (!event.getEntity().level().isClientSide()) {
                NpcMaker.onLivingDeath(event.getEntity(), event.getSource());
            }
        });
        NeoForge.EVENT_BUS.addListener((LivingDamageEvent.Post event) -> {
            if (!event.getEntity().level().isClientSide()) {
                NpcMaker.onLivingDamaged(event.getEntity(), event.getSource(), event.getHealthDamage());
            }
        });
    }
}
