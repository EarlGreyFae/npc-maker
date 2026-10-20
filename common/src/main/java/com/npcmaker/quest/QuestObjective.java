package com.npcmaker.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.npcmaker.content.NpcContent;
import com.npcmaker.data.NpcDefinition;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.faction.FactionDefinition;
import com.npcmaker.faction.Factions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Optional;

/**
 * One goal inside a quest stage. In JSON: {@code {"type": "kill", "entity": "minecraft:zombie", "count": 5}}.
 * Every objective can have a {@code "text"} to show instead of the automatic label ("Kill Zombie").
 */
public sealed interface QuestObjective {

    String type();

    /** How many times it has to happen (1 for talk / reach / custom). */
    int count();

    Optional<String> text();

    /** The label shown in the journal and tracker, without the "2/5" counter. */
    Component label();

    default Component labelOr(String fallback) {
        return text().<Component>map(Component::literal).orElseGet(() -> Component.literal(fallback));
    }

    /**
     * Kill something: an entity type ({@code "minecraft:zombie"}) or tag ({@code "#minecraft:undead"}), members of a
     * faction ({@code "faction": "vampire_court"}) or NPCs of a template ({@code "npc": "bandit"}). Give one of them.
     */
    record Kill(Optional<String> entity, Optional<String> faction, Optional<String> npc, int count, Optional<String> text)
            implements QuestObjective {
        static final MapCodec<Kill> CODEC = RecordCodecBuilder.<Kill>mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("entity").forGetter(Kill::entity),
                Codec.STRING.optionalFieldOf("faction").forGetter(Kill::faction),
                Codec.STRING.optionalFieldOf("npc").forGetter(Kill::npc),
                Codec.intRange(1, 100000).optionalFieldOf("count", 1).forGetter(Kill::count),
                Codec.STRING.optionalFieldOf("text").forGetter(Kill::text)
        ).apply(i, Kill::new)).validate(k -> k.entity.isPresent() || k.faction.isPresent() || k.npc.isPresent()
                ? DataResult.success(k)
                : DataResult.error(() -> "A kill objective needs \"entity\", \"faction\" or \"npc\""));

        public String type() {
            return "kill";
        }

        public boolean matches(LivingEntity victim) {
            if (npc.isPresent()) {
                return victim instanceof NpcEntity n && n.instance() != null && n.instance().template().equals(npc.get());
            }
            if (faction.isPresent()) {
                return Factions.factionsOf(victim).contains(faction.get());
            }
            String spec = entity.orElse("");
            if (spec.startsWith("#")) {
                Identifier tag = Identifier.tryParse(spec.substring(1));
                return tag != null && victim.is(TagKey.create(Registries.ENTITY_TYPE, tag));
            }
            Identifier id = Identifier.tryParse(spec);
            return id != null && id.equals(BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()));
        }

        public Component label() {
            String what;
            if (npc.isPresent()) {
                what = NpcContent.NPCS.get(npc.get()).map(NpcDefinition::displayName).orElse(npc.get());
            } else if (faction.isPresent()) {
                what = NpcContent.FACTIONS.get(faction.get()).map(FactionDefinition::name).orElse(faction.get()) + " members";
            } else {
                String spec = entity.orElse("?");
                if (spec.startsWith("#")) {
                    String path = spec.substring(spec.indexOf(':') + 1);
                    what = Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
                } else {
                    Identifier id = Identifier.tryParse(spec);
                    Optional<EntityType<?>> type = id == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(id);
                    what = type.map(t -> t.getDescription().getString()).orElse(spec);
                }
            }
            return labelOr("Kill " + what);
        }
    }

    /** Have {@code count} of an item in your inventory (checked every second; nothing is taken). */
    record Collect(Item item, int count, Optional<String> text) implements QuestObjective {
        static final MapCodec<Collect> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Collect::item),
                Codec.intRange(1, 100000).optionalFieldOf("count", 1).forGetter(Collect::count),
                Codec.STRING.optionalFieldOf("text").forGetter(Collect::text)
        ).apply(i, Collect::new));

        public String type() {
            return "collect";
        }

        public Component label() {
            return labelOr("Collect " + item.getDefaultInstance().getHoverName().getString());
        }
    }

    /** Bring {@code count} of an item to an NPC of a template and talk to it; the items are handed over. */
    record Deliver(Item item, int count, String npc, Optional<String> text) implements QuestObjective {
        static final MapCodec<Deliver> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Deliver::item),
                Codec.intRange(1, 100000).optionalFieldOf("count", 1).forGetter(Deliver::count),
                Codec.STRING.fieldOf("npc").forGetter(Deliver::npc),
                Codec.STRING.optionalFieldOf("text").forGetter(Deliver::text)
        ).apply(i, Deliver::new));

        public String type() {
            return "deliver";
        }

        public Component label() {
            return labelOr("Bring " + item.getDefaultInstance().getHoverName().getString() + " to " + npcName(npc));
        }
    }

    /** Talk to (right-click) an NPC of a template. */
    record Talk(String npc, Optional<String> text) implements QuestObjective {
        static final MapCodec<Talk> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("npc").forGetter(Talk::npc),
                Codec.STRING.optionalFieldOf("text").forGetter(Talk::text)
        ).apply(i, Talk::new));

        public String type() {
            return "talk";
        }

        public int count() {
            return 1;
        }

        public Component label() {
            return labelOr("Talk to " + npcName(npc));
        }
    }

    /** Get within {@code radius} blocks (default 5) of a spot, optionally only in one dimension. */
    record Reach(Vec3 pos, double radius, Optional<Identifier> dimension, Optional<String> text) implements QuestObjective {
        static final MapCodec<Reach> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Vec3.CODEC.fieldOf("pos").forGetter(Reach::pos),
                Codec.doubleRange(0.5, 1024).optionalFieldOf("radius", 5.0).forGetter(Reach::radius),
                Identifier.CODEC.optionalFieldOf("dimension").forGetter(Reach::dimension),
                Codec.STRING.optionalFieldOf("text").forGetter(Reach::text)
        ).apply(i, Reach::new));

        public String type() {
            return "reach";
        }

        public int count() {
            return 1;
        }

        public Component label() {
            return labelOr(String.format("Go to %.0f %.0f %.0f", pos.x, pos.y, pos.z));
        }
    }

    /** Ticked off by a dialogue action ({@code complete_objective}) or {@code /npcmaker quest complete}. */
    record Custom(String id, int count, Optional<String> text) implements QuestObjective {
        static final MapCodec<Custom> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Custom::id),
                Codec.intRange(1, 100000).optionalFieldOf("count", 1).forGetter(Custom::count),
                Codec.STRING.optionalFieldOf("text").forGetter(Custom::text)
        ).apply(i, Custom::new));

        public String type() {
            return "custom";
        }

        public Component label() {
            return labelOr(id.replace('_', ' '));
        }
    }

    private static String npcName(String template) {
        return NpcContent.NPCS.get(template).map(NpcDefinition::displayName).orElse(template);
    }

    Map<String, MapCodec<? extends QuestObjective>> TYPES = Map.of(
            "kill", Kill.CODEC,
            "collect", Collect.CODEC,
            "deliver", Deliver.CODEC,
            "talk", Talk.CODEC,
            "reach", Reach.CODEC,
            "custom", Custom.CODEC);

    Codec<String> TYPE_CODEC = Codec.STRING.validate(t -> TYPES.containsKey(t)
            ? DataResult.success(t)
            : DataResult.error(() -> "Unknown objective type '" + t + "'. Known: " + TYPES.keySet()));

    Codec<QuestObjective> CODEC = TYPE_CODEC.dispatch("type", QuestObjective::type, TYPES::get);
}
