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
 * Server → client: reputation changed with one or more factions because of a single action (a kill, a hit, a
 * dialogue reply). The client shows them together on a pop-up card in the top-right corner.
 */
public record ReputationNoticePayload(List<Entry> entries) implements CustomPacketPayload {

    /**
     * @param faction  the faction's name, in its colour
     * @param delta    how much it changed
     * @param now      the new value
     * @param standing the standing tier it's now in (e.g. "Friendly"), in the tier's colour
     */
    public record Entry(Component faction, int delta, int now, Component standing) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
                ComponentSerialization.STREAM_CODEC, Entry::faction,
                ByteBufCodecs.VAR_INT, Entry::delta,
                ByteBufCodecs.VAR_INT, Entry::now,
                ComponentSerialization.STREAM_CODEC, Entry::standing,
                Entry::new);
    }

    public static final Type<ReputationNoticePayload> TYPE = new Type<>(Constants.id("reputation_notice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReputationNoticePayload> CODEC =
            Entry.CODEC.apply(ByteBufCodecs.list(32)).map(ReputationNoticePayload::new, ReputationNoticePayload::entries);

    @Override
    public Type<ReputationNoticePayload> type() {
        return TYPE;
    }
}
