package com.npcmaker;

import com.mojang.brigadier.CommandDispatcher;
import com.npcmaker.command.NpcCommands;
import com.npcmaker.content.NpcContent;
import com.npcmaker.dialogue.DialogueManager;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcRespawns;
import com.npcmaker.quest.QuestManager;
import com.npcmaker.faction.PlayerFactions;
import com.npcmaker.item.NpcWandItem;
import com.npcmaker.player.PlayerData;
import com.npcmaker.platform.Services;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** Loader-agnostic entry points. Each loader calls these from its own events. */
public final class NpcMaker {

    private static int tickCounter;

    private NpcMaker() {
    }

    public static void init() {
        Constants.LOG.info("{} starting on {}", Constants.MOD_NAME, Services.PLATFORM.getPlatformName());
    }

    /** Before worlds load, so NPC entities find their templates when their chunks load. */
    public static void onServerStarting(MinecraftServer server) {
        NpcContent.load(server);
        com.npcmaker.content.SkinLibrary.init(server);
        com.npcmaker.custom.CustomContent.init(server);
        NpcRespawns.load(server);
        PlayerData.init(server);
    }

    public static void onServerStopping(MinecraftServer server) {
        NpcRespawns.save();
        DialogueManager.clear();
    }

    public static void onServerTick(MinecraftServer server) {
        ++tickCounter;
        if (tickCounter % 10 == 0) {
            NpcWandItem.showMarkers(server);
        }
        if (tickCounter % 20 == 0) {
            NpcRespawns.tick(server);
            DialogueManager.tick(server);
            QuestManager.tick(server);
        }
    }

    /** A player joined: send their quest journal so the tracker shows straight away, and the custom content list. */
    public static void onPlayerJoin(ServerPlayer player) {
        com.npcmaker.custom.CustomContent.sendManifest(player);
        QuestManager.forgetPlayer(player);
        QuestManager.sync(player);
        QuestManager.sendPendingRewards(player);
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        NpcCommands.register(dispatcher);
    }

    /**
     * Any living entity died on the server. The kill counts for the player behind the killing blow (arrows too),
     * or else for the player who hit it in the last few seconds, so a kill a guard or companion finished off still
     * counts, the same rule vanilla uses for XP and drops. For quests, a companion's kill also counts for its owner.
     */
    public static void onLivingDeath(LivingEntity victim, DamageSource source) {
        ServerPlayer player = source.getEntity() instanceof ServerPlayer direct ? direct
                : victim.getLastHurtByPlayer() instanceof ServerPlayer recent ? recent
                : null;
        if (player != null) {
            PlayerFactions.onKill(player, victim);
        } else if (source.getEntity() instanceof NpcEntity npc && npc.companionOwner() instanceof ServerPlayer owner) {
            player = owner;
        }
        QuestManager.onKill(victim, player);
    }

    /** Any living entity took damage on the server. */
    public static void onLivingDamaged(LivingEntity victim, DamageSource source, float amount) {
        if (amount > 0 && victim.isAlive() && source.getEntity() instanceof ServerPlayer player) {
            PlayerFactions.onAttack(player, victim);
        }
    }
}
