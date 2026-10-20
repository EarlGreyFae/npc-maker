package com.npcmaker.fabric;

import com.npcmaker.NpcMaker;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcEntityTypes;
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
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.api.ModInitializer;
import com.npcmaker.item.NpcItems;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public class NpcMakerFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        NpcMaker.init();

        Registry.register(BuiltInRegistries.ENTITY_TYPE, NpcEntityTypes.NPC_KEY, NpcEntityTypes.create());
        FabricDefaultAttributeRegistry.register(NpcEntityTypes.npc(), NpcEntity.createAttributes());
        Registry.register(BuiltInRegistries.ITEM, NpcItems.WAND_KEY, NpcItems.createWand());
        for (int i = 0; i < com.npcmaker.custom.NpcParticles.CUSTOM.size(); i++) {
            Registry.register(BuiltInRegistries.PARTICLE_TYPE, com.npcmaker.custom.NpcParticles.id(i), com.npcmaker.custom.NpcParticles.CUSTOM.get(i));
        }
        CreativeModeTabEvents.modifyOutputEvent(NpcItems.TOOLS_TAB).register(output ->
                output.accept(new ItemStack(NpcItems.wand()), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS));

        PayloadTypeRegistry.clientboundPlay().register(OpenDialoguePayload.TYPE, OpenDialoguePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CloseDialoguePayload.TYPE, CloseDialoguePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ReputationNoticePayload.TYPE, ReputationNoticePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FactionStandingsPayload.TYPE, FactionStandingsPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(QuestSyncPayload.TYPE, QuestSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(QuestNoticePayload.TYPE, QuestNoticePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(QuestOfferPayload.TYPE, QuestOfferPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RewardChoicePayload.TYPE, RewardChoicePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(QuestMarkersPayload.TYPE, QuestMarkersPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OpenEditorPayload.TYPE, OpenEditorPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EditorResultPayload.TYPE, EditorResultPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SaveNpcPayload.TYPE, SaveNpcPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SkinPayloads.Data.TYPE, SkinPayloads.Data.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SkinPayloads.Library.TYPE, SkinPayloads.Library.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SkinPayloads.Upload.TYPE, SkinPayloads.Upload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SkinPayloads.Request.TYPE, SkinPayloads.Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(com.npcmaker.network.DialoguePayloads.Data.TYPE, com.npcmaker.network.DialoguePayloads.Data.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(com.npcmaker.network.DialoguePayloads.Request.TYPE, com.npcmaker.network.DialoguePayloads.Request.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(com.npcmaker.network.DialoguePayloads.Save.TYPE, com.npcmaker.network.DialoguePayloads.Save.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(com.npcmaker.network.DialoguePayloads.Request.TYPE,
                (payload, context) -> NpcNetworking.handleRequestDialogue(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(com.npcmaker.network.DialoguePayloads.Save.TYPE,
                (payload, context) -> NpcNetworking.handleSaveDialogue(context.player(), payload));
        PayloadTypeRegistry.clientboundPlay().register(com.npcmaker.network.ContentPayloads.Index.TYPE, com.npcmaker.network.ContentPayloads.Index.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(com.npcmaker.network.ContentPayloads.File.TYPE, com.npcmaker.network.ContentPayloads.File.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(com.npcmaker.network.ContentPayloads.Saved.TYPE, com.npcmaker.network.ContentPayloads.Saved.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(com.npcmaker.network.ContentPayloads.Request.TYPE, com.npcmaker.network.ContentPayloads.Request.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(com.npcmaker.network.ContentPayloads.Save.TYPE, com.npcmaker.network.ContentPayloads.Save.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(com.npcmaker.network.ContentPayloads.Request.TYPE,
                (payload, context) -> NpcNetworking.handleRequestContent(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(com.npcmaker.network.ContentPayloads.Save.TYPE,
                (payload, context) -> NpcNetworking.handleSaveContent(context.player(), payload));
        PayloadTypeRegistry.clientboundPlay().register(com.npcmaker.network.CustomPayloads.Manifest.TYPE, com.npcmaker.network.CustomPayloads.Manifest.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(com.npcmaker.network.CustomPayloads.FilePart.TYPE, com.npcmaker.network.CustomPayloads.FilePart.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(com.npcmaker.network.CustomPayloads.RequestFiles.TYPE, com.npcmaker.network.CustomPayloads.RequestFiles.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(com.npcmaker.network.CustomPayloads.UploadPart.TYPE, com.npcmaker.network.CustomPayloads.UploadPart.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(com.npcmaker.network.CustomPayloads.Delete.TYPE, com.npcmaker.network.CustomPayloads.Delete.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(com.npcmaker.network.CustomPayloads.RequestFiles.TYPE,
                (payload, context) -> NpcNetworking.handleCustomRequest(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(com.npcmaker.network.CustomPayloads.UploadPart.TYPE,
                (payload, context) -> NpcNetworking.handleCustomUpload(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(com.npcmaker.network.CustomPayloads.Delete.TYPE,
                (payload, context) -> NpcNetworking.handleCustomDelete(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(SkinPayloads.Upload.TYPE,
                (payload, context) -> NpcNetworking.handleUploadSkin(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(SkinPayloads.Request.TYPE,
                (payload, context) -> NpcNetworking.handleRequestSkin(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(SaveNpcPayload.TYPE,
                (payload, context) -> NpcNetworking.handleSaveNpc(context.player(), payload));
        PayloadTypeRegistry.serverboundPlay().register(DialogueChoicePayload.TYPE, DialogueChoicePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RequestFactionsPayload.TYPE, RequestFactionsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(QuestActionPayload.TYPE, QuestActionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(QuestActionPayload.TYPE,
                (payload, context) -> NpcNetworking.handleQuestAction(context.player(), payload));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> NpcMaker.onPlayerJoin(handler.getPlayer()));
        ServerPlayNetworking.registerGlobalReceiver(DialogueChoicePayload.TYPE,
                (payload, context) -> NpcNetworking.handleChoice(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(RequestFactionsPayload.TYPE,
                (payload, context) -> NpcNetworking.handleRequestFactions(context.player()));

        ServerLifecycleEvents.SERVER_STARTING.register(NpcMaker::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(NpcMaker::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(NpcMaker::onServerTick);
        ServerLivingEntityEvents.AFTER_DEATH.register(NpcMaker::onLivingDeath);
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) ->
                NpcMaker.onLivingDamaged(entity, source, damageTaken));
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> NpcMaker.registerCommands(dispatcher));
    }
}
