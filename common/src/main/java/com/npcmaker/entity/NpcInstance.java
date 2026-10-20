package com.npcmaker.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * What a placed NPC knows beyond its template: which template it uses, where it belongs, who it follows as a
 * companion, and its patrol route. Saved on the entity and in the respawn queue.
 *
 * @param home    where the NPC stands, and where it respawns
 * @param yaw     body/head rotation at home, in degrees
 * @param pitch   head pitch at home, in degrees
 * @param owner   the player this NPC is a companion of, if any
 * @param waiting a companion told to wait stays put instead of following
 * @param patrol  waypoints to walk; empty stands at home
 */
public record NpcInstance(String template, Vec3 home, float yaw, float pitch, Optional<UUID> owner, boolean waiting,
                          PatrolRoute patrol) {

    public static final Codec<NpcInstance> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("template").forGetter(NpcInstance::template),
            Vec3.CODEC.fieldOf("home").forGetter(NpcInstance::home),
            Codec.FLOAT.optionalFieldOf("yaw", 0.0F).forGetter(NpcInstance::yaw),
            Codec.FLOAT.optionalFieldOf("pitch", 0.0F).forGetter(NpcInstance::pitch),
            UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(NpcInstance::owner),
            Codec.BOOL.optionalFieldOf("waiting", false).forGetter(NpcInstance::waiting),
            PatrolRoute.CODEC.optionalFieldOf("patrol", PatrolRoute.NONE).forGetter(NpcInstance::patrol)
    ).apply(i, NpcInstance::new));

    public static NpcInstance placed(String template, Vec3 home, float yaw, float pitch) {
        return new NpcInstance(template, home, yaw, pitch, Optional.empty(), false, PatrolRoute.NONE);
    }

    public NpcInstance withTemplate(String template) {
        return new NpcInstance(template, home, yaw, pitch, owner, waiting, patrol);
    }

    public NpcInstance withHome(Vec3 home, float yaw, float pitch) {
        return new NpcInstance(template, home, yaw, pitch, owner, waiting, patrol);
    }

    public NpcInstance withOwner(Optional<UUID> owner) {
        return new NpcInstance(template, home, yaw, pitch, owner, false, patrol);
    }

    public NpcInstance withWaiting(boolean waiting) {
        return new NpcInstance(template, home, yaw, pitch, owner, waiting, patrol);
    }

    public NpcInstance withPatrol(PatrolRoute patrol) {
        return new NpcInstance(template, home, yaw, pitch, owner, waiting, patrol);
    }

    public boolean isCompanion() {
        return owner.isPresent();
    }

    /** Patrols when it has waypoints and isn't busy being someone's companion. */
    public boolean isPatrolling() {
        return !patrol.isEmpty() && !isCompanion();
    }
}
