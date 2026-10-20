package com.npcmaker.faction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * A faction, loaded from {@code <world>/npcmaker/factions/<id>.json}. NPCs join a faction by listing its id
 * in their own definition; vanilla mobs join through {@code entityTypes} / {@code entityTags}; players join through
 * dialogue, quest rewards or admin commands.
 *
 * @param entityTypes           vanilla/modded entity type ids that count as members, e.g. "minecraft:zombie"
 * @param entityTags            entity type tags that count as members, e.g. "minecraft:undead"
 * @param relations             faction id → relation toward that faction
 * @param defaultPlayerRelation relation toward players who are not members and have neutral reputation
 * @param hostileBelow          player reputation at or below which the faction turns hostile
 * @param alliedAbove           player reputation at or above which the faction treats the player as an ally
 * @param excludes              factions a player can't belong to at the same time as this one (checked both ways)
 * @param reputationChanges     how much reputation player actions are worth with this faction
 * @param standings             named reputation tiers (Hated … Revered by default), highest {@code min} first
 * @param hidden                keep this faction off the players' Factions screen (secret societies, monsters)
 */
public record FactionDefinition(String name, String color, List<String> entityTypes, List<String> entityTags,
                                Map<String, Relation> relations, Relation defaultPlayerRelation,
                                int hostileBelow, int alliedAbove, List<String> excludes,
                                ReputationChanges reputationChanges, List<StandingTier> standings, boolean hidden) {

    /** A faction with default reputation rules, standings and visibility. */
    public static FactionDefinition of(String name, String color, List<String> entityTags, Map<String, Relation> relations,
                                       Relation defaultPlayerRelation, List<String> excludes) {
        return new FactionDefinition(name, color, List.of(), entityTags, relations, defaultPlayerRelation, -50, 50,
                excludes, ReputationChanges.DEFAULT, StandingTier.DEFAULTS, false);
    }

    /** Reputation runs from -100 to 100 and starts at 0. */
    public static final int MIN_REPUTATION = -100;
    public static final int MAX_REPUTATION = 100;

    /**
     * @param killMember   change when the player kills one of this faction's members
     * @param attackMember change when the player hits a member (at most once every few seconds per member)
     * @param killEnemy    change when the player kills a member of a faction this one is hostile to
     * @param allyShare    fraction of a kill/attack penalty that also applies to this faction's allies
     */
    public record ReputationChanges(int killMember, int attackMember, int killEnemy, float allyShare) {
        public static final ReputationChanges DEFAULT = new ReputationChanges(-10, -2, 2, 0.5F);

        public static final Codec<ReputationChanges> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(-100, 100).optionalFieldOf("kill_member", DEFAULT.killMember).forGetter(ReputationChanges::killMember),
                Codec.intRange(-100, 100).optionalFieldOf("attack_member", DEFAULT.attackMember).forGetter(ReputationChanges::attackMember),
                Codec.intRange(-100, 100).optionalFieldOf("kill_enemy", DEFAULT.killEnemy).forGetter(ReputationChanges::killEnemy),
                Codec.floatRange(0.0F, 1.0F).optionalFieldOf("ally_share", DEFAULT.allyShare).forGetter(ReputationChanges::allyShare)
        ).apply(i, ReputationChanges::new));
    }

    public static final Codec<FactionDefinition> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(FactionDefinition::name),
            Codec.STRING.optionalFieldOf("color", "#FFFFFF").forGetter(FactionDefinition::color),
            Codec.STRING.listOf().optionalFieldOf("entity_types", List.of()).forGetter(FactionDefinition::entityTypes),
            Codec.STRING.listOf().optionalFieldOf("entity_tags", List.of()).forGetter(FactionDefinition::entityTags),
            Codec.unboundedMap(Codec.STRING, Relation.CODEC).optionalFieldOf("relations", Map.of()).forGetter(FactionDefinition::relations),
            Relation.CODEC.optionalFieldOf("default_player_relation", Relation.NEUTRAL).forGetter(FactionDefinition::defaultPlayerRelation),
            Codec.intRange(MIN_REPUTATION, MAX_REPUTATION).optionalFieldOf("hostile_below", -50).forGetter(FactionDefinition::hostileBelow),
            Codec.intRange(MIN_REPUTATION, MAX_REPUTATION).optionalFieldOf("allied_above", 50).forGetter(FactionDefinition::alliedAbove),
            Codec.STRING.listOf().optionalFieldOf("excludes", List.of()).forGetter(FactionDefinition::excludes),
            ReputationChanges.CODEC.optionalFieldOf("reputation_changes", ReputationChanges.DEFAULT).forGetter(FactionDefinition::reputationChanges),
            StandingTier.CODEC.listOf(1, 16).optionalFieldOf("standings", StandingTier.DEFAULTS).forGetter(FactionDefinition::standings),
            Codec.BOOL.optionalFieldOf("hidden", false).forGetter(FactionDefinition::hidden)
    ).apply(i, FactionDefinition::new));

    /** The tier a reputation value falls in: the highest tier whose minimum it reaches (lowest tier as a floor). */
    public StandingTier standingFor(int reputation) {
        List<StandingTier> sorted = sortedStandings();
        for (StandingTier tier : sorted) {
            if (reputation >= tier.min()) {
                return tier;
            }
        }
        return sorted.get(sorted.size() - 1);
    }

    /** Tiers from highest to lowest minimum. */
    public List<StandingTier> sortedStandings() {
        return standings.stream().sorted((a, b) -> Integer.compare(b.min(), a.min())).toList();
    }

    /** The minimum reputation for a tier by name (case-insensitive), if this faction has it. */
    public OptionalInt minFor(String tierName) {
        return standings.stream().filter(t -> t.name().equalsIgnoreCase(tierName))
                .mapToInt(StandingTier::min).findFirst();
    }

    public Relation relationTo(String otherFactionId) {
        return relations.getOrDefault(otherFactionId, Relation.NEUTRAL);
    }

    public Relation relationForReputation(int reputation) {
        if (reputation <= hostileBelow) {
            return Relation.HOSTILE;
        }
        if (reputation >= alliedAbove) {
            return Relation.ALLIED;
        }
        return defaultPlayerRelation;
    }

    /** Colour as 0xRRGGBB for chat output; white if the text isn't a valid colour. */
    public int colorRgb() {
        try {
            return Integer.parseInt(color.startsWith("#") ? color.substring(1) : color, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }
}
