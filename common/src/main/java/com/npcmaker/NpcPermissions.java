package com.npcmaker;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Who may create, edit and remove NPCs: operators (permission level 2), plus anyone in creative mode. */
public final class NpcPermissions {

    private NpcPermissions() {
    }

    public static boolean canEdit(CommandSourceStack source) {
        if (Commands.LEVEL_GAMEMASTERS.check(source.permissions())) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        return player != null && player.isCreative();
    }

    public static boolean canEdit(Player player) {
        return player.isCreative() || Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }
}
