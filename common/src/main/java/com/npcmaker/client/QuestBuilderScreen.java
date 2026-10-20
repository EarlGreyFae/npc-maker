package com.npcmaker.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.npcmaker.network.ContentPayloads;
import com.npcmaker.network.NpcNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The quest builder: a full-screen editor for one quest file. Sections down the left (details, who can get it, each
 * stage, rewards, the pick-one reward); the chosen section on the right. Opened from the dialogue graph (a reply's
 * "Offer quest card" / "Start quest" action: pick, create or edit a quest) or from the NPC editor's Quests tab.
 * Quests are world-wide, so Save just saves. Uses NpcUiTheme.
 */
public class QuestBuilderScreen extends Screen implements ClientContent.Listener, JsonRows.Host {

    private static final int TOOLBAR_H = 24;
    private static final int STATUS_H = 14;
    private static final int SIDE_W = 150;
    private static final int ROW = 16;
    private static final int BUTTON_H = 14;
    private static final int LABEL_W = 120;
    private static final int MAX_STAGES = 16;
    private static final int SLOT = 18;
    private static final long CONFIRM_MS = 3000L;

    private static final List<JsonRows.Kind> OBJECTIVES = List.of(
            new JsonRows.Kind("talk", "Talk to an NPC", List.of(JsonRows.Field.of('N', "npc", "NPC"),
                    JsonRows.Field.text("text", "journal text (optional)"))),
            new JsonRows.Kind("kill", "Kill mobs", List.of(new JsonRows.Field("entity", "mob", 'E', List.of(), ""),
                    JsonRows.Field.whole("count", "how many", "1"), JsonRows.Field.text("text", "journal text (optional)")), "entity"),
            new JsonRows.Kind("kill", "Kill faction members", List.of(new JsonRows.Field("faction", "faction", 'F', List.of(), ""),
                    JsonRows.Field.whole("count", "how many", "1"), JsonRows.Field.text("text", "journal text (optional)")), "faction"),
            new JsonRows.Kind("kill", "Kill NPCs", List.of(new JsonRows.Field("npc", "NPC", 'N', List.of(), ""),
                    JsonRows.Field.whole("count", "how many", "1"), JsonRows.Field.text("text", "journal text (optional)")), "npc"),
            new JsonRows.Kind("collect", "Collect items", List.of(JsonRows.Field.of('i', "item", "item"),
                    JsonRows.Field.whole("count", "how many", "1"), JsonRows.Field.text("text", "journal text (optional)"))),
            new JsonRows.Kind("deliver", "Bring items to an NPC", List.of(JsonRows.Field.of('i', "item", "item"),
                    JsonRows.Field.whole("count", "how many", "1"), JsonRows.Field.of('N', "npc", "NPC"),
                    JsonRows.Field.text("text", "journal text (optional)"))),
            new JsonRows.Kind("reach", "Go to a place", List.of(JsonRows.Field.of('P', "pos", "place"),
                    JsonRows.Field.number("radius", "within (blocks)", "5"), JsonRows.Field.text("text", "journal text (optional)"))),
            new JsonRows.Kind("custom", "Ticked by dialogue", List.of(JsonRows.Field.text("id", "objective id"),
                    JsonRows.Field.whole("count", "how many", "1"), JsonRows.Field.text("text", "journal text (optional)"))));

    private static final List<JsonRows.Kind> REWARDS = List.of(
            new JsonRows.Kind("item", "Item", List.of(JsonRows.Field.of('I', "item", "item"))),
            new JsonRows.Kind("xp", "Experience", List.of(JsonRows.Field.whole("amount", "amount", "10"),
                    new JsonRows.Field("levels", "levels, not points", 'b', List.of(), "false"))),
            new JsonRows.Kind("reputation", "Reputation", List.of(JsonRows.Field.of('F', "faction", "faction"),
                    JsonRows.Field.whole("amount", "± amount", "5"))),
            new JsonRows.Kind("join_faction", "Join faction", List.of(JsonRows.Field.of('F', "faction", "faction"))),
            new JsonRows.Kind("set_flag", "Set flag", List.of(JsonRows.Field.text("flag", "flag"), JsonRows.Field.toggle("value", "on"))),
            new JsonRows.Kind("start_quest", "Start next quest", List.of(JsonRows.Field.of('Q', "quest", "quest"))),
            new JsonRows.Kind("command", "Run command", List.of(JsonRows.Field.text("command", "command ({player} = name)"))));

