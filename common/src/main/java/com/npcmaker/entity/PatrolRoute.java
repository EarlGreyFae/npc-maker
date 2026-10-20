package com.npcmaker.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A placed NPC's patrol: waypoints in order, walked as a loop or back and forth. Set with the NPC Wand or
 * {@code /npcmaker patrol}. An NPC with at least one waypoint patrols instead of standing at home.
 */
public record PatrolRoute(List<Waypoint> points, Mode mode) {

    public static final PatrolRoute NONE = new PatrolRoute(List.of(), Mode.LOOP);

    /** Default pause at each waypoint, in seconds. */
    public static final int DEFAULT_WAIT = 2;

    public enum Mode implements StringRepresentable {
        /** 1 → 2 → 3 → 1 → 2 … */
        LOOP("loop"),
        /** 1 → 2 → 3 → 2 → 1 → 2 … */
        PING_PONG("ping_pong");

        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);
        private final String name;

        Mode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * @param waitSeconds how long to stop here
     * @param yaw         which way to face while waiting; empty glances around instead
     */
    public record Waypoint(Vec3 pos, int waitSeconds, Optional<Float> yaw) {
        public static final Codec<Waypoint> CODEC = RecordCodecBuilder.create(i -> i.group(
                Vec3.CODEC.fieldOf("pos").forGetter(Waypoint::pos),
                Codec.intRange(0, 3600).optionalFieldOf("wait", DEFAULT_WAIT).forGetter(Waypoint::waitSeconds),
                Codec.FLOAT.optionalFieldOf("yaw").forGetter(Waypoint::yaw)
        ).apply(i, Waypoint::new));

        public Waypoint withWait(int seconds) {
            return new Waypoint(pos, seconds, yaw);
        }

        public Waypoint withYaw(Optional<Float> newYaw) {
            return new Waypoint(pos, waitSeconds, newYaw);
        }
    }

    public static final Codec<PatrolRoute> CODEC = RecordCodecBuilder.create(i -> i.group(
            Waypoint.CODEC.listOf().optionalFieldOf("points", List.of()).forGetter(PatrolRoute::points),
            Mode.CODEC.optionalFieldOf("mode", Mode.LOOP).forGetter(PatrolRoute::mode)
    ).apply(i, PatrolRoute::new));

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public PatrolRoute withPoint(Waypoint point) {
        List<Waypoint> list = new ArrayList<>(points);
        list.add(point);
        return new PatrolRoute(List.copyOf(list), mode);
    }

    public PatrolRoute withPoint(int index, Waypoint point) {
        List<Waypoint> list = new ArrayList<>(points);
        list.set(index, point);
        return new PatrolRoute(List.copyOf(list), mode);
    }

    public PatrolRoute withoutPoint(int index) {
        List<Waypoint> list = new ArrayList<>(points);
        list.remove(index);
        return new PatrolRoute(List.copyOf(list), mode);
    }

    public PatrolRoute withMode(Mode newMode) {
        return new PatrolRoute(points, newMode);
    }
}
