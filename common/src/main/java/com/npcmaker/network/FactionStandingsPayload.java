package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/** Server → client: open (or refresh) the Factions screen with the player's standing in each visible faction. */
public record FactionStandingsPayload(List<Row> rows) implements CustomPacketPayload {

    /**
     * @param name       faction name in its colour
     * @param member     whether the player belongs to it
     * @param reputation -100..100
     * @param standing   tier name in the tier's colour
     * @param tierColor  the tier colour as 0xRRGGBB, for the reputation bar
     * @param relation   how the faction sees the player: "allied", "neutral" or "hostile"
     */
    public record Row(Component name, boolean member, int reputation, Component standing, int tierColor, String relation) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Row> CODEC = StreamCodec.composite(
                ComponentSerialization.STREAM_CODEC, Row::name,
                ByteBufCodecs.BOOL, Row::member,
                ByteBufCodecs.VAR_INT, Row::reputation,
                ComponentSerialization.STREAM_CODEC, Row::standing,
                ByteBufCodecs.INT, Row::tierColor,
                ByteBufCodecs.STRING_UTF8, Row::relation,
                Row::new);
    }

    public static final Type<FactionStandingsPayload> TYPE = new Type<>(Constants.id("faction_standings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FactionStandingsPayload> CODEC =
            Row.CODEC.apply(ByteBufCodecs.list(256)).map(FactionStandingsPayload::new, FactionStandingsPayload::rows);

    @Override
    public Type<FactionStandingsPayload> type() {
        return TYPE;
    }
}
