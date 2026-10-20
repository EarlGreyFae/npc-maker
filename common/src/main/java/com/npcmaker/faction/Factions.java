package com.npcmaker.faction;

import com.npcmaker.content.NpcContent;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.player.PlayerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Works out how NPCs regard other entities, based on faction membership and relations. */
public final class Factions {

    private Factions() {
    }

    /** Faction ids an entity belongs to: an NPC's template factions, or factions that claim its mob type. */
    public static List<String> factionsOf(LivingEntity entity) {
        if (entity instanceof NpcEntity npc) {
            return npc.definition().map(d -> d.factions()).orElse(List.of());
        }
        List<String> result = new ArrayList<>();
        Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        for (Map.Entry<String, FactionDefinition> e : NpcContent.FACTIONS.all().entrySet()) {
            FactionDefinition faction = e.getValue();
            boolean member = faction.entityTypes().contains(typeId.toString())
                    || faction.entityTags().stream().anyMatch(tag -> isInTag(entity, tag));
            if (member) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    private static boolean isInTag(LivingEntity entity, String tag) {
        Identifier id = Identifier.tryParse(tag.startsWith("#") ? tag.substring(1) : tag);
        return id != null && entity.is(TagKey.create(Registries.ENTITY_TYPE, id));
    }

    /** How {@code npc} regards {@code target}. Shared factions make them allies; otherwise the worst relation wins. */
    public static Relation relation(NpcEntity npc, LivingEntity target) {
        List<String> mine = factionsOf(npc);
        if (mine.isEmpty() || target == npc) {
            return Relation.NEUTRAL;
        }
        if (target instanceof ServerPlayer player) {
            return relationToPlayer(mine, player);
        }
        if (target instanceof Player) {
            return Relation.NEUTRAL;
        }
        List<String> theirs = factionsOf(target);
        if (theirs.isEmpty()) {
            return Relation.NEUTRAL;
        }
        Relation result = null;
        for (String a : mine) {
            for (String b : theirs) {
                Relation pair = a.equals(b) ? Relation.ALLIED : between(a, b);
                result = result == null ? pair : Relation.worst(result, pair);
            }
        }
        return result;
    }

    /**
     * How a group of factions regards a player. Per faction: members are allies; otherwise reputation decides
     * (hostile/allied past the faction's thresholds, its default in between), and belonging to an enemy faction
     * makes the player hostile while belonging to an allied one makes a neutral faction friendly. Across several
     * factions the most hostile view wins.
     */
    public static Relation relationToPlayer(List<String> factions, ServerPlayer player) {
        PlayerData data = PlayerData.get(player);
        Relation result = null;
        for (String id : factions) {
            Optional<FactionDefinition> def = NpcContent.FACTIONS.get(id);
            if (def.isEmpty()) {
                continue;
            }
            Relation view;
            if (data.isMember(id)) {
                view = Relation.ALLIED;
            } else {
                Relation byReputation = def.get().relationForReputation(data.reputation(id));
                Relation byMembership = Relation.NEUTRAL;
                for (String theirs : data.factions()) {
                    Relation r = between(id, theirs);
                    if (r == Relation.HOSTILE) {
                        byMembership = Relation.HOSTILE;
                        break;
                    }
                    if (r == Relation.ALLIED) {
                        byMembership = Relation.ALLIED;
                    }
                }
                if (byMembership == Relation.HOSTILE || byReputation == Relation.HOSTILE) {
                    view = Relation.HOSTILE;
                } else if (byMembership == Relation.ALLIED || byReputation == Relation.ALLIED) {
                    view = Relation.ALLIED;
                } else {
                    view = Relation.NEUTRAL;
                }
            }
            result = result == null ? view : Relation.worst(result, view);
        }
        return result == null ? Relation.NEUTRAL : result;
    }

    /** Relations are looked up in both directions; if the two factions disagree, the more hostile view wins. */
    private static Relation between(String a, String b) {
        Relation ab = NpcContent.FACTIONS.get(a).map(f -> f.relationTo(b)).orElse(Relation.NEUTRAL);
        Relation ba = NpcContent.FACTIONS.get(b).map(f -> f.relationTo(a)).orElse(Relation.NEUTRAL);
        return Relation.worst(ab, ba);
    }

    public static boolean isHostile(NpcEntity npc, LivingEntity target) {
        if (target instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return false;
        }
        return relation(npc, target) == Relation.HOSTILE;
    }
}
