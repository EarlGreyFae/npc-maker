package com.npcmaker.entity.ai;

import com.npcmaker.entity.NpcEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/** While a player has the dialogue box open, the NPC stops where it is and faces them. */
public class TalkToPlayerGoal extends Goal {

    private final NpcEntity npc;

    public TalkToPlayerGoal(NpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return npc.conversationPartner() != null && npc.getTarget() == null;
    }

    @Override
    public void start() {
        npc.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Player partner = npc.conversationPartner();
        if (partner != null) {
            npc.getLookControl().setLookAt(partner, 30.0F, npc.getMaxHeadXRot());
        }
    }
}
