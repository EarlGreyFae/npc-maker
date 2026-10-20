package com.npcmaker.entity.ai;

import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcInstance;
import com.npcmaker.entity.PatrolRoute;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * Walks the NPC's patrol route: to each waypoint in turn, waiting there for the waypoint's wait time while facing
 * its direction (or glancing around), then on to the next as a loop or back and forth. After a fight or a
 * conversation it carries on from where it was.
 */
public class PatrolGoal extends Goal {

    private static final double ARRIVED_DISTANCE_SQR = 1.0;
    /** If the NPC hasn't got closer for this long, the waypoint is unreachable: skip to the next. */
    private static final int STUCK_TICKS = 200;

    private final NpcEntity npc;
    private int index;
    private int direction = 1;
    private int waitTicks = -1;
    private int repath;
    private int glance;
    private int stuckTicks;
    private double bestDistanceSqr = Double.MAX_VALUE;

    public PatrolGoal(NpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    private List<PatrolRoute.Waypoint> points() {
        NpcInstance instance = npc.instance();
        return instance == null ? List.of() : instance.patrol().points();
    }

    @Override
    public boolean canUse() {
        return npc.isPatrolActive() && npc.getTarget() == null && npc.conversationPartner() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repath = 0;
        stuckTicks = 0;
        bestDistanceSqr = Double.MAX_VALUE;
        if (index >= points().size()) {
            index = 0;
        }
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        List<PatrolRoute.Waypoint> points = points();
        if (points.isEmpty()) {
            return;
        }
        if (index >= points.size()) {
            index = 0;
        }
        PatrolRoute.Waypoint point = points.get(index);

        if (waitTicks >= 0) {
            waitAt(point);
            if (--waitTicks < 0) {
                advance(points.size());
            }
            return;
        }

        double distanceSqr = npc.distanceToSqr(point.pos());
        if (distanceSqr <= ARRIVED_DISTANCE_SQR) {
            npc.getNavigation().stop();
            waitTicks = point.waitSeconds() * 20;
            glance = 0;
            return;
        }
        if (distanceSqr < bestDistanceSqr - 0.25) {
            bestDistanceSqr = distanceSqr;
            stuckTicks = 0;
        } else if (++stuckTicks > STUCK_TICKS) {
            advance(points.size());
            return;
        }
        if (--repath <= 0) {
            repath = 20;
            Vec3 pos = point.pos();
            npc.getNavigation().moveTo(pos.x, pos.y, pos.z, 0.9);
        }
    }

    private void waitAt(PatrolRoute.Waypoint point) {
        if (point.yaw().isPresent()) {
            float yaw = point.yaw().get();
            Vec3 look = npc.getEyePosition().add(Vec3.directionFromRotation(0.0F, yaw).scale(4.0));
            npc.getLookControl().setLookAt(look.x, look.y, look.z, 10.0F, npc.getMaxHeadXRot());
            float body = Mth.approachDegrees(npc.yBodyRot, yaw, 10.0F);
            npc.setYBodyRot(body);
            npc.setYRot(body);
        } else if (--glance <= 0) {
            glance = 30 + npc.getRandom().nextInt(30);
            double angle = npc.getRandom().nextDouble() * Math.PI * 2.0;
            npc.getLookControl().setLookAt(npc.getX() + Math.cos(angle) * 4.0, npc.getEyeY(), npc.getZ() + Math.sin(angle) * 4.0);
        }
    }

    private void advance(int size) {
        waitTicks = -1;
        repath = 0;
        stuckTicks = 0;
        bestDistanceSqr = Double.MAX_VALUE;
        if (size <= 1) {
            index = 0;
            return;
        }
        NpcInstance instance = npc.instance();
        if (instance != null && instance.patrol().mode() == PatrolRoute.Mode.PING_PONG) {
            if (index + direction < 0 || index + direction >= size) {
                direction = -direction;
            }
            index += direction;
        } else {
            index = (index + 1) % size;
        }
    }
}
