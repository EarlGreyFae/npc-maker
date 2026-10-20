package com.npcmaker.network;

import com.npcmaker.Constants;
import com.npcmaker.content.SkinLibrary;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/** Network messages for the shared skin library. */
public final class SkinPayloads {

    private SkinPayloads() {
    }

    /** Client → server: add a PNG to the library (editor only). */
    public record Upload(String fileName, byte[] png) implements CustomPacketPayload {
        public static final Type<Upload> TYPE = new Type<>(Constants.id("upload_skin"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Upload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Upload::fileName,
                ByteBufCodecs.byteArray(SkinLibrary.MAX_BYTES), Upload::png,
                Upload::new);

        @Override
        public Type<Upload> type() {
            return TYPE;
        }
    }

    /** Client → server: send me this skin's picture (an NPC near me wears it). */
    public record Request(String name) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Constants.id("request_skin"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Request::name,
                Request::new);

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /** Server → client: a skin's picture (empty if it's not in the library). */
    public record Data(String name, byte[] png) implements CustomPacketPayload {
        public static final Type<Data> TYPE = new Type<>(Constants.id("skin_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Data::name,
                ByteBufCodecs.byteArray(SkinLibrary.MAX_BYTES), Data::png,
                Data::new);

        @Override
        public Type<Data> type() {
            return TYPE;
        }
    }

    /**
     * Server → client: the library's skin names for the editor.
     *
     * @param added the skin just uploaded (to select it), or ""
     */
    public record Library(List<String> names, String added) implements CustomPacketPayload {
        public static final Type<Library> TYPE = new Type<>(Constants.id("skin_library"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Library> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(4096)), Library::names,
                ByteBufCodecs.STRING_UTF8, Library::added,
                Library::new);

        @Override
        public Type<Library> type() {
            return TYPE;
        }
    }
}
