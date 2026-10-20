package com.npcmaker.ai;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.faction.Factions;
import com.npcmaker.faction.Relation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * A check inside an AI rule's {@code "if"}. Target checks look at whoever the rule's trigger found (the nearby
 * player, the attacker…) and fail when there is none.
 */
public sealed interface AiCondition {

    String type();

    boolean test(NpcEntity npc, @Nullable LivingEntity target);

    /**
     * How this NPC's factions regard the target: "allied", "neutral" or "hostile". With {@code "value": false} it
     * means "anything but" (e.g. not allied).
     */
    record TargetRelation(Relation relation, boolean value) implements AiCondition {
        static final MapCodec<TargetRelation> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Relation.CODEC.fieldOf("relation").forGetter(TargetRelation::relation),
                Codec.BOOL.optionalFieldOf("value", true).forGetter(TargetRelation::value)
        ).apply(i, TargetRelation::new));

        public String type() {
            return "target_relation";
        }

        public boolean test(NpcEntity npc, @Nullable LivingEntity target) {
            return target != null && (Factions.relation(npc, target) == relation) == value;
        }
    }

    /** The target is a given entity type ("minecraft:zombie") or in a tag ("#minecraft:undead"). */
    record TargetType(String entity) implements AiCondition {
        static final MapCodec<TargetType> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("entity").forGetter(TargetType::entity)
        ).apply(i, TargetType::new));

        public String type() {
            return "target_type";
        }

        public boolean test(NpcEntity npc, @Nullable LivingEntity target) {
            if (target == null) {
                return false;
            }
            if (entity.startsWith("#")) {
                Identifier id = Identifier.tryParse(entity.substring(1));
                return id != null && target.is(TagKey.create(Registries.ENTITY_TYPE, id));
            }
            return BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString().equals(entity);
        }
    }

    /** The target is (or, with false, isn't) a player. */
    record IsPlayer(boolean value) implements AiCondition {
        static final MapCodec<IsPlayer> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.BOOL.optionalFieldOf("value", true).forGetter(IsPlayer::value)
        ).apply(i, IsPlayer::new));

        public String type() {
            return "is_player";
        }

        public boolean test(NpcEntity npc, @Nullable LivingEntity target) {
            return target != null && (target instanceof Player) == value;
        }
    }

    /** The NPC itself carries at least {@code count} of an item. */
    record HasItem(Item item, int count) implements AiCondition {
        static final MapCodec<HasItem> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(HasItem::item),
                Codec.intRange(1, 10000).optionalFieldOf("count", 1).forGetter(HasItem::count)
        ).apply(i, HasItem::new));

        public String type() {
            return "has_item";
        }

        public boolean test(NpcEntity npc, @Nullable LivingEntity target) {
            return npc.inventory().countItem(item) >= count;
        }
    }

    /** True only some of the time, rolled each time the rule is checked (about twice a second). */
    record Chance(float chance) implements AiCondition {
        static final MapCodec<Chance> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.floatRange(0.0F, 1.0F).fieldOf("chance").forGetter(Chance::chance)
        ).apply(i, Chance::new));

        public String type() {
            return "chance";
        }

        public boolean test(NpcEntity npc, @Nullable LivingEntity target) {
            return npc.getRandom().nextFloat() < chance;
        }
    }

    Map<String, MapCodec<? extends AiCondition>> TYPES = Map.of(
            "target_relation", TargetRelation.CODEC,
            "target_type", TargetType.CODEC,
            "is_player", IsPlayer.CODEC,
            "has_item", HasItem.CODEC,
            "chance", Chance.CODEC);

    Codec<String> TYPE_CODEC = Codec.STRING.validate(t -> TYPES.containsKey(t)
            ? DataResult.success(t)
            : DataResult.error(() -> "Unknown AI condition '" + t + "'. Known: " + TYPES.keySet()));

    Codec<AiCondition> CODEC = TYPE_CODEC.dispatch("type", AiCondition::type, TYPES::get);
}
