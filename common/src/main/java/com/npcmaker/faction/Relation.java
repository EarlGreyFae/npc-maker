package com.npcmaker.faction;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** How one faction regards another. Ordered from friendliest to most hostile. */
public enum Relation implements StringRepresentable {
    ALLIED("allied"),
    NEUTRAL("neutral"),
    HOSTILE("hostile");

    public static final Codec<Relation> CODEC = StringRepresentable.fromEnum(Relation::values);

    private final String name;

    Relation(String name) {
        this.name = name;
    }

    /** When two sides disagree, the more hostile view wins. */
    public static Relation worst(Relation a, Relation b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
