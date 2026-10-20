package com.npcmaker.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * What an NPC says on its own, in a speech bubble above its name. Every list can hold as many lines as you like
 * (one is picked at random), and lines can use styling tags like {@code <i>…</i>}.
 *
 * <pre>
 * "speech": {
 *   "approach": ["Halt, traveller."],
 *   "idle": ["Quiet tonight...", "Keep your torch lit."],
 *   "hit_warning": ["Hey! Watch it!"],
 *   "radius": 8,
 *   "interval_seconds": 30
 * }
 * </pre>
 *
 * @param bubbles                 false silences every bubble from this NPC (approach, idle and hit warnings)
 * @param approach                said once when a player comes within {@code radius}
 * @param idle                    said now and then while a player stays within {@code radius}
 * @param hitWarning              said on a first, possibly accidental hit (see the two-hit rule)
 * @param warnOnHit               false keeps the two-hit rule but forgives the first hit silently
 * @param radius                  how close a player has to be, in blocks
 * @param intervalSeconds         average time between idle lines (randomised ±25%)
 * @param approachCooldownSeconds how long before the same player gets an approach line again
 */
public record Speech(boolean bubbles, List<String> approach, List<String> idle, List<String> hitWarning,
                     boolean warnOnHit, int radius, int intervalSeconds, int approachCooldownSeconds) {

    public static final List<String> DEFAULT_HIT_WARNING = List.of("Hey! Watch it!");

    public static final Speech DEFAULT = new Speech(true, List.of(), List.of(), DEFAULT_HIT_WARNING, true, 8, 30, 120);

    public static final Codec<Speech> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("bubbles", DEFAULT.bubbles).forGetter(Speech::bubbles),
            Codec.STRING.listOf().optionalFieldOf("approach", List.of()).forGetter(Speech::approach),
            Codec.STRING.listOf().optionalFieldOf("idle", List.of()).forGetter(Speech::idle),
            Codec.STRING.listOf(1, Integer.MAX_VALUE).optionalFieldOf("hit_warning", DEFAULT_HIT_WARNING).forGetter(Speech::hitWarning),
            Codec.BOOL.optionalFieldOf("warn_on_hit", DEFAULT.warnOnHit).forGetter(Speech::warnOnHit),
            Codec.intRange(1, 32).optionalFieldOf("radius", DEFAULT.radius).forGetter(Speech::radius),
            Codec.intRange(5, 3600).optionalFieldOf("interval_seconds", DEFAULT.intervalSeconds).forGetter(Speech::intervalSeconds),
            Codec.intRange(0, 86400).optionalFieldOf("approach_cooldown_seconds", DEFAULT.approachCooldownSeconds).forGetter(Speech::approachCooldownSeconds)
    ).apply(i, Speech::new));
}
