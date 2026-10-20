package com.npcmaker.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.JsonOps;
import com.npcmaker.network.DialoguePayloads;
import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.SaveNpcPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The dialogue graph: a simple Twine-like canvas for an NPC's conversation. Each box is something the NPC says; each
 * player reply is an arrow from a ● on the box to the box it leads to. A reply that leads nowhere ends the
 * conversation (a red END stub); one that leads back to an earlier box is a loop (teal arrow). The START box is where
 * the conversation begins. Pick a box to edit its lines and replies in the side panel, including each reply's
 * conditions ("If") and rewards / actions ("Do").
 *
 * <p>Drag boxes to arrange them (positions are saved in the file as {@code "pos"}, which the game ignores), drag empty
 * space to pan, drag from a ● onto a box to connect, or into empty space to make a new box there. Right-click a ● to
 * make that reply end the conversation. Saving asks, with two clicks, whether to update the conversation for every NPC
 * using it or give only this NPC its own copy. Uses NpcUiTheme.
 */
public class DialogueGraphScreen extends Screen {

    // ---- layout constants -----------------------------------------------------------------------------------

    private static final int BOX_W = 150;
    private static final int HEADER = 13;
    private static final int PREVIEW_LINES = 2;
    private static final int LINE = 10;
    private static final int REPLY_ROW = 11;
    private static final int TOOLBAR_H = 24;
    private static final int STATUS_H = 14;
    private static final int PANEL_W = 214;
    private static final int ROW = 16;
    private static final int BUTTON_H = 14;
    private static final int MAX_REPLIES = 9;
    private static final int MAX_PAGES = 6;
    private static final long CONFIRM_MS = 3000L;

    private static final int COLOR_LINK = 0xFFD4AF37;
    private static final int COLOR_LOOP = 0xFF4FC3B8;
    private static final int COLOR_END = 0xFFE04848;

    // ---- state ----------------------------------------------------------------------------------------------

    private final @Nullable NpcEditorScreen parent;
    private DialoguePayloads.Data data;
    private JsonObject tree = new JsonObject();
    private boolean dirty;
    private @Nullable String selected;
    private double panX = 30;
    private double panY = 30;

    private enum Drag { NONE, PAN, BOX, LINK }

    private Drag drag = Drag.NONE;
    private @Nullable String dragNode;
    private int dragReply = -1;
    private double dragStartX;
    private double dragStartY;
    private double dragOrigX;
    private double dragOrigY;
    private double mouseXNow;
    private double mouseYNow;

    private boolean choosingScope;
    private int armedScope = -1;
    private long armedAt;
    private long discardArmedAt = -1;
    private long deleteArmedAt = -1;

    private Component status = Component.empty();
    private int statusColor = NpcUiTheme.BORDER_DIM;

    /** Side panel: -1 shows the box; otherwise the reply whose conditions ("if") or actions ("actions") are edited. */
    private int subReply = -1;
    private String subKey = "";
    private int panelScroll;
    private String pendingName = "";

    private DialogueGraphScreen(@Nullable NpcEditorScreen parent, DialoguePayloads.Data data) {
        super(Component.literal("Conversation"));
        this.parent = parent;
        load(data);
    }

