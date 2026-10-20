package com.npcmaker;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Constants {

    public static final String MOD_ID = "npcmaker";
    public static final String MOD_NAME = "NPC Maker";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private Constants() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
