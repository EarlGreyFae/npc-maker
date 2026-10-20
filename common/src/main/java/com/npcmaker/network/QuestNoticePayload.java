package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Server → client: show the quest banner ("Quest started", "Quest complete" with the rewards, …).
 *
 * @param kind  one of the constants below
 * @param lines extra lines under the title: the new step, or the rewards
 */
public record QuestNoticePayload(int kind, Component title, List<Component> lines) implements CustomPacketPayload {

    public static final int STARTED = 0;
    public static final int NEXT_STAGE = 1;
    public static final int COMPLETED = 2;
    public static final int ABANDONED = 3;

    public static final Type<QuestNoticePayload> TYPE = new Type<>(Constants.id("quest_notice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestNoticePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, QuestNoticePayload::kind,
            ComponentSerialization.STREAM_CODEC, QuestNoticePayload::title,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(16)), QuestNoticePayload::lines,
            QuestNoticePayload::new);

    @Override
    public Type<QuestNoticePayload> type() {
        return TYPE;
    }
}
