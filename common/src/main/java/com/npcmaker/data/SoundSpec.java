package com.npcmaker.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * A sound to play: any sound event id (vanilla, modded or from a resource pack) with volume and pitch. In JSON
 * either the full object or just the id: {@code "minecraft:entity.villager.ambient"}.
 */
public record SoundSpec(Identifier sound, float volume, float pitch) {

    private static final Codec<SoundSpec> FULL_CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("sound").forGetter(SoundSpec::sound),
            Codec.floatRange(0.0F, 10.0F).optionalFieldOf("volume", 1.0F).forGetter(SoundSpec::volume),
            Codec.floatRange(0.5F, 2.0F).optionalFieldOf("pitch", 1.0F).forGetter(SoundSpec::pitch)
    ).apply(i, SoundSpec::new));

    public static final Codec<SoundSpec> CODEC = Codec.withAlternative(FULL_CODEC,
            Identifier.CODEC.xmap(id -> new SoundSpec(id, 1.0F, 1.0F), SoundSpec::sound));

    /** Registered sounds resolve to their registry entry; resource-pack-only sounds still play by id. */
    public SoundEvent event() {
        return BuiltInRegistries.SOUND_EVENT.getOptional(sound).orElseGet(() -> SoundEvent.createVariableRangeEvent(sound));
    }
}
