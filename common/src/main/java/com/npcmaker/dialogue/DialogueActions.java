package com.npcmaker.dialogue;

import com.npcmaker.Constants;
import com.npcmaker.entity.NpcEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Inventory and command helpers shared by dialogue conditions and actions (and later quest rewards). */
public final class DialogueActions {

    private DialogueActions() {
    }

    public static int countItem(ServerPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static void take(ServerPlayer player, Item item, int count) {
        Inventory inv = player.getInventory();
        int remaining = count;
        for (int slot = 0; slot < inv.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.is(item)) {
                int taken = Math.min(remaining, stack.getCount());
                stack.shrink(taken);
                remaining -= taken;
            }
        }
        inv.setChanged();
    }

    public static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            player.drop(stack, false, Prediction.SERVER_ONLY);
        }
    }

    public static void runCommand(ServerPlayer player, NpcEntity npc, String command) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return;
        }
        String resolved = command.replace("{player}", player.getGameProfile().name());
        var source = level.getServer().createCommandSourceStack()
                .withEntity(npc)
                .withLevel(level)
                .withPosition(npc.position())
                .withPermission(PermissionSet.ALL_PERMISSIONS)
                .withSuppressedOutput();
        try {
            level.getServer().getCommands().performPrefixedCommand(source, resolved);
        } catch (Exception e) {
            Constants.LOG.error("Dialogue command failed: {}", resolved, e);
        }
    }
}
