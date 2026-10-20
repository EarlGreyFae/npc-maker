package com.npcmaker.custom;

import com.npcmaker.Constants;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The particle slots custom particles play through: {@code npcmaker:custom_0} to {@code custom_31}. Particle types
 * can't be added while the game runs, so NPC Maker registers these at startup and each uploaded particle texture is
 * given one; the player's game points the slot at the texture when it builds the custom content pack. Each loader
 * registers them (and the client particle for them).
 */
public final class NpcParticles {

    public static final List<SimpleParticleType> CUSTOM = new ArrayList<>();

    static {
        for (int i = 0; i < CustomContent.PARTICLE_SLOTS; i++) {
            CUSTOM.add(new Slot());
        }
    }

    private NpcParticles() {
    }

    public static Identifier id(int slot) {
        return Constants.id("custom_" + slot);
    }

    private static final class Slot extends SimpleParticleType {
        Slot() {
            super(false);
        }
    }
}
