package com.npcmaker.faction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * A named reputation band, e.g. "Friendly" from 20 upward. A faction's tiers are checked from the highest
 * {@code min} down; the first one the player's reputation reaches is their standing.
 *
 * @param color #RRGGBB used for the name on the reputation card and the Factions screen
 */
public record StandingTier(String name, int min, String color) {

    public static final Codec<StandingTier> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(StandingTier::name),
            Codec.intRange(FactionDefinition.MIN_REPUTATION, FactionDefinition.MAX_REPUTATION).fieldOf("min").forGetter(StandingTier::min),
            Codec.STRING.optionalFieldOf("color", "#BBBBBB").forGetter(StandingTier::color)
    ).apply(i, StandingTier::new));

    /**
     * Defaults, lined up with the default thresholds: Hated is where a faction turns hostile (-50 and below),
     * Honored is where it treats you as an ally (50 and above).
     */
    public static final List<StandingTier> DEFAULTS = List.of(
            new StandingTier("Revered", 75, "#E8C46A"),
            new StandingTier("Honored", 50, "#4FC3F7"),
            new StandingTier("Friendly", 20, "#7FD36A"),
            new StandingTier("Neutral", -19, "#BBBBBB"),
            new StandingTier("Disliked", -49, "#E67E22"),
            new StandingTier("Hated", FactionDefinition.MIN_REPUTATION, "#E04848"));

    public int colorRgb() {
        try {
            return Integer.parseInt(color.startsWith("#") ? color.substring(1) : color, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xBBBBBB;
        }
    }
}
