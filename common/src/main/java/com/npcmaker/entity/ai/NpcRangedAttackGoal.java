package com.npcmaker.entity.ai;

import com.npcmaker.entity.NpcEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BowItem;

import java.util.EnumSet;

/**
 * Shoots with a held bow or crossbow, like a skeleton: close to firing range, stop, draw for about a second, loose,
 * repeat. Uses arrows from the NPC's inventory unless the template gives it unlimited ammo.
 */
public class NpcRangedAttackGoal extends Goal {

    private static final double MAX_RANGE = 15.0;
    private static final int DRAW_TICKS = 20;
    private static final int COOLDOWN_TICKS = 20;

    private final NpcEntity npc;
    private LivingEntity target;
    private int seeTime;
    private int cooldown;
    private int repath;

    public NpcRangedAttackGoal(NpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity found = npc.getTarget();
        if (found == null || !found.isAlive() || !npc.canFireRangedWeapon()) {
            return false;
        }
        target = found;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse() || (target != null && target.isAlive() && npc.isUsingItem() && npc.canFireRangedWeapon());
    }

    @Override
    public void start() {
        seeTime = 0;
        cooldown = 0;
    }

    @Override
    public void stop() {
        target = null;
        npc.stopUsingItem();
        npc.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null) {
            return;
        }
        double distanceSqr = npc.distanceToSqr(target);
        boolean sees = npc.getSensing().hasLineOfSight(target);
        seeTime = sees ? Math.min(seeTime + 1, 60) : Math.max(seeTime - 1, 0);

        if (distanceSqr > MAX_RANGE * MAX_RANGE || seeTime < 5) {
            if (--repath <= 0) {
                repath = 10;
                npc.getNavigation().moveTo(target, 1.0);
            }
        } else {
            npc.getNavigation().stop();
        }
        npc.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (!npc.isUsingItem()) {
            if (sees && distanceSqr <= MAX_RANGE * MAX_RANGE) {
                npc.startUsingItem(InteractionHand.MAIN_HAND);
            }
            return;
        }
        int drawn = npc.getTicksUsingItem();
        if (drawn >= DRAW_TICKS && sees) {
            npc.stopUsingItem();
            npc.shootAt(target, BowItem.getPowerForTime(drawn));
            cooldown = COOLDOWN_TICKS;
        }
    }
}
