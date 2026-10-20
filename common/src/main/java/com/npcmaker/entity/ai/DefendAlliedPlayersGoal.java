package com.npcmaker.entity.ai;

import com.npcmaker.entity.NpcEntity;
import com.npcmaker.faction.Factions;
import com.npcmaker.faction.Relation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * Guard and aggressive NPCs step in when something attacks a player their factions are allied with (a member, or
 * someone in good standing), the same way they already defend each other.
 */
public class DefendAlliedPlayersGoal extends Goal {

    private static final double RANGE = 16.0;
    /** Only attacks within the last 5 seconds count as "being attacked right now". */
    private static final int RECENT_TICKS = 100;

    private final NpcEntity npc;
    /** Where the NPC defends from: its guard post for guards, wherever it is for aggressive NPCs. */
    private final Supplier<Vec3> post;
    private LivingEntity candidate;
    private int cooldown;

    public DefendAlliedPlayersGoal(NpcEntity npc, Supplier<Vec3> post) {
        this.npc = npc;
        this.post = post;
        setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (--cooldown > 0 || npc.getTarget() != null) {
            return false;
        }
        cooldown = reducedTickDelay(10);
        List<String> mine = Factions.factionsOf(npc);
        if (mine.isEmpty()) {
            return false;
        }
        Vec3 center = post.get();
        for (Player player : npc.level().players()) {
            if (!(player instanceof ServerPlayer serverPlayer) || player.isSpectator()
                    || player.distanceToSqr(center) > RANGE * RANGE) {
                continue;
            }
            LivingEntity attacker = player.getLastHurtByMob();
            if (attacker == null || !attacker.isAlive() || attacker == npc || attacker == player
                    || player.tickCount - player.getLastHurtByMobTimestamp() > RECENT_TICKS) {
                continue;
            }
            if (Factions.relationToPlayer(mine, serverPlayer) != Relation.ALLIED) {
                continue;
            }
            boolean attackerIsFriend = attacker instanceof ServerPlayer other
                    ? Factions.relationToPlayer(mine, other) == Relation.ALLIED
                    : Factions.relation(npc, attacker) == Relation.ALLIED;
            if (!attackerIsFriend) {
                candidate = attacker;
                return true;
            }
        }
        return false;
    }

    @Override
    public void start() {
        npc.setTarget(candidate);
        candidate = null;
    }
}
