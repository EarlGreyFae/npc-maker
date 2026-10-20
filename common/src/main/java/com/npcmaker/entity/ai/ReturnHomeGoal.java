package com.npcmaker.entity.ai;

import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcInstance;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Stationary NPCs walk back to their home position after being pushed, knocked back or pulled into a fight. */
public class ReturnHomeGoal extends Goal {

    private static final double LEAVE_DISTANCE_SQR = 1.5 * 1.5;

    private final NpcEntity npc;
    private int repath;

    public ReturnHomeGoal(NpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        NpcInstance instance = npc.instance();
        return instance != null && !instance.isCompanion() && npc.getTarget() == null
                && npc.distanceToSqr(instance.home()) > LEAVE_DISTANCE_SQR;
    }

    @Override
    public boolean canContinueToUse() {
        NpcInstance instance = npc.instance();
        return instance != null && npc.getTarget() == null && npc.distanceToSqr(instance.home()) > 0.25;
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void tick() {
        if (--repath <= 0) {
            repath = adjustedTickDelay(20);
            Vec3 home = npc.instance().home();
            if (!npc.getNavigation().moveTo(home.x, home.y, home.z, 1.0) && npc.distanceToSqr(home) < 4.0) {
                // Can't path the last bit (e.g. standing on a slab edge): step straight there.
                npc.getMoveControl().setWantedPosition(home.x, home.y, home.z, 1.0);
            }
        }
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
    }
}
