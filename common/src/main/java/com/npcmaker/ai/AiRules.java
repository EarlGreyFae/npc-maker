package com.npcmaker.ai;

import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcEntityTypes;
import com.npcmaker.faction.Factions;
import com.npcmaker.faction.Relation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Checks AI rule triggers and carries out their actions (movement actions are carried out by RulesGoal). */
public final class AiRules {

    private static final double ALLY_RANGE = 16.0;
    private static final int RECENT_HURT_TICKS = 60;

    private AiRules() {
    }

    /** Whether a rule's trigger and conditions match right now, and who it's about (if anyone). */
    public record Match(boolean matched, @Nullable LivingEntity target) {
        static final Match NO = new Match(false, null);
    }

    public static Match check(NpcEntity npc, AiRule rule) {
        Level level = npc.level();
        return switch (rule.when()) {
            case ALWAYS -> self(npc, rule, true);
            case HEALTH_BELOW -> {
                double fraction = rule.number(0.3);
                if (fraction > 1.0) {
                    fraction /= 100.0; // written as a percentage
                }
                yield self(npc, rule, npc.getHealth() < npc.getMaxHealth() * fraction);
            }
            case TIME_IS -> self(npc, rule, switch (rule.word("day").toLowerCase(Locale.ROOT)) {
                case "night" -> level.isDarkOutside();
                default -> level.isBrightOutside();
            });
            case WEATHER_IS -> self(npc, rule, switch (rule.word("clear").toLowerCase(Locale.ROOT)) {
                case "thunder" -> level.isThundering();
                case "rain" -> level.isRaining() && !level.isThundering();
                default -> !level.isRaining();
            });
            case ATTACKED -> about(npc, rule, Threats.recentAttacker(npc, RECENT_HURT_TICKS));
            case ALLY_ATTACKED -> {
                List<NpcEntity> allies = level.getEntities(NpcEntityTypes.npc(), npc.getBoundingBox().inflate(ALLY_RANGE),
                        other -> other != npc && other.isAlive() && Factions.relation(npc, other) == Relation.ALLIED);
                for (NpcEntity ally : allies) {
                    LivingEntity attacker = Threats.recentAttacker(ally, RECENT_HURT_TICKS);
                    if (attacker != null && attacker != npc) {
                        Match match = about(npc, rule, attacker);
                        if (match.matched()) {
                            yield match;
                        }
                    }
                }
                yield Match.NO;
            }
            case PLAYER_NEAR -> {
                double radius = rule.number(8.0);
                Optional<? extends Player> player = level.players().stream()
                        .filter(p -> !p.isSpectator() && p.distanceToSqr(npc) <= radius * radius && conditionsPass(npc, rule, p))
                        .min(Comparator.comparingDouble(p -> p.distanceToSqr(npc)));
                yield player.<Match>map(p -> new Match(true, p)).orElse(Match.NO);
            }
            case ENTITY_NEAR -> {
                double radius = rule.number(8.0);
                Optional<LivingEntity> entity = level.getEntitiesOfClass(LivingEntity.class, npc.getBoundingBox().inflate(radius),
                                e -> e != npc && e.isAlive() && !(e instanceof Player p && p.isSpectator())
                                        && e.distanceToSqr(npc) <= radius * radius && conditionsPass(npc, rule, e))
                        .stream()
                        .min(Comparator.comparingDouble(e -> e.distanceToSqr(npc)));
                yield entity.<Match>map(e -> new Match(true, e)).orElse(Match.NO);
            }
        };
    }

    private static Match self(NpcEntity npc, AiRule rule, boolean triggered) {
        return triggered && conditionsPass(npc, rule, null) ? new Match(true, null) : Match.NO;
    }

    private static Match about(NpcEntity npc, AiRule rule, @Nullable LivingEntity target) {
        return target != null && conditionsPass(npc, rule, target) ? new Match(true, target) : Match.NO;
    }

    private static boolean conditionsPass(NpcEntity npc, AiRule rule, @Nullable LivingEntity target) {
        for (AiCondition condition : rule.conditions()) {
            if (!condition.test(npc, target)) {
                return false;
            }
        }
        return true;
    }

    /** Carries out a non-movement action once. */
    public static void fire(NpcEntity npc, AiRule rule, @Nullable LivingEntity target) {
        switch (rule.then()) {
            case ATTACK -> {
                if (target != null && target != npc) {
                    npc.setTarget(target);
                }
            }
            case LOOK_AT -> {
                if (target != null) {
                    npc.getLookControl().setLookAt(target);
                }
            }
            case SAY -> {
                if (!rule.text().isEmpty()) {
                    npc.sayIfAllowed(rule.text().get(npc.getRandom().nextInt(rule.text().size())));
                }
            }
            case PLAY_SOUND -> rule.sound().ifPresent(s -> npc.playSound(s.event(), s.volume(), s.pitch()));
            case EMIT_PARTICLES -> rule.particle().ifPresent(id -> {
                ParticleType<?> type = com.npcmaker.data.ParticleSpec.resolve(id);
                if (type instanceof SimpleParticleType simple && npc.level() instanceof ServerLevel level) {
                    level.sendParticles(simple, npc.getX(), npc.getY() + npc.getBbHeight() * 0.6, npc.getZ(),
                            rule.count(), 0.4, 0.5, 0.4, 0.02);
                }
            });
            case PLAY_ANIMATION -> rule.animation().ifPresent(npc::playAnimation);
            case USE_ITEM -> rule.item().ifPresent(npc::useInventoryItem);
            case PLACE_BLOCK -> npc.placeBlock(rule.item(), rule.pos().map(BlockPos::containing));
            case RUN_COMMAND -> rule.command().ifPresent(command -> npc.runCommandAsNpc(command
                    .replace("{npc}", npc.getStringUUID())
                    .replace("{npc_name}", npc.label().getString())
                    .replace("{target}", target != null ? target.getStringUUID() : npc.getStringUUID())
                    .replace("{target_name}", target != null ? target.getName().getString() : "")));
            default -> {
                // movement actions are handled by RulesGoal
            }
        }
    }
}
