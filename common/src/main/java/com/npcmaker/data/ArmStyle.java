package com.npcmaker.data;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

public enum ArmStyle implements StringRepresentable {
    /** Use whatever the skin says (username skins and default skins both carry this). */
    AUTO("auto"),
    /** Classic 4-pixel "Steve" arms. */
    WIDE("wide"),
    /** 3-pixel "Alex" arms. */
    SLIM("slim");

    public static final Codec<ArmStyle> CODEC = StringRepresentable.fromEnum(ArmStyle::values);

    private final String name;

    ArmStyle(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
