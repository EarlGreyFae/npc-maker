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
 * Server → client: everything the quest journal and tracker show, sent on login and whenever the player's quests
 * change. Text is resolved on the server, so the client needs no quest files.
 *
 * @param completed titles of finished quests, most recent first
 * @param tracked   id of the quest pinned to the HUD, or "" for none
 */
public record QuestSyncPayload(List<Quest> active, List<Component> completed, String tracked) implements CustomPacketPayload {

    /** @param needed how many are needed; progress >= needed means done */
    public record Objective(Component label, int progress, int needed) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Objective> CODEC = StreamCodec.composite(
                ComponentSerialization.STREAM_CODEC, Objective::label,
                ByteBufCodecs.VAR_INT, Objective::progress,
                ByteBufCodecs.VAR_INT, Objective::needed,
                Objective::new);

        public boolean done() {
            return progress >= needed;
        }
    }

    /**
     * @param giver     who hands it out (empty if nobody in particular)
     * @param stageText what to do now (may be empty)
     * @param stage     0-based current stage
     */
    public record Quest(String id, Component title, Component description, Component giver, Component stageText,
                        int stage, int stages, List<Objective> objectives, boolean abandonable) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Quest> CODEC = StreamCodec.of(
                (buf, q) -> {
                    ByteBufCodecs.STRING_UTF8.encode(buf, q.id);
                    ComponentSerialization.STREAM_CODEC.encode(buf, q.title);
                    ComponentSerialization.STREAM_CODEC.encode(buf, q.description);
                    ComponentSerialization.STREAM_CODEC.encode(buf, q.giver);
                    ComponentSerialization.STREAM_CODEC.encode(buf, q.stageText);
                    ByteBufCodecs.VAR_INT.encode(buf, q.stage);
                    ByteBufCodecs.VAR_INT.encode(buf, q.stages);
                    Objective.CODEC.apply(ByteBufCodecs.list(16)).encode(buf, q.objectives);
                    ByteBufCodecs.BOOL.encode(buf, q.abandonable);
                },
                buf -> new Quest(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ComponentSerialization.STREAM_CODEC.decode(buf),
                        ComponentSerialization.STREAM_CODEC.decode(buf),
                        ComponentSerialization.STREAM_CODEC.decode(buf),
                        ComponentSerialization.STREAM_CODEC.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        Objective.CODEC.apply(ByteBufCodecs.list(16)).decode(buf),
                        ByteBufCodecs.BOOL.decode(buf)));
    }

    public static final Type<QuestSyncPayload> TYPE = new Type<>(Constants.id("quest_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestSyncPayload> CODEC = StreamCodec.composite(
            Quest.CODEC.apply(ByteBufCodecs.list(256)), QuestSyncPayload::active,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(1024)), QuestSyncPayload::completed,
            ByteBufCodecs.STRING_UTF8, QuestSyncPayload::tracked,
            QuestSyncPayload::new);

    @Override
    public Type<QuestSyncPayload> type() {
        return TYPE;
    }
}
