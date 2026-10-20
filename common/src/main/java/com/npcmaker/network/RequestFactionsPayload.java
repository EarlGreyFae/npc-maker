package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: the player pressed the Factions key; send back their standings. */
public record RequestFactionsPayload() implements CustomPacketPayload {

    public static final RequestFactionsPayload INSTANCE = new RequestFactionsPayload();
    public static final Type<RequestFactionsPayload> TYPE = new Type<>(Constants.id("request_factions"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestFactionsPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<RequestFactionsPayload> type() {
        return TYPE;
    }
}
