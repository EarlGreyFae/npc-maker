package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Network messages for the Live list (every NPC loaded right now): the I key, the editor's Live tab and the
 * stand-alone Live screen. Shaped like Enemy Maker's Live list so the two behave the same.
 */
public final class LivePayloads {

    /** I was pressed: edit the NPC being looked at, or open the Live list. */
    public static final String KEY = "key";
    public static final String LIST = "list";
    public static final String EDIT = "edit";
    public static final String TELEPORT = "tp";
    public static final String REMOVE = "remove";

    private LivePayloads() {
    }

    /** Client → server: one of the actions above; {@code uuid} names the NPC ("" for key / list). */
    public record Action(String action, String uuid) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Constants.id("live_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Action::action,
                ByteBufCodecs.STRING_UTF8, Action::uuid,
                Action::new);

        @Override
        public Type<Action> type() {
            return TYPE;
        }
    }

    /**
     * Server → client: every loaded NPC, as a JSON array of objects with uuid, id (entity id), template, name,
     * dimension, x, y, z, distance (-1 in another dimension), health and max_health.
     *
     * @param open open the stand-alone Live screen (the I key found no NPC to edit); otherwise just refresh
     */
    public record Snapshot(String json, boolean open) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(Constants.id("live_list"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.composite(
                OpenEditorPayload.BIG_STRING, Snapshot::json,
                ByteBufCodecs.BOOL, Snapshot::open,
                Snapshot::new);

        @Override
        public Type<Snapshot> type() {
            return TYPE;
        }
    }
}