    static final List<JsonRows.Kind> CONDITIONS = List.of(
            new JsonRows.Kind("quest", "Quest state", List.of(JsonRows.Field.of('Q', "quest", "quest"),
                    JsonRows.Field.choice("state", "state", "completed", "active", "not_started", "can_start"))),
            new JsonRows.Kind("flag", "Has flag", List.of(JsonRows.Field.text("flag", "flag"), JsonRows.Field.toggle("value", "set"))),
            new JsonRows.Kind("has_item", "Carries item", List.of(JsonRows.Field.of('i', "item", "item"), JsonRows.Field.whole("count", "how many", "1"))),
            new JsonRows.Kind("faction_member", "In faction", List.of(JsonRows.Field.of('F', "faction", "faction"), JsonRows.Field.toggle("value", "member"))),
            new JsonRows.Kind("reputation", "Reputation", List.of(JsonRows.Field.of('F', "faction", "faction"), JsonRows.Field.whole("min", "at least", "0"))),
            new JsonRows.Kind("standing", "Standing", List.of(JsonRows.Field.of('F', "faction", "faction"), JsonRows.Field.text("at_least", "at least (tier name)"))),
            new JsonRows.Kind("can_join_faction", "Can join faction", List.of(JsonRows.Field.of('F', "faction", "faction"))),
            new JsonRows.Kind("is_companion", "Is companion", List.of(JsonRows.Field.toggle("value", "yes"))),
            new JsonRows.Kind("chance", "Chance", List.of(JsonRows.Field.chance("chance", "0–1"))),
            new JsonRows.Kind("can_edit", "Builders only", List.of()));

    private final Screen parent;
    private final @Nullable Consumer<String> onSaved;
    private String questId;
    private JsonObject quest;
    private boolean dirty;
    private String section = "details";
    private int mainScroll;
    private long discardArmedAt = -1;
    private long deleteStageArmedAt = -1;
    private Component status = Component.empty();
    private int statusColor = NpcUiTheme.BORDER_DIM;
    /** The row list for the current section (objectives, rewards or conditions), and which kinds it uses. */
    private @Nullable JsonRows active;
    private @Nullable List<JsonRows.Kind> activeKinds;

    private QuestBuilderScreen(Screen parent, String questId, JsonObject quest, @Nullable Consumer<String> onSaved) {
        super(Component.literal("Quest builder"));
        this.parent = parent;
        this.questId = questId;
        this.quest = quest;
        this.onSaved = onSaved;
    }

    /** A new quest given by {@code giver}; {@code onSaved} gets its id once it's saved. */
    public static void create(Screen parent, String giver, @Nullable Consumer<String> onSaved) {
        JsonObject q = new JsonObject();
        q.addProperty("title", "New quest");
        q.addProperty("description", "");
        if (!giver.isEmpty()) {
            q.addProperty("giver", giver);
        }
        JsonArray stages = new JsonArray();
        JsonObject stage = new JsonObject();
        stage.addProperty("text", "");
        JsonArray objectives = new JsonArray();
        JsonObject talk = new JsonObject();
        talk.addProperty("type", "talk");
        talk.addProperty("npc", giver);
        objectives.add(talk);
        stage.add("objectives", objectives);
        stages.add(stage);
        q.add("stages", stages);
        q.add("rewards", new JsonArray());
        QuestBuilderScreen screen = new QuestBuilderScreen(parent, "", q, onSaved);
        screen.dirty = true;
        screen.status = Component.literal("A new quest. Give it a title, objectives and rewards, then Save.");
        screen.statusColor = NpcUiTheme.ACCENT;
        Minecraft.getInstance().gui.setScreen(screen);
    }

