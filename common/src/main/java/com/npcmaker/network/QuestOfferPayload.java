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

/**
 * Server → client: show the quest offer card (title, story, first step, rewards) with Accept / Decline.
 *
 * @param stages      how many steps the quest has
 * @param objectives  labels of the first step's objectives (with counts)
 * @param rewardItems fixed item rewards, shown as icons
 * @param rewardLines other fixed rewards as text (XP, reputation, …)
 * @param chooseOne   items the player will pick one of at the end
 */
public record QuestOfferPayload(String quest, Component title, Component description, Component giver, int stages,
                                Component stepText, List<Component> objectives, List<ItemStack> rewardItems,
                                List<Component> rewardLines, List<ItemStack> chooseOne) implements CustomPacketPayload {

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Component>> TEXTS =
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(32));
    private static final StreamCodec<RegistryFriendlyByteBuf, List<ItemStack>> ITEMS =
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(32));

    public static final Type<QuestOfferPayload> TYPE = new Type<>(Constants.id("quest_offer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestOfferPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.quest);
                ComponentSerialization.STREAM_CODEC.encode(buf, p.title);
                ComponentSerialization.STREAM_CODEC.encode(buf, p.description);
                ComponentSerialization.STREAM_CODEC.encode(buf, p.giver);
                ByteBufCodecs.VAR_INT.encode(buf, p.stages);
                ComponentSerialization.STREAM_CODEC.encode(buf, p.stepText);
                TEXTS.encode(buf, p.objectives);
                ITEMS.encode(buf, p.rewardItems);
                TEXTS.encode(buf, p.rewardLines);
                ITEMS.encode(buf, p.chooseOne);
            },
            buf -> new QuestOfferPayload(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ComponentSerialization.STREAM_CODEC.decode(buf),
                    ComponentSerialization.STREAM_CODEC.decode(buf),
                    ComponentSerialization.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ComponentSerialization.STREAM_CODEC.decode(buf),
                    TEXTS.decode(buf),
                    ITEMS.decode(buf),
                    TEXTS.decode(buf),
                    ITEMS.decode(buf)));

    @Override
    public Type<QuestOfferPayload> type() {
        return TYPE;
    }
}
