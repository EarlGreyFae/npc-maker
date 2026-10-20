package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Server → client: open (or refresh) the NPC editor.
 *
 * @param rawJson       the template file as written (what gets saved back, so short files stay short)
 * @param effectiveJson the template with its AI-template settings merged in (what the fields show)
 * @param users         how many loaded NPCs use this template (for the save choice)
 * @param home          this NPC's home position; {@code yaw} and {@code pitch} its facing there
 */
public record OpenEditorPayload(int entityId, String template, String rawJson, String effectiveJson, int users,
                                Vec3 home, float yaw, float pitch) implements CustomPacketPayload {

    static final StreamCodec<io.netty.buffer.ByteBuf, String> BIG_STRING = ByteBufCodecs.stringUtf8(262144);

    public static final Type<OpenEditorPayload> TYPE = new Type<>(Constants.id("open_editor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenEditorPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.VAR_INT.encode(buf, p.entityId);
                ByteBufCodecs.STRING_UTF8.encode(buf, p.template);
                BIG_STRING.encode(buf, p.rawJson);
                BIG_STRING.encode(buf, p.effectiveJson);
                ByteBufCodecs.VAR_INT.encode(buf, p.users);
                buf.writeDouble(p.home.x);
                buf.writeDouble(p.home.y);
                buf.writeDouble(p.home.z);
                buf.writeFloat(p.yaw);
                buf.writeFloat(p.pitch);
            },
            buf -> new OpenEditorPayload(
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    BIG_STRING.decode(buf),
                    BIG_STRING.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                    buf.readFloat(),
                    buf.readFloat()));

    @Override
    public Type<OpenEditorPayload> type() {
        return TYPE;
    }
}
