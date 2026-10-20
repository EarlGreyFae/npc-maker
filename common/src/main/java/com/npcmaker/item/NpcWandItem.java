package com.npcmaker.item;

import com.npcmaker.NpcPermissions;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcInstance;
import com.npcmaker.entity.PatrolRoute;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The NPC Wand, for building patrol routes (operators and creative players only):
 * <ul>
 *   <li>right-click an NPC to choose whose route to edit,</li>
 *   <li>right-click blocks to add waypoints in order (sneak-right-click a block to undo the last one),</li>
 *   <li>sneak-right-click the NPC to finish.</li>
 * </ul>
 * The NPC waits at home while its route is being edited and only starts walking it once it's finished (or after
 * a minute without the editor holding the wand).
 * While held, the chosen NPC's waypoints show as particles. Wait times, facing and loop/back-and-forth are set with
 * {@code /npcmaker patrol}.
 */
public class NpcWandItem extends Item {

    /** Which NPC each player is editing. */
    private static final Map<UUID, UUID> SELECTED = new HashMap<>();

    public NpcWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof NpcEntity npc)) {
            return InteractionResult.PASS;
        }
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!NpcPermissions.canEdit(player)) {
            notice(player, Component.literal("Only operators and creative players can edit NPCs.").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        UUID current = SELECTED.get(player.getUUID());
        if (player.isShiftKeyDown() && npc.getUUID().equals(current)) {
            SELECTED.remove(player.getUUID());
            npc.finishRouteEdit();
            int count = npc.instance() == null ? 0 : npc.instance().patrol().points().size();
            player.sendSystemMessage(Component.empty().append(npc.label()).append(Component.literal(count == 0
                    ? ": no waypoints, so it stays at home."
                    : ": patrol saved with " + count + " waypoint(s). Starting the route.").withStyle(ChatFormatting.GREEN)));
            return InteractionResult.SUCCESS_SERVER;
        }
        // Switching to a different NPC finishes the previous one's route.
        selectedNpc(player).filter(previous -> previous != npc).ifPresent(NpcEntity::finishRouteEdit);
        SELECTED.put(player.getUUID(), npc.getUUID());
        npc.startRouteEdit(player);
        int count = npc.instance() == null ? 0 : npc.instance().patrol().points().size();
        player.sendSystemMessage(Component.literal("Editing the patrol of ").append(npc.label())
                .append(Component.literal(" (" + count + " waypoint(s)); it waits at home until you finish. Right-click blocks to add waypoints, sneak-right-click a block to undo, sneak-right-click the NPC to save and start the route.")
                        .withStyle(ChatFormatting.GRAY)));
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        Optional<NpcEntity> found = selectedNpc(player);
        if (found.isEmpty()) {
            notice(player, Component.literal("Right-click an NPC with the wand first to choose whose patrol to edit.").withStyle(ChatFormatting.GRAY));
            return InteractionResult.FAIL;
        }
        NpcEntity npc = found.get();
        NpcInstance instance = npc.instance();
        if (instance == null) {
            return InteractionResult.FAIL;
        }
        PatrolRoute route = instance.patrol();
        if (player.isShiftKeyDown()) {
            if (route.isEmpty()) {
                notice(player, Component.literal("No waypoints to undo.").withStyle(ChatFormatting.GRAY));
                return InteractionResult.FAIL;
            }
            npc.setPatrol(route.withoutPoint(route.points().size() - 1));
            notice(player, Component.literal("Removed waypoint " + route.points().size() + ".").withStyle(ChatFormatting.YELLOW));
            return InteractionResult.SUCCESS_SERVER;
        }
        BlockPos stand = context.getClickedPos().above();
        PatrolRoute.Waypoint point = new PatrolRoute.Waypoint(Vec3.atBottomCenterOf(stand), PatrolRoute.DEFAULT_WAIT, Optional.empty());
        npc.setPatrol(route.withPoint(point));
        notice(player, Component.literal("Waypoint " + (route.points().size() + 1) + " added.").withStyle(ChatFormatting.GREEN));
        return InteractionResult.SUCCESS_SERVER;
    }

    private static Optional<NpcEntity> selectedNpc(Player player) {
        UUID id = SELECTED.get(player.getUUID());
        if (id == null || !(player.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        Entity entity = level.getEntity(id);
        return entity instanceof NpcEntity npc && npc.isAlive() ? Optional.of(npc) : Optional.empty();
    }

    /** Short message above the hotbar. */
    private static void notice(Player player, Component message) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(message, true);
        }
    }

    private static boolean holdsWand(Player player) {
        return player.getMainHandItem().getItem() instanceof NpcWandItem || player.getOffhandItem().getItem() instanceof NpcWandItem;
    }

    /** Called a few times a second: shows the edited NPC's waypoints to players holding the wand. */
    public static void showMarkers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!holdsWand(player)) {
                continue;
            }
            Optional<NpcEntity> npc = selectedNpc(player);
            if (npc.isEmpty() || npc.get().instance() == null) {
                continue;
            }
            npc.get().touchRouteEdit(player);
            ServerLevel level = (ServerLevel) player.level();
            List<PatrolRoute.Waypoint> points = npc.get().instance().patrol().points();
            for (int i = 0; i < points.size(); i++) {
                Vec3 p = points.get(i).pos();
                // The first waypoint is marked differently so the route's start is clear.
                level.sendParticles(player, i == 0 ? ParticleTypes.FLAME : ParticleTypes.HAPPY_VILLAGER, true, true,
                        p.x, p.y + 0.6, p.z, 4, 0.15, 0.3, 0.15, 0.0);
                if (i + 1 < points.size() || (points.size() > 2 && npc.get().instance().patrol().mode() == PatrolRoute.Mode.LOOP)) {
                    Vec3 next = points.get((i + 1) % points.size()).pos();
                    for (int step = 1; step < 6; step++) {
                        Vec3 dot = p.lerp(next, step / 6.0);
                        level.sendParticles(player, ParticleTypes.END_ROD, true, true, dot.x, dot.y + 0.3, dot.z, 1, 0, 0, 0, 0);
                    }
                }
            }
        }
    }

    public static void forget(UUID player) {
        SELECTED.remove(player);
    }
}
