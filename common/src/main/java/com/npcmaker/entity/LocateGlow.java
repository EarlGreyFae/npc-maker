package com.npcmaker.entity;

import java.util.HashMap;
import java.util.Map;

/**
 * Which NPCs the Live list's "Find" is lighting up, by entity id, until when. Only ever filled on the client (by
 * LiveGlow), so the glow is the finder's alone; kept here so NpcEntity can check it without naming client classes.
 */
public final class LocateGlow {

    /** NPC Maker gold. */
    public static final int COLOR = 0xE8C46A;

    private static final Map<Integer, Long> UNTIL = new HashMap<>();

    private LocateGlow() {
    }

    public static void light(int entityId, long untilMillis) {
        UNTIL.put(entityId, untilMillis);
    }

    public static boolean isLit(int entityId) {
        Long until = UNTIL.get(entityId);
        return until != null && System.currentTimeMillis() < until;
    }

    /** Forgets glows that ran out. */
    public static void prune() {
        long now = System.currentTimeMillis();
        UNTIL.values().removeIf(until -> until <= now);
    }

    public static void clear() {
        UNTIL.clear();
    }
}