    /** Opens an existing quest (fetched from the server first). */
    public static void edit(Screen parent, String questId, @Nullable Consumer<String> onSaved) {
        ClientContent.request(ContentPayloads.QUEST, questId, raw -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui.screen() != parent) {
                return; // the player moved on
            }
            JsonObject q;
            try {
                JsonElement e = JsonParser.parseString(raw);
                q = e.isJsonObject() ? e.getAsJsonObject() : null;
            } catch (Exception ex) {
                q = null;
            }
            if (q == null) {
                return;
            }
            mc.gui.setScreen(new QuestBuilderScreen(parent, questId, q, onSaved));
        });
    }

    // ---- model --------------------------------------------------------------------------------------------

    private JsonArray stages() {
        if (!quest.has("stages") || !quest.get("stages").isJsonArray()) {
            quest.add("stages", new JsonArray());
        }
        return quest.getAsJsonArray("stages");
    }

    private JsonObject stage(int index) {
        return stages().get(index).getAsJsonObject();
    }

    private JsonArray array(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            o.add(key, new JsonArray());
        }
        return o.getAsJsonArray(key);
    }

    private static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    private boolean bool(String key, boolean fallback) {
        JsonElement e = quest.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : fallback;
    }

    private int stageIndex() {
        return section.startsWith("stage:") ? Integer.parseInt(section.substring(6)) : -1;
    }

    @Override
    public void changed() {
        dirty = true;
        discardArmedAt = -1;
    }

    // ---- JsonRows.Host ------------------------------------------------------------------------------------

    @Override
    public Font font() {
        return font;
    }

    @Override
    public Screen screen() {
        return this;
    }

    @Override
    public void addBox(EditBox box) {
        addRenderableWidget(box);
    }

    @Override
    public void rebuild() {
        rebuildWidgets();
    }

    @Override
    public DynamicOps<JsonElement> ops() {
        return minecraft.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
    }

    /** Hover tooltips and the (?) help mode. */
    private final HelpLayer help = new HelpLayer();

    @Override
    public HelpLayer help() {
        return help;
    }

    @Override
    public int clipTop() {
        return TOOLBAR_H + 2;
    }

    @Override
    public int clipBottom() {
        return height - STATUS_H - 2;
    }

    // ---- layout -------------------------------------------------------------------------------------------

    private int mainX() {
        return SIDE_W + 14;
    }

    private int mainW() {
        return Math.min(width - mainX() - 12, 460);
    }

    private int mainTop() {
        return TOOLBAR_H + 8 - mainScroll;
    }

    private int detailRowY(int row) {
        return mainTop() + row * (ROW + 4);
    }

    private int slotsY() {
        return mainTop() + 22;
    }

    // ---- widgets ------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        int x = mainX();
        int w = mainW();
        switch (section) {
            case "details" -> {
                editBox(x + LABEL_W, detailRowY(0), w - LABEL_W, str(quest, "title", ""), "Quest title", s -> {
                    quest.addProperty("title", s);
                    changed();
                });
                editBox(x + LABEL_W, detailRowY(1), w - LABEL_W, str(quest, "description", ""), "What the journal says", s -> {
                    quest.addProperty("description", s);
                    changed();
                });
                editBox(x + LABEL_W, detailRowY(4), 60, String.valueOf(quest.has("cooldown_minutes")
                        ? quest.get("cooldown_minutes").getAsInt() : 0), "0", s -> {
                    try {
                        int v = Mth.clamp(Integer.parseInt(s.trim()), 0, 525600);
                        if (v == 0) {
                            quest.remove("cooldown_minutes");
                        } else {
                            quest.addProperty("cooldown_minutes", v);
                        }
                        changed();
                    } catch (NumberFormatException ignored) {
                    }
                });
            }
            case "requires" -> rowsInit(CONDITIONS);
            case "rewards" -> rowsInit(REWARDS);
            case "choose" -> {
            }
            default -> {
                int index = stageIndex();
                if (index < 0 || index >= stages().size()) {
                    section = "details";
                    init();
                    return;
                }
                JsonObject stage = stage(index);
                editBox(x, mainTop() + 14, w, str(stage, "text", ""), "What to do now (optional, shown above the objectives)", s -> {
                    if (s.isBlank()) {
                        stage.remove("text");
                    } else {
                        stage.addProperty("text", s);
                    }
                    changed();
                });
                rowsInit(OBJECTIVES);
            }
        }
    }

    /** Sets up the row list for this section's kinds (objectives, rewards or conditions). */
    private void rowsInit(List<JsonRows.Kind> kinds) {
        if (active == null || activeKinds != kinds) {
            String add = kinds == OBJECTIVES ? "+ Add objective" : kinds == REWARDS ? "+ Add reward" : "+ Add condition";
            active = new JsonRows(this, kinds, add, kinds == OBJECTIVES ? 16 : 12);
            active.setHelpGroup(kinds == OBJECTIVES ? "objective" : kinds == REWARDS ? "reward" : "condition");
            activeKinds = kinds;
        }
        active.setSource(rowsSource());
        active.layout(mainX(), rowsTop(), mainW());
        active.init();
    }

    private java.util.function.Supplier<JsonArray> rowsSource() {
        return switch (section) {
            case "requires" -> () -> array(quest, "requires");
            case "rewards" -> () -> array(quest, "rewards");
            default -> () -> array(stage(stageIndex()), "objectives");
        };
    }

    private int rowsTop() {
        return section.startsWith("stage:") ? mainTop() + 50 : mainTop() + 16;
    }

    private boolean showsRows() {
        return section.equals("requires") || section.equals("rewards") || section.startsWith("stage:");
    }

    private void editBox(int bx, int by, int bw, String value, String hint, Consumer<String> responder) {
        EditBox box = new EditBox(font, bx + 3, by + 4, bw - 6, 10, Component.empty());
        box.setBordered(false);
        box.setMaxLength(512);
        box.setTextColor(NpcUiTheme.TEXT);
        box.setHint(Component.literal(hint).withColor(0xFF6E6578));
        box.setValue(value);
        box.setResponder(responder);
        box.visible = by >= clipTop() && by + ROW <= clipBottom();
        addRenderableWidget(box);
    }

    // ---- side list ----------------------------------------------------------------------------------------

    private record Entry(String section, String label, int y) {
    }

    private List<Entry> sideEntries() {
        List<Entry> list = new ArrayList<>();
        int y = TOOLBAR_H + 8;
        list.add(new Entry("details", "Details", y));
        y += 18;
        list.add(new Entry("requires", "Who can get it (" + array(quest, "requires").size() + ")", y));
        y += 24;
        for (int i = 0; i < stages().size(); i++) {
            list.add(new Entry("stage:" + i, "Stage " + (i + 1) + " (" + array(stage(i), "objectives").size() + ")", y));
            y += 18;
        }
        list.add(new Entry("addstage", "+ Add stage", y));
        y += 24;
        list.add(new Entry("rewards", "Rewards (" + array(quest, "rewards").size() + ")", y));
        y += 18;
        list.add(new Entry("choose", "Pick-one reward (" + array(quest, "choose_one").size() + ")", y));
        return list;
    }

    // ---- toolbar ------------------------------------------------------------------------------------------

    private record Button(int x, int y, int w, String label, boolean enabled, Runnable action) {
        boolean contains(double mx, double my) {
            return enabled && mx >= x && mx < x + w && my >= y && my < y + BUTTON_H;
        }
    }

    private List<Button> toolbar() {
        List<Button> list = new ArrayList<>();
        int right = width - 6;
        String back = discardArmed() ? "Discard changes?" : "Back";
        int bw = font.width(back) + 14;
        list.add(new Button(right - bw, 5, bw, back, true, this::tryBack));
        String save = questId.isEmpty() ? "Create quest" : "Save";
        int sw = font.width(save) + 18;
        list.add(new Button(right - bw - 5 - sw, 5, sw, save, dirty, this::save));
        return list;
    }

    private boolean discardArmed() {
        return discardArmedAt >= 0 && Util.getMillis() - discardArmedAt < CONFIRM_MS;
    }

    private void save() {
        String title = str(quest, "title", "").trim();
        if (title.isEmpty()) {
            showResult(false, "Give the quest a title first.");
            return;
        }
        for (int i = 0; i < stages().size(); i++) {
            if (array(stage(i), "objectives").isEmpty()) {
                showResult(false, "Stage " + (i + 1) + " needs at least one objective.");
                return;
            }
        }
        NpcNetworking.sendToServer(new ContentPayloads.Save(ContentPayloads.QUEST, questId.isEmpty() ? title : questId,
                quest.toString(), questId.isEmpty()));
        status = Component.literal("Saving…");
        statusColor = NpcUiTheme.BORDER_DIM;
    }

    private void tryBack() {
        if (dirty && !discardArmed()) {
            discardArmedAt = Util.getMillis();
            showResult(false, "Unsaved changes. Press Back again to throw them away.");
            statusColor = 0xFFE0A048;
            return;
        }
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        tryBack();
    }

    // ---- ClientContent.Listener ---------------------------------------------------------------------------

    @Override
    public void showResult(boolean ok, String message) {
        status = Component.literal(message);
        statusColor = ok ? 0xFF7FD36A : 0xFFE04848;
    }

    @Override
    public void contentSaved(String kind, String id) {
        if (!kind.equals(ContentPayloads.QUEST)) {
            return;
        }
        questId = id;
        dirty = false;
        if (onSaved != null) {
            onSaved.accept(id);
        }
    }

    // ---- input --------------------------------------------------------------------------------------------

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
        if (help.click(mx, my)) {
            return true;
        }
        for (Button b : toolbar()) {
            if (b.contains(mx, my)) {
                b.action().run();
                return true;
            }
        }
        if (my < TOOLBAR_H || my >= height - STATUS_H) {
            return true;
        }
        if (mx < SIDE_W) {
            for (Entry e : sideEntries()) {
                if (inside(mx, my, 6, e.y(), SIDE_W - 12, 15)) {
                    if (e.section().startsWith("stage:") && mx >= SIDE_W - 20) {
                        deleteStage(Integer.parseInt(e.section().substring(6)));
                        return true;
                    }
                    if (e.section().equals("addstage")) {
                        if (stages().size() < MAX_STAGES) {
                            JsonObject stage = new JsonObject();
                            stage.add("objectives", new JsonArray());
                            stages().add(stage);
                            changed();
                            select("stage:" + (stages().size() - 1));
                        }
                        return true;
                    }
                    select(e.section());
                    return true;
                }
            }
            return true;
        }
        if (section.equals("details") && clickDetails(mx, my)) {
            return true;
        }
        if (section.equals("choose") && clickChoose(mx, my, right)) {
            return true;
        }
        if (showsRows() && active != null && active.click(mx, my, event.button())) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void select(String next) {
        section = next;
        mainScroll = 0;
        deleteStageArmedAt = -1;
        rebuildWidgets();
    }

    private void deleteStage(int index) {
        if (stages().size() <= 1) {
            showResult(false, "A quest needs at least one stage.");
            return;
        }
        if (deleteStageArmedAt >= 0 && Util.getMillis() - deleteStageArmedAt < CONFIRM_MS) {
            stages().remove(index);
            changed();
            deleteStageArmedAt = -1;
            select("details");
        } else {
            deleteStageArmedAt = Util.getMillis();
            showResult(false, "Click ✖ again to delete stage " + (index + 1) + ".");
            statusColor = 0xFFE0A048;
        }
    }

    private boolean clickDetails(double mx, double my) {
        int x = mainX() + LABEL_W;
        int w = mainW() - LABEL_W;
        if (inside(mx, my, x, detailRowY(2), w, ROW)) {
            Minecraft.getInstance().gui.setScreen(PickListScreen.single(this, "Who gives this quest?",
                    PickListScreen.fromIndex(ClientContent.npcs()), str(quest, "giver", ""), "Nobody", null, id -> {
                        if (id.isEmpty()) {
                            quest.remove("giver");
                        } else {
                            quest.addProperty("giver", id);
                        }
                        changed();
                    }));
            return true;
        }
        if (inside(mx, my, x, detailRowY(3), w, ROW)) {
            quest.addProperty("repeatable", !bool("repeatable", false));
            changed();
            return true;
        }
        if (inside(mx, my, x, detailRowY(5), w, ROW)) {
            quest.addProperty("abandonable", !bool("abandonable", true));
            changed();
            return true;
        }
        return false;
    }

    private int slotX(int i) {
        return mainX() + i * (SLOT + 4);
    }

    private boolean clickChoose(double mx, double my, boolean right) {
        JsonArray items = array(quest, "choose_one");
        for (int i = 0; i < 9; i++) {
            if (inside(mx, my, slotX(i), slotsY(), SLOT, SLOT)) {
                int index = i;
                if (right) {
                    if (index < items.size()) {
                        items.remove(index);
                        changed();
                    }
                    return true;
                }
                Minecraft.getInstance().gui.setScreen(new ItemPickerScreen(this, Component.literal("Pick-one reward"), stack -> {
                    JsonArray now = array(quest, "choose_one");
                    if (stack.isEmpty()) {
                        if (index < now.size()) {
                            now.remove(index);
                        }
                    } else {
                        ItemStack.CODEC.encodeStart(ops(), stack).result().ifPresent(json -> {
                            if (index < now.size()) {
                                now.set(index, json);
                            } else {
                                now.add(json);
                            }
                        });
                    }
                    changed();
                }));
                return true;
            }
        }
        return false;
    }

    private ItemStack chooseStack(int index) {
        JsonArray items = array(quest, "choose_one");
        if (index >= items.size()) {
            return ItemStack.EMPTY;
        }
        return ItemStack.OPTIONAL_CODEC.parse(ops(), items.get(index)).result().orElse(ItemStack.EMPTY);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (section.equals("choose")) {
            JsonArray items = array(quest, "choose_one");
            for (int i = 0; i < 9 && i < items.size(); i++) {
                if (inside(x, y, slotX(i), slotsY(), SLOT, SLOT)) {
                    ItemStack stack = chooseStack(i);
                    int step = minecraft.hasShiftDown() ? 8 : 1;
                    stack.setCount(Mth.clamp(stack.getCount() + (int) Math.signum(scrollY) * step, 1, stack.getMaxStackSize()));
                    int index = i;
                    ItemStack.CODEC.encodeStart(ops(), stack).result().ifPresent(json -> items.set(index, json));
                    changed();
                    return true;
                }
            }
        }
        if (x >= SIDE_W && showsRows() && active != null) {
            int content = rowsTop() + mainScroll + active.height() + 20;
            int max = Math.max(0, content - (height - STATUS_H));
            mainScroll = Mth.clamp(mainScroll - (int) Math.signum(scrollY) * 18, 0, max);
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() && help.escape()) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        help.begin();
        graphics.fill(0, 0, width, height, 0xF00C0614);

        // side list
        graphics.fill(0, TOOLBAR_H, SIDE_W, height - STATUS_H, NpcUiTheme.PANEL);
        graphics.fill(SIDE_W, TOOLBAR_H, SIDE_W + 1, height - STATUS_H, NpcUiTheme.BORDER);
        boolean deleteArmed = deleteStageArmedAt >= 0 && Util.getMillis() - deleteStageArmedAt < CONFIRM_MS;
        for (Entry e : sideEntries()) {
            boolean selected = e.section().equals(section);
            boolean hover = inside(mouseX, mouseY, 6, e.y(), SIDE_W - 12, 15);
            if (selected || hover) {
                graphics.fill(6, e.y(), SIDE_W - 6, e.y() + 15, NpcUiTheme.PANEL_INSET);
                graphics.outline(6, e.y(), SIDE_W - 12, 15, selected ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            }
            int color = e.section().equals("addstage") ? (stages().size() < MAX_STAGES ? NpcUiTheme.ACCENT : 0xFF6E6578)
                    : selected ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT;
            graphics.text(font, font.plainSubstrByWidth(e.label(), SIDE_W - 34), 12, e.y() + 4, color, false);
            String sectionKey = e.section().startsWith("stage:") ? "stage" : e.section();
            help.spot(6, e.y(), SIDE_W - 12, 15, "quest.section." + sectionKey);
            if (e.section().startsWith("stage:") && stages().size() > 1) {
                boolean overX = inside(mouseX, mouseY, SIDE_W - 20, e.y(), 14, 15);
                graphics.text(font, "✖", SIDE_W - 17, e.y() + 4, overX ? 0xFFE04848 : deleteArmed ? 0xFFE0A048 : 0xFF6E6578, false);
            }
        }

        // main
        int x = mainX();
        int w = mainW();
        switch (section) {
            case "details" -> drawDetails(graphics, x, w, mouseX, mouseY);
            case "requires" -> label(graphics, "Players can only get this quest if all of these are true:", x, mainTop() + 2);
            case "rewards" -> label(graphics, "When the quest is done, the player gets:", x, mainTop() + 2);
            case "choose" -> drawChoose(graphics, x, mouseX, mouseY);
            default -> {
                int index = stageIndex();
                label(graphics, "Stage " + (index + 1) + " of " + stages().size()
                        + (index == stages().size() - 1 ? " (finishing it completes the quest)" : ""), x, mainTop() + 2);
                frame(graphics, x, mainTop() + 14, w);
                if (mainTop() + 14 >= clipTop()) {
                    help.spot(x, mainTop() + 14, w, ROW, "quest.stage_text");
                }
                label(graphics, "Objectives (all must be done to reach the next stage):", x, mainTop() + 38);
            }
        }
        if (showsRows() && active != null) {
            active.draw(graphics, mouseX, mouseY);
        }

        // toolbar
        graphics.fill(0, 0, width, TOOLBAR_H, NpcUiTheme.PANEL);
        graphics.fill(0, TOOLBAR_H - 1, width, TOOLBAR_H, NpcUiTheme.BORDER);
        String title = "Quest: " + str(quest, "title", "") + (questId.isEmpty() ? "  (new)" : "  (" + questId + ")");
        graphics.text(font, font.plainSubstrByWidth(title, width - 200), 8, 8, NpcUiTheme.ACCENT, false);
        int leftmost = width;
        for (Button b : toolbar()) {
            leftmost = Math.min(leftmost, b.x());
            help.spot(b.x(), b.y(), b.w(), BUTTON_H, b.label().equals("Back") || b.label().startsWith("Discard") ? "quest.back" : "quest.save");
        }
        help.button(leftmost - 18, 6);
        for (Button b : toolbar()) {
            boolean hover = b.contains(mouseX, mouseY);
            boolean armedLook = b.label().startsWith("Discard");
            graphics.fill(b.x(), b.y(), b.x() + b.w(), b.y() + BUTTON_H, NpcUiTheme.PANEL_LIGHT);
            graphics.outline(b.x(), b.y(), b.w(), BUTTON_H, armedLook || hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            int color = !b.enabled() ? 0xFF6E6578 : armedLook ? NpcUiTheme.ACCENT : hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT;
            graphics.centeredText(font, Component.literal(b.label()), b.x() + b.w() / 2, b.y() + 3, color);
        }

        // status
        graphics.fill(0, height - STATUS_H, width, height, NpcUiTheme.PANEL);
        graphics.fill(0, height - STATUS_H, width, height - STATUS_H + 1, NpcUiTheme.BORDER_DIM);
        String line = status.getString().isEmpty()
                ? "Pick a section on the left · chips: click to pick or step through, right-click to step back or clear · scroll for more"
                : status.getString();
        graphics.text(font, font.plainSubstrByWidth(line, width - 12), 6, height - STATUS_H + 3,
                status.getString().isEmpty() ? 0xFF6E6578 : statusColor, false);

        super.extractRenderState(graphics, mouseX, mouseY, a);
        help.render(graphics, font, mouseX, mouseY, width, height);
    }

    private void drawDetails(GuiGraphicsExtractor graphics, int x, int w, int mouseX, int mouseY) {
        String[] labels = {"Title", "Journal text", "Given by", "Repeatable", "Wait to repeat (min)", "Can be dropped"};
        String[] keys = {"title", "description", "giver", "repeatable", "cooldown", "abandonable"};
        for (int i = 0; i < labels.length; i++) {
            label(graphics, labels[i], x, detailRowY(i) + 4);
            help.spot(x, detailRowY(i), w, ROW, "quest." + keys[i]);
        }
        frame(graphics, x + LABEL_W, detailRowY(0), w - LABEL_W);
        frame(graphics, x + LABEL_W, detailRowY(1), w - LABEL_W);
        String giver = str(quest, "giver", "");
        chip(graphics, x + LABEL_W, detailRowY(2), w - LABEL_W,
                giver.isEmpty() ? "Nobody (pick an NPC…)" : ClientContent.label(ClientContent.npcs(), giver) + "  (" + giver + ")", mouseX, mouseY);
        boolean repeat = bool("repeatable", false);
        chip(graphics, x + LABEL_W, detailRowY(3), w - LABEL_W, repeat ? "✔ Yes" : "✖ No", mouseX, mouseY);
        frame(graphics, x + LABEL_W, detailRowY(4), 60);
        boolean drop = bool("abandonable", true);
        chip(graphics, x + LABEL_W, detailRowY(5), w - LABEL_W, drop ? "✔ Yes" : "✖ No", mouseX, mouseY);
        int ty = detailRowY(7);
        for (String text : List.of(
                "Hand the quest out from the NPC's conversation: in the dialogue graph,",
                "give a reply the \"Offer quest card\" action (players get Accept / Decline)",
                "or \"Start quest\". Use a \"Quest state\" condition to show replies only",
                "while the quest is active, or once it's completed.")) {
            label(graphics, text, x, ty);
            ty += 11;
        }
    }

    private void drawChoose(GuiGraphicsExtractor graphics, int x, int mouseX, int mouseY) {
        label(graphics, "The player picks ONE of these when the quest is done (on top of the rewards):", x, mainTop() + 2);
        JsonArray items = array(quest, "choose_one");
        int hovered = -1;
        for (int i = 0; i < 9; i++) {
            int sx = slotX(i);
            boolean over = inside(mouseX, mouseY, sx, slotsY(), SLOT, SLOT);
            help.spot(sx, slotsY(), SLOT, SLOT, "quest.choose.slot");
            graphics.fill(sx, slotsY(), sx + SLOT, slotsY() + SLOT, NpcUiTheme.PANEL_INSET);
            graphics.outline(sx, slotsY(), SLOT, SLOT, over ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            if (i < items.size()) {
                ItemStack stack = chooseStack(i);
                graphics.item(stack, sx + 1, slotsY() + 1);
                graphics.itemDecorations(font, stack, sx + 1, slotsY() + 1);
                if (over) {
                    hovered = i;
                }
            }
        }
        label(graphics, "Click a slot to pick an item, right-click to remove, scroll to change the amount.", x, slotsY() + SLOT + 8);
        label(graphics, "Leave all slots empty for no choice.", x, slotsY() + SLOT + 20);
        if (hovered >= 0) {
            help.quiet();
            graphics.setTooltipForNextFrame(font, chooseStack(hovered), mouseX, mouseY);
        }
    }

    private void label(GuiGraphicsExtractor graphics, String text, int x, int y) {
        if (y >= clipTop() && y < clipBottom() - 8) {
            graphics.text(font, font.plainSubstrByWidth(text, width - x - 8), x, y, 0xFFBFB3A0, false);
        }
    }

    private void frame(GuiGraphicsExtractor graphics, int fx, int fy, int fw) {
        if (fy < clipTop() || fy + ROW > clipBottom()) {
            return;
        }
        graphics.fill(fx, fy, fx + fw, fy + ROW, NpcUiTheme.PANEL_INSET);
        graphics.outline(fx, fy, fw, ROW, NpcUiTheme.BORDER_DIM);
    }

    private void chip(GuiGraphicsExtractor graphics, int cx, int cy, int cw, String text, int mouseX, int mouseY) {
        if (cy < clipTop() || cy + ROW > clipBottom()) {
            return;
        }
        boolean hover = inside(mouseX, mouseY, cx, cy, cw, ROW);
        graphics.fill(cx, cy, cx + cw, cy + ROW, NpcUiTheme.PANEL_LIGHT);
        graphics.outline(cx, cy, cw, ROW, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        graphics.text(font, font.plainSubstrByWidth(text, cw - 6), cx + 3, cy + 4, hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT, false);
    }
}
