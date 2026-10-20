package com.npcmaker.entity.ai;

import com.npcmaker.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Companions walk after their owner, and catch up by teleporting when they fall far behind. */
public class FollowOwnerGoal extends Goal {

    private static final double START_DISTANCE = 5.0;
    private static final double STOP_DISTANCE = 2.5;
    private static final double TELEPORT_DISTANCE = 24.0;

    private final NpcEntity npc;
    private Player owner;
    private int repath;

    public FollowOwnerGoal(NpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Player found = npc.companionOwner();
        if (found == null || found.isSpectator() || npc.instance().waiting()
                || npc.distanceToSqr(found) < START_DISTANCE * START_DISTANCE) {
            return false;
        }
        owner = found;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return owner != null && owner.isAlive() && owner.level() == npc.level() && !npc.instance().waiting()
                && npc.distanceToSqr(owner) > STOP_DISTANCE * STOP_DISTANCE;
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void stop() {
        owner = null;
        npc.getNavigation().stop();
    }

    @Override
    public void tick() {
        npc.getLookControl().setLookAt(owner, 10.0F, npc.getMaxHeadXRot());
        if (--repath > 0) {
            return;
        }
        repath = adjustedTickDelay(10);
        if (npc.distanceToSqr(owner) > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            teleportNearOwner();
        } else {
            npc.getNavigation().moveTo(owner, 1.2);
        }
    }

    private void teleportNearOwner() {
        BlockPos center = owner.blockPosition();
        for (int attempt = 0; attempt < 10; attempt++) {
            int dx = npc.getRandom().nextIntBetweenInclusive(-3, 3);
            int dz = npc.getRandom().nextIntBetweenInclusive(-3, 3);
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) {
                continue;
            }
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos pos = center.offset(dx, dy, dz);
                if (canStandAt(pos)) {
                    moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                    return;
                }
            }
        }
        moveTo(owner.getX(), owner.getY(), owner.getZ());
    }

    private boolean canStandAt(BlockPos pos) {
        BlockPos below = pos.below();
        return npc.level().getBlockState(below).isFaceSturdy(npc.level(), below, Direction.UP)
                && npc.level().noCollision(npc, npc.getBoundingBox().move(Vec3.atBottomCenterOf(pos).subtract(npc.position())));
    }

    private void moveTo(double x, double y, double z) {
        npc.snapTo(x, y, z, npc.getYRot(), npc.getXRot());
        npc.getNavigation().stop();
    }
}
