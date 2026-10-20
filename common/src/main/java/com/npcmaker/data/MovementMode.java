package com.npcmaker.data;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

public enum MovementMode implements StringRepresentable {
    /** Stands at its home position and walks back if pushed away. */
    STATIONARY("stationary"),
    /** Wanders randomly within a radius of home. */
    WANDER("wander"),
    /** Walks an ordered list of waypoints. */
    PATROL("patrol"),
    /** Follows a player (escort quests, companions). */
    FOLLOW("follow");

    public static final Codec<MovementMode> CODEC = StringRepresentable.fromEnum(MovementMode::values);

    private final String name;

    MovementMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
