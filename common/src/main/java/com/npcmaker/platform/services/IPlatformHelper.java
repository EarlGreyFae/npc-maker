package com.npcmaker.platform.services;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

public interface IPlatformHelper {

    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    /** The loader's config directory; NPC Maker keeps its global settings here. */
    Path getConfigDir();

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);
}
