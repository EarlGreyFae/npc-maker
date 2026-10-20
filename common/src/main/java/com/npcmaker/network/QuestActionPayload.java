package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: a button in the quest journal, offer card or reward screen.
 *
 * @param index which reward was picked (for CHOOSE_REWARD), otherwise 0
 */
public record QuestActionPayload(String quest, int action, int index) implements CustomPacketPayload {

    public static final int TRACK = 0;
    public static final int UNTRACK = 1;
    public static final int ABANDON = 2;
    public static final int ACCEPT = 3;
    public static final int DECLINE = 4;
    public static final int CHOOSE_REWARD = 5;

    public QuestActionPayload(String quest, int action) {
        this(quest, action, 0);
    }

    public static final Type<QuestActionPayload> TYPE = new Type<>(Constants.id("quest_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestActionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, QuestActionPayload::quest,
            ByteBufCodecs.VAR_INT, QuestActionPayload::action,
            ByteBufCodecs.VAR_INT, QuestActionPayload::index,
            QuestActionPayload::new);

    @Override
    public Type<QuestActionPayload> type() {
        return TYPE;
    }
}
