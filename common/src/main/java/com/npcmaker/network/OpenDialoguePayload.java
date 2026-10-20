package com.npcmaker.network;

import com.npcmaker.Constants;
import com.npcmaker.data.Voice;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server → client: show (or move the open dialogue box on to) a node of a conversation. The replies are only the
 * ones the player qualifies for; the client answers with their position in this list.
 */
public record OpenDialoguePayload(int entityId, Component name, List<Component> pages, List<Component> replies, Voice voice)
        implements CustomPacketPayload {

    public static final Type<OpenDialoguePayload> TYPE = new Type<>(Constants.id("open_dialogue"));

    private static final StreamCodec<RegistryFriendlyByteBuf, Voice> VOICE_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, Voice::sound,
            ByteBufCodecs.FLOAT, Voice::pitch,
            ByteBufCodecs.FLOAT, Voice::volume,
            ByteBufCodecs.BOOL, Voice::enabled,
            Voice::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenDialoguePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenDialoguePayload::entityId,
            ComponentSerialization.STREAM_CODEC, OpenDialoguePayload::name,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(64)), OpenDialoguePayload::pages,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(9)), OpenDialoguePayload::replies,
            VOICE_CODEC, OpenDialoguePayload::voice,
            OpenDialoguePayload::new);

    @Override
    public Type<OpenDialoguePayload> type() {
        return TYPE;
    }
}
