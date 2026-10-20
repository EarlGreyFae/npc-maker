package com.npcmaker.ai;

import com.npcmaker.entity.NpcEntity;
import com.npcmaker.faction.Factions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;

/** What an NPC treats as danger: whoever just hurt it, monsters close by, and anything its factions are hostile to. */
public final class Threats {

    /** Hurt within this many ticks counts as "being attacked right now". */
    public static final int RECENT_TICKS = 100;

    private Threats() {
    }

    public static @Nullable LivingEntity recentAttacker(LivingEntity npc, int withinTicks) {
        LivingEntity attacker = npc.getLastHurtByMob();
        if (attacker != null && attacker.isAlive() && npc.tickCount - npc.getLastHurtByMobTimestamp() <= withinTicks) {
            return attacker;
        }
        return null;
    }

    /** The nearest danger within {@code radius} blocks, or null if it's safe. */
    public static @Nullable LivingEntity nearestDanger(NpcEntity npc, double radius) {
        LivingEntity attacker = recentAttacker(npc, RECENT_TICKS);
        if (attacker != null && attacker.distanceToSqr(npc) < (radius * 2) * (radius * 2)) {
            return attacker;
        }
        return npc.level().getEntitiesOfClass(LivingEntity.class, npc.getBoundingBox().inflate(radius),
                        e -> e != npc && e.isAlive() && isDangerTo(npc, e))
                .stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(npc)))
                .orElse(null);
    }

    private static boolean isDangerTo(NpcEntity npc, LivingEntity other) {
        if (other instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return false;
        }
        if (other instanceof Mob mob && mob.getTarget() == npc) {
            return true;
        }
        if (other instanceof Enemy && !(other instanceof NpcEntity)) {
            return true;
        }
        return Factions.isHostile(npc, other);
    }

    /** A spot about 16 blocks away from {@code from}, or null if there's nowhere to go. */
    public static @Nullable Vec3 awayFrom(NpcEntity npc, Vec3 from) {
        return net.minecraft.world.entity.ai.util.DefaultRandomPos.getPosAway(npc, 16, 7, from);
    }
}
