package com.npcmaker.data;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

public enum CombatStance implements StringRepresentable {
    /** Never fights. */
    PASSIVE("passive"),
    /** Fights back when attacked. */
    DEFENSIVE("defensive"),
    /** Defends allies and its home/patrol area; attacks hostiles that come close. */
    GUARD("guard"),
    /** Seeks out and attacks anything its factions are hostile to. */
    AGGRESSIVE("aggressive");

    public static final Codec<CombatStance> CODEC = StringRepresentable.fromEnum(CombatStance::values);

    private final String name;

    CombatStance(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
