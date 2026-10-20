package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: the conversation is over (finished, walked away, NPC gone); close the dialogue box. */
public record CloseDialoguePayload() implements CustomPacketPayload {

    public static final CloseDialoguePayload INSTANCE = new CloseDialoguePayload();
    public static final Type<CloseDialoguePayload> TYPE = new Type<>(Constants.id("close_dialogue"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CloseDialoguePayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<CloseDialoguePayload> type() {
        return TYPE;
    }
}
