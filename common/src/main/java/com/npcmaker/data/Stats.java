package com.npcmaker.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Combat stats, applied to the NPC's vanilla attributes.
 *
 * @param health              max health in half-hearts (20 = 10 hearts, like a player)
 * @param strength            melee attack damage in half-hearts, before weapon bonuses
 * @param armor               armor points (0–30)
 * @param speed               movement speed (0.3 ≈ a walking player)
 * @param knockbackResistance 0 = normal knockback, 1 = immovable
 */
public record Stats(double health, double strength, double armor, double speed, double knockbackResistance) {

    public static final Stats DEFAULT = new Stats(20.0, 3.0, 0.0, 0.3, 0.0);

    public static final Codec<Stats> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.doubleRange(1.0, 1024.0).optionalFieldOf("health", DEFAULT.health).forGetter(Stats::health),
            Codec.doubleRange(0.0, 2048.0).optionalFieldOf("strength", DEFAULT.strength).forGetter(Stats::strength),
            Codec.doubleRange(0.0, 30.0).optionalFieldOf("armor", DEFAULT.armor).forGetter(Stats::armor),
            Codec.doubleRange(0.0, 1.0).optionalFieldOf("speed", DEFAULT.speed).forGetter(Stats::speed),
            Codec.doubleRange(0.0, 1.0).optionalFieldOf("knockback_resistance", DEFAULT.knockbackResistance).forGetter(Stats::knockbackResistance)
    ).apply(i, Stats::new));
}
