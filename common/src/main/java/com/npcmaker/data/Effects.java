package com.npcmaker.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Optional;

/**
 * Animations and particles for an NPC ({@code "effects"} in the NPC file).
 *
 * <pre>
 * "effects": {
 *   "idle_pose": "read",
 *   "idle_emotes": ["wave", "cheer"],
 *   "emote_interval_seconds": 40,
 *   "greet_emote": "wave",
 *   "aura": {"particle": "minecraft:end_rod", "count": 1, "interval": 20},
 *   "on_greet": "minecraft:heart",
 *   "on_hurt": "minecraft:damage_indicator",
 *   "on_death": "minecraft:soul",
 *   "on_quest_given": "minecraft:happy_villager",
 *   "on_quest_done": "minecraft:totem_of_undying"
 * }
 * </pre>
 *
 * @param idlePose             a pose (sit, sleep, read, work) held while the NPC stands still
 * @param idleEmotes           one of these plays now and then while a player is near
 * @param emoteIntervalSeconds average time between idle emotes (randomised ±25%)
 * @param greetEmote           played when a player starts talking to the NPC
 * @param aura                 particles shown around the NPC all the time (while players are within 32 blocks)
 */
public record Effects(Optional<String> idlePose, List<String> idleEmotes, int emoteIntervalSeconds,
                      Optional<String> greetEmote, Optional<ParticleSpec> aura, Optional<ParticleSpec> onGreet,
                      Optional<ParticleSpec> onHurt, Optional<ParticleSpec> onDeath, Optional<ParticleSpec> onQuestGiven,
                      Optional<ParticleSpec> onQuestDone) {

    public static final Effects NONE = new Effects(Optional.empty(), List.of(), 40, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public static final Codec<Effects> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("idle_pose").forGetter(Effects::idlePose),
            Codec.STRING.listOf().optionalFieldOf("idle_emotes", List.of()).forGetter(Effects::idleEmotes),
            Codec.intRange(5, 3600).optionalFieldOf("emote_interval_seconds", 40).forGetter(Effects::emoteIntervalSeconds),
            Codec.STRING.optionalFieldOf("greet_emote").forGetter(Effects::greetEmote),
            ParticleSpec.CODEC.optionalFieldOf("aura").forGetter(Effects::aura),
            ParticleSpec.CODEC.optionalFieldOf("on_greet").forGetter(Effects::onGreet),
            ParticleSpec.CODEC.optionalFieldOf("on_hurt").forGetter(Effects::onHurt),
            ParticleSpec.CODEC.optionalFieldOf("on_death").forGetter(Effects::onDeath),
            ParticleSpec.CODEC.optionalFieldOf("on_quest_given").forGetter(Effects::onQuestGiven),
            ParticleSpec.CODEC.optionalFieldOf("on_quest_done").forGetter(Effects::onQuestDone)
    ).apply(i, Effects::new));
}
