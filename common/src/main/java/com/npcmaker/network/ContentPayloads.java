package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Network messages for editing world-wide content files (quests and factions) from the in-game editors.
 * {@code kind} is {@link #QUEST} or {@link #FACTION}.
 */
public final class ContentPayloads {

    public static final String QUEST = "quest";
    public static final String FACTION = "faction";

    private ContentPayloads() {
    }

    /**
     * One entry of the index.
     *
     * @param label display name (NPC name, faction name, quest title)
     * @param extra NPC: nothing; faction: its colour; quest: the giver's template id
     */
    public record Entry(String id, String label, String extra) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Entry::id,
                ByteBufCodecs.STRING_UTF8, Entry::label,
                ByteBufCodecs.STRING_UTF8, Entry::extra,
                Entry::new);
    }

    /** Server → client: what NPC templates, factions and quests exist, for the editors' pickers. */
    public record Index(List<Entry> npcs, List<Entry> factions, List<Entry> quests) implements CustomPacketPayload {
        public static final Type<Index> TYPE = new Type<>(Constants.id("content_index"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Index> CODEC = StreamCodec.composite(
                Entry.CODEC.apply(ByteBufCodecs.list()), Index::npcs,
                Entry.CODEC.apply(ByteBufCodecs.list()), Index::factions,
                Entry.CODEC.apply(ByteBufCodecs.list()), Index::quests,
                Index::new);

        @Override
        public Type<Index> type() {
            return TYPE;
        }
    }

    /** Client → server: send me this file. */
    public record Request(String kind, String id) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Constants.id("request_content"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Request::kind,
                ByteBufCodecs.STRING_UTF8, Request::id,
                Request::new);

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /** Server → client: a file as written ("" if there's no such file). */
    public record File(String kind, String id, String rawJson) implements CustomPacketPayload {
        public static final Type<File> TYPE = new Type<>(Constants.id("content_file"));
        public static final StreamCodec<RegistryFriendlyByteBuf, File> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, File::kind,
                ByteBufCodecs.STRING_UTF8, File::id,
                OpenEditorPayload.BIG_STRING, File::rawJson,
                File::new);

        @Override
        public Type<File> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: save a file.
     *
     * @param id     the file to overwrite, or (with {@code create}) the wanted id for a new one
     * @param create make a new file; the server picks a free id close to {@code id}
     */
    public record Save(String kind, String id, String rawJson, boolean create) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(Constants.id("save_content"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Save> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Save::kind,
                ByteBufCodecs.STRING_UTF8, Save::id,
                OpenEditorPayload.BIG_STRING, Save::rawJson,
                ByteBufCodecs.BOOL, Save::create,
                Save::new);

        @Override
        public Type<Save> type() {
            return TYPE;
        }
    }

    /** Server → client: a file was saved under this id. */
    public record Saved(String kind, String id) implements CustomPacketPayload {
        public static final Type<Saved> TYPE = new Type<>(Constants.id("content_saved"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Saved> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Saved::kind,
                ByteBufCodecs.STRING_UTF8, Saved::id,
                Saved::new);

        @Override
        public Type<Saved> type() {
            return TYPE;
        }
    }
}
