package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Network messages for custom content (sounds, particle textures, Blockbench models and animations): uploads from
 * builders, and the server sending every player the files so their game can build the resource pack itself.
 */
public final class CustomPayloads {

    /** Bytes per upload piece (client → server packets are small). */
    public static final int UP_CHUNK = 30_000;
    /** Bytes per download piece. */
    public static final int DOWN_CHUNK = 500_000;

    private CustomPayloads() {
    }

    /** One file the server has: where it goes, its SHA-1 (for caching) and size. */
    public record FileEntry(String path, String sha1, int size) {
        public static final StreamCodec<RegistryFriendlyByteBuf, FileEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, FileEntry::path,
                ByteBufCodecs.STRING_UTF8, FileEntry::sha1,
                ByteBufCodecs.VAR_INT, FileEntry::size,
                FileEntry::new);
    }

    /** A custom particle and the particle slot (npcmaker:custom_N) it plays through. */
    public record Slot(String name, int slot) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Slot> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Slot::name,
                ByteBufCodecs.VAR_INT, Slot::slot,
                Slot::new);
    }

    /** Server → client: everything the world's custom content folder holds. Sent on join and after every change. */
    public record Manifest(List<FileEntry> files, List<Slot> particles) implements CustomPacketPayload {
        public static final Type<Manifest> TYPE = new Type<>(Constants.id("custom_manifest"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Manifest> CODEC = StreamCodec.composite(
                FileEntry.CODEC.apply(ByteBufCodecs.list()), Manifest::files,
                Slot.CODEC.apply(ByteBufCodecs.list()), Manifest::particles,
                Manifest::new);

        @Override
        public Type<Manifest> type() {
            return TYPE;
        }
    }

    /** Client → server: send me these files (by SHA-1); I don't have them cached. */
    public record RequestFiles(List<String> sha1s) implements CustomPacketPayload {
        public static final Type<RequestFiles> TYPE = new Type<>(Constants.id("custom_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestFiles> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(512)), RequestFiles::sha1s,
                RequestFiles::new);

        @Override
        public Type<RequestFiles> type() {
            return TYPE;
        }
    }

    /** Server → client: one piece of a file. */
    public record FilePart(String sha1, int part, int parts, byte[] data) implements CustomPacketPayload {
        public static final Type<FilePart> TYPE = new Type<>(Constants.id("custom_file"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FilePart> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, FilePart::sha1,
                ByteBufCodecs.VAR_INT, FilePart::part,
                ByteBufCodecs.VAR_INT, FilePart::parts,
                ByteBufCodecs.byteArray(DOWN_CHUNK), FilePart::data,
                FilePart::new);

        @Override
        public Type<FilePart> type() {
            return TYPE;
        }
    }

    /** Client → server: one piece of an uploaded file (named as on the builder's computer). */
    public record UploadPart(String fileName, int part, int parts, byte[] data) implements CustomPacketPayload {
        public static final Type<UploadPart> TYPE = new Type<>(Constants.id("custom_upload"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadPart> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, UploadPart::fileName,
                ByteBufCodecs.VAR_INT, UploadPart::part,
                ByteBufCodecs.VAR_INT, UploadPart::parts,
                ByteBufCodecs.byteArray(UP_CHUNK), UploadPart::data,
                UploadPart::new);

        @Override
        public Type<UploadPart> type() {
            return TYPE;
        }
    }

    /** Client → server: delete a custom file (its manifest path). */
    public record Delete(String path) implements CustomPacketPayload {
        public static final Type<Delete> TYPE = new Type<>(Constants.id("custom_delete"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Delete> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Delete::path,
                Delete::new);

        @Override
        public Type<Delete> type() {
            return TYPE;
        }
    }
}
