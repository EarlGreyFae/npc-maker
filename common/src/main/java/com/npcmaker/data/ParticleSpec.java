package com.npcmaker.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Particles to show at an NPC: any simple particle type (vanilla or modded) with how many and how widely spread. In
 * JSON either the full object or just the id: {@code "minecraft:heart"}.
 *
 * @param interval for auras, ticks between puffs (20 = once a second); ignored for one-off effects
 */
public record ParticleSpec(Identifier particle, int count, float spread, int interval) {

    private static final Codec<ParticleSpec> FULL_CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("particle").forGetter(ParticleSpec::particle),
            Codec.intRange(1, 200).optionalFieldOf("count", 6).forGetter(ParticleSpec::count),
            Codec.floatRange(0.0F, 4.0F).optionalFieldOf("spread", 0.4F).forGetter(ParticleSpec::spread),
            Codec.intRange(1, 1200).optionalFieldOf("interval", 10).forGetter(ParticleSpec::interval)
    ).apply(i, ParticleSpec::new));

    public static final Codec<ParticleSpec> CODEC = Codec.withAlternative(FULL_CODEC,
            Identifier.CODEC.xmap(id -> new ParticleSpec(id, 6, 0.4F, 10), ParticleSpec::particle));

    /**
     * The particle type for an id: a registered one, or a custom particle ({@code npcmaker_custom:<name>}), which
     * plays through the particle slot it was given.
     */
    public static @org.jspecify.annotations.Nullable ParticleType<?> resolve(Identifier id) {
        if (id.getNamespace().equals(com.npcmaker.custom.CustomContent.NAMESPACE)) {
            Identifier slot = com.npcmaker.custom.CustomContent.particleType(id.getPath());
            return slot == null ? null : BuiltInRegistries.PARTICLE_TYPE.getValue(slot);
        }
        return BuiltInRegistries.PARTICLE_TYPE.getValue(id);
    }

    /** Shows the particles around the entity's middle; particles that need extra settings are skipped. */
    public void spawn(ServerLevel level, Entity at, int amount) {
        ParticleType<?> type = resolve(particle);
        if (type instanceof SimpleParticleType simple) {
            level.sendParticles(simple, at.getX(), at.getY() + at.getBbHeight() * 0.6, at.getZ(),
                    amount, spread, at.getBbHeight() * 0.35, spread, 0.02);
        }
    }

    public void spawn(ServerLevel level, Entity at) {
        spawn(level, at, count);
    }
}
