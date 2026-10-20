package com.npcmaker.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Movement, combat, death and item-use behaviour. Health and strength live in {@link Stats}; patrol routes live
 * on each placed NPC (set with the NPC Wand).
 *
 * @param lookAtPlayers  turn to face nearby players
 * @param respawn        come back at the home position after being killed, instead of staying dead
 * @param respawnSeconds delay before respawning
 * @param itemUse        which built-in item uses (healing, milk, food, weapon switching, ammo) are on
 * @param flee           NPCs that don't fight run from danger to their home, hide there until it's safe, then
 *                       carry on (like villagers); off makes them ignore danger
 */
public record Behavior(MovementMode movement, int wanderRadius, CombatStance combat,
                       boolean invulnerable, boolean lookAtPlayers, boolean respawn, int respawnSeconds,
                       ItemUse itemUse, boolean flee) {

    public static final Behavior DEFAULT = new Behavior(MovementMode.STATIONARY, 8, CombatStance.PASSIVE,
            true, true, true, 60, ItemUse.DEFAULT, true);

    public static final Codec<Behavior> CODEC = RecordCodecBuilder.create(i -> i.group(
            MovementMode.CODEC.optionalFieldOf("movement", DEFAULT.movement).forGetter(Behavior::movement),
            Codec.intRange(1, 128).optionalFieldOf("wander_radius", DEFAULT.wanderRadius).forGetter(Behavior::wanderRadius),
            CombatStance.CODEC.optionalFieldOf("combat", DEFAULT.combat).forGetter(Behavior::combat),
            Codec.BOOL.optionalFieldOf("invulnerable", DEFAULT.invulnerable).forGetter(Behavior::invulnerable),
            Codec.BOOL.optionalFieldOf("look_at_players", DEFAULT.lookAtPlayers).forGetter(Behavior::lookAtPlayers),
            Codec.BOOL.optionalFieldOf("respawn", DEFAULT.respawn).forGetter(Behavior::respawn),
            Codec.intRange(1, 86400).optionalFieldOf("respawn_seconds", DEFAULT.respawnSeconds).forGetter(Behavior::respawnSeconds),
            ItemUse.CODEC.optionalFieldOf("item_use", ItemUse.DEFAULT).forGetter(Behavior::itemUse),
            Codec.BOOL.optionalFieldOf("flee", DEFAULT.flee).forGetter(Behavior::flee)
    ).apply(i, Behavior::new));
}
