package com.npcmaker.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * Per-event sounds. Anything left out is silent, except hurt/death which fall back to the player sounds, and the
 * voice which falls back to a soft blip.
 *
 * @param ambient played now and then while idle
 * @param greet   played when a player interacts with the NPC
 * @param voice   blips while the NPC's dialogue text types out
 */
public record Sounds(Optional<SoundSpec> ambient, Optional<SoundSpec> greet,
                     Optional<SoundSpec> hurt, Optional<SoundSpec> death, Voice voice) {

    public static final Sounds NONE = new Sounds(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Voice.DEFAULT);

    public static final Codec<Sounds> CODEC = RecordCodecBuilder.create(i -> i.group(
            SoundSpec.CODEC.optionalFieldOf("ambient").forGetter(Sounds::ambient),
            SoundSpec.CODEC.optionalFieldOf("greet").forGetter(Sounds::greet),
            SoundSpec.CODEC.optionalFieldOf("hurt").forGetter(Sounds::hurt),
            SoundSpec.CODEC.optionalFieldOf("death").forGetter(Sounds::death),
            Voice.CODEC.optionalFieldOf("voice", Voice.DEFAULT).forGetter(Sounds::voice)
    ).apply(i, Sounds::new));
}
