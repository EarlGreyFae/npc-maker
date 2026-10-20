package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Network messages for the dialogue graph editor. */
public final class DialoguePayloads {

    private DialoguePayloads() {
    }

    /** Client → server: send me this NPC's conversation for the graph editor. */
    public record Request(int entityId) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Constants.id("request_dialogue"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Request::entityId,
                Request::new);

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /**
     * Server → client: an NPC's conversation.
     *
     * @param dialogueId the conversation file it uses, or "" if it has none yet
     * @param rawJson    the file as written ("" if there's no file yet)
     * @param greeting   the NPC's greeting lines joined by "\n", to start a new conversation from
     * @param users      loaded NPCs using this conversation (for the save choice)
     */
    public record Data(int entityId, String dialogueId, String rawJson, String greeting, int users) implements CustomPacketPayload {
        public static final Type<Data> TYPE = new Type<>(Constants.id("dialogue_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Data::entityId,
                ByteBufCodecs.STRING_UTF8, Data::dialogueId,
                OpenEditorPayload.BIG_STRING, Data::rawJson,
                ByteBufCodecs.STRING_UTF8, Data::greeting,
                ByteBufCodecs.VAR_INT, Data::users,
                Data::new);

        @Override
        public Type<Data> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: save the conversation.
     *
     * @param scope {@link SaveNpcPayload#ALL} updates it for every NPC using it; {@link SaveNpcPayload#ONLY_THIS} makes a
     *              copy for just this NPC
     */
    public record Save(int entityId, String dialogueId, String rawJson, int scope) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(Constants.id("save_dialogue"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Save> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Save::entityId,
                ByteBufCodecs.STRING_UTF8, Save::dialogueId,
                OpenEditorPayload.BIG_STRING, Save::rawJson,
                ByteBufCodecs.VAR_INT, Save::scope,
                Save::new);

        @Override
        public Type<Save> type() {
            return TYPE;
        }
    }
}
