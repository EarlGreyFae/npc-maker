package com.npcmaker.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;

/**
 * The "talking" blips played while an NPC's dialogue text types out. Any sound works, including modded and
 * resource-pack sounds; pitch sets how deep or high the voice is (0.5 = deep, 2.0 = squeaky).
 *
 * <pre>"voice": { "sound": "minecraft:block.note_block.bass", "pitch": 0.8 }</pre>
 * Or just a sound id, or {@code "voice": {"enabled": false}} for silence.
 */
public record Voice(Identifier sound, float pitch, float volume, boolean enabled) {

    public static final Voice DEFAULT = new Voice(Identifier.withDefaultNamespace("block.note_block.bit"), 1.0F, 0.18F, true);

    private static final Codec<Voice> FULL_CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.optionalFieldOf("sound", DEFAULT.sound).forGetter(Voice::sound),
            Codec.floatRange(0.5F, 2.0F).optionalFieldOf("pitch", DEFAULT.pitch).forGetter(Voice::pitch),
            Codec.floatRange(0.0F, 1.0F).optionalFieldOf("volume", DEFAULT.volume).forGetter(Voice::volume),
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(Voice::enabled)
    ).apply(i, Voice::new));

    public static final Codec<Voice> CODEC = Codec.withAlternative(FULL_CODEC,
            Identifier.CODEC.xmap(id -> new Voice(id, DEFAULT.pitch, DEFAULT.volume, true), Voice::sound));
}
