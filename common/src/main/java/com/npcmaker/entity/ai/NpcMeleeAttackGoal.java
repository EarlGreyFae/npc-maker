package com.npcmaker.entity.ai;

import com.npcmaker.entity.NpcEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/** Melee attacks, except while the NPC holds a ranged weapon it can fire (then NpcRangedAttackGoal takes over). */
public class NpcMeleeAttackGoal extends MeleeAttackGoal {

    private final NpcEntity npc;

    public NpcMeleeAttackGoal(NpcEntity npc) {
        super(npc, 1.2, true);
        this.npc = npc;
    }

    @Override
    public boolean canUse() {
        return !npc.canFireRangedWeapon() && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return !npc.canFireRangedWeapon() && super.canContinueToUse();
    }
}
