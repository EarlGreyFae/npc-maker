package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: the player picked reply number {@code choice} (0-based, among the replies they were shown),
 * or {@link #CLOSE} to leave the conversation.
 */
public record DialogueChoicePayload(int entityId, int choice) implements CustomPacketPayload {

    public static final int CLOSE = -1;

    public static final Type<DialogueChoicePayload> TYPE = new Type<>(Constants.id("dialogue_choice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DialogueChoicePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DialogueChoicePayload::entityId,
            ByteBufCodecs.VAR_INT, DialogueChoicePayload::choice,
            DialogueChoicePayload::new);

    @Override
    public Type<DialogueChoicePayload> type() {
        return TYPE;
    }
}
