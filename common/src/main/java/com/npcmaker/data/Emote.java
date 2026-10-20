package com.npcmaker.data;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * NPC Maker's built-in animations. One-shot emotes play once (wave, bow…); poses loop while the NPC stands still
 * (sit, sleep, read, work). Rules, dialogue replies, greetings and idle time can play any of them; the idle pose is
 * set per NPC. The client draws them (see NpcPoses).
 */
public enum Emote {
    WAVE("wave", "Wave", false, 40),
    BOW("bow", "Bow", false, 40),
    CHEER("cheer", "Cheer", false, 40),
    POINT("point", "Point", false, 40),
    SALUTE("salute", "Salute", false, 40),
    SIT("sit", "Sit", true, 0),
    SLEEP("sleep", "Sleep", true, 0),
    READ("read", "Read", true, 0),
    WORK("work", "Work (hammer)", true, 0);

    private final String id;
    private final String label;
    private final boolean pose;
    private final int ticks;

    Emote(String id, String label, boolean pose, int ticks) {
        this.id = id;
        this.label = label;
        this.pose = pose;
        this.ticks = ticks;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** Loops while the NPC stands still, instead of playing once. */
    public boolean isPose() {
        return pose;
    }

    /** Length of a one-shot emote (a pose played as an emote holds for 5 s). */
    public int ticks() {
        return pose ? 100 : ticks;
    }

    public static @Nullable Emote byId(String id) {
        String key = id.toLowerCase(Locale.ROOT).trim();
        for (Emote e : values()) {
            if (e.id.equals(key)) {
                return e;
            }
        }
        return null;
    }

    public static List<Emote> oneShots() {
        return Arrays.stream(values()).filter(e -> !e.pose).toList();
    }

    public static List<Emote> poses() {
        return Arrays.stream(values()).filter(e -> e.pose).toList();
    }
}
