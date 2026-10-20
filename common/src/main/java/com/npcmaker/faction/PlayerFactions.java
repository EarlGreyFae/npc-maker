package com.npcmaker.faction;

import com.npcmaker.content.NpcContent;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcEntityTypes;
import com.npcmaker.network.FactionStandingsPayload;
import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.ReputationNoticePayload;
import com.npcmaker.player.PlayerData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Players' side of factions: joining, leaving, reputation, and how factions react to what players do. */
public final class PlayerFactions {

    /** How far guards look for a player attacking one of their faction. */
    private static final double GUARD_ALERT_RADIUS = 16.0;
    /** Hitting the same member again within this many ticks doesn't cost reputation twice. */
    private static final int ATTACK_COOLDOWN_TICKS = 100;

    /** A second hit within this many ticks of a warning counts as a real attack (10 seconds). */
    private static final int WARNING_WINDOW_TICKS = 200;

    private static final Map<UUID, Map<UUID, Long>> LAST_ATTACK = new HashMap<>();
    private static final Map<UUID, Map<UUID, Long>> WARNED = new HashMap<>();

    private PlayerFactions() {
    }

    public enum JoinResult { JOINED, ALREADY_MEMBER, UNKNOWN_FACTION, EXCLUDED }

    /** The first faction the player belongs to that rules out joining {@code faction}, if any. */
    public static Optional<String> conflictFor(ServerPlayer player, String faction) {
        Optional<FactionDefinition> def = NpcContent.FACTIONS.get(faction);
        if (def.isEmpty()) {
            return Optional.empty();
        }
        for (String current : PlayerData.get(player).factions()) {
            boolean excluded = def.get().excludes().contains(current)
                    || NpcContent.FACTIONS.get(current).map(f -> f.excludes().contains(faction)).orElse(false);
            if (excluded) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }

    public static boolean canJoin(ServerPlayer player, String faction) {
        return NpcContent.FACTIONS.get(faction).isPresent()
                && !PlayerData.get(player).isMember(faction)
                && conflictFor(player, faction).isEmpty();
    }

    public static JoinResult join(ServerPlayer player, String faction) {
        if (NpcContent.FACTIONS.get(faction).isEmpty()) {
            return JoinResult.UNKNOWN_FACTION;
        }
        PlayerData data = PlayerData.get(player);
        if (data.isMember(faction)) {
            return JoinResult.ALREADY_MEMBER;
        }
        Optional<String> conflict = conflictFor(player, faction);
        if (conflict.isPresent()) {
            player.sendSystemMessage(Component.literal("You can't join ").append(name(faction))
                    .append(" while you belong to ").append(name(conflict.get())).append(".").withStyle(ChatFormatting.RED));
            return JoinResult.EXCLUDED;
        }
        data.addFaction(faction);
        player.sendSystemMessage(Component.literal("You joined ").append(name(faction)).append("."));
        return JoinResult.JOINED;
    }

    public static boolean leave(ServerPlayer player, String faction) {
        boolean left = PlayerData.get(player).removeFaction(faction);
        if (left) {
            player.sendSystemMessage(Component.literal("You left ").append(name(faction)).append("."));
        }
        return left;
    }

    /** Changes reputation and shows it on the top-right reputation card, e.g. "+2 Dawnguard  Friendly 24". */
    public static int changeReputation(ServerPlayer player, String faction, int delta) {
        if (delta == 0 || NpcContent.FACTIONS.get(faction).isEmpty()) {
            return PlayerData.get(player).reputation(faction);
        }
        apply(player, faction, delta).ifPresent(entry -> notify(player, List.of(entry)));
        return PlayerData.get(player).reputation(faction);
    }

    /** Applies a change without telling the player; returns what changed (nothing if already at the limit). */
    private static Optional<ReputationNoticePayload.Entry> apply(ServerPlayer player, String faction, int delta) {
        if (delta == 0 || NpcContent.FACTIONS.get(faction).isEmpty()) {
            return Optional.empty();
        }
        PlayerData data = PlayerData.get(player);
        int before = data.reputation(faction);
        int after = data.setReputation(faction, before + delta);
        return after == before ? Optional.empty()
                : Optional.of(new ReputationNoticePayload.Entry(name(faction), after - before, after, standingName(faction, after)));
    }

    /** The standing tier name for a reputation value, in the tier's colour (e.g. green "Friendly"). */
    public static Component standingName(String faction, int reputation) {
        return NpcContent.FACTIONS.get(faction)
                .<Component>map(f -> {
                    StandingTier tier = f.standingFor(reputation);
                    return Component.literal(tier.name()).withColor(tier.colorRgb());
                })
                .orElse(Component.literal(String.valueOf(reputation)));
    }

    /** One card per action, listing every faction it affected. */
    private static void notify(ServerPlayer player, List<ReputationNoticePayload.Entry> entries) {
        if (!entries.isEmpty()) {
            NpcNetworking.sendToPlayer(player, new ReputationNoticePayload(entries));
        }
    }

    private static void applyAll(ServerPlayer player, Map<String, Integer> changes) {
        List<ReputationNoticePayload.Entry> entries = new ArrayList<>();
        changes.forEach((faction, delta) -> apply(player, faction, delta).ifPresent(entries::add));
        notify(player, entries);
    }

    /** Sends the player their standings, which opens the Factions screen on their side. */
    public static void sendStandings(ServerPlayer player) {
        PlayerData data = PlayerData.get(player);
        List<FactionStandingsPayload.Row> rows = new ArrayList<>();
        NpcContent.FACTIONS.all().entrySet().stream()
                .filter(e -> !e.getValue().hidden())
                .sorted((a, b) -> {
                    int byMember = Boolean.compare(data.isMember(b.getKey()), data.isMember(a.getKey()));
                    return byMember != 0 ? byMember : a.getValue().name().compareToIgnoreCase(b.getValue().name());
                })
                .forEach(e -> {
                    int rep = data.reputation(e.getKey());
                    StandingTier tier = e.getValue().standingFor(rep);
                    Relation relation = Factions.relationToPlayer(List.of(e.getKey()), player);
                    rows.add(new FactionStandingsPayload.Row(name(e.getKey()), data.isMember(e.getKey()), rep,
                            Component.literal(tier.name()).withColor(tier.colorRgb()), tier.colorRgb(), relation.getSerializedName()));
                });
        NpcNetworking.sendToPlayer(player, new FactionStandingsPayload(rows));
    }

    public static Component name(String faction) {
        return NpcContent.FACTIONS.get(faction)
                .<Component>map(f -> Component.literal(f.name()).withColor(f.colorRgb()))
                .orElse(Component.literal(faction));
    }

    // ---- reactions to what players do ---------------------------------------------------------------------

    /**
     * A player killed something: its factions (and their allies) think less of them, its enemies more. Counts in
     * survival, adventure and creative alike (so testing in creative behaves like real play); spectators can't kill.
     */
    public static void onKill(ServerPlayer player, LivingEntity victim) {
        if (player.isSpectator()) {
            return;
        }
        Map<String, Integer> changes = new LinkedHashMap<>();
        for (String victimFaction : Factions.factionsOf(victim)) {
            NpcContent.FACTIONS.get(victimFaction).ifPresent(def -> {
                FactionDefinition.ReputationChanges rules = def.reputationChanges();
                if (tracksReputation(def)) {
                    changes.merge(victimFaction, rules.killMember(), Integer::sum);
                }
                for (var other : NpcContent.FACTIONS.all().entrySet()) {
                    if (other.getKey().equals(victimFaction)) {
                        continue;
                    }
                    Relation relation = Relation.worst(other.getValue().relationTo(victimFaction), def.relationTo(other.getKey()));
                    if (relation == Relation.HOSTILE) {
                        changes.merge(other.getKey(), other.getValue().reputationChanges().killEnemy(), Integer::sum);
                    } else if (isAllied(other.getKey(), victimFaction) && tracksReputation(other.getValue())) {
                        changes.merge(other.getKey(), Math.round(rules.killMember() * other.getValue().reputationChanges().allyShare()), Integer::sum);
                    }
                }
            });
        }
        applyAll(player, changes);
    }

    /**
     * A player hit something: if it belongs to a faction, that costs a little reputation and nearby fighting NPCs
     * of the same side turn on the attacker (except creative players, whom NPCs never attack).
     */
    public static void onAttack(ServerPlayer player, LivingEntity victim) {
        if (player.isSpectator() || victim == player) {
            return;
        }
        List<String> victimFactions = Factions.factionsOf(victim);
        if (victimFactions.isEmpty() && !(victim instanceof NpcEntity)) {
            return;
        }
        long now = player.level().getGameTime();
        if (isWarningOnly(player, victim, victimFactions, now)) {
            return;
        }
        if (victimFactions.isEmpty()) {
            return;
        }
        Map<UUID, Long> hits = LAST_ATTACK.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        Long last = hits.get(victim.getUUID());
        if (last == null || now - last >= ATTACK_COOLDOWN_TICKS) {
            hits.put(victim.getUUID(), now);
            hits.values().removeIf(t -> now - t > ATTACK_COOLDOWN_TICKS * 4L);
            Map<String, Integer> changes = new LinkedHashMap<>();
            for (String faction : victimFactions) {
                NpcContent.FACTIONS.get(faction).filter(PlayerFactions::tracksReputation).ifPresent(def -> {
                    int penalty = def.reputationChanges().attackMember();
                    changes.merge(faction, penalty, Integer::sum);
                    for (var other : NpcContent.FACTIONS.all().entrySet()) {
                        if (!other.getKey().equals(faction) && isAllied(other.getKey(), faction) && tracksReputation(other.getValue())) {
                            changes.merge(other.getKey(), Math.round(penalty * other.getValue().reputationChanges().allyShare()), Integer::sum);
                        }
                    }
                });
            }
            applyAll(player, changes);
        }
        if (!player.isCreative()) {
            alertGuards(player, victim, victimFactions);
        }
    }

    /**
     * Two-hit rule: the first hit on an NPC is treated as a possible accident, whatever the player's standing with
     * its factions (hitting an NPC by mistake is easy; fighting one should be deliberate). NPCs say one of their
     * hit-warning lines (default "Hey! Watch it!", can be switched off per NPC) and nothing else happens: no
     * reputation change, no guards, and the NPC doesn't hit back. A second hit on the same target within
     * {@link #WARNING_WINDOW_TICKS} is a real attack. Hitting back at an NPC that's already fighting you counts at
     * once. Vanilla mobs in factions get the grace hit only from factions that aren't hostile to the player.
     */
    private static boolean isWarningOnly(ServerPlayer player, LivingEntity victim, List<String> victimFactions, long now) {
        if (victim instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == player) {
            return false; // it's already fighting you: hitting back is deliberate
        }
        boolean hostileMob = !(victim instanceof NpcEntity) && !victimFactions.isEmpty()
                && Factions.relationToPlayer(victimFactions, player) == Relation.HOSTILE;
        if (hostileMob) {
            return false;
        }
        Map<UUID, Long> warnings = WARNED.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        warnings.values().removeIf(t -> now - t > WARNING_WINDOW_TICKS);
        Long warnedAt = warnings.get(victim.getUUID());
        if (warnedAt != null && warnedAt != now) {
            warnings.remove(victim.getUUID());
            return false;
        }
        warnings.put(victim.getUUID(), now);
        if (victim instanceof NpcEntity npc) {
            npc.sayHitWarning();
        }
        return true;
    }

    /** True if {@code player}'s hit on {@code victim} this very tick was only a warning (see the two-hit rule). */
    public static boolean wasWarningHit(ServerPlayer player, LivingEntity victim) {
        Map<UUID, Long> warnings = WARNED.get(player.getUUID());
        Long warnedAt = warnings == null ? null : warnings.get(victim.getUUID());
        return warnedAt != null && warnedAt == player.level().getGameTime();
    }

    /** Fighting NPCs of the victim's factions (or their allies) close by turn on the attacker. */
    private static void alertGuards(ServerPlayer player, LivingEntity victim, List<String> victimFactions) {
        if (!(victim.level() instanceof ServerLevel level)) {
            return;
        }
        AABB area = victim.getBoundingBox().inflate(GUARD_ALERT_RADIUS);
        for (NpcEntity npc : level.getEntities(NpcEntityTypes.npc(), area, npc -> npc != victim && npc.isAlive())) {
            if (npc.getTarget() != null || !npc.fightsForAllies() || npc.isCompanionOf(player)) {
                continue;
            }
            boolean sameSide = Factions.factionsOf(npc).stream()
                    .anyMatch(f -> victimFactions.contains(f) || victimFactions.stream().anyMatch(v -> isAllied(f, v)));
            if (sameSide) {
                npc.setTarget(player);
            }
        }
    }

    /**
     * Factions hostile to every player by default (monsters like the Undead) don't lose reputation when their
     * members are killed or hit; otherwise every zombie kill would flash a penalty. Their enemies still reward it.
     */
    private static boolean tracksReputation(FactionDefinition def) {
        return def.defaultPlayerRelation() != Relation.HOSTILE;
    }

    private static boolean isAllied(String a, String b) {
        Relation ab = NpcContent.FACTIONS.get(a).map(f -> f.relationTo(b)).orElse(Relation.NEUTRAL);
        Relation ba = NpcContent.FACTIONS.get(b).map(f -> f.relationTo(a)).orElse(Relation.NEUTRAL);
        return Relation.worst(ab, ba) == Relation.ALLIED;
    }

    public static void forget(UUID player) {
        LAST_ATTACK.remove(player);
        WARNED.remove(player);
    }
}
