package com.npcmaker.entity.ai;

import com.npcmaker.ai.AiRule;
import com.npcmaker.ai.Threats;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Carries out the movement action of the first matching AI rule (flee, go_home, go_to, follow, stop) for as long
 * as that rule keeps matching. Runs ahead of the NPC's normal movement and fighting.
 */
public class RulesGoal extends Goal {

    private final NpcEntity npc;
    private int repath;

    public RulesGoal(NpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // A conversation (or an open quest card) comes first: it shares the top priority, so without this a rule
        // like "go home at night" would keep the NPC from turning to face the player.
        return npc.activeMovementRule() != null && npc.conversationPartner() == null;
    }

    @Override
    public void start() {
        repath = 0;
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
        AiRule rule = npc.activeMovementRule();
        if (rule == null) {
            return;
        }
        LivingEntity target = npc.activeRuleTarget();
        boolean doPath = --repath <= 0;
        if (doPath) {
            repath = 20;
        }
        switch (rule.then()) {
            case STOP -> {
                npc.getNavigation().stop();
                if (target != null) {
                    npc.getLookControl().setLookAt(target);
                }
            }
            case FOLLOW -> {
                if (target == null) {
                    return;
                }
                npc.getLookControl().setLookAt(target);
                if (npc.distanceToSqr(target) > 3 * 3) {
                    if (doPath) {
                        npc.getNavigation().moveTo(target, 1.1);
                    }
                } else {
                    npc.getNavigation().stop();
                }
            }
            case GO_HOME -> {
                NpcInstance instance = npc.instance();
                if (instance != null && doPath && npc.distanceToSqr(instance.home()) > 1.5 * 1.5) {
                    Vec3 home = instance.home();
                    npc.getNavigation().moveTo(home.x, home.y, home.z, 1.0);
                }
            }
            case GO_TO -> rule.pos().ifPresent(pos -> {
                if (doPath && npc.distanceToSqr(pos) > 1.5 * 1.5) {
                    npc.getNavigation().moveTo(pos.x, pos.y, pos.z, 1.0);
                }
            });
            case FLEE -> {
                if (!doPath) {
                    return;
                }
                LivingEntity from = target != null ? target : Threats.nearestDanger(npc, 16.0);
                if (from == null) {
                    from = npc.getTarget();
                }
                if (from != null) {
                    Vec3 away = Threats.awayFrom(npc, from.position());
                    if (away != null) {
                        npc.getNavigation().moveTo(away.x, away.y, away.z, 1.4);
                    }
                }
            }
            default -> {
            }
        }
    }
}
