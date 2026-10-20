package com.npcmaker.entity.ai;

import com.npcmaker.entity.NpcEntity;
import com.npcmaker.faction.Factions;
import com.npcmaker.faction.Relation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/** Companions attack whatever hurts their owner, and whatever their owner attacks. */
public class DefendOwnerGoal extends Goal {

    private final NpcEntity npc;
    private int seenHurtByTimestamp;
    private int seenAttackTimestamp;
    private LivingEntity candidate;

    public DefendOwnerGoal(NpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        Player owner = npc.companionOwner();
        if (owner == null) {
            return false;
        }
        LivingEntity attacker = owner.getLastHurtByMob();
        if (owner.getLastHurtByMobTimestamp() != seenHurtByTimestamp && isValid(attacker, owner)) {
            candidate = attacker;
            seenHurtByTimestamp = owner.getLastHurtByMobTimestamp();
            return true;
        }
        LivingEntity victim = owner.getLastHurtMob();
        if (owner.getLastHurtMobTimestamp() != seenAttackTimestamp && isValid(victim, owner)) {
            candidate = victim;
            seenAttackTimestamp = owner.getLastHurtMobTimestamp();
            return true;
        }
        return false;
    }

    private boolean isValid(LivingEntity target, Player owner) {
        if (target == null || !target.isAlive() || target == npc || target == owner || target.level() != npc.level()) {
            return false;
        }
        if (target instanceof NpcEntity other && other.isCompanionOf(owner)) {
            return false;
        }
        return Factions.relation(npc, target) != Relation.ALLIED;
    }

    @Override
    public void start() {
        npc.setTarget(candidate);
        candidate = null;
    }
}
