package com.npcmaker.entity.ai;

import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * While a stationary NPC idles at home, it holds its saved rotation: body yaw, head yaw and head pitch. Higher
 * priority look goals (looking at a nearby player) interrupt it, and it takes over again afterwards.
 */
public class FaceHomeGoal extends Goal {

    private static final float BODY_TURN_SPEED = 10.0F;

    private final NpcEntity npc;

    public FaceHomeGoal(NpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        NpcInstance instance = npc.instance();
        return instance != null && !instance.isCompanion() && npc.getTarget() == null
                && npc.getNavigation().isDone()
                && npc.distanceToSqr(instance.home()) < 2.25;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        NpcInstance instance = npc.instance();
        // Vanilla resets head pitch whenever nothing is being looked at, so keep looking at a point in front.
        Vec3 look = npc.getEyePosition().add(Vec3.directionFromRotation(instance.pitch(), instance.yaw()).scale(4.0));
        npc.getLookControl().setLookAt(look.x, look.y, look.z, BODY_TURN_SPEED, npc.getMaxHeadXRot());
        // Bodies only follow the head part of the way on their own; turn the body the rest of the way.
        float body = Mth.approachDegrees(npc.yBodyRot, instance.yaw(), BODY_TURN_SPEED);
        npc.setYBodyRot(body);
        npc.setYRot(body);
    }
}
