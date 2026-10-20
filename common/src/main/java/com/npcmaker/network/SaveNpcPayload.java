package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Client → server: save the editor.
 *
 * @param scope    {@link #ALL} updates the template (every NPC using it); {@link #ONLY_THIS} gives this NPC its own copy
 * @param home     the NPC's position and facing (always per-NPC, whatever the scope)
 * @param resetGear gear or inventory was edited, so re-equip and restock the NPCs from the saved template
 */
public record SaveNpcPayload(int entityId, String template, String rawJson, int scope, Vec3 home, float yaw, float pitch,
                             boolean resetGear) implements CustomPacketPayload {

    public static final int ALL = 0;
    public static final int ONLY_THIS = 1;

    public static final Type<SaveNpcPayload> TYPE = new Type<>(Constants.id("save_npc"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveNpcPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.VAR_INT.encode(buf, p.entityId);
                ByteBufCodecs.STRING_UTF8.encode(buf, p.template);
                OpenEditorPayload.BIG_STRING.encode(buf, p.rawJson);
                ByteBufCodecs.VAR_INT.encode(buf, p.scope);
                buf.writeDouble(p.home.x);
                buf.writeDouble(p.home.y);
                buf.writeDouble(p.home.z);
                buf.writeFloat(p.yaw);
                buf.writeFloat(p.pitch);
                buf.writeBoolean(p.resetGear);
            },
            buf -> new SaveNpcPayload(
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    OpenEditorPayload.BIG_STRING.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean()));

    @Override
    public Type<SaveNpcPayload> type() {
        return TYPE;
    }
}
