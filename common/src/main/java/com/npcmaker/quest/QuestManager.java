package com.npcmaker.quest;

import com.npcmaker.Constants;
import com.npcmaker.content.NpcContent;
import com.npcmaker.dialogue.DialogueActions;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.QuestActionPayload;
import com.npcmaker.network.QuestMarkersPayload;
import com.npcmaker.network.QuestNoticePayload;
import com.npcmaker.network.QuestOfferPayload;
import com.npcmaker.network.QuestSyncPayload;
import com.npcmaker.network.RewardChoicePayload;
import net.minecraft.world.item.ItemStack;
import com.npcmaker.player.PlayerData;
import com.npcmaker.player.PlayerData.QuestProgress;
import com.npcmaker.text.TextTags;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs quests on the server: starting, progress from kills / items / talking / places, stage changes, rewards, and
 * keeping each player's journal and tracker up to date. Kills and reached places count for every player on the same
 * quest within {@link #SHARE_RADIUS} blocks, so groups progress together.
 */
public final class QuestManager {

    public static final double SHARE_RADIUS = 32.0;

    private QuestManager() {
    }

    public static Optional<QuestDefinition> definition(String id) {
        return NpcContent.QUESTS.get(id);
    }

    // ---- state checks (dialogue conditions use these) -----------------------------------------------------

    public static boolean isActive(ServerPlayer player, String id) {
        return PlayerData.get(player).quest(id).isPresent();
    }

    public static boolean hasCompleted(ServerPlayer player, String id) {
        return PlayerData.get(player).completedQuests().containsKey(id);
    }

    /** 1-based stage of an active quest, or 0 if it isn't active. */
    public static int stageOf(ServerPlayer player, String id) {
        return PlayerData.get(player).quest(id).map(p -> p.stage() + 1).orElse(0);
    }

    /**
     * Whether the player could take the quest now: it exists, isn't active, isn't finished (or is repeatable and off
     * cooldown), and its {@code requires} conditions pass (checked against {@code npc} when there is one).
     */
    public static boolean canStart(ServerPlayer player, String id, @Nullable NpcEntity npc) {
        Optional<QuestDefinition> def = definition(id);
        if (def.isEmpty() || isActive(player, id)) {
            return false;
        }
        Long finished = PlayerData.get(player).completedQuests().get(id);
        if (finished != null) {
            if (!def.get().repeatable()) {
                return false;
            }
            if (System.currentTimeMillis() - finished < def.get().cooldownMinutes() * 60_000L) {
                return false;
            }
        }
        return npc == null || def.get().requires().stream().allMatch(c -> c.test(player, npc));
    }

    // ---- starting, abandoning, finishing -------------------------------------------------------------------

    /**
     * Starts a quest. With {@code force} the usual checks (requirements, already finished, cooldown) are skipped,
     * for admin commands and follow-up quests; an already active quest is never restarted.
     */
    public static boolean start(ServerPlayer player, String id, @Nullable NpcEntity npc, boolean force) {
        Optional<QuestDefinition> found = definition(id);
        if (found.isEmpty()) {
            Constants.LOG.warn("Tried to start unknown quest '{}'", id);
            return false;
        }
        if (isActive(player, id) || (!force && !canStart(player, id, npc))) {
            return false;
        }
        QuestDefinition def = found.get();
        PlayerData data = PlayerData.get(player);
        data.setQuest(id, fresh(def, 0));
        if (data.trackedQuest().isEmpty() || data.quest(data.trackedQuest()).isEmpty()) {
            data.setTrackedQuest(id);
        }
        notice(player, QuestNoticePayload.STARTED, def, stageLines(def, 0));
        if (npc != null) {
            npc.showEffect(com.npcmaker.data.Effects::onQuestGiven);
        }
        checkCollect(player, id);
        sync(player);
        return true;
    }

    /** The quest's giver standing near the player (16 blocks), for the "quest done" effect. */
    private static @Nullable NpcEntity giverNear(ServerPlayer player, QuestDefinition def) {
        if (def.giver().isEmpty()) {
            return null;
        }
        String template = def.giver().get();
        return player.level().getEntities(com.npcmaker.entity.NpcEntityTypes.npc(), player.getBoundingBox().inflate(16),
                        n -> n.isAlive() && template.equals(n.templateId())).stream()
                .min(java.util.Comparator.comparingDouble(n -> n.distanceToSqr(player))).orElse(null);
    }

    public static boolean abandon(ServerPlayer player, String id, boolean force) {
        PlayerData data = PlayerData.get(player);
        Optional<QuestDefinition> def = definition(id);
        if (data.quest(id).isEmpty() || (!force && def.map(d -> !d.abandonable()).orElse(false))) {
            return false;
        }
        data.removeQuest(id);
        retrack(data);
        def.ifPresent(d -> notice(player, QuestNoticePayload.ABANDONED, d, List.of()));
        sync(player);
        return true;
    }

    /** Finishes an active quest right away (admin command), with rewards. */
    public static boolean forceComplete(ServerPlayer player, String id) {
        Optional<QuestDefinition> def = definition(id);
        if (def.isEmpty() || !isActive(player, id)) {
            return false;
        }
        finish(player, id, def.get());
        return true;
    }

    /** Forgets a quest entirely (active and finished), so it can be taken again from scratch. */
    public static void reset(ServerPlayer player, String id) {
        PlayerData data = PlayerData.get(player);
        data.removeQuest(id);
        data.forgetCompleted(id);
        retrack(data);
        sync(player);
    }

    private static void finish(ServerPlayer player, String id, QuestDefinition def) {
        PlayerData data = PlayerData.get(player);
        data.removeQuest(id);
        data.markCompleted(id, System.currentTimeMillis());
        retrack(data);
        List<Component> lines = new ArrayList<>();
        for (QuestReward reward : def.rewards()) {
            reward.describe().ifPresent(lines::add);
        }
        if (!def.chooseOne().isEmpty()) {
            lines.add(Component.literal("+ a reward of your choice").withStyle(net.minecraft.ChatFormatting.GOLD));
            data.setPendingReward(id, true);
        }
        notice(player, QuestNoticePayload.COMPLETED, def, lines);
        NpcEntity giver = giverNear(player, def);
        if (giver != null) {
            giver.showEffect(com.npcmaker.data.Effects::onQuestDone);
        }
        for (QuestReward reward : def.rewards()) {
            try {
                reward.give(player);
            } catch (Exception e) {
                Constants.LOG.error("Quest '{}' reward {} failed", id, reward.type(), e);
            }
        }
        if (!def.chooseOne().isEmpty()) {
            sendRewardChoice(player, id, def);
        }
        sync(player);
    }

    // ---- reward choices -------------------------------------------------------------------------------------

    private static void sendRewardChoice(ServerPlayer player, String id, QuestDefinition def) {
        NpcNetworking.sendToPlayer(player, new RewardChoicePayload(id, TextTags.parse(def.title()),
                def.chooseOne().stream().map(ItemStack::copy).toList()));
    }

    /** On login: reopen the reward picker for quests finished without picking (e.g. logged out on the screen). */
    public static void sendPendingRewards(ServerPlayer player) {
        PlayerData data = PlayerData.get(player);
        for (String id : List.copyOf(data.pendingRewards())) {
            Optional<QuestDefinition> def = definition(id);
            if (def.isEmpty() || def.get().chooseOne().isEmpty()) {
                data.setPendingReward(id, false);
            } else {
                sendRewardChoice(player, id, def.get());
            }
        }
    }

    private static void chooseReward(ServerPlayer player, String id, int index) {
        PlayerData data = PlayerData.get(player);
        Optional<QuestDefinition> def = definition(id);
        if (!data.pendingRewards().contains(id) || def.isEmpty() || index < 0 || index >= def.get().chooseOne().size()) {
            return;
        }
        data.setPendingReward(id, false);
        DialogueActions.give(player, def.get().chooseOne().get(index).copy());
    }

    // ---- offers ---------------------------------------------------------------------------------------------

    /** A quest card shown to a player and not answered yet, with the NPC who offered it (for its requirements). */
    private record Offer(String quest, int npcId) {
    }

    private static final Map<java.util.UUID, Offer> OFFERS = new java.util.HashMap<>();

    /** Shows the quest offer card (if the player could take the quest); Accept starts it. */
    public static void offer(ServerPlayer player, String id, NpcEntity npc) {
        Optional<QuestDefinition> found = definition(id);
        if (found.isEmpty() || !canStart(player, id, npc)) {
            return;
        }
        QuestDefinition def = found.get();
        OFFERS.put(player.getUUID(), new Offer(id, npc.getId()));
        QuestDefinition.Stage first = def.stages().get(0);
        List<Component> objectives = first.objectives().stream()
                .<Component>map(o -> o.count() > 1 ? o.label().copy().append("  ×" + o.count()) : o.label())
                .toList();
        List<ItemStack> items = new ArrayList<>();
        List<Component> lines = new ArrayList<>();
        for (QuestReward reward : def.rewards()) {
            switch (reward) {
                case QuestReward.Item item -> items.add(item.item().copy());
                case QuestReward.Reputation rep -> lines.add(Component.literal((rep.amount() > 0 ? "+" : "") + rep.amount() + " ")
                        .append(com.npcmaker.faction.PlayerFactions.name(rep.faction())).append(" reputation"));
                default -> reward.describe().ifPresent(lines::add);
            }
        }
        NpcNetworking.sendToPlayer(player, new QuestOfferPayload(id, TextTags.parse(def.title()), TextTags.parse(def.description()),
                giverName(def), def.stages().size(), first.text().map(TextTags::parse).orElse(Component.empty()),
                objectives, items, lines, def.chooseOne().stream().map(ItemStack::copy).toList()));
    }

    private static void answerOffer(ServerPlayer player, String id, boolean accept) {
        Offer offer = OFFERS.remove(player.getUUID());
        if (offer == null) {
            return;
        }
        NpcEntity npc = player.level().getEntity(offer.npcId()) instanceof NpcEntity n ? n : null;
        if (npc != null) {
            npc.endConversation(player);
        }
        if (accept && offer.quest().equals(id)) {
            start(player, id, npc, false);
        }
    }

    /**
     * Whether the NPC should keep standing with the player because its quest card is still open. The conversation
     * that showed the card ends when the card replaces it, but the NPC waits for Accept or Decline.
     */
    public static boolean holdsConversation(ServerPlayer player, NpcEntity npc) {
        Offer offer = OFFERS.get(player.getUUID());
        return offer != null && offer.npcId() == npc.getId();
    }

    /** Drops offers whose NPC died or vanished, so nothing is left waiting. */
    private static void tickOffers(MinecraftServer server) {
        OFFERS.entrySet().removeIf(e -> {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            return player == null || !(player.level().getEntity(e.getValue().npcId()) instanceof NpcEntity npc) || !npc.isAlive();
        });
    }

    public static void forgetPlayer(ServerPlayer player) {
        Offer offer = OFFERS.remove(player.getUUID());
        if (offer != null && player.level().getEntity(offer.npcId()) instanceof NpcEntity npc) {
            npc.endConversation(player);
        }
        MARKERS_SENT.remove(player.getUUID());
    }

    private static Component giverName(QuestDefinition def) {
        return def.giver().flatMap(NpcContent.NPCS::get)
                .<Component>map(d -> Component.literal(d.displayName()).withColor(d.appearance().nameColorRgb()))
                .orElse(Component.empty());
    }

    // ---- markers over NPCs ----------------------------------------------------------------------------------

    private static final double MARKER_RANGE = 48.0;
    private static final Map<java.util.UUID, Map<Integer, Integer>> MARKERS_SENT = new java.util.HashMap<>();

    /** Which marker this player sees over this NPC: turn-in "?" beats a new quest "!" beats an in-progress "?". */
    private static int markerFor(ServerPlayer player, NpcEntity npc) {
        if (npc.instance() == null) {
            return 0;
        }
        String template = npc.instance().template();
        PlayerData data = PlayerData.get(player);
        boolean inProgress = false;
        for (Map.Entry<String, QuestProgress> e : data.activeQuests().entrySet()) {
            Optional<QuestDefinition> def = definition(e.getKey());
            if (def.isEmpty()) {
                continue;
            }
            if (def.get().giver().map(template::equals).orElse(false)) {
                inProgress = true;
            }
            Optional<QuestDefinition.Stage> stage = stage(def.get(), e.getValue());
            if (stage.isEmpty()) {
                continue;
            }
            List<QuestObjective> objectives = stage.get().objectives();
            for (int i = 0; i < objectives.size(); i++) {
                if (e.getValue().get(i) >= objectives.get(i).count()) {
                    continue;
                }
                if (objectives.get(i) instanceof QuestObjective.Talk talk && talk.npc().equals(template)) {
                    return QuestMarkersPayload.TURN_IN;
                }
                if (objectives.get(i) instanceof QuestObjective.Deliver deliver && deliver.npc().equals(template)) {
                    if (DialogueActions.countItem(player, deliver.item()) > 0) {
                        return QuestMarkersPayload.TURN_IN;
                    }
                    inProgress = true;
                }
            }
        }
        for (Map.Entry<String, QuestDefinition> e : NpcContent.QUESTS.all().entrySet()) {
            if (e.getValue().giver().map(template::equals).orElse(false) && canStart(player, e.getKey(), npc)) {
                return QuestMarkersPayload.AVAILABLE;
            }
        }
        return inProgress ? QuestMarkersPayload.IN_PROGRESS : 0;
    }

    /** For {@code /npcmaker quest markers}: what this player should see over nearby NPCs right now. */
    public static List<Component> describeMarkers(ServerPlayer player) {
        List<Component> lines = new ArrayList<>();
        for (NpcEntity npc : player.level().getEntities(com.npcmaker.entity.NpcEntityTypes.npc(),
                player.getBoundingBox().inflate(MARKER_RANGE), NpcEntity::isAlive)) {
            int marker = markerFor(player, npc);
            String shown = switch (marker) {
                case QuestMarkersPayload.AVAILABLE -> "gold !";
                case QuestMarkersPayload.TURN_IN -> "gold ?";
                case QuestMarkersPayload.IN_PROGRESS -> "grey ?";
                default -> "none";
            };
            lines.add(Component.literal("  ").append(npc.label()).append(" (id " + npc.getId() + "): " + shown));
        }
        Map<Integer, Integer> sent = MARKERS_SENT.get(player.getUUID());
        lines.add(Component.literal("  last sent to client: " + (sent == null ? "nothing" : sent)));
        return lines;
    }

    private static void updateMarkers(ServerPlayer player) {
        Map<Integer, Integer> markers = new java.util.HashMap<>();
        for (NpcEntity npc : player.level().getEntities(com.npcmaker.entity.NpcEntityTypes.npc(),
                player.getBoundingBox().inflate(MARKER_RANGE), NpcEntity::isAlive)) {
            int marker = markerFor(player, npc);
            if (marker != 0) {
                markers.put(npc.getId(), marker);
            }
        }
        if (!markers.equals(MARKERS_SENT.get(player.getUUID()))) {
            MARKERS_SENT.put(player.getUUID(), markers);
            NpcNetworking.sendToPlayer(player, new QuestMarkersPayload(markers));
        }
    }

    /** If the tracked quest is gone, track the next active one (or nothing). */
    private static void retrack(PlayerData data) {
        if (data.quest(data.trackedQuest()).isEmpty()) {
            data.setTrackedQuest(data.activeQuests().keySet().stream().findFirst().orElse(""));
        }
    }

    public static void handleAction(ServerPlayer player, QuestActionPayload payload) {
        PlayerData data = PlayerData.get(player);
        switch (payload.action()) {
            case QuestActionPayload.TRACK -> {
                if (data.quest(payload.quest()).isPresent()) {
                    data.setTrackedQuest(payload.quest());
                }
            }
            case QuestActionPayload.UNTRACK -> data.setTrackedQuest("");
            case QuestActionPayload.ABANDON -> abandon(player, payload.quest(), false);
            case QuestActionPayload.ACCEPT -> answerOffer(player, payload.quest(), true);
            case QuestActionPayload.DECLINE -> answerOffer(player, payload.quest(), false);
            case QuestActionPayload.CHOOSE_REWARD -> chooseReward(player, payload.quest(), payload.index());
            default -> {
            }
        }
        sync(player);
    }

    // ---- progress -----------------------------------------------------------------------------------------

    private static QuestProgress fresh(QuestDefinition def, int stage) {
        return new QuestProgress(stage, Collections.nCopies(def.stages().get(stage).objectives().size(), 0));
    }

    /** Current stage of an active quest, or empty if the quest or its file is gone (or the file shrank). */
    private static Optional<QuestDefinition.Stage> stage(QuestDefinition def, QuestProgress progress) {
        return progress.stage() < def.stages().size() ? Optional.of(def.stages().get(progress.stage())) : Optional.empty();
    }

    /** Sets objective {@code index} of the current stage to {@code value} (capped), then moves on if the stage is done. */
    private static void setProgress(ServerPlayer player, String id, int index, int value) {
        PlayerData data = PlayerData.get(player);
        Optional<QuestProgress> found = data.quest(id);
        Optional<QuestDefinition> def = definition(id);
        if (found.isEmpty() || def.isEmpty()) {
            return;
        }
        Optional<QuestDefinition.Stage> stage = stage(def.get(), found.get());
        if (stage.isEmpty()) {
            finish(player, id, def.get());
            return;
        }
        List<QuestObjective> objectives = stage.get().objectives();
        int capped = Math.min(value, objectives.get(index).count());
        if (found.get().get(index) == capped) {
            return;
        }
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < objectives.size(); i++) {
            counts.add(i == index ? capped : found.get().get(i));
        }
        QuestProgress updated = new QuestProgress(found.get().stage(), counts);
        data.setQuest(id, updated);
        boolean stageDone = true;
        for (int i = 0; i < objectives.size(); i++) {
            stageDone &= updated.get(i) >= objectives.get(i).count();
        }
        if (!stageDone) {
            sync(player);
        } else if (updated.stage() + 1 >= def.get().stages().size()) {
            finish(player, id, def.get());
        } else {
            int next = updated.stage() + 1;
            data.setQuest(id, fresh(def.get(), next));
            notice(player, QuestNoticePayload.NEXT_STAGE, def.get(), stageLines(def.get(), next));
            checkCollect(player, id);
            sync(player);
        }
    }

    private static void addProgress(ServerPlayer player, String id, int index, int amount) {
        PlayerData.get(player).quest(id).ifPresent(p -> setProgress(player, id, index, p.get(index) + amount));
    }

    /** Runs {@code action} for every objective of type {@code type} in the current stage of each active quest. */
    private static <T extends QuestObjective> void forEachObjective(ServerPlayer player, Class<T> type, ObjectiveVisitor<T> action) {
        PlayerData data = PlayerData.get(player);
        for (String id : List.copyOf(data.activeQuests().keySet())) {
            Optional<QuestProgress> progress = data.quest(id);
            Optional<QuestDefinition> def = definition(id);
            if (progress.isEmpty() || def.isEmpty()) {
                continue;
            }
            Optional<QuestDefinition.Stage> stage = stage(def.get(), progress.get());
            if (stage.isEmpty()) {
                continue;
            }
            int stageIndex = progress.get().stage();
            List<QuestObjective> objectives = stage.get().objectives();
            for (int i = 0; i < objectives.size(); i++) {
                // an earlier objective may have finished the stage or the quest; then this stage's list is stale
                QuestProgress now = data.quest(id).orElse(null);
                if (now == null || now.stage() != stageIndex) {
                    break;
                }
                if (type.isInstance(objectives.get(i)) && now.get(i) < objectives.get(i).count()) {
                    action.visit(id, i, type.cast(objectives.get(i)), now.get(i));
                }
            }
        }
    }

    @FunctionalInterface
    private interface ObjectiveVisitor<T> {
        void visit(String quest, int index, T objective, int current);
    }

    /**
     * Something died. If a player had a hand in it ({@code credited}), it counts for them and for every other player
     * on a matching kill objective within {@link #SHARE_RADIUS} blocks.
     */
    public static void onKill(LivingEntity victim, @Nullable ServerPlayer credited) {
        if (credited == null || !(victim.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        List<ServerPlayer> players = new ArrayList<>(level.getPlayers(p -> p != credited && p.isAlive()
                && p.distanceToSqr(victim) <= SHARE_RADIUS * SHARE_RADIUS));
        players.add(credited);
        for (ServerPlayer player : players) {
            forEachObjective(player, QuestObjective.Kill.class, (id, index, kill, current) -> {
                if (kill.matches(victim)) {
                    addProgress(player, id, index, 1);
                }
            });
        }
    }

    /**
     * The player right-clicked an NPC: ticks off "talk to" objectives for its template and hands over items for
     * "deliver" objectives. Returns whether anything changed.
     */
    public static boolean onTalk(ServerPlayer player, NpcEntity npc) {
        if (npc.instance() == null) {
            return false;
        }
        String template = npc.instance().template();
        boolean[] changed = {false};
        forEachObjective(player, QuestObjective.Deliver.class, (id, index, deliver, current) -> {
            if (deliver.npc().equals(template)) {
                int give = Math.min(DialogueActions.countItem(player, deliver.item()), deliver.count() - current);
                if (give > 0) {
                    DialogueActions.take(player, deliver.item(), give);
                    changed[0] = true;
                    addProgress(player, id, index, give);
                }
            }
        });
        forEachObjective(player, QuestObjective.Talk.class, (id, index, talk, current) -> {
            if (talk.npc().equals(template)) {
                changed[0] = true;
                setProgress(player, id, index, 1);
            }
        });
        return changed[0];
    }

    /** Ticks off custom objectives with this id (or every custom objective of the current stage when id is empty). */
    public static void completeCustom(ServerPlayer player, String quest, Optional<String> objective) {
        forEachObjective(player, QuestObjective.Custom.class, (id, index, custom, current) -> {
            if (id.equals(quest) && objective.map(custom.id()::equals).orElse(true)) {
                addProgress(player, id, index, 1);
            }
        });
    }

    private static void checkCollect(ServerPlayer player, String quest) {
        forEachObjective(player, QuestObjective.Collect.class, (id, index, collect, current) -> {
            if (id.equals(quest)) {
                setProgress(player, id, index, DialogueActions.countItem(player, collect.item()));
            }
        });
    }

    /** Once a second: item counts for "collect" and positions for "reach". */
    public static void tick(MinecraftServer server) {
        tickOffers(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            updateMarkers(player);
            if (PlayerData.get(player).activeQuests().isEmpty() || !player.isAlive()) {
                continue;
            }
            forEachObjective(player, QuestObjective.Collect.class, (id, index, collect, current) ->
                    setProgress(player, id, index, DialogueActions.countItem(player, collect.item())));
            forEachObjective(player, QuestObjective.Reach.class, (id, index, reach, current) -> {
                boolean rightDimension = reach.dimension().map(d -> d.equals(player.level().dimension().identifier())).orElse(true);
                if (rightDimension && player.position().distanceToSqr(reach.pos()) <= reach.radius() * reach.radius()) {
                    int stage = PlayerData.get(player).quest(id).map(QuestProgress::stage).orElse(-1);
                    setProgress(player, id, index, 1);
                    shareReach(player, id, stage, index);
                }
            });
        }
    }

    /** Players on the same quest step near someone who reached the place get it too. */
    private static void shareReach(ServerPlayer reacher, String quest, int stage, int index) {
        for (ServerPlayer other : reacher.level().getPlayers(p -> p != reacher && p.distanceToSqr(reacher) <= SHARE_RADIUS * SHARE_RADIUS)) {
            if (PlayerData.get(other).quest(quest).map(p -> p.stage() == stage).orElse(false)) {
                setProgress(other, quest, index, 1);
            }
        }
    }

    // ---- telling the client -------------------------------------------------------------------------------

    private static List<Component> stageLines(QuestDefinition def, int stage) {
        QuestDefinition.Stage s = def.stages().get(stage);
        if (s.text().isPresent()) {
            return List.of(TextTags.parse(s.text().get()));
        }
        return s.objectives().stream().map(QuestObjective::label).toList();
    }

    private static void notice(ServerPlayer player, int kind, QuestDefinition def, List<Component> lines) {
        NpcNetworking.sendToPlayer(player, new QuestNoticePayload(kind, TextTags.parse(def.title()), lines));
    }

    /** Sends the player's whole quest journal (active quests with progress, finished titles, tracked quest). */
    public static void sync(ServerPlayer player) {
        PlayerData data = PlayerData.get(player);
        List<QuestSyncPayload.Quest> active = new ArrayList<>();
        for (Map.Entry<String, QuestProgress> e : data.activeQuests().entrySet()) {
            Optional<QuestDefinition> found = definition(e.getKey());
            if (found.isEmpty()) {
                continue;
            }
            QuestDefinition def = found.get();
            QuestProgress progress = e.getValue();
            Optional<QuestDefinition.Stage> stage = stage(def, progress);
            List<QuestSyncPayload.Objective> objectives = new ArrayList<>();
            stage.ifPresent(s -> {
                for (int i = 0; i < s.objectives().size(); i++) {
                    QuestObjective o = s.objectives().get(i);
                    objectives.add(new QuestSyncPayload.Objective(o.label(), progress.get(i), o.count()));
                }
            });
            Component giver = giverName(def);
            active.add(new QuestSyncPayload.Quest(e.getKey(), TextTags.parse(def.title()), TextTags.parse(def.description()),
                    giver, stage.flatMap(QuestDefinition.Stage::text).map(TextTags::parse).orElse(Component.empty()),
                    progress.stage(), def.stages().size(), objectives, def.abandonable()));
        }
        List<Component> completed = data.completedQuests().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .map(e -> definition(e.getKey()).<Component>map(d -> TextTags.parse(d.title())).orElse(Component.literal(e.getKey())))
                .toList();
        NpcNetworking.sendToPlayer(player, new QuestSyncPayload(active, completed, data.trackedQuest()));
        updateMarkers(player);
    }

    public static void syncAll(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(QuestManager::sync);
    }
}
