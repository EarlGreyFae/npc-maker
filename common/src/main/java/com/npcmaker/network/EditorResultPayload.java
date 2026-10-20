package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: how a save went, shown in the editor's status line. */
public record EditorResultPayload(boolean ok, String message) implements CustomPacketPayload {

    public static final Type<EditorResultPayload> TYPE = new Type<>(Constants.id("editor_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EditorResultPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, EditorResultPayload::ok,
            ByteBufCodecs.STRING_UTF8, EditorResultPayload::message,
            EditorResultPayload::new);

    @Override
    public Type<EditorResultPayload> type() {
        return TYPE;
    }
}
