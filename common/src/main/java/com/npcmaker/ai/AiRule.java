package com.npcmaker.ai;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.npcmaker.data.SoundSpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * One "when → if → then" AI rule from an NPC template's {@code "ai_rules"} list, e.g.
 * <pre>{ "when": "health_below", "value": 0.3, "then": "flee" }</pre>
 * Rules are checked about twice a second, top to bottom. For movement actions (flee, go_home, go_to, follow, stop)
 * the first rule that matches wins and takes over movement while it keeps matching; every other matching rule
 * (say, play_sound, attack…) also fires, at most once per its cooldown.
 *
 * @param value    the trigger's setting: a radius (player_near, entity_near), a fraction of max health
 *                 (health_below; 0.3 = 30%), "day"/"night" (time_is) or "clear"/"rain"/"thunder" (weather_is)
 * @param text     lines for "say" (one picked at random; styling tags work)
 * @param count    particles for "emit_particles"
 * @param cooldown seconds before this rule can fire again (default 10 for say/sound/command/item/particles)
 */
public record AiRule(Trigger when, Optional<Either<Double, String>> value, List<AiCondition> conditions, Action then,
                     List<String> text, Optional<SoundSpec> sound, Optional<Identifier> particle, int count,
                     Optional<Item> item, Optional<Vec3> pos, Optional<String> command, Optional<Integer> cooldown,
                     Optional<String> animation) {

    public enum Trigger implements StringRepresentable {
        ALWAYS("always"),
        /** A player (spectators excepted) is within {@code value} blocks (default 8). Target: that player. */
        PLAYER_NEAR("player_near"),
        /** Any living thing matching the conditions is within {@code value} blocks (default 8). Target: it. */
        ENTITY_NEAR("entity_near"),
        /** Hurt by something in the last 3 seconds. Target: the attacker. */
        ATTACKED("attacked"),
        /** An allied NPC within 16 blocks was hurt in the last 3 seconds. Target: its attacker. */
        ALLY_ATTACKED("ally_attacked"),
        /** Health is below {@code value} of max health (0.3 = 30%). */
        HEALTH_BELOW("health_below"),
        /** {@code value} is "day" or "night". */
        TIME_IS("time_is"),
        /** {@code value} is "clear", "rain" or "thunder". */
        WEATHER_IS("weather_is");

        public static final Codec<Trigger> CODEC = StringRepresentable.fromEnum(Trigger::values);
        private final String name;

        Trigger(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public enum Action implements StringRepresentable {
        /** Attack the target. */
        ATTACK("attack", false),
        /** Run away from the target (or whoever is threatening it). */
        FLEE("flee", true),
        GO_HOME("go_home", true),
        /** Walk to {@code pos}. */
        GO_TO("go_to", true),
        /** Follow the target (keeps about 3 blocks away). */
        FOLLOW("follow", true),
        /** Stand still. */
        STOP("stop", true),
        LOOK_AT("look_at", false),
        /** Speech bubble with one of {@code text}. */
        SAY("say", false),
        PLAY_SOUND("play_sound", false),
        /** {@code count} particles of type {@code particle} around the NPC. */
        EMIT_PARTICLES("emit_particles", false),
        /** Use {@code item} from the NPC's inventory the vanilla way (potions, food, milk…). */
        USE_ITEM("use_item", false),
        /**
         * Place a block from the inventory: {@code item} (or the first placeable item it carries) at {@code pos},
         * or next to the NPC when no {@code pos} is given (at its feet for things it can walk through, like torches).
         */
        PLACE_BLOCK("place_block", false),
        /** Run {@code command} as the NPC (placeholders {npc} and {target}). */
        RUN_COMMAND("run_command", false),
        /** Play one of the built-in animations ({@code animation}: wave, bow, cheer, point, salute, sit, sleep, read, work). */
        PLAY_ANIMATION("play_animation", false);

        public static final Codec<Action> CODEC = StringRepresentable.fromEnum(Action::values);
        private final String name;
        private final boolean movement;

        Action(String name, boolean movement) {
            this.name = name;
            this.movement = movement;
        }

        public boolean isMovement() {
            return movement;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final Codec<AiRule> CODEC = RecordCodecBuilder.create(i -> i.group(
            Trigger.CODEC.fieldOf("when").forGetter(AiRule::when),
            Codec.either(Codec.DOUBLE, Codec.STRING).optionalFieldOf("value").forGetter(AiRule::value),
            AiCondition.CODEC.listOf().optionalFieldOf("if", List.of()).forGetter(AiRule::conditions),
            Action.CODEC.fieldOf("then").forGetter(AiRule::then),
            Codec.withAlternative(Codec.STRING.listOf(), Codec.STRING.xmap(List::of, l -> l.isEmpty() ? "" : l.get(0)))
                    .optionalFieldOf("text", List.of()).forGetter(AiRule::text),
            SoundSpec.CODEC.optionalFieldOf("sound").forGetter(AiRule::sound),
            Identifier.CODEC.optionalFieldOf("particle").forGetter(AiRule::particle),
            Codec.intRange(1, 200).optionalFieldOf("count", 8).forGetter(AiRule::count),
            BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("item").forGetter(AiRule::item),
            Vec3.CODEC.optionalFieldOf("pos").forGetter(AiRule::pos),
            Codec.STRING.optionalFieldOf("command").forGetter(AiRule::command),
            Codec.intRange(0, 86400).optionalFieldOf("cooldown").forGetter(AiRule::cooldown),
            Codec.STRING.optionalFieldOf("animation").forGetter(AiRule::animation)
    ).apply(i, AiRule::new));

    public double number(double fallback) {
        return value.flatMap(v -> v.left()).orElse(fallback);
    }

    public String word(String fallback) {
        return value.flatMap(v -> v.right()).orElse(fallback);
    }

    /** Cooldown in ticks: as written, or 10 s for chatty/one-off actions and none for the rest. */
    public int cooldownTicks() {
        int seconds = cooldown.orElse(switch (then) {
            case SAY, PLAY_SOUND, RUN_COMMAND, USE_ITEM, PLACE_BLOCK, PLAY_ANIMATION -> 10;
            case EMIT_PARTICLES -> 2;
            default -> 0;
        });
        return seconds * 20;
    }
}
