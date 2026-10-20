package com.npcmaker.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.npcmaker.NpcPermissions;
import com.npcmaker.content.ContentFolder;
import com.npcmaker.content.NpcContent;
import com.npcmaker.content.NpcPresets;
import com.google.gson.JsonObject;
import com.npcmaker.data.NpcDefinition;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.PatrolRoute;
import com.npcmaker.item.NpcItems;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import com.npcmaker.faction.Factions;
import com.npcmaker.faction.PlayerFactions;
import com.npcmaker.player.PlayerData;
import com.npcmaker.quest.QuestManager;
import com.npcmaker.editor.NpcEditing;
import com.npcmaker.text.TextTags;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code /npcmaker} — spawn, move, remove and inspect NPCs, manage companions, create and reload templates.
 * Usable by operators and creative-mode players.
 */
public final class NpcCommands {

    private static final SuggestionProvider<CommandSourceStack> TEMPLATES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(NpcContent.NPCS.all().keySet(), builder);

    private NpcCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // For every player: open your own Factions screen (same as the J key).
        dispatcher.register(Commands.literal("factions").executes(ctx -> {
            PlayerFactions.sendStandings(ctx.getSource().getPlayerOrException());
            return 1;
        }));
        dispatcher.register(Commands.literal("npcmaker")
                .requires(NpcPermissions::canEdit)
                .then(Commands.literal("spawn")
                        .then(Commands.argument("template", StringArgumentType.word()).suggests(TEMPLATES)
                                .executes(ctx -> spawn(ctx, ctx.getSource().getPosition(), ctx.getSource().getRotation()))
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(ctx -> spawn(ctx, Vec3Argument.getVec3(ctx, "pos"), ctx.getSource().getRotation()))
                                        .then(Commands.argument("rotation", RotationArgument.rotation())
                                                .executes(ctx -> spawn(ctx, Vec3Argument.getVec3(ctx, "pos"),
                                                        RotationArgument.getRotation(ctx, "rotation").getRotation(ctx.getSource())))))))
                .then(Commands.literal("move")
                        .then(Commands.argument("npcs", EntityArgument.entities())
                                .executes(ctx -> move(ctx, ctx.getSource().getPosition(), ctx.getSource().getRotation()))
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(ctx -> move(ctx, Vec3Argument.getVec3(ctx, "pos"), null))
                                        .then(Commands.argument("rotation", RotationArgument.rotation())
                                                .executes(ctx -> move(ctx, Vec3Argument.getVec3(ctx, "pos"),
                                                        RotationArgument.getRotation(ctx, "rotation").getRotation(ctx.getSource())))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("npcs", EntityArgument.entities()).executes(NpcCommands::remove)))
                .then(Commands.literal("companion")
                        .then(Commands.argument("npcs", EntityArgument.entities())
                                .then(Commands.argument("owner", EntityArgument.player()).executes(NpcCommands::companion))))
                .then(Commands.literal("dismiss")
                        .then(Commands.argument("npcs", EntityArgument.entities()).executes(NpcCommands::dismiss)))
                .then(Commands.literal("create")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.literal("as")
                                        .then(Commands.argument("template", StringArgumentType.word()).suggests(AI_TEMPLATES)
                                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                                        .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "template"))))))
                                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> create(ctx, null)))))
                .then(Commands.literal("list").executes(NpcCommands::list))
                .then(Commands.literal("reload").executes(NpcCommands::reload))
                .then(Commands.literal("wand").executes(NpcCommands::giveWand))
                .then(Commands.literal("restock")
                        .then(Commands.argument("npcs", EntityArgument.entities()).executes(NpcCommands::restock)))
                .then(Commands.literal("edit")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            NpcEntity npc = NpcEditing.lookedAt(player);
                            if (npc == null) {
                                ctx.getSource().sendFailure(NpcEditing.nothingLookedAt());
                                return 0;
                            }
                            NpcEditing.open(player, npc);
                            return 1;
                        })
                        .then(Commands.argument("npc", EntityArgument.entity()).executes(ctx -> {
                            NpcEditing.open(ctx.getSource().getPlayerOrException(), singleNpc(ctx));
                            return 1;
                        })))
                .then(Commands.literal("debug")
                        .then(Commands.argument("npc", EntityArgument.entity()).executes(NpcCommands::debug)))
                .then(Commands.literal("quest")
                        .then(Commands.literal("list").executes(NpcCommands::questList))
                        .then(Commands.literal("info")
                                .then(Commands.argument("player", EntityArgument.player()).executes(NpcCommands::questInfo)))
                        .then(Commands.literal("markers")
                                .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                                    ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                                    ctx.getSource().sendSuccess(() -> Component.literal("Quest markers for " + player.getGameProfile().name() + ":"), false);
                                    QuestManager.describeMarkers(player).forEach(line -> ctx.getSource().sendSuccess(() -> line, false));
                                    return 1;
                                })))
                        .then(questCommand("start", (player, quest) -> QuestManager.start(player, quest, null, true),
                                "Started", "is already on that quest"))
                        .then(questCommand("abandon", (player, quest) -> QuestManager.abandon(player, quest, true),
                                "Abandoned", "isn't on that quest"))
                        .then(questCommand("reset", (player, quest) -> { QuestManager.reset(player, quest); return true; },
                                "Reset", ""))
                        .then(questCommand("complete", QuestManager::forceComplete, "Completed", "isn't on that quest")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("quest", StringArgumentType.word()).suggests(QUESTS)
                                                .then(Commands.argument("objective", StringArgumentType.word())
                                                        .executes(NpcCommands::questObjective))))))
                .then(Commands.literal("patrol")
                        .then(Commands.argument("npc", EntityArgument.entity())
                                .then(Commands.literal("list").executes(NpcCommands::patrolList))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> patrolEdit(ctx, r -> r.withPoint(new PatrolRoute.Waypoint(
                                                        Vec3Argument.getVec3(ctx, "pos"), PatrolRoute.DEFAULT_WAIT, Optional.empty())),
                                                        "Waypoint added."))))
                                .then(Commands.literal("clear").executes(ctx -> patrolEdit(ctx, r -> PatrolRoute.NONE, "Patrol cleared; it stays at home.")))
                                .then(Commands.literal("mode")
                                        .then(Commands.literal("loop").executes(ctx -> patrolEdit(ctx, r -> r.withMode(PatrolRoute.Mode.LOOP), "Patrol now loops.")))
                                        .then(Commands.literal("ping_pong").executes(ctx -> patrolEdit(ctx, r -> r.withMode(PatrolRoute.Mode.PING_PONG), "Patrol now goes back and forth."))))
                                .then(Commands.literal("wait")
                                        .then(Commands.argument("waypoint", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 3600))
                                                        .executes(ctx -> patrolPoint(ctx, p -> p.withWait(IntegerArgumentType.getInteger(ctx, "seconds")))))))
                                .then(Commands.literal("face")
                                        .then(Commands.argument("waypoint", IntegerArgumentType.integer(1))
                                                .then(Commands.literal("here")
                                                        .executes(ctx -> patrolPoint(ctx, p -> p.withYaw(Optional.of(ctx.getSource().getRotation().y)))))
                                                .then(Commands.literal("around")
                                                        .executes(ctx -> patrolPoint(ctx, p -> p.withYaw(Optional.empty()))))
                                                .then(Commands.argument("yaw", FloatArgumentType.floatArg(-180, 360))
                                                        .executes(ctx -> patrolPoint(ctx, p -> p.withYaw(Optional.of(FloatArgumentType.getFloat(ctx, "yaw"))))))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("waypoint", IntegerArgumentType.integer(1))
                                                .executes(NpcCommands::patrolRemove)))))
                .then(Commands.literal("faction")
                        .then(Commands.literal("list").executes(NpcCommands::factionList))
                        .then(Commands.literal("info")
                                .then(Commands.argument("player", EntityArgument.player()).executes(NpcCommands::factionInfo)))
                        .then(Commands.literal("join")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("faction", StringArgumentType.word()).suggests(FACTIONS)
                                                .executes(NpcCommands::factionJoin))))
                        .then(Commands.literal("leave")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("faction", StringArgumentType.word()).suggests(FACTIONS)
                                                .executes(NpcCommands::factionLeave))))
                        .then(Commands.literal("reputation")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("faction", StringArgumentType.word()).suggests(FACTIONS)
                                                .executes(ctx -> factionReputation(ctx, null, 0))
                                                .then(Commands.literal("set")
                                                        .then(Commands.argument("amount", IntegerArgumentType.integer(-100, 100))
                                                                .executes(ctx -> factionReputation(ctx, "set", IntegerArgumentType.getInteger(ctx, "amount")))))
                                                .then(Commands.literal("add")
                                                        .then(Commands.argument("amount", IntegerArgumentType.integer(-200, 200))
                                                                .executes(ctx -> factionReputation(ctx, "add", IntegerArgumentType.getInteger(ctx, "amount"))))))))));
    }

    // ---- wand, restock, patrol ----------------------------------------------------------------------------

    private static int giveWand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ItemStack wand = new ItemStack(NpcItems.wand());
        if (!player.getInventory().add(wand)) {
            player.drop(wand, false, Prediction.SERVER_ONLY);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Here's an NPC Wand. Right-click an NPC, then right-click blocks to add patrol waypoints; sneak-right-click the NPC to finish."), false);
        return 1;
    }

    private static int restock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<NpcEntity> list = npcs(ctx);
        list.forEach(NpcEntity::restock);
        ctx.getSource().sendSuccess(() -> Component.literal("Restocked " + list.size() + " NPC(s) with their template's gear and inventory"), true);
        return list.size();
    }

    // ---- quests -------------------------------------------------------------------------------------------

    private static final SuggestionProvider<CommandSourceStack> QUESTS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(NpcContent.QUESTS.all().keySet(), builder);

    @FunctionalInterface
    private interface QuestChange {
        boolean apply(ServerPlayer player, String quest);
    }

    /** {@code /npcmaker quest <name> <player> <quest>} for start / abandon / reset / complete. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> questCommand(
            String name, QuestChange change, String done, String failed) {
        return Commands.literal(name)
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("quest", StringArgumentType.word()).suggests(QUESTS)
                                .executes(ctx -> {
                                    ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                                    String quest = StringArgumentType.getString(ctx, "quest");
                                    if (NpcContent.QUESTS.get(quest).isEmpty()) {
                                        ctx.getSource().sendFailure(Component.literal("No quest '" + quest + "'. See /npcmaker quest list"));
                                        return 0;
                                    }
                                    if (!change.apply(player, quest)) {
                                        ctx.getSource().sendFailure(Component.literal(player.getGameProfile().name() + " " + failed));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal(done + " '" + quest + "' for "
                                            + player.getGameProfile().name()), true);
                                    return 1;
                                })));
    }

    private static int questObjective(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        String quest = StringArgumentType.getString(ctx, "quest");
        String objective = StringArgumentType.getString(ctx, "objective");
        QuestManager.completeCustom(player, quest, Optional.of(objective));
        ctx.getSource().sendSuccess(() -> Component.literal("Ticked off '" + objective + "' in '" + quest + "' for "
                + player.getGameProfile().name() + " (if it was on that step)"), true);
        return 1;
    }

    private static int questList(CommandContext<CommandSourceStack> ctx) {
        var all = NpcContent.QUESTS.all();
        ctx.getSource().sendSuccess(() -> Component.literal(all.size() + " quest(s):"), false);
        all.forEach((id, def) -> ctx.getSource().sendSuccess(() -> Component.literal("  " + id + " — ").withStyle(ChatFormatting.GRAY)
                .append(TextTags.parse(def.title())).append(Component.literal("  (" + def.stages().size() + " stage(s))")
                        .withStyle(ChatFormatting.DARK_GRAY)), false));
        return all.size();
    }

    private static int questInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        PlayerData data = PlayerData.get(player);
        ctx.getSource().sendSuccess(() -> Component.literal(player.getGameProfile().name() + ": active "
                + data.activeQuests().size() + ", finished " + data.completedQuests().size()
                + (data.trackedQuest().isEmpty() ? "" : ", tracking '" + data.trackedQuest() + "'")), false);
        data.activeQuests().forEach((id, progress) -> ctx.getSource().sendSuccess(() -> Component.literal(
                "  " + id + " — stage " + (progress.stage() + 1) + ", progress " + progress.progress()).withStyle(ChatFormatting.GRAY), false));
        data.completedQuests().keySet().forEach(id -> ctx.getSource().sendSuccess(() -> Component.literal(
                "  " + id + " — finished").withStyle(ChatFormatting.DARK_GRAY), false));
        return 1;
    }

    private static int debug(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NpcEntity npc = singleNpc(ctx);
        ctx.getSource().sendSuccess(() -> Component.empty().append(npc.label()).append(" AI (> = running):"), false);
        for (String line : npc.debugLines()) {
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static NpcEntity singleNpc(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (EntityArgument.getEntity(ctx, "npc") instanceof NpcEntity npc && npc.instance() != null) {
            return npc;
        }
        throw EntityArgument.NO_ENTITIES_FOUND.create();
    }

    private static int patrolList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NpcEntity npc = singleNpc(ctx);
        PatrolRoute route = npc.instance().patrol();
        if (route.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.empty().append(npc.label()).append(" has no patrol; it stays at home."), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.empty().append(npc.label()).append(": " + route.points().size()
                + " waypoint(s), " + (route.mode() == PatrolRoute.Mode.LOOP ? "looping" : "back and forth")), false);
        for (int i = 0; i < route.points().size(); i++) {
            PatrolRoute.Waypoint p = route.points().get(i);
            String line = String.format("  %d. %.1f %.1f %.1f · wait %ds · %s", i + 1, p.pos().x, p.pos().y, p.pos().z,
                    p.waitSeconds(), p.yaw().map(y -> String.format("faces %.0f", y)).orElse("looks around"));
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return route.points().size();
    }

    private static int patrolEdit(CommandContext<CommandSourceStack> ctx, java.util.function.UnaryOperator<PatrolRoute> change,
                                  String message) throws CommandSyntaxException {
        NpcEntity npc = singleNpc(ctx);
        npc.setPatrol(change.apply(npc.instance().patrol()));
        ctx.getSource().sendSuccess(() -> Component.empty().append(npc.label()).append(": " + message), true);
        return 1;
    }

    private static int patrolPoint(CommandContext<CommandSourceStack> ctx, java.util.function.UnaryOperator<PatrolRoute.Waypoint> change)
            throws CommandSyntaxException {
        NpcEntity npc = singleNpc(ctx);
        PatrolRoute route = npc.instance().patrol();
        int index = IntegerArgumentType.getInteger(ctx, "waypoint") - 1;
        if (index >= route.points().size()) {
            ctx.getSource().sendFailure(Component.literal("It only has " + route.points().size() + " waypoint(s)."));
            return 0;
        }
        npc.setPatrol(route.withPoint(index, change.apply(route.points().get(index))));
        ctx.getSource().sendSuccess(() -> Component.literal("Waypoint " + (index + 1) + " updated."), true);
        return 1;
    }

    private static int patrolRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NpcEntity npc = singleNpc(ctx);
        PatrolRoute route = npc.instance().patrol();
        int index = IntegerArgumentType.getInteger(ctx, "waypoint") - 1;
        if (index >= route.points().size()) {
            ctx.getSource().sendFailure(Component.literal("It only has " + route.points().size() + " waypoint(s)."));
            return 0;
        }
        npc.setPatrol(route.withoutPoint(index));
        ctx.getSource().sendSuccess(() -> Component.literal("Waypoint " + (index + 1) + " removed."), true);
        return 1;
    }

    private static final SuggestionProvider<CommandSourceStack> FACTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(NpcContent.FACTIONS.all().keySet(), builder);

    private static boolean factionExists(CommandContext<CommandSourceStack> ctx, String faction) {
        if (NpcContent.FACTIONS.get(faction).isPresent()) {
            return true;
        }
        ctx.getSource().sendFailure(Component.literal("No faction called '" + faction + "'. Use /npcmaker faction list."));
        return false;
    }

    private static int factionList(CommandContext<CommandSourceStack> ctx) {
        var all = NpcContent.FACTIONS.all();
        ctx.getSource().sendSuccess(() -> Component.literal(all.size() + " faction(s):"), false);
        all.forEach((id, def) -> {
            MutableComponent line = Component.literal("  " + id + " — ").withStyle(ChatFormatting.GRAY)
                    .append(PlayerFactions.name(id));
            if (!def.excludes().isEmpty()) {
                line.append(Component.literal("  (excludes " + String.join(", ", def.excludes()) + ")").withStyle(ChatFormatting.DARK_GRAY));
            }
            ctx.getSource().sendSuccess(() -> line, false);
        });
        return all.size();
    }

    private static int factionInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        PlayerData data = PlayerData.get(player);
        ctx.getSource().sendSuccess(() -> Component.empty().append(player.getDisplayName()).append(":"), false);
        MutableComponent member = Component.literal("  Member of: ").withStyle(ChatFormatting.GRAY);
        if (data.factions().isEmpty()) {
            member.append(Component.literal("none"));
        } else {
            boolean first = true;
            for (String f : data.factions()) {
                if (!first) {
                    member.append(", ");
                }
                member.append(PlayerFactions.name(f));
                first = false;
            }
        }
        ctx.getSource().sendSuccess(() -> member, false);
        NpcContent.FACTIONS.all().keySet().forEach(f -> {
            int rep = data.reputation(f);
            String standing = Factions.relationToPlayer(List.of(f), player).getSerializedName();
            ctx.getSource().sendSuccess(() -> Component.literal("  ").append(PlayerFactions.name(f))
                    .append(Component.literal(": " + rep + " (" + standing + ")").withStyle(ChatFormatting.GRAY)), false);
        });
        return data.factions().size();
    }

    private static int factionJoin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        String faction = StringArgumentType.getString(ctx, "faction");
        if (!factionExists(ctx, faction)) {
            return 0;
        }
        PlayerFactions.JoinResult result = PlayerFactions.join(player, faction);
        switch (result) {
            case JOINED -> ctx.getSource().sendSuccess(() -> Component.empty().append(player.getDisplayName())
                    .append(" joined ").append(PlayerFactions.name(faction)), true);
            case ALREADY_MEMBER -> ctx.getSource().sendFailure(Component.literal("Already a member."));
            case EXCLUDED -> ctx.getSource().sendFailure(Component.literal("They belong to a faction that "
                    + faction + " excludes; remove that membership first."));
            default -> {
            }
        }
        return result == PlayerFactions.JoinResult.JOINED ? 1 : 0;
    }

    private static int factionLeave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        String faction = StringArgumentType.getString(ctx, "faction");
        if (!PlayerFactions.leave(player, faction)) {
            ctx.getSource().sendFailure(Component.literal("Not a member of '" + faction + "'."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.empty().append(player.getDisplayName())
                .append(" left ").append(PlayerFactions.name(faction)), true);
        return 1;
    }

    /** {@code mode} null just shows the value; "set" or "add" changes it. */
    private static int factionReputation(CommandContext<CommandSourceStack> ctx, String mode, int amount) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        String faction = StringArgumentType.getString(ctx, "faction");
        if (!factionExists(ctx, faction)) {
            return 0;
        }
        PlayerData data = PlayerData.get(player);
        int value;
        if ("set".equals(mode)) {
            value = PlayerFactions.changeReputation(player, faction, amount - data.reputation(faction));
        } else if ("add".equals(mode)) {
            value = PlayerFactions.changeReputation(player, faction, amount);
        } else {
            value = data.reputation(faction);
        }
        int shown = value;
        ctx.getSource().sendSuccess(() -> Component.empty().append(player.getDisplayName()).append("'s reputation with ")
                .append(PlayerFactions.name(faction)).append(": " + shown), mode != null);
        return value;
    }

    private static List<NpcEntity> npcs(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<NpcEntity> found = NpcEntity.only(EntityArgument.getEntities(ctx, "npcs"));
        if (found.isEmpty()) {
            throw EntityArgument.NO_ENTITIES_FOUND.create();
        }
        return found;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, Vec3 pos, Vec2 rotation) {
        String template = StringArgumentType.getString(ctx, "template");
        NpcEntity npc = NpcEntity.place(ctx.getSource().getLevel(), template, pos, rotation.y, rotation.x);
        if (npc == null) {
            ctx.getSource().sendFailure(Component.literal("No NPC template called '" + template + "'. Use /npcmaker list."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Spawned ").append(npc.label()), true);
        return 1;
    }

    /** {@code rotation == null} keeps each NPC's current facing. */
    private static int move(CommandContext<CommandSourceStack> ctx, Vec3 pos, Vec2 rotation) throws CommandSyntaxException {
        List<NpcEntity> list = npcs(ctx);
        for (NpcEntity npc : list) {
            float yaw = rotation != null ? rotation.y : npc.getYRot();
            float pitch = rotation != null ? rotation.x : npc.getXRot();
            npc.moveHome(pos, yaw, pitch);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Moved " + list.size() + " NPC(s)"), true);
        return list.size();
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<NpcEntity> list = npcs(ctx);
        list.forEach(NpcEntity::discard);
        ctx.getSource().sendSuccess(() -> Component.literal("Removed " + list.size() + " NPC(s)"), true);
        return list.size();
    }

    private static int companion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<NpcEntity> list = npcs(ctx);
        ServerPlayer owner = EntityArgument.getPlayer(ctx, "owner");
        list.forEach(npc -> npc.setOwner(Optional.of(owner.getUUID())));
        ctx.getSource().sendSuccess(() -> Component.literal(list.size() + " NPC(s) now follow ").append(owner.getDisplayName()), true);
        return list.size();
    }

    private static int dismiss(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<NpcEntity> list = npcs(ctx);
        list.forEach(npc -> npc.setOwner(Optional.empty()));
        ctx.getSource().sendSuccess(() -> Component.literal("Dismissed " + list.size() + " companion(s); they stay where they are"), true);
        return list.size();
    }

    private static final SuggestionProvider<CommandSourceStack> AI_TEMPLATES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(NpcPresets.NAMES, builder);

    /**
     * Writes a new NPC file. With an AI template the file holds just the name and {@code "ai_template"}, so it
     * stays short and anything added to it overrides the template.
     */
    private static int create(CommandContext<CommandSourceStack> ctx, String aiTemplate) {
        String id = StringArgumentType.getString(ctx, "id");
        String name = StringArgumentType.getString(ctx, "name");
        if (!ContentFolder.isValidId(id)) {
            ctx.getSource().sendFailure(Component.literal("Template ids may only use a-z, 0-9, _ and -"));
            return 0;
        }
        if (NpcContent.NPCS.get(id).isPresent()) {
            ctx.getSource().sendFailure(Component.literal("Template '" + id + "' already exists"));
            return 0;
        }
        if (aiTemplate != null && !NpcPresets.NAMES.contains(aiTemplate)) {
            ctx.getSource().sendFailure(Component.literal("Unknown AI template '" + aiTemplate + "'. Choose from: "
                    + String.join(", ", NpcPresets.NAMES)));
            return 0;
        }
        try {
            if (aiTemplate != null) {
                JsonObject json = new JsonObject();
                json.addProperty("name", name);
                json.addProperty("ai_template", aiTemplate);
                NpcContent.NPCS.saveRaw(id, json);
            } else {
                NpcContent.NPCS.save(id, NpcDefinition.basic(name));
            }
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("Could not save template: " + e.getMessage()));
            return 0;
        }
        String kind = aiTemplate != null ? " (" + aiTemplate + ")" : "";
        ctx.getSource().sendSuccess(() -> Component.literal("Created template '" + id + "'" + kind + " in npcmaker/npcs/" + id
                + ".json. Spawn it with /npcmaker spawn " + id), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var all = NpcContent.NPCS.all();
        if (all.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No NPC templates yet. Create one with /npcmaker create <id> <name>"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(all.size() + " NPC template(s):"), false);
        all.forEach((id, def) -> ctx.getSource().sendSuccess(() -> Component.literal("  " + id + " — ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(def.displayName()).withColor(def.appearance().nameColorRgb())), false));
        return all.size();
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        NpcContent.load(ctx.getSource().getServer());
        int updated = NpcEntity.refreshAll(ctx.getSource().getServer());
        QuestManager.syncAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("Reloaded " + NpcContent.NPCS.all().size() + " NPC template(s), "
                + NpcContent.DIALOGUES.all().size() + " dialogue(s), " + NpcContent.QUESTS.all().size() + " quest(s), "
                + NpcContent.FACTIONS.all().size()
                + " faction(s); updated " + updated + " loaded NPC(s). Check the server log for files that failed to load."), true);
        return updated;
    }
}