    /** Opens the graph (from the NPC editor), or refreshes it after a save. */
    public static void open(DialoguePayloads.Data payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof DialogueGraphScreen graph && graph.data.entityId() == payload.entityId()) {
            String keep = graph.selected;
            graph.load(payload);
            graph.selected = keep != null && graph.nodes().has(keep) ? keep : null;
            graph.rebuildWidgets();
        } else if (mc.gui.screen() instanceof NpcEditorScreen editor) {
            mc.gui.setScreen(new DialogueGraphScreen(editor, payload));
        }
    }

    /** The NPC editor this graph returns to (for refreshing it after a save). */
    @Nullable NpcEditorScreen parentEditor() {
        return parent;
    }

    public static void result(boolean ok, String message) {
        if (Minecraft.getInstance().gui.screen() instanceof DialogueGraphScreen graph) {
            graph.status = Component.literal(message);
            graph.statusColor = ok ? 0xFF7FD36A : 0xFFE04848;
        }
    }

    private void load(DialoguePayloads.Data payload) {
        this.data = payload;
        this.dirty = false;
        this.choosingScope = false;
        this.armedScope = -1;
        this.subReply = -1;
        if (payload.rawJson().isBlank()) {
            tree = newTree(payload.greeting());
            dirty = true;
            status = Component.literal("A new conversation, started from the NPC's greeting. Save to keep it.");
            statusColor = NpcUiTheme.ACCENT;
        } else {
            try {
                JsonElement parsed = JsonParser.parseString(payload.rawJson());
                tree = parsed.isJsonObject() ? parsed.getAsJsonObject() : newTree(payload.greeting());
            } catch (Exception e) {
                tree = newTree(payload.greeting());
            }
        }
        if (!tree.has("nodes") || !tree.get("nodes").isJsonObject()) {
            tree.add("nodes", new JsonObject());
        }
        autoLayout();
    }

    private static JsonObject newTree(String greeting) {
        JsonObject root = new JsonObject();
        root.addProperty("start", "greet");
        JsonObject nodes = new JsonObject();
        JsonObject greet = new JsonObject();
        JsonArray pages = new JsonArray();
        for (String line : greeting.split("\n")) {
            if (!line.isBlank()) {
                pages.add(line);
            }
        }
        if (pages.isEmpty()) {
            pages.add("Hello, {player}.");
        }
        greet.add("text", pages);
        JsonArray replies = new JsonArray();
        JsonObject bye = new JsonObject();
        bye.addProperty("text", "Farewell.");
        replies.add(bye);
        greet.add("replies", replies);
        nodes.add("greet", greet);
        root.add("nodes", nodes);
        return root;
    }

    // ---- model ----------------------------------------------------------------------------------------------

    private JsonObject nodes() {
        return tree.getAsJsonObject("nodes");
    }

    private @Nullable JsonObject node(@Nullable String id) {
        if (id == null || !nodes().has(id) || !nodes().get(id).isJsonObject()) {
            return null;
        }
        return nodes().getAsJsonObject(id);
    }

    private String start() {
        return tree.has("start") ? tree.get("start").getAsString() : "";
    }

    private static List<String> pages(JsonObject node) {
        List<String> pages = new ArrayList<>();
        JsonElement t = node.get("text");
        if (t == null) {
            return pages;
        }
        if (t.isJsonArray()) {
            t.getAsJsonArray().forEach(e -> pages.add(e.getAsString()));
        } else {
            pages.add(t.getAsString());
        }
        return pages;
    }

    private static void setPages(JsonObject node, List<String> pages) {
        JsonArray array = new JsonArray();
        pages.forEach(array::add);
        if (array.isEmpty()) {
            array.add("");
        }
        node.add("text", array);
    }

    private static JsonArray replies(JsonObject node) {
        if (!node.has("replies") || !node.get("replies").isJsonArray()) {
            node.add("replies", new JsonArray());
        }
        return node.getAsJsonArray("replies");
    }

    private static @Nullable String gotoOf(JsonObject reply) {
        return reply.has("goto") ? reply.get("goto").getAsString() : null;
    }

    private static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    private double[] pos(String id) {
        JsonObject n = node(id);
        if (n != null && n.has("pos") && n.get("pos").isJsonArray() && n.getAsJsonArray("pos").size() == 2) {
            JsonArray p = n.getAsJsonArray("pos");
            return new double[]{p.get(0).getAsDouble(), p.get(1).getAsDouble()};
        }
        return new double[]{0, 0};
    }

    private void setPos(String id, double x, double y) {
        JsonObject n = node(id);
        if (n != null) {
            JsonArray p = new JsonArray();
            p.add(Math.round(x));
            p.add(Math.round(y));
            n.add("pos", p);
        }
    }

    private void changed() {
        dirty = true;
        armedScope = -1;
        choosingScope = false;
    }

    /** Boxes without a saved position are laid out in columns by distance from the start. */
    private void autoLayout() {
        Map<String, Integer> depth = new LinkedHashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        if (node(start()) != null) {
            depth.put(start(), 0);
            queue.add(start());
        }
        while (!queue.isEmpty()) {
            String id = queue.poll();
            JsonObject n = node(id);
            if (n == null) {
                continue;
            }
            for (JsonElement r : replies(n)) {
                String target = r.isJsonObject() ? gotoOf(r.getAsJsonObject()) : null;
                if (target != null && node(target) != null && !depth.containsKey(target)) {
                    depth.put(target, depth.get(id) + 1);
                    queue.add(target);
                }
            }
        }
        int maxDepth = depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (String id : nodes().keySet()) {
            depth.putIfAbsent(id, maxDepth + 1);
        }
        Map<Integer, Integer> rows = new HashMap<>();
        for (Map.Entry<String, Integer> e : depth.entrySet()) {
            JsonObject n = node(e.getKey());
            if (n == null || n.has("pos")) {
                continue;
            }
            int row = rows.merge(e.getValue(), 1, Integer::sum) - 1;
            setPos(e.getKey(), e.getValue() * (BOX_W + 80), row * 120);
        }
    }

    private String newNodeId() {
        for (int n = 1; ; n++) {
            String id = "step_" + n;
            if (!nodes().has(id)) {
                return id;
            }
        }
    }

    private String addNode(double canvasX, double canvasY) {
        String id = newNodeId();
        JsonObject n = new JsonObject();
        JsonArray pages = new JsonArray();
        pages.add("…");
        n.add("text", pages);
        n.add("replies", new JsonArray());
        nodes().add(id, n);
        setPos(id, canvasX, canvasY);
        changed();
        return id;
    }

    /** Removes a box; replies and openers pointing at it end the conversation instead. */
    private void deleteNode(String id) {
        nodes().remove(id);
        for (String other : nodes().keySet()) {
            JsonObject n = node(other);
            if (n == null) {
                continue;
            }
            for (JsonElement r : replies(n)) {
                if (r.isJsonObject() && id.equals(gotoOf(r.getAsJsonObject()))) {
                    r.getAsJsonObject().remove("goto");
                }
            }
        }
        if (tree.has("openers") && tree.get("openers").isJsonArray()) {
            JsonArray kept = new JsonArray();
            tree.getAsJsonArray("openers").forEach(o -> {
                if (!(o.isJsonObject() && id.equals(str(o.getAsJsonObject(), "goto", "")))) {
                    kept.add(o);
                }
            });
            tree.add("openers", kept);
        }
        if (id.equals(start()) && !nodes().keySet().isEmpty()) {
            tree.addProperty("start", nodes().keySet().iterator().next());
        }
        changed();
    }

    /** Renames a box everywhere it's mentioned (replies, start, openers), keeping box order. */
    private boolean renameNode(String from, String to) {
        if (from.equals(to) || !to.matches("[a-z0-9_]+") || nodes().has(to)) {
            return false;
        }
        JsonObject renamed = new JsonObject();
        for (Map.Entry<String, JsonElement> e : nodes().entrySet()) {
            renamed.add(e.getKey().equals(from) ? to : e.getKey(), e.getValue());
        }
        tree.add("nodes", renamed);
        for (String id : nodes().keySet()) {
            for (JsonElement r : replies(node(id))) {
                if (r.isJsonObject() && from.equals(gotoOf(r.getAsJsonObject()))) {
                    r.getAsJsonObject().addProperty("goto", to);
                }
            }
        }
        if (from.equals(start())) {
            tree.addProperty("start", to);
        }
        if (tree.has("openers") && tree.get("openers").isJsonArray()) {
            tree.getAsJsonArray("openers").forEach(o -> {
                if (o.isJsonObject() && from.equals(str(o.getAsJsonObject(), "goto", ""))) {
                    o.getAsJsonObject().addProperty("goto", to);
                }
            });
        }
        changed();
        return true;
    }

    // ---- canvas geometry ------------------------------------------------------------------------------------

    private int canvasRight() {
        return selected != null ? width - PANEL_W : width;
    }

    private int boxHeight(JsonObject n) {
        return HEADER + PREVIEW_LINES * LINE + 4 + Math.max(1, replies(n).size()) * REPLY_ROW + 4;
    }

    private int boxX(String id) {
        return (int) Math.round(pos(id)[0] + panX);
    }

    private int boxY(String id) {
        return (int) Math.round(pos(id)[1] + panY + TOOLBAR_H);
    }

    private int portX(String id) {
        return boxX(id) + BOX_W;
    }

    private int portY(String id, int reply) {
        return boxY(id) + HEADER + PREVIEW_LINES * LINE + 4 + reply * REPLY_ROW + REPLY_ROW / 2;
    }

    private @Nullable String boxAt(double mx, double my) {
        if (mx >= canvasRight() || my < TOOLBAR_H || my >= height - STATUS_H) {
            return null;
        }
        String hit = null;
        for (String id : nodes().keySet()) {
            JsonObject n = node(id);
            if (n != null && mx >= boxX(id) && mx < boxX(id) + BOX_W && my >= boxY(id) && my < boxY(id) + boxHeight(n)) {
                hit = id; // later boxes draw on top, so the last hit wins
            }
        }
        return hit;
    }

    /** {node, reply index} of the ● under the mouse, or null. */
    private @Nullable Object[] portAt(double mx, double my) {
        if (mx >= canvasRight() || my < TOOLBAR_H) {
            return null;
        }
        for (String id : nodes().keySet()) {
            JsonObject n = node(id);
            if (n == null) {
                continue;
            }
            JsonArray rs = replies(n);
            for (int i = 0; i < rs.size(); i++) {
                if (Math.abs(mx - portX(id)) <= 5 && Math.abs(my - portY(id, i)) <= 5) {
                    return new Object[]{id, i};
                }
            }
        }
        return null;
    }

    // ---- widgets --------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        JsonObject n = node(selected);
        if (n == null) {
            return;
        }
        int x = panelX() + 8;
        int w = PANEL_W - 16;
        if (subReply >= 0) {
            initSubPanel(n, x, w);
            return;
        }
        // name
        pendingName = selected;
        EditBox name = box(x + 38, panelRowY(0), w - 38 - 18, selected, 32);
        name.setResponder(s -> pendingName = s.trim());
        // pages
        List<String> pages = pages(n);
        for (int i = 0; i < pages.size(); i++) {
            int index = i;
            EditBox page = box(x, pageRowY(i), w - 14, pages.get(i), 512);
            page.setResponder(s -> {
                List<String> now = pages(node(selected));
                if (index < now.size()) {
                    now.set(index, s);
                    setPages(node(selected), now);
                    changed();
                }
            });
        }
        // replies
        JsonArray rs = replies(n);
        for (int i = 0; i < rs.size(); i++) {
            int index = i;
            JsonObject reply = rs.get(i).getAsJsonObject();
            EditBox text = box(x, replyRowY(i), w - 14, str(reply, "text", ""), 256);
            text.setResponder(s -> {
                JsonArray now = replies(node(selected));
                if (index < now.size()) {
                    now.get(index).getAsJsonObject().addProperty("text", s);
                    changed();
                }
            });
        }
    }

    private EditBox box(int bx, int by, int bw, String value, int max) {
        EditBox box = new EditBox(font, bx + 3, by + 3, bw - 6, 10, Component.empty());
        box.setBordered(false);
        box.setMaxLength(max);
        box.setTextColor(NpcUiTheme.TEXT);
        box.setValue(value);
        if (by < TOOLBAR_H + 2 || by > height - STATUS_H - ROW) {
            box.visible = false; // scrolled out of the panel
        }
        addRenderableWidget(box);
        return box;
    }

    // ---- side panel geometry --------------------------------------------------------------------------------

    private int panelX() {
        return width - PANEL_W;
    }

    private int panelTop() {
        return TOOLBAR_H + 4 - panelScroll;
    }

    private int panelRowY(int row) {
        return panelTop() + row * (ROW + 2);
    }

    /** Rows: 0 name, 1 start/delete buttons, 2 "NPC says", pages…, add page, "Replies", reply pairs…, add reply. */
    private int pageRowY(int page) {
        return panelRowY(3 + page);
    }

    private int addPageRowY() {
        return pageRowY(pages(node(selected)).size());
    }

    private int repliesLabelY() {
        return addPageRowY() + ROW + 6;
    }

    private int replyRowY(int reply) {
        return repliesLabelY() + 12 + reply * (ROW * 2 + 6);
    }

    private int addReplyRowY() {
        return replyRowY(replies(node(selected)).size());
    }

    private int panelContentBottom() {
        return addReplyRowY() + ROW + 40 + panelScroll;
    }

    // ---- sub panel: conditions or actions of one reply ------------------------------------------------------

    private record Field(String key, String label, char kind, List<String> options) {
        static Field text(String key, String label) {
            return new Field(key, label, 't', List.of());
        }

        static Field number(String key, String label) {
            return new Field(key, label, 'n', List.of());
        }

        static Field decimal(String key, String label) {
            return new Field(key, label, 'd', List.of());
        }

        static Field toggle(String key, String label) {
            return new Field(key, label, 'b', List.of());
        }

        static Field choice(String key, String label, String... options) {
            return new Field(key, label, 'c', List.of(options));
        }

        /** A quest: pick one, create a new one or edit it in the quest builder. */
        static Field quest(String key) {
            return new Field(key, "quest", 'q', List.of());
        }

        /** Item id (has_item / take_item). */
        static Field item(String key) {
            return new Field(key, "item", 'i', List.of());
        }

        /** Whole item stack, with components (give_item). */
        static Field stack(String key) {
            return new Field(key, "item", 'I', List.of());
        }
    }

    private record Kind(String type, String label, List<Field> fields) {
    }

    private static final List<Kind> ACTIONS = List.of(
            new Kind("give_item", "Give item", List.of(Field.stack("item"))),
            new Kind("take_item", "Take item", List.of(Field.item("item"), Field.number("count", "count"))),
            new Kind("change_reputation", "Reputation", List.of(Field.text("faction", "faction"), Field.number("amount", "±"))),
            new Kind("start_quest", "Start quest", List.of(Field.quest("quest"))),
            new Kind("offer_quest", "Offer quest card", List.of(Field.quest("quest"))),
            new Kind("complete_objective", "Tick objective", List.of(Field.quest("quest"), Field.text("objective", "objective"))),
            new Kind("abandon_quest", "Drop quest", List.of(Field.quest("quest"))),
            new Kind("set_flag", "Set flag", List.of(Field.text("flag", "flag"), Field.toggle("value", "on"))),
            new Kind("join_faction", "Join faction", List.of(Field.text("faction", "faction"))),
            new Kind("leave_faction", "Leave faction", List.of(Field.text("faction", "faction"))),
            new Kind("become_companion", "Become companion", List.of()),
            new Kind("dismiss_companion", "Stop following", List.of()),
            new Kind("play_sound", "Play sound", List.of(Field.text("sound", "sound id"))),
            new Kind("play_animation", "Play animation", List.of(new Field("animation", "animation", 'a', List.of()))),
            new Kind("particles", "Particles", List.of(new Field("particle", "particles", 'p', List.of()))),
            new Kind("run_command", "Run command", List.of(Field.text("command", "command"))));

    private static final List<Kind> CONDITIONS = List.of(
            new Kind("flag", "Has flag", List.of(Field.text("flag", "flag"), Field.toggle("value", "set"))),
            new Kind("has_item", "Carries item", List.of(Field.item("item"), Field.number("count", "count"))),
            new Kind("quest", "Quest state", List.of(Field.quest("quest"),
                    Field.choice("state", "state", "can_start", "active", "completed", "not_started"))),
            new Kind("faction_member", "In faction", List.of(Field.text("faction", "faction"), Field.toggle("value", "member"))),
            new Kind("reputation", "Reputation", List.of(Field.text("faction", "faction"), Field.number("min", "min"))),
            new Kind("standing", "Standing", List.of(Field.text("faction", "faction"), Field.text("at_least", "at least"))),
            new Kind("can_join_faction", "Can join faction", List.of(Field.text("faction", "faction"))),
            new Kind("is_companion", "Is companion", List.of(Field.toggle("value", "yes"))),
            new Kind("chance", "Chance", List.of(Field.decimal("chance", "0–1"))),
            new Kind("can_edit", "Builders only", List.of()));

    private List<Kind> subKinds() {
        return subKey.equals("if") ? CONDITIONS : ACTIONS;
    }

    private @Nullable JsonObject subReplyObject() {
        JsonObject n = node(selected);
        if (n == null || subReply < 0 || subReply >= replies(n).size()) {
            return null;
        }
        return replies(n).get(subReply).getAsJsonObject();
    }

    private JsonArray subList() {
        JsonObject reply = subReplyObject();
        if (reply == null) {
            return new JsonArray();
        }
        if (!reply.has(subKey) || !reply.get(subKey).isJsonArray()) {
            reply.add(subKey, new JsonArray());
        }
        return reply.getAsJsonArray(subKey);
    }

    private static @Nullable Kind kindOf(List<Kind> kinds, String type) {
        return kinds.stream().filter(k -> k.type().equals(type)).findFirst().orElse(null);
    }

    private int subRowY(int index) {
        return panelTop() + 36 + index * (ROW * 2 + 8);
    }

    private void initSubPanel(JsonObject n, int x, int w) {
        JsonArray list = subList();
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).isJsonObject()) {
                continue;
            }
            JsonObject entry = list.get(i).getAsJsonObject();
            Kind kind = kindOf(subKinds(), str(entry, "type", ""));
            if (kind == null) {
                continue;
            }
            int fx = x;
            int fy = subRowY(i) + ROW + 2;
            int fieldW = (w - 14) / Math.max(1, kind.fields().size());
            for (Field field : kind.fields()) {
                if (field.kind() == 't' || field.kind() == 'n' || field.kind() == 'd') {
                    EditBox box = box(fx, fy, fieldW - 3, str(entry, field.key(), ""), 256);
                    box.setHint(Component.literal(field.label()).withColor(0xFF6E6578));
                    box.setResponder(s -> writeField(entry, field, s));
                }
                fx += fieldW;
            }
        }
    }

    private void writeField(JsonObject entry, Field field, String value) {
        String v = value.trim();
        if (v.isEmpty()) {
            entry.remove(field.key());
        } else if (field.kind() == 'n') {
            try {
                entry.addProperty(field.key(), Integer.parseInt(v));
            } catch (NumberFormatException e) {
                return;
            }
        } else if (field.kind() == 'd') {
            try {
                entry.addProperty(field.key(), Mth.clamp(Double.parseDouble(v), 0, 1));
            } catch (NumberFormatException e) {
                return;
            }
        } else {
            entry.addProperty(field.key(), v);
        }
        changed();
    }

    private static JsonObject newEntry(Kind kind) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", kind.type());
        for (Field field : kind.fields()) {
            switch (field.kind()) {
                case 'b' -> entry.addProperty(field.key(), true);
                case 'c' -> entry.addProperty(field.key(), field.options().get(0));
                case 'n' -> {
                    if (field.key().equals("count")) {
                        entry.addProperty("count", 1);
                    }
                }
                default -> {
                }
            }
        }
        return entry;
    }

    // ---- toolbar --------------------------------------------------------------------------------------------

    private record Button(int x, int y, int w, String label, boolean enabled, Runnable action) {
        boolean contains(double mx, double my) {
            return enabled && mx >= x && mx < x + w && my >= y && my < y + BUTTON_H;
        }
    }

    private List<Button> toolbar() {
        List<Button> list = new ArrayList<>();
        int y = 5;
        int right = width - 6;
        if (choosingScope) {
            String cancel = "Cancel";
            int cw = font.width(cancel) + 14;
            list.add(new Button(right - cw, y, cw, cancel, true, () -> {
                choosingScope = false;
                armedScope = -1;
                status = Component.empty();
            }));
            String only = armed(SaveNpcPayload.ONLY_THIS) ? "Confirm: only this NPC" : "Only this NPC";
            int ow = font.width(only) + 14;
            list.add(new Button(right - cw - 5 - ow, y, ow, only, true, () -> pressScope(SaveNpcPayload.ONLY_THIS)));
            int users = Math.max(1, data.dialogueId().isEmpty() && parent != null ? parent.templateUsers() : data.users());
            String all = armed(SaveNpcPayload.ALL) ? "Confirm: update " + users + " NPC" + (users == 1 ? "" : "s")
                    : data.dialogueId().isEmpty() ? "All " + users + " with this template" : "All " + users + " using it";
            int aw = font.width(all) + 14;
            list.add(new Button(right - cw - 5 - ow - 5 - aw, y, aw, all, true, () -> pressScope(SaveNpcPayload.ALL)));
            return list;
        }
        String back = discardArmed() ? "Discard changes?" : "Back";
        int bw = font.width(back) + 14;
        list.add(new Button(right - bw, y, bw, back, true, this::tryBack));
        String save = "Save…";
        int sw = font.width(save) + 18;
        list.add(new Button(right - bw - 5 - sw, y, sw, save, dirty, () -> {
            choosingScope = true;
            armedScope = -1;
            status = Component.literal("Update the conversation for every NPC using it, or only this one? Click twice to confirm.");
            statusColor = NpcUiTheme.ACCENT;
        }));
        String add = "+ Box";
        int addW = font.width(add) + 14;
        int left = 8 + font.width(titleText()) + 12;
        list.add(new Button(left, y, addW, add, true, () -> {
            String id = addNode(-panX + 40 + (canvasRight() - 80) / 3.0, -panY + 40);
            selected = id;
            subReply = -1;
            panelScroll = 0;
            rebuildWidgets();
        }));
        return list;
    }

    private String titleText() {
        return "Conversation: " + (data.dialogueId().isEmpty() ? "(new)" : data.dialogueId());
    }

    private boolean armed(int scope) {
        return armedScope == scope && Util.getMillis() - armedAt < CONFIRM_MS;
    }

    private boolean discardArmed() {
        return discardArmedAt >= 0 && Util.getMillis() - discardArmedAt < CONFIRM_MS;
    }

    private void pressScope(int scope) {
        if (!armed(scope)) {
            armedScope = scope;
            armedAt = Util.getMillis();
            return;
        }
        NpcNetworking.sendToServer(new DialoguePayloads.Save(data.entityId(), data.dialogueId(), tree.toString(), scope));
        choosingScope = false;
        armedScope = -1;
        status = Component.literal("Saving…");
        statusColor = NpcUiTheme.BORDER_DIM;
    }

    private void tryBack() {
        if (dirty && !discardArmed()) {
            discardArmedAt = Util.getMillis();
            status = Component.literal("Unsaved changes. Press Back again to throw them away.");
            statusColor = 0xFFE0A048;
            return;
        }
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        tryBack();
    }

    // ---- side panel buttons ---------------------------------------------------------------------------------

    /** The side panel's buttons that are scrolled into view. */
    private List<Button> panelButtons() {
        return allPanelButtons().stream()
                .filter(b -> b.y() >= TOOLBAR_H + 2 && b.y() <= height - STATUS_H - BUTTON_H)
                .toList();
    }

    private List<Button> allPanelButtons() {
        List<Button> list = new ArrayList<>();
        JsonObject n = node(selected);
        if (n == null) {
            return list;
        }
        int x = panelX() + 8;
        int w = PANEL_W - 16;
        if (subReply >= 0) {
            list.add(new Button(x, panelTop() + 4, font.width("‹ Back to box") + 12, "‹ Back to box", true, () -> {
                subReply = -1;
                rebuildWidgets();
            }));
            JsonArray entries = subList();
            for (int i = 0; i < entries.size(); i++) {
                int index = i;
                list.add(new Button(x + w - 10, subRowY(i) + 2, 10, "✖", true, () -> {
                    subList().remove(index);
                    changed();
                    rebuildWidgets();
                }));
            }
            String add = subKey.equals("if") ? "+ Add condition" : "+ Add reward / action";
            list.add(new Button(x, subRowY(entries.size()), w, add, entries.size() < 8, () -> {
                subList().add(newEntry(subKinds().get(0)));
                changed();
                rebuildWidgets();
            }));
            return list;
        }
        list.add(new Button(x + w - 16, panelRowY(0) + 1, 16, "✓", true, () -> {
            if (pendingName.equals(selected)) {
                return;
            }
            String old = selected;
            if (renameNode(old, pendingName)) {
                selected = pendingName;
                status = Component.literal("Renamed '" + old + "' to '" + pendingName + "'.");
                statusColor = NpcUiTheme.BORDER_DIM;
                rebuildWidgets();
            } else {
                status = Component.literal("Box names use a-z, 0-9 and _, and must be unique.");
                statusColor = 0xFFE04848;
            }
        }));
        boolean isStart = selected.equals(start());
        list.add(new Button(x, panelRowY(1), w / 2 - 3, isStart ? "★ Start box" : "Make start", !isStart, () -> {
            tree.addProperty("start", selected);
            changed();
        }));
        boolean deleteArmed = deleteArmedAt >= 0 && Util.getMillis() - deleteArmedAt < CONFIRM_MS;
        list.add(new Button(x + w / 2 + 3, panelRowY(1), w / 2 - 3, deleteArmed ? "Really delete?" : "Delete box",
                nodes().size() > 1, () -> {
            if (deleteArmedAt >= 0 && Util.getMillis() - deleteArmedAt < CONFIRM_MS) {
                deleteNode(selected);
                selected = null;
                deleteArmedAt = -1;
                rebuildWidgets();
            } else {
                deleteArmedAt = Util.getMillis();
            }
        }));
        List<String> pages = pages(n);
        for (int i = 0; i < pages.size(); i++) {
            int index = i;
            list.add(new Button(x + w - 10, pageRowY(i) + 2, 10, "✖", pages.size() > 1, () -> {
                List<String> now = pages(node(selected));
                now.remove(index);
                setPages(node(selected), now);
                changed();
                rebuildWidgets();
            }));
        }
        list.add(new Button(x, addPageRowY(), w, "+ Add a page (the NPC says more)", pages.size() < MAX_PAGES, () -> {
            List<String> now = pages(node(selected));
            now.add("");
            setPages(node(selected), now);
            changed();
            rebuildWidgets();
        }));
        JsonArray rs = replies(n);
        for (int i = 0; i < rs.size(); i++) {
            int index = i;
            JsonObject reply = rs.get(i).getAsJsonObject();
            int y = replyRowY(i);
            list.add(new Button(x + w - 10, y + 2, 10, "✖", true, () -> {
                replies(node(selected)).remove(index);
                changed();
                rebuildWidgets();
            }));
            int y2 = y + ROW + 2;
            String target = gotoOf(reply);
            String goLabel = "→ " + (target == null ? "End" : target);
            int third = (w - 4) / 3;
            list.add(new Button(x, y2, third + 20, goLabel, true, () -> cycleTarget(index, false)));
            int ifCount = reply.has("if") && reply.get("if").isJsonArray() ? reply.getAsJsonArray("if").size() : 0;
            int doCount = reply.has("actions") && reply.get("actions").isJsonArray() ? reply.getAsJsonArray("actions").size() : 0;
            list.add(new Button(x + third + 22, y2, third - 10, "If (" + ifCount + ")", true, () -> openSub(index, "if")));
            list.add(new Button(x + 2 * third + 14, y2, w - 2 * third - 14, "Do (" + doCount + ")", true, () -> openSub(index, "actions")));
        }
        list.add(new Button(x, addReplyRowY(), w, "+ Add a reply", rs.size() < MAX_REPLIES, () -> {
            JsonObject reply = new JsonObject();
            reply.addProperty("text", "…");
            replies(node(selected)).add(reply);
            changed();
            rebuildWidgets();
        }));
        return list;
    }

    private void openSub(int reply, String key) {
        subReply = reply;
        subKey = key;
        panelScroll = 0;
        rebuildWidgets();
    }

    /** Steps a reply's target through End and every box (right-click goes back). */
    private void cycleTarget(int reply, boolean back) {
        JsonArray rs = replies(node(selected));
        if (reply >= rs.size()) {
            return;
        }
        List<String> targets = new ArrayList<>();
        targets.add(null);
        targets.addAll(nodes().keySet());
        JsonObject r = rs.get(reply).getAsJsonObject();
        int at = targets.indexOf(gotoOf(r));
        String next = targets.get(Math.floorMod(at + (back ? -1 : 1), targets.size()));
        if (next == null) {
            r.remove("goto");
        } else {
            r.addProperty("goto", next);
        }
        changed();
    }

    /** Chips in the sub panel: entry type, toggles, choices and items. */
    private boolean clickSubChips(double mx, double my, boolean back) {
        JsonArray list = subList();
        int x = panelX() + 8;
        int w = PANEL_W - 16;
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).isJsonObject()) {
                continue;
            }
            JsonObject entry = list.get(i).getAsJsonObject();
            int y = subRowY(i);
            if (inside(mx, my, x, y, w - 14, ROW)) {
                List<Kind> kinds = subKinds();
                Kind current = kindOf(kinds, str(entry, "type", ""));
                int at = current == null ? -1 : kinds.indexOf(current);
                Kind next = kinds.get(Math.floorMod(at + (back ? -1 : 1), kinds.size()));
                list.set(i, newEntry(next));
                changed();
                rebuildWidgets();
                return true;
            }
            Kind kind = kindOf(subKinds(), str(entry, "type", ""));
            if (kind == null) {
                continue;
            }
            int fx = x;
            int fy = y + ROW + 2;
            int fieldW = (w - 14) / Math.max(1, kind.fields().size());
            for (Field field : kind.fields()) {
                if (inside(mx, my, fx, fy, fieldW - 3, ROW)) {
                    switch (field.kind()) {
                        case 'b' -> {
                            entry.addProperty(field.key(), !(entry.has(field.key()) && entry.get(field.key()).getAsBoolean()));
                            changed();
                            return true;
                        }
                        case 'c' -> {
                            int idx = field.options().indexOf(str(entry, field.key(), field.options().get(0)));
                            entry.addProperty(field.key(), field.options().get(Math.floorMod(idx + (back ? -1 : 1), field.options().size())));
                            changed();
                            return true;
                        }
                        case 'q' -> {
                            pickQuest(entry, field.key());
                            return true;
                        }
                        case 'a' -> {
                            List<PickListScreen.Option> options = new ArrayList<>(PickListScreen.animationOptions(false));
                            options.addAll(PickListScreen.animationOptions(true).stream()
                                    .filter(o -> !o.id().startsWith(com.npcmaker.custom.CustomContent.ANIMATION_PREFIX)).toList());
                            minecraft.gui.setScreen(PickListScreen.single(this, "Animation", options, str(entry, field.key(), ""),
                                    null, null, id -> {
                                        entry.addProperty(field.key(), id);
                                        changed();
                                    }));
                            return true;
                        }
                        case 'p' -> {
                            minecraft.gui.setScreen(PickListScreen.single(this, "Particles", PickListScreen.particleOptions(),
                                    str(entry, field.key(), ""), null, null, id -> {
                                        entry.addProperty(field.key(), id);
                                        changed();
                                    }));
                            return true;
                        }
                        case 'i', 'I' -> {
                            pickItem(stack -> {
                                if (stack.isEmpty()) {
                                    entry.remove(field.key());
                                } else if (field.kind() == 'i') {
                                    entry.addProperty(field.key(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                                } else {
                                    ItemStack.CODEC.encodeStart(itemOps(), stack).result().ifPresent(json -> entry.add(field.key(), json));
                                }
                                changed();
                            });
                            return true;
                        }
                        default -> {
                        }
                    }
                }
                fx += fieldW;
            }
        }
        return false;
    }

    private com.mojang.serialization.DynamicOps<JsonElement> itemOps() {
        return minecraft.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
    }

    /** The NPC's template id (for a new quest's giver), or "" if the graph wasn't opened from the editor. */
    private String templateId() {
        return parent != null ? parent.templateId() : "";
    }

    /** Pick a quest for an action or condition, make a new one, or open the chosen one in the quest builder. */
    private void pickQuest(JsonObject entry, String key) {
        String current = str(entry, key, "");
        List<PickListScreen.Option> options = new ArrayList<>();
        if (!current.isEmpty()) {
            options.add(new PickListScreen.Option("@edit", "✎ Edit '" + ClientContent.label(ClientContent.quests(), current) + "'…", ""));
        }
        options.add(new PickListScreen.Option("@new", "+ New quest…", ""));
        options.addAll(PickListScreen.fromIndex(ClientContent.quests()));
        minecraft.gui.setScreen(PickListScreen.single(this, "Quest for this reply", options, current, null, null, id -> {
            switch (id) {
                case "@edit" -> QuestBuilderScreen.edit(this, current, null);
                case "@new" -> QuestBuilderScreen.create(this, templateId(), created -> {
                    entry.addProperty(key, created);
                    changed();
                });
                default -> {
                    entry.addProperty(key, id);
                    changed();
                }
            }
        }));
    }

    /** The quest a reply hands out (offer card or start), or null. */
    private static @Nullable String questGiven(JsonObject reply) {
        if (!reply.has("actions") || !reply.get("actions").isJsonArray()) {
            return null;
        }
        for (JsonElement a : reply.getAsJsonArray("actions")) {
            if (a.isJsonObject()) {
                String type = str(a.getAsJsonObject(), "type", "");
                if (type.equals("offer_quest") || type.equals("start_quest")) {
                    String quest = str(a.getAsJsonObject(), "quest", "");
                    if (!quest.isEmpty()) {
                        return quest;
                    }
                }
            }
        }
        return null;
    }

    private void pickItem(Consumer<ItemStack> onPick) {
        minecraft.gui.setScreen(new ItemPickerScreen(this, Component.literal("Item for this reply"), onPick));
    }

    // ---- input ----------------------------------------------------------------------------------------------

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (help.click(mx, my)) {
            return true;
        }
        boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
        mouseXNow = mx;
        mouseYNow = my;
        for (Button b : toolbar()) {
            if (b.contains(mx, my)) {
                b.action().run();
                return true;
            }
        }
        if (my < TOOLBAR_H) {
            return true;
        }
        if (selected != null && mx >= panelX()) {
            for (Button b : panelButtons()) {
                if (b.contains(mx, my)) {
                    if (right && b.label().startsWith("→")) {
                        break; // right-click on a target chip steps back; handled below
                    }
                    b.action().run();
                    return true;
                }
            }
            if (subReply >= 0 && clickSubChips(mx, my, right)) {
                return true;
            }
            if (subReply < 0 && right) {
                // right-click a "→ target" chip steps back through the targets
                JsonArray rs = replies(node(selected));
                int x = panelX() + 8;
                int third = (PANEL_W - 16 - 4) / 3;
                for (int i = 0; i < rs.size(); i++) {
                    if (inside(mx, my, x, replyRowY(i) + ROW + 2, third + 20, BUTTON_H)) {
                        cycleTarget(i, true);
                        return true;
                    }
                }
            }
            return super.mouseClicked(event, doubleClick);
        }
        // canvas
        Object[] port = portAt(mx, my);
        if (port != null) {
            String id = (String) port[0];
            int reply = (Integer) port[1];
            if (right) {
                replies(node(id)).get(reply).getAsJsonObject().remove("goto");
                changed();
                status = Component.literal("That reply now ends the conversation.");
                statusColor = NpcUiTheme.BORDER_DIM;
                if (id.equals(selected)) {
                    rebuildWidgets();
                }
                return true;
            }
            drag = Drag.LINK;
            dragNode = id;
            dragReply = reply;
            return true;
        }
        String box = boxAt(mx, my);
        if (box != null) {
            if (!box.equals(selected)) {
                selected = box;
                subReply = -1;
                panelScroll = 0;
                deleteArmedAt = -1;
                rebuildWidgets();
            }
            drag = Drag.BOX;
            dragNode = box;
            dragStartX = mx;
            dragStartY = my;
            dragOrigX = pos(box)[0];
            dragOrigY = pos(box)[1];
            return true;
        }
        drag = Drag.PAN;
        dragStartX = mx;
        dragStartY = my;
        dragOrigX = panX;
        dragOrigY = panY;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        mouseXNow = event.x();
        mouseYNow = event.y();
        switch (drag) {
            case PAN -> {
                panX = dragOrigX + (event.x() - dragStartX);
                panY = dragOrigY + (event.y() - dragStartY);
                return true;
            }
            case BOX -> {
                if (dragNode != null) {
                    setPos(dragNode, dragOrigX + (event.x() - dragStartX), dragOrigY + (event.y() - dragStartY));
                    if (Math.abs(event.x() - dragStartX) + Math.abs(event.y() - dragStartY) > 2) {
                        dirty = true;
                    }
                }
                return true;
            }
            case LINK -> {
                return true;
            }
            default -> {
                return super.mouseDragged(event, dx, dy);
            }
        }
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        Drag was = drag;
        drag = Drag.NONE;
        if (was == Drag.PAN) {
            if (Math.abs(event.x() - dragStartX) + Math.abs(event.y() - dragStartY) < 3 && selected != null) {
                selected = null; // a click on empty canvas closes the panel
                rebuildWidgets();
            }
            return true;
        }
        if (was == Drag.LINK && dragNode != null) {
            JsonObject reply = replies(node(dragNode)).get(dragReply).getAsJsonObject();
            String target = boxAt(event.x(), event.y());
            boolean moved = Math.abs(event.x() - portX(dragNode)) + Math.abs(event.y() - portY(dragNode, dragReply)) > 16;
            if (target != null) {
                reply.addProperty("goto", target);
                changed();
            } else if (moved && event.x() < canvasRight() && event.y() > TOOLBAR_H) {
                String created = addNode(event.x() - panX, event.y() - panY - TOOLBAR_H - HEADER / 2.0);
                reply.addProperty("goto", created);
                selected = created;
                subReply = -1;
                panelScroll = 0;
            }
            rebuildWidgets();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (selected != null && x >= panelX()) {
            int maxScroll = Math.max(0, panelContentBottom() - (height - STATUS_H));
            panelScroll = Mth.clamp(panelScroll - (int) Math.signum(scrollY) * 18, 0, maxScroll);
            rebuildWidgets();
            return true;
        }
        panY += scrollY * 20;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() && help.escape()) {
            return true;
        }
        if (event.isEscape() && subReply >= 0) {
            subReply = -1;
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- drawing --------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        help.begin();
        help.spot(0, TOOLBAR_H, canvasRight(), height - STATUS_H - TOOLBAR_H, "graph.canvas");
        // canvas background with a faint dot grid
        graphics.fill(0, 0, width, height, 0xF00C0614);
        int gx0 = (int) (Math.floorMod((int) panX, 20));
        int gy0 = (int) (Math.floorMod((int) panY, 20));
        for (int gx = gx0; gx < canvasRight(); gx += 20) {
            for (int gy = TOOLBAR_H + gy0; gy < height - STATUS_H; gy += 20) {
                graphics.fill(gx, gy, gx + 1, gy + 1, 0x30D4AF37);
            }
        }

        drawLinks(graphics);
        for (String id : nodes().keySet()) {
            drawBox(graphics, id, mouseX, mouseY);
        }
        if (drag == Drag.LINK && dragNode != null) {
            int px = portX(dragNode);
            int py = portY(dragNode, dragReply);
            drawRoute(graphics, px, py, (int) mouseXNow, (int) mouseYNow, COLOR_LINK, false);
        }
        drawOpeners(graphics);

        // toolbar
        graphics.fill(0, 0, width, TOOLBAR_H, NpcUiTheme.PANEL);
        graphics.fill(0, TOOLBAR_H - 1, width, TOOLBAR_H, NpcUiTheme.BORDER);
        graphics.text(font, titleText(), 8, 8, NpcUiTheme.ACCENT, false);
        help.spot(6, 5, font.width(titleText()) + 4, 14, "graph.title");
        int leftmost = width;
        for (Button b : toolbar()) {
            drawButton(graphics, b, mouseX, mouseY);
            if (b.x() > width / 2) {
                leftmost = Math.min(leftmost, b.x());
            }
        }
        help.button(leftmost - 18, 6);

        // side panel
        if (selected != null && node(selected) != null) {
            drawPanel(graphics, mouseX, mouseY);
        }

        // status line
        graphics.fill(0, height - STATUS_H, width, height, NpcUiTheme.PANEL);
        graphics.fill(0, height - STATUS_H, width, height - STATUS_H + 1, NpcUiTheme.BORDER_DIM);
        Component line = status.getString().isEmpty()
                ? Component.literal("Drag boxes to move · drag empty space to pan · drag from ● to a box to connect, or into space for a new box · right-click ● to end there")
                : status;
        graphics.text(font, font.plainSubstrByWidth(line.getString(), width - 12), 6, height - STATUS_H + 3,
                status.getString().isEmpty() ? 0xFF6E6578 : statusColor, false);

        super.extractRenderState(graphics, mouseX, mouseY, a);
        help.render(graphics, font, mouseX, mouseY, width, height);
    }

    /** Hover tooltips and the (?) help mode. */
    private final HelpLayer help = new HelpLayer();

    /** A help spot on the canvas, trimmed to the part that isn't under the toolbar, side panel or status line. */
    private void canvasSpot(int x, int y, int w, int h, String key) {
        int x1 = Math.min(x + w, canvasRight());
        int y0 = Math.max(y, TOOLBAR_H);
        int y1 = Math.min(y + h, height - STATUS_H);
        help.spot(x, y0, x1 - x, y1 - y0, key);
    }

    /** Which help text a button gets, from its label. */
    private @Nullable String buttonHelpKey(String label) {
        if (label.equals("Save…")) {
            return "graph.save";
        } else if (label.equals("Back") || label.startsWith("Discard")) {
            return "graph.back";
        } else if (label.equals("+ Box")) {
            return "graph.add_box";
        } else if (label.equals("Cancel")) {
            return "graph.scope.cancel";
        } else if (label.toLowerCase(java.util.Locale.ROOT).contains("only this")) {
            return "graph.scope.only";
        } else if (label.startsWith("All ") || label.startsWith("Confirm: update")) {
            return "graph.scope.all";
        } else if (label.equals("✓")) {
            return "graph.panel.name";
        } else if (label.equals("★ Start box") || label.equals("Make start")) {
            return "graph.panel.make_start";
        } else if (label.equals("Delete box") || label.equals("Really delete?")) {
            return "graph.panel.delete";
        } else if (label.equals("✖")) {
            return subReply >= 0 ? "graph.sub.delete" : "graph.panel.remove";
        } else if (label.startsWith("+ Add a page")) {
            return "graph.panel.add_page";
        } else if (label.startsWith("→")) {
            return "graph.panel.target";
        } else if (label.startsWith("If (")) {
            return "graph.panel.if";
        } else if (label.startsWith("Do (")) {
            return "graph.panel.do";
        } else if (label.equals("+ Add a reply")) {
            return "graph.panel.add_reply";
        } else if (label.startsWith("‹ Back")) {
            return "graph.sub.back";
        } else if (label.startsWith("+ Add condition") || label.startsWith("+ Add reward")) {
            return "graph.sub.add";
        }
        return null;
    }

    private void drawBox(GuiGraphicsExtractor graphics, String id, int mouseX, int mouseY) {
        JsonObject n = node(id);
        if (n == null) {
            return;
        }
        int x = boxX(id);
        int y = boxY(id);
        int h = boxHeight(n);
        if (x > canvasRight() || x + BOX_W < 0 || y > height || y + h < TOOLBAR_H) {
            return;
        }
        boolean isStart = id.equals(start());
        boolean isSelected = id.equals(selected);
        canvasSpot(x, y, BOX_W, h, "graph.box");
        if (isStart) {
            canvasSpot(x + BOX_W - font.width("START") - 6, y + 1, font.width("START") + 4, HEADER - 2, "graph.box.start");
        }
        for (int i = 0; i < replies(n).size(); i++) {
            canvasSpot(portX(id) - 5, portY(id, i) - 5, 11, 11, "graph.port");
        }
        graphics.fill(x, y, x + BOX_W, y + h, NpcUiTheme.PANEL);
        graphics.fill(x, y, x + BOX_W, y + HEADER, isStart ? 0xFF3A2A12 : NpcUiTheme.PANEL_LIGHT);
        graphics.outline(x, y, BOX_W, h, isSelected ? NpcUiTheme.TEXT_HOVER : isStart ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        if (isSelected) {
            graphics.outline(x - 1, y - 1, BOX_W + 2, h + 2, NpcUiTheme.BORDER);
        }
        String title = font.plainSubstrByWidth(id, BOX_W - (isStart ? 46 : 8));
        graphics.text(font, title, x + 4, y + 3, NpcUiTheme.ACCENT, false);
        if (isStart) {
            graphics.text(font, "START", x + BOX_W - font.width("START") - 4, y + 3, 0xFFFFE08A, false);
        }
        List<String> pages = pages(n);
        String first = pages.isEmpty() ? "" : pages.get(0);
        List<FormattedCharSequence> preview = font.split(net.minecraft.network.chat.Component.literal(first), BOX_W - 8);
        for (int i = 0; i < Math.min(PREVIEW_LINES, preview.size()); i++) {
            graphics.text(font, preview.get(i), x + 4, y + HEADER + 2 + i * LINE, 0xFFBFB3A0, false);
        }
        if (pages.size() > 1) {
            String more = "+" + (pages.size() - 1);
            graphics.text(font, more, x + BOX_W - font.width(more) - 4, y + HEADER + 2 + LINE, 0xFF6E6578, false);
        }
        int sepY = y + HEADER + PREVIEW_LINES * LINE + 2;
        graphics.fill(x + 3, sepY, x + BOX_W - 3, sepY + 1, 0x40D4AF37);
        JsonArray rs = replies(n);
        if (rs.isEmpty()) {
            graphics.text(font, "(no replies: click to continue)", x + 4, sepY + 4, 0xFF6E6578, false);
        }
        for (int i = 0; i < rs.size(); i++) {
            JsonObject reply = rs.get(i).getAsJsonObject();
            int ry = portY(id, i) - 4;
            String quest = questGiven(reply);
            int tx = x + 4;
            if (quest != null) {
                // replies that hand out a quest carry the same gold "!" players see over the NPC
                graphics.text(font, "!", tx, ry, 0xFFFFD24A, true);
                tx += font.width("!") + 3;
                if (inside(mouseX, mouseY, x, ry - 2, BOX_W - 8, REPLY_ROW)) {
                    help.quiet();
                    graphics.setTooltipForNextFrame(font, Component.literal("Hands out the quest: "
                            + ClientContent.label(ClientContent.quests(), quest)), mouseX, mouseY);
                }
            }
            String text = font.plainSubstrByWidth("» " + str(reply, "text", ""), BOX_W - 10 - (tx - x));
            graphics.text(font, text, tx, ry, NpcUiTheme.TEXT, false);
            boolean hasExtras = (reply.has("if") && reply.getAsJsonArray("if").size() > 0)
                    || (reply.has("actions") && reply.getAsJsonArray("actions").size() > 0);
            int px = portX(id);
            int py = portY(id, i);
            boolean hover = Math.abs(mouseX - px) <= 5 && Math.abs(mouseY - py) <= 5;
            graphics.fill(px - 3, py - 3, px + 3, py + 3, hover ? 0xFFFFE08A : hasExtras ? 0xFFE8C46A : COLOR_LINK);
            graphics.fill(px - 2, py - 2, px + 2, py + 2, hover ? 0xFFFFE08A : 0xFF2A1A08);
        }
    }

    private void drawLinks(GuiGraphicsExtractor graphics) {
        for (String id : nodes().keySet()) {
            JsonObject n = node(id);
            if (n == null) {
                continue;
            }
            JsonArray rs = replies(n);
            for (int i = 0; i < rs.size(); i++) {
                String target = gotoOf(rs.get(i).getAsJsonObject());
                int px = portX(id);
                int py = portY(id, i);
                if (drag == Drag.LINK && id.equals(dragNode) && i == dragReply) {
                    continue;
                }
                if (target == null || node(target) == null) {
                    // END stub
                    graphics.fill(px + 3, py, px + 14, py + 1, COLOR_END);
                    int tw = font.width("END") + 6;
                    graphics.fill(px + 14, py - 5, px + 14 + tw, py + 5, 0xFF3A1010);
                    graphics.outline(px + 14, py - 5, tw, 10, COLOR_END);
                    graphics.text(font, "END", px + 17, py - 4, COLOR_END, false);
                    continue;
                }
                int tx = boxX(target);
                int ty = boxY(target) + HEADER / 2;
                boolean loop = pos(target)[0] <= pos(id)[0];
                drawRoute(graphics, px, py, tx, ty, loop ? COLOR_LOOP : COLOR_LINK, true);
            }
        }
    }

    /** An elbow line (out, across, in) with an arrowhead at the end. */
    private void drawRoute(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color, boolean arrow) {
        int out = x1 + 10;
        int in = x2 - 10;
        if (in > out) {
            int mid = (out + in) / 2;
            hLine(graphics, x1 + 3, mid, y1, color);
            vLine(graphics, mid, y1, y2, color);
            hLine(graphics, mid, x2, y2, color);
        } else {
            // going back: out to the right, over to the target's left side, then in
            int bend = Math.min(y1, y2) - 14;
            hLine(graphics, x1 + 3, out, y1, color);
            vLine(graphics, out, y1, bend, color);
            hLine(graphics, in, out, bend, color);
            vLine(graphics, in, bend, y2, color);
            hLine(graphics, in, x2, y2, color);
        }
        if (arrow) {
            graphics.fill(x2 - 4, y2 - 3, x2 - 3, y2 + 4, color);
            graphics.fill(x2 - 3, y2 - 2, x2 - 2, y2 + 3, color);
            graphics.fill(x2 - 2, y2 - 1, x2 - 1, y2 + 2, color);
        }
    }

    private static void hLine(GuiGraphicsExtractor graphics, int xa, int xb, int y, int color) {
        graphics.fill(Math.min(xa, xb), y, Math.max(xa, xb) + 1, y + 1, color);
    }

    private static void vLine(GuiGraphicsExtractor graphics, int x, int ya, int yb, int color) {
        graphics.fill(x, Math.min(ya, yb), x + 1, Math.max(ya, yb) + 1, color);
    }

    /** Openers (conditional starts) as small tags above their box. */
    private void drawOpeners(GuiGraphicsExtractor graphics) {
        if (!tree.has("openers") || !tree.get("openers").isJsonArray()) {
            return;
        }
        Map<String, Integer> perBox = new HashMap<>();
        for (JsonElement o : tree.getAsJsonArray("openers")) {
            if (!o.isJsonObject()) {
                continue;
            }
            String target = str(o.getAsJsonObject(), "goto", "");
            if (node(target) == null) {
                continue;
            }
            int k = perBox.merge(target, 1, Integer::sum) - 1;
            String label = "▶ starts here if…";
            int x = boxX(target) + k * (font.width(label) + 8);
            int y = boxY(target) - 11;
            graphics.fill(x, y, x + font.width(label) + 6, y + 10, 0xFF2A1F08);
            graphics.text(font, label, x + 3, y + 1, NpcUiTheme.BORDER_DIM, false);
            canvasSpot(x, y, font.width(label) + 6, 10, "graph.opener");
        }
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x0 = panelX();
        graphics.fill(x0, TOOLBAR_H, width, height - STATUS_H, NpcUiTheme.PANEL);
        graphics.fill(x0, TOOLBAR_H, x0 + 1, height - STATUS_H, NpcUiTheme.BORDER);
        int x = x0 + 8;
        int w = PANEL_W - 16;
        JsonObject n = node(selected);
        if (subReply >= 0) {
            drawSubPanel(graphics, x, w, mouseX, mouseY);
            for (Button b : panelButtons()) {
                drawButton(graphics, b, mouseX, mouseY);
            }
            return;
        }
        label(graphics, "Box", x, panelRowY(0) + 4);
        frame(graphics, x + 38, panelRowY(0), w - 38 - 18);
        panelSpot(x, panelRowY(0), w - 18, ROW, "graph.panel.name", null);
        label(graphics, "The NPC says (one box per page):", x, panelRowY(2) + 5);
        List<String> pages = pages(n);
        for (int i = 0; i < pages.size(); i++) {
            frame(graphics, x, pageRowY(i), w - 14);
            panelSpot(x, pageRowY(i), w - 14, ROW, "graph.panel.page", null);
        }
        label(graphics, "The player can reply:", x, repliesLabelY());
        JsonArray rs = replies(n);
        for (int i = 0; i < rs.size(); i++) {
            frame(graphics, x, replyRowY(i), w - 14);
            panelSpot(x, replyRowY(i), w - 14, ROW, "graph.panel.reply", null);
        }
        if (rs.isEmpty() && repliesLabelY() + 12 < height - STATUS_H) {
            graphics.text(font, "No replies: the box ends after the NPC speaks.", x, repliesLabelY() + 12, 0xFF6E6578, false);
        }
        for (Button b : panelButtons()) {
            drawButton(graphics, b, mouseX, mouseY);
        }
    }

    private void drawSubPanel(GuiGraphicsExtractor graphics, int x, int w, int mouseX, int mouseY) {
        JsonObject reply = subReplyObject();
        if (reply == null) {
            return;
        }
        String heading = subKey.equals("if") ? "Show this reply only if:" : "When picked, do:";
        label(graphics, heading, x, panelTop() + 23);
        String quoted = font.plainSubstrByWidth("» " + str(reply, "text", ""), w);
        graphics.text(font, quoted, x + font.width("‹ Back to box") + 18, panelTop() + 7, NpcUiTheme.TEXT, false);
        JsonArray list = subList();
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).isJsonObject()) {
                continue;
            }
            JsonObject entry = list.get(i).getAsJsonObject();
            int y = subRowY(i);
            Kind kind = kindOf(subKinds(), str(entry, "type", ""));
            String typeLabel = kind == null ? str(entry, "type", "?") + " (from file)" : kind.label();
            chip(graphics, x, y, w - 14, typeLabel + "  ▸", mouseX, mouseY);
            panelSpot(x, y, w - 14, ROW, "graph.sub.entry", (subKey.equals("if") ? "graph.if." : "graph.do.") + str(entry, "type", ""));
            if (kind == null) {
                continue;
            }
            int fx = x;
            int fy = y + ROW + 2;
            int fieldW = (w - 14) / Math.max(1, kind.fields().size());
            for (Field field : kind.fields()) {
                panelSpot(fx, fy, fieldW - 3, ROW, "graph.sub.field", null);
                switch (field.kind()) {
                    case 'b' -> {
                        boolean on = entry.has(field.key()) && entry.get(field.key()).getAsBoolean();
                        chip(graphics, fx, fy, fieldW - 3, (on ? "✔ " : "✖ ") + field.label(), mouseX, mouseY);
                    }
                    case 'c' -> chip(graphics, fx, fy, fieldW - 3, str(entry, field.key(), field.options().get(0)), mouseX, mouseY);
                    case 'a' -> {
                        String anim = str(entry, field.key(), "");
                        com.npcmaker.data.Emote builtIn = com.npcmaker.data.Emote.byId(anim);
                        chip(graphics, fx, fy, fieldW - 3, anim.isEmpty() ? "Pick an animation…" : builtIn != null ? builtIn.label()
                                : "★ " + anim.substring(anim.indexOf(':') + 1), mouseX, mouseY);
                    }
                    case 'p' -> {
                        String particle = str(entry, field.key(), "");
                        chip(graphics, fx, fy, fieldW - 3, particle.isEmpty() ? "Pick particles…" : "✦ " + particle.replace("minecraft:", ""),
                                mouseX, mouseY);
                    }
                    case 'q' -> {
                        String quest = str(entry, field.key(), "");
                        chip(graphics, fx, fy, fieldW - 3, quest.isEmpty() ? "Pick or make a quest…"
                                : "! " + ClientContent.label(ClientContent.quests(), quest), mouseX, mouseY);
                    }
                    case 'i', 'I' -> {
                        ItemStack stack = itemIn(entry, field);
                        chip(graphics, fx, fy, fieldW - 3, stack.isEmpty() ? "Pick item…" : "    " + stack.getHoverName().getString(), mouseX, mouseY);
                        if (!stack.isEmpty()) {
                            graphics.item(stack, fx + 1, fy);
                        }
                    }
                    default -> frame(graphics, fx, fy, fieldW - 3);
                }
                fx += fieldW;
            }
        }
        if (list.isEmpty()) {
            graphics.text(font, subKey.equals("if") ? "No conditions: always shown." : "Nothing happens besides the reply.",
                    x, subRowY(0) + BUTTON_H + 6, 0xFF6E6578, false);
        }
    }

    private ItemStack itemIn(JsonObject entry, Field field) {
        JsonElement e = entry.get(field.key());
        if (e == null || minecraft.level == null) {
            return ItemStack.EMPTY;
        }
        if (field.kind() == 'I') {
            return ItemStack.OPTIONAL_CODEC.parse(itemOps(), e).result().orElse(ItemStack.EMPTY);
        }
        Identifier key = Identifier.tryParse(e.getAsString());
        Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        return item == null ? ItemStack.EMPTY : item.getDefaultInstance();
    }

    private void label(GuiGraphicsExtractor graphics, String text, int x, int y) {
        if (y >= TOOLBAR_H && y < height - STATUS_H - 8) {
            graphics.text(font, text, x, y, 0xFFBFB3A0, false);
        }
    }

    private void frame(GuiGraphicsExtractor graphics, int fx, int fy, int fw) {
        if (fy < TOOLBAR_H || fy > height - STATUS_H - ROW) {
            return;
        }
        graphics.fill(fx, fy, fx + fw, fy + ROW, NpcUiTheme.PANEL_INSET);
        graphics.outline(fx, fy, fw, ROW, NpcUiTheme.BORDER_DIM);
    }

    private void chip(GuiGraphicsExtractor graphics, int cx, int cy, int cw, String text, int mouseX, int mouseY) {
        if (cy < TOOLBAR_H || cy > height - STATUS_H - ROW) {
            return;
        }
        boolean hover = inside(mouseX, mouseY, cx, cy, cw, ROW);
        graphics.fill(cx, cy, cx + cw, cy + ROW, NpcUiTheme.PANEL_LIGHT);
        graphics.outline(cx, cy, cw, ROW, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        graphics.text(font, font.plainSubstrByWidth(text, cw - 6), cx + 3, cy + 4, hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT, false);
    }

    private void drawButton(GuiGraphicsExtractor graphics, Button b, int mouseX, int mouseY) {
        if (b.y() < TOOLBAR_H && b.y() > 6) {
            return; // scrolled under the toolbar
        }
        if (b.y() > height - STATUS_H - BUTTON_H) {
            return;
        }
        boolean hover = b.contains(mouseX, mouseY);
        boolean armedLook = b.label().startsWith("Confirm") || b.label().startsWith("Really");
        graphics.fill(b.x(), b.y(), b.x() + b.w(), b.y() + BUTTON_H, NpcUiTheme.PANEL_LIGHT);
        graphics.outline(b.x(), b.y(), b.w(), BUTTON_H, armedLook || hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        int color = !b.enabled() ? 0xFF6E6578 : armedLook ? NpcUiTheme.ACCENT
                : b.label().equals("✖") ? (hover ? 0xFFE04848 : 0xFF8F8A80) : hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT;
        String text = font.plainSubstrByWidth(b.label(), b.w() - 4);
        graphics.text(font, text, b.x() + (b.w() - font.width(text)) / 2, b.y() + 3, color, false);
        String key = buttonHelpKey(b.label());
        if (key != null) {
            help.spot(b.x(), b.y(), b.w(), BUTTON_H, key);
        }
    }

    /** A help spot in the side panel, only while it's scrolled into view. */
    private void panelSpot(int x, int y, int w, int h, String key, @Nullable String valueKey) {
        if (y >= TOOLBAR_H && y + h <= height - STATUS_H) {
            help.spot(x, y, w, h, key, valueKey);
        }
    }
}
