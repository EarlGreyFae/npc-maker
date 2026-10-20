package com.npcmaker.network;

import com.npcmaker.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Server → client: a finished quest lets the player pick one of these items. */
public record RewardChoicePayload(String quest, Component title, List<ItemStack> items) implements CustomPacketPayload {

    public static final Type<RewardChoicePayload> TYPE = new Type<>(Constants.id("reward_choice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RewardChoicePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RewardChoicePayload::quest,
            ComponentSerialization.STREAM_CODEC, RewardChoicePayload::title,
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(9)), RewardChoicePayload::items,
            RewardChoicePayload::new);

    @Override
    public Type<RewardChoicePayload> type() {
        return TYPE;
    }
}
