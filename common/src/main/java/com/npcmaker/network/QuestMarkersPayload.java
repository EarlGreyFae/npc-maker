package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Map;

/**
 * Server → client: quest markers over nearby NPCs for this player only (entity id → marker). Sent when they change.
 */
public record QuestMarkersPayload(Map<Integer, Integer> markers) implements CustomPacketPayload {

    /** Gold "!": has a quest you can take. */
    public static final int AVAILABLE = 1;
    /** Grey "?": involved in a quest you're on, nothing to do with them yet. */
    public static final int IN_PROGRESS = 2;
    /** Gold "?": talk to them or hand something in now. */
    public static final int TURN_IN = 3;

    public static final Type<QuestMarkersPayload> TYPE = new Type<>(Constants.id("quest_markers"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestMarkersPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.<RegistryFriendlyByteBuf, Integer, Integer, java.util.HashMap<Integer, Integer>>map(
                    java.util.HashMap::new, ByteBufCodecs.VAR_INT, ByteBufCodecs.VAR_INT, 4096),
            p -> new java.util.HashMap<>(p.markers()),
            QuestMarkersPayload::new);

    @Override
    public Type<QuestMarkersPayload> type() {
        return TYPE;
    }
}
