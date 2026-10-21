package com.npcmaker.neoforge;

import com.npcmaker.Constants;
import com.npcmaker.client.NpcClientSettings;
import com.npcmaker.client.NpcKeys;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import com.npcmaker.client.NpcMakerClient;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.npcmaker.client.NpcRenderer;
import com.npcmaker.client.ReputationCard;
import com.npcmaker.client.QuestHud;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import com.npcmaker.entity.NpcEntityTypes;
import com.npcmaker.network.NpcNetworking;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Client-only setup; NeoForge never loads this class on a dedicated server. */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class NpcMakerNeoForgeClient {

    public NpcMakerNeoForgeClient(IEventBus modBus) {
        NpcMakerClient.init();
        NpcNetworking.setClientSender(payload -> ClientPacketDistributor.sendToServer(payload));
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerEntityRenderer(NpcEntityTypes.npc(), NpcRenderer::new));
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent event) -> {
            for (var type : com.npcmaker.custom.NpcParticles.CUSTOM) {
                event.registerSpriteSet(type, com.npcmaker.client.CustomParticle.Provider::new);
            }
        });
        modBus.addListener((RegisterGuiLayersEvent event) ->
        {
            event.registerAboveAll(NpcMakerClient.REPUTATION_CARD_LAYER, ReputationCard::render);
            event.registerAboveAll(NpcMakerClient.QUEST_HUD_LAYER, QuestHud::render);
        });
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            event.register(NpcKeys.FACTIONS);
            event.register(NpcKeys.QUESTS);
            event.register(NpcKeys.EDITOR);
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> NpcKeys.onClientTick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> event.getDispatcher().register(
                Commands.literal("npcbubbles")
                        .executes(ctx -> { ctx.getSource().sendSuccess(() -> NpcClientSettings.setSpeechBubbles(null), false); return 1; })
                        .then(Commands.literal("on")
                                .executes(ctx -> { ctx.getSource().sendSuccess(() -> NpcClientSettings.setSpeechBubbles(true), false); return 1; }))
                        .then(Commands.literal("off")
                                .executes(ctx -> { ctx.getSource().sendSuccess(() -> NpcClientSettings.setSpeechBubbles(false), false); return 1; }))));
    }
}
