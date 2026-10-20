package com.npcmaker.entity.ai;

import com.npcmaker.ai.Threats;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * For NPCs that don't fight, like vanilla villagers: when danger shows up they run to their home spot and hide
 * there; if the danger is at home too, they run away from it instead. Once it has been safe for 10 seconds they
 * go back to what they were doing (standing, wandering, patrolling).
 */
public class FleeAndHideGoal extends Goal {

    private static final double DANGER_RADIUS = 10.0;
    /** Home only counts as a hiding place if the danger is at least this far from it. */
    private static final double SAFE_HOME_DISTANCE = 8.0;
    private static final int SAFE_TICKS = 200;
    private static final double RUN_SPEED = 1.4;

    private final NpcEntity npc;
    private LivingEntity danger;
    private int lastDangerTick;
    private int recheck;
    private int scanCooldown;

    public FleeAndHideGoal(NpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // Goals are only checked every other tick (offset per entity), so throttle with a countdown, not tickCount.
        if (--scanCooldown > 0) {
            return false;
        }
        scanCooldown = 5;
        danger = Threats.nearestDanger(npc, DANGER_RADIUS);
        return danger != null;
    }

    @Override
    public boolean canContinueToUse() {
        return npc.tickCount - lastDangerTick < SAFE_TICKS;
    }

    @Override
    public void start() {
        lastDangerTick = npc.tickCount;
        recheck = 0;
    }

    @Override
    public void stop() {
        danger = null;
        npc.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (--recheck > 0) {
            return;
        }
        recheck = 20;
        LivingEntity found = Threats.nearestDanger(npc, DANGER_RADIUS);
        if (found != null) {
            danger = found;
            lastDangerTick = npc.tickCount;
        } else if (danger != null && (!danger.isAlive() || danger.distanceToSqr(npc) > 24 * 24)) {
            danger = null;
        }
        if (danger == null) {
            // Safe for now: stay hidden and keep an eye out until the safe period is over.
            npc.getNavigation().stop();
            return;
        }
        NpcInstance instance = npc.instance();
        Vec3 home = instance != null && !instance.isCompanion() ? instance.home() : null;
        boolean homeIsSafe = home != null && danger.distanceToSqr(home) > SAFE_HOME_DISTANCE * SAFE_HOME_DISTANCE;
        if (homeIsSafe) {
            if (npc.distanceToSqr(home) > 1.5 * 1.5) {
                npc.getNavigation().moveTo(home.x, home.y, home.z, RUN_SPEED);
            } else {
                npc.getNavigation().stop();
                npc.getLookControl().setLookAt(danger);
            }
            return;
        }
        Vec3 away = Threats.awayFrom(npc, danger.position());
        if (away != null) {
            npc.getNavigation().moveTo(away.x, away.y, away.z, RUN_SPEED);
        }
    }
}
