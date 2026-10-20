package com.npcmaker.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The editor's AI rule builder: each rule is a two-line row of choices and boxes.
 * <pre>
 *  ≡ When [Player near] [12]  → Then [Attack]                 ✖
 *    If   [Target not allied]   [extra for the action…]   every [10] s
 * </pre>
 * Click a choice to step through its options (right-click steps back). Drag ≡ to reorder; rules run top to bottom.
 * Rules with conditions the builder doesn't offer (from hand-written files) keep them untouched.
 */
final class RulesPanel {

    /** What the panel needs from the editor screen. */
    interface Host {
        Font font();

        JsonArray rules();

        /** Store the edited list (marks the editor as changed). */
        void setRules(JsonArray rules);

        void addBox(EditBox box);

        /** Re-create the text boxes (after rows were added, removed, moved or changed shape). */
        void rebuild();

        void pickItem(String title, Consumer<ItemStack> onPick);

        /** Opens a searchable list (sounds, particles, animations); "" means cleared. */
        void pickFrom(String title, List<PickListScreen.Option> options, String current,
                      @org.jspecify.annotations.Nullable Consumer<String> preview, Consumer<String> onPick);
    }

    /** Extras picked from a list instead of typed: sound, particle, animation. */
    private static boolean picked(String kind) {
        return kind.equals("sound") || kind.equals("particle") || kind.equals("animation");
    }

    /** Opens the right list for a rule's extra and writes the choice back (a sound keeps its volume and pitch). */
    private void pickExtra(int index, JsonObject rule, String kind) {
        switch (kind) {
            case "sound" -> host.pickFrom("Sound for this rule (▶ to listen)", PickListScreen.soundOptions(),
                    extraText(rule, "sound"), PickListScreen::playSound, id -> edit(index, r -> {
                        JsonElement current = r.get("sound");
                        if (id.isEmpty()) {
                            r.remove("sound");
                        } else if (current != null && current.isJsonObject()) {
                            current.getAsJsonObject().addProperty("sound", id);
                        } else {
                            r.addProperty("sound", id);
                        }
                    }, false));
            case "particle" -> host.pickFrom("Particles for this rule", PickListScreen.particleOptions(),
                    str(rule, "particle", ""), null, id -> edit(index, r -> put(r, "particle", id), false));
            default -> {
                List<PickListScreen.Option> options = new ArrayList<>();
                for (com.npcmaker.data.Emote emote : com.npcmaker.data.Emote.values()) {
                    options.add(new PickListScreen.Option(emote.id(), emote.label(), emote.isPose() ? "pose" : ""));
                }
                PickListScreen.animationOptions(false).stream()
                        .filter(o -> o.id().startsWith(com.npcmaker.custom.CustomContent.ANIMATION_PREFIX)).forEach(options::add);
                host.pickFrom("Animation for this rule", options, str(rule, "animation", ""), null,
                        id -> edit(index, r -> put(r, "animation", id), false));
            }
        }
    }

    // ---- options ------------------------------------------------------------------------------------------

    private record Option(String id, String label) {
    }

    private static final List<Option> TRIGGERS = List.of(
            new Option("always", "Always"), new Option("player_near", "Player near"), new Option("entity_near", "Mob near"),
            new Option("attacked", "Attacked"), new Option("ally_attacked", "Ally attacked"),
            new Option("health_below", "Health below"), new Option("time_is", "Time is"), new Option("weather_is", "Weather is"));

    private static final List<Option> ACTIONS = List.of(
            new Option("attack", "Attack"), new Option("flee", "Flee"), new Option("go_home", "Go home"),
            new Option("go_to", "Go to"), new Option("follow", "Follow"), new Option("stop", "Stand still"),
            new Option("look_at", "Look at"), new Option("say", "Say"), new Option("play_sound", "Play sound"),
            new Option("emit_particles", "Particles"), new Option("use_item", "Use item"), new Option("place_block", "Place block"),
            new Option("run_command", "Run command"), new Option("play_animation", "Play animation"));

    /** The builder's one condition slot; each maps to a condition object (or none). */
    private static final List<Option> CONDITIONS = List.of(
            new Option("", "No condition"), new Option("allied", "Target allied"), new Option("not_allied", "Target not allied"),
            new Option("hostile", "Target hostile"), new Option("not_hostile", "Target not hostile"),
            new Option("player", "Target is a player"), new Option("not_player", "Target isn't a player"),
            new Option("chance25", "25% of the time"), new Option("chance50", "50% of the time"),
            new Option("chance75", "75% of the time"));

    private static final List<String> TIMES = List.of("day", "night");
    private static final List<String> WEATHERS = List.of("clear", "rain", "thunder");

    // ---- layout -------------------------------------------------------------------------------------------

    private static final int LINE = 16;
    private static final int ROW_H = LINE * 2 + 8;
    private static final int HANDLE_W = 10;
    private static final int GAP = 4;

    private final Host host;
    private int x;
    private int y;
    private int w;
    private int h;
    private int scroll;
    private int dragging = -1;
    private double dragMouseY;

    RulesPanel(Host host) {
        this.host = host;
    }

    void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    private int visibleRows() {
        return Math.max(1, (h - LINE - 6) / ROW_H);
    }

    private int rowY(int index) {
        return y + (index - scroll) * ROW_H;
    }

    private int addButtonY() {
        return y + Math.min(host.rules().size() - scroll, visibleRows()) * ROW_H;
    }

    // ---- geometry of one row --------------------------------------------------------------------------------

    private Font font() {
        return host.font();
    }

    private int whenLabelX() {
        return x + HANDLE_W + 2;
    }

    private int triggerX() {
        return whenLabelX() + font().width("When") + GAP;
    }

    private static final int TRIGGER_W = 74;
    private static final int VALUE_W = 38;
    private static final int ACTION_W = 74;
    private static final int COND_W = 108;
    private static final int COOLDOWN_W = 26;

    private int valueX() {
        return triggerX() + TRIGGER_W + GAP;
    }

    private int thenLabelX() {
        return valueX() + VALUE_W + GAP;
    }

    private int actionX() {
        return thenLabelX() + font().width("→ Then") + GAP;
    }

    private int deleteX() {
        return x + w - 10;
    }

    private int ifLabelX() {
        return whenLabelX();
    }

    private int conditionX() {
        return triggerX();
    }

    private int cooldownX() {
        return x + w - COOLDOWN_W - font().width(" s") - 2;
    }

    private int everyLabelX() {
        return cooldownX() - GAP - font().width("every");
    }

    private int extraX() {
        return conditionX() + COND_W + GAP;
    }

    private int extraW() {
        return Math.max(20, everyLabelX() - GAP - extraX());
    }

    // ---- reading and writing one rule ----------------------------------------------------------------------

    private static String str(JsonObject rule, String key, String fallback) {
        JsonElement e = rule.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    private static int indexOf(List<Option> options, String id) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id().equals(id)) {
                return i;
            }
        }
        return 0;
    }

    private static String labelOf(List<Option> options, String id) {
        return options.get(indexOf(options, id)).label();
    }

    /** Which builder choice the rule's conditions match: an option id, or "custom" for anything else. */
    private static String conditionChoice(JsonObject rule) {
        JsonElement conds = rule.get("if");
        if (conds == null || !conds.isJsonArray() || conds.getAsJsonArray().isEmpty()) {
            return "";
        }
        JsonArray list = conds.getAsJsonArray();
        if (list.size() > 1 || !list.get(0).isJsonObject()) {
            return "custom";
        }
        JsonObject c = list.get(0).getAsJsonObject();
        String type = str(c, "type", "");
        boolean value = !c.has("value") || c.get("value").getAsBoolean();
        switch (type) {
            case "target_relation" -> {
                String relation = str(c, "relation", "");
                if (relation.equals("allied")) {
                    return value ? "allied" : "not_allied";
                }
                if (relation.equals("hostile")) {
                    return value ? "hostile" : "not_hostile";
                }
                return "custom";
            }
            case "is_player" -> {
                return value ? "player" : "not_player";
            }
            case "chance" -> {
                double chance = c.has("chance") ? c.get("chance").getAsDouble() : 0.5;
                return chance <= 0.3 ? "chance25" : chance <= 0.6 ? "chance50" : "chance75";
            }
            default -> {
                return "custom";
            }
        }
    }

    private static @Nullable JsonObject conditionFor(String choice) {
        JsonObject c = new JsonObject();
        switch (choice) {
            case "allied", "not_allied" -> {
                c.addProperty("type", "target_relation");
                c.addProperty("relation", "allied");
                if (choice.startsWith("not")) {
                    c.addProperty("value", false);
                }
            }
            case "hostile", "not_hostile" -> {
                c.addProperty("type", "target_relation");
                c.addProperty("relation", "hostile");
                if (choice.startsWith("not")) {
                    c.addProperty("value", false);
                }
            }
            case "player", "not_player" -> {
                c.addProperty("type", "is_player");
                if (choice.startsWith("not")) {
                    c.addProperty("value", false);
                }
            }
            case "chance25", "chance50", "chance75" -> {
                c.addProperty("type", "chance");
                c.addProperty("chance", Integer.parseInt(choice.substring(6)) / 100.0);
            }
            default -> {
                return null;
            }
        }
        return c;
    }

    /** Rules show their extra box only for actions that need one. */
    private static String extraKind(String action) {
        return switch (action) {
            case "say" -> "text";
            case "play_sound" -> "sound";
            case "emit_particles" -> "particle";
            case "use_item", "place_block" -> "item";
            case "go_to" -> "pos";
            case "run_command" -> "command";
            case "play_animation" -> "animation";
            default -> "";
        };
    }

    private static String extraText(JsonObject rule, String kind) {
        switch (kind) {
            case "text" -> {
                JsonElement t = rule.get("text");
                if (t == null) {
                    return "";
                }
                if (t.isJsonArray()) {
                    List<String> lines = new ArrayList<>();
                    t.getAsJsonArray().forEach(e -> lines.add(e.getAsString()));
                    return String.join(" | ", lines);
                }
                return t.getAsString();
            }
            case "sound" -> {
                JsonElement s = rule.get("sound");
                if (s == null) {
                    return "";
                }
                return s.isJsonObject() ? str(s.getAsJsonObject(), "sound", "") : s.getAsString();
            }
            case "particle" -> {
                return str(rule, "particle", "");
            }
            case "pos" -> {
                JsonElement p = rule.get("pos");
                if (p == null || !p.isJsonArray() || p.getAsJsonArray().size() != 3) {
                    return "";
                }
                JsonArray a = p.getAsJsonArray();
                return a.get(0).getAsString() + " " + a.get(1).getAsString() + " " + a.get(2).getAsString();
            }
            case "command" -> {
                return str(rule, "command", "");
            }
            default -> {
                return "";
            }
        }
    }

    private static void writeExtra(JsonObject rule, String kind, String value) {
        String v = value.trim();
        switch (kind) {
            case "text" -> {
                rule.remove("text");
                JsonArray lines = new JsonArray();
                for (String line : v.split("\\|")) {
                    if (!line.isBlank()) {
                        lines.add(line.trim());
                    }
                }
                if (!lines.isEmpty()) {
                    rule.add("text", lines);
                }
            }
            case "sound" -> put(rule, "sound", v);
            case "particle" -> put(rule, "particle", v);
            case "command" -> put(rule, "command", v);
            case "pos" -> {
                rule.remove("pos");
                String[] parts = v.split("[ ,]+");
                if (parts.length == 3) {
                    try {
                        JsonArray pos = new JsonArray();
                        for (String part : parts) {
                            pos.add(Double.parseDouble(part));
                        }
                        rule.add("pos", pos);
                    } catch (NumberFormatException ignored) {
                        // keep it unset until it's three numbers
                    }
                }
            }
            default -> {
            }
        }
    }

    private static void put(JsonObject rule, String key, String value) {
        if (value.isEmpty()) {
            rule.remove(key);
        } else {
            rule.addProperty(key, value);
        }
    }

    /** Value box: a radius, a health percentage, or nothing (time and weather are choices). */
    private static String valueKind(String trigger) {
        return switch (trigger) {
            case "player_near", "entity_near" -> "radius";
            case "health_below" -> "percent";
            case "time_is" -> "time";
            case "weather_is" -> "weather";
            default -> "";
        };
    }

    private JsonArray copy() {
        return host.rules().deepCopy();
    }

    private void edit(int index, Consumer<JsonObject> change, boolean reshape) {
        JsonArray rules = copy();
        if (index < 0 || index >= rules.size() || !rules.get(index).isJsonObject()) {
            return;
        }
        change.accept(rules.get(index).getAsJsonObject());
        host.setRules(rules);
        if (reshape) {
            host.rebuild();
        }
    }

    // ---- widgets --------------------------------------------------------------------------------------------

    void init() {
        JsonArray rules = host.rules();
        int end = Math.min(rules.size(), scroll + visibleRows());
        for (int i = scroll; i < end; i++) {
            if (!rules.get(i).isJsonObject()) {
                continue;
            }
            int index = i;
            JsonObject rule = rules.get(i).getAsJsonObject();
            int ry = rowY(i);
            String trigger = str(rule, "when", "always");
            String kind = valueKind(trigger);
            if (kind.equals("radius") || kind.equals("percent")) {
                double value = rule.has("value") && rule.get("value").isJsonPrimitive()
                        && rule.get("value").getAsJsonPrimitive().isNumber() ? rule.get("value").getAsDouble()
                        : kind.equals("radius") ? 8 : 0.3;
                String shown = kind.equals("radius") ? trim(value) : trim(value * 100);
                EditBox box = box(valueX(), ry, VALUE_W, shown);
                box.setResponder(s -> {
                    try {
                        double v = Double.parseDouble(s.trim());
                        double stored = kind.equals("radius") ? Mth.clamp(v, 1, 64) : Mth.clamp(v, 1, 100) / 100.0;
                        edit(index, r -> r.addProperty("value", stored), false);
                    } catch (NumberFormatException ignored) {
                        // wait for a number
                    }
                });
            }
            String extra = extraKind(str(rule, "then", "attack"));
            if (!extra.isEmpty() && !extra.equals("item") && !picked(extra)) {
                EditBox box = box(extraX(), ry + LINE + 2, extraW(), extraText(rule, extra));
                box.setMaxLength(256);
                box.setHint(Component.literal(switch (extra) {
                    case "text" -> "Line one | Line two";
                    case "sound" -> "minecraft:entity.villager.yes";
                    case "particle" -> "minecraft:heart";
                    case "pos" -> "x y z";
                    default -> "say Hello {target_name}";
                }).withColor(0xFF6E6578));
                box.setResponder(s -> edit(index, r -> writeExtra(r, extra, s), false));
            }
            String cooldown = rule.has("cooldown") ? rule.get("cooldown").getAsString() : "";
            EditBox cd = box(cooldownX(), ry + LINE + 2, COOLDOWN_W, cooldown);
            cd.setHint(Component.literal("auto").withColor(0xFF6E6578));
            cd.setResponder(s -> {
                String v = s.trim();
                if (v.isEmpty()) {
                    edit(index, r -> r.remove("cooldown"), false);
                    return;
                }
                try {
                    int seconds = Mth.clamp(Integer.parseInt(v), 0, 86400);
                    edit(index, r -> r.addProperty("cooldown", seconds), false);
                } catch (NumberFormatException ignored) {
                    // wait for a whole number
                }
            });
        }
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    private EditBox box(int bx, int by, int bw, String value) {
        EditBox box = new EditBox(font(), bx + 3, by + 3, bw - 6, 10, Component.empty());
        box.setBordered(false);
        box.setMaxLength(48);
        box.setTextColor(NpcUiTheme.TEXT);
        box.setValue(value);
        host.addBox(box);
        return box;
    }

    // ---- input ----------------------------------------------------------------------------------------------

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    boolean contains(double mx, double my) {
        return inside(mx, my, x, y, w, h);
    }

    /** @return true if the click was used */
    boolean click(double mx, double my, int button) {
        boolean back = button == InputConstants.MOUSE_BUTTON_RIGHT;
        JsonArray rules = host.rules();
        int end = Math.min(rules.size(), scroll + visibleRows());
        for (int i = scroll; i < end; i++) {
            if (!rules.get(i).isJsonObject()) {
                continue;
            }
            int index = i;
            JsonObject rule = rules.get(i).getAsJsonObject();
            int ry = rowY(i);
            if (inside(mx, my, x, ry, HANDLE_W, LINE * 2)) {
                dragging = index;
                dragMouseY = my;
                return true;
            }
            if (inside(mx, my, deleteX(), ry, 10, LINE)) {
                JsonArray left = copy();
                left.remove(index);
                host.setRules(left);
                scroll = Mth.clamp(scroll, 0, Math.max(0, left.size() - visibleRows()));
                host.rebuild();
                return true;
            }
            if (inside(mx, my, triggerX(), ry, TRIGGER_W, LINE)) {
                String next = step(TRIGGERS, str(rule, "when", "always"), back);
                edit(index, r -> {
                    r.addProperty("when", next);
                    r.remove("value");
                    switch (valueKind(next)) {
                        case "radius" -> r.addProperty("value", 8);
                        case "percent" -> r.addProperty("value", 0.3);
                        case "time" -> r.addProperty("value", "night");
                        case "weather" -> r.addProperty("value", "rain");
                        default -> {
                        }
                    }
                }, true);
                return true;
            }
            String vk = valueKind(str(rule, "when", "always"));
            if ((vk.equals("time") || vk.equals("weather")) && inside(mx, my, valueX(), ry, VALUE_W, LINE)) {
                List<String> values = vk.equals("time") ? TIMES : WEATHERS;
                int at = Math.max(0, values.indexOf(str(rule, "value", values.get(0))));
                String next = values.get(Math.floorMod(at + (back ? -1 : 1), values.size()));
                edit(index, r -> r.addProperty("value", next), false);
                return true;
            }
            if (inside(mx, my, actionX(), ry, ACTION_W, LINE)) {
                String next = step(ACTIONS, str(rule, "then", "attack"), back);
                edit(index, r -> r.addProperty("then", next), true);
                return true;
            }
            if (inside(mx, my, conditionX(), ry + LINE + 2, COND_W, LINE)) {
                String current = conditionChoice(rule);
                String next = current.equals("custom") ? "" : step(CONDITIONS, current, back);
                edit(index, r -> {
                    r.remove("if");
                    JsonObject c = conditionFor(next);
                    if (c != null) {
                        JsonArray list = new JsonArray();
                        list.add(c);
                        r.add("if", list);
                    }
                }, false);
                return true;
            }
            if (extraKind(str(rule, "then", "attack")).equals("item") && inside(mx, my, extraX(), ry + LINE + 2, extraW(), LINE)) {
                host.pickItem("Item for this rule", stack -> edit(index, r -> {
                    if (stack.isEmpty()) {
                        r.remove("item");
                    } else {
                        r.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    }
                }, false));
                return true;
            }
            String extraKind = extraKind(str(rule, "then", "attack"));
            if (picked(extraKind) && inside(mx, my, extraX(), ry + LINE + 2, extraW(), LINE)) {
                if (back) {
                    edit(index, r -> r.remove(extraKind), false); // right-click clears
                } else {
                    pickExtra(index, rule, extraKind);
                }
                return true;
            }
        }
        if (inside(mx, my, x, addButtonY(), w, LINE)) {
            JsonArray more = copy();
            JsonObject rule = new JsonObject();
            rule.addProperty("when", "player_near");
            rule.addProperty("value", 8);
            rule.addProperty("then", "look_at");
            more.add(rule);
            host.setRules(more);
            scroll = Math.max(0, more.size() - visibleRows());
            host.rebuild();
            return true;
        }
        return false;
    }

    private static String step(List<Option> options, String current, boolean back) {
        int at = indexOf(options, current);
        return options.get(Math.floorMod(at + (back ? -1 : 1), options.size())).id();
    }

    void drag(double my) {
        if (dragging >= 0) {
            dragMouseY = my;
        }
    }

    /** Drop a dragged rule where the mouse is. */
    boolean release() {
        if (dragging < 0) {
            return false;
        }
        int from = dragging;
        dragging = -1;
        int to = dropIndex();
        if (to == from || to == from + 1) {
            return true;
        }
        JsonArray rules = copy();
        JsonElement moved = rules.remove(from);
        int insertAt = to > from ? to - 1 : to;
        JsonArray reordered = new JsonArray();
        for (int i = 0; i < rules.size(); i++) {
            if (i == insertAt) {
                reordered.add(moved);
            }
            reordered.add(rules.get(i));
        }
        if (insertAt >= rules.size()) {
            reordered.add(moved);
        }
        host.setRules(reordered);
        host.rebuild();
        return true;
    }

    /** The gap the dragged rule would drop into (0 = top). */
    private int dropIndex() {
        int size = host.rules().size();
        return Mth.clamp(scroll + (int) Math.round((dragMouseY - y) / ROW_H), 0, size);
    }

    boolean scroll(double amount) {
        int max = Math.max(0, host.rules().size() - visibleRows());
        int next = Mth.clamp(scroll - (int) Math.signum(amount), 0, max);
        if (next != scroll) {
            scroll = next;
            host.rebuild();
        }
        return true;
    }

    // ---- drawing --------------------------------------------------------------------------------------------

    /** Help spots for every visible rule row, the add button and the panel. */
    void help(HelpLayer help) {
        JsonArray rules = host.rules();
        int end = Math.min(rules.size(), scroll + visibleRows());
        for (int i = scroll; i < end; i++) {
            if (!rules.get(i).isJsonObject()) {
                continue;
            }
            JsonObject rule = rules.get(i).getAsJsonObject();
            int ry = rowY(i);
            int ly = ry + LINE + 2;
            String trigger = str(rule, "when", "always");
            String action = str(rule, "then", "attack");
            help.spot(x, ry, HANDLE_W, LINE * 2, "rules.drag");
            help.spot(triggerX(), ry, TRIGGER_W, LINE, "rules.trigger", "rules.trigger." + trigger);
            if (!valueKind(trigger).isEmpty()) {
                help.spot(valueX(), ry, VALUE_W, LINE, "rules.value");
            }
            help.spot(actionX(), ry, ACTION_W, LINE, "rules.action", "rules.action." + action);
            help.spot(deleteX(), ry, 10, LINE, "rules.delete");
            help.spot(conditionX(), ly, COND_W, LINE, "rules.condition");
            String extra = extraKind(action);
            if (!extra.isEmpty()) {
                help.spot(extraX(), ly, extraW(), LINE, "rules.extra." + extra);
            }
            help.spot(cooldownX(), ly, COOLDOWN_W, LINE, "rules.cooldown");
        }
        help.spot(x, addButtonY(), w, LINE, "rules.add");
    }

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Font font = font();
        JsonArray rules = host.rules();
        int end = Math.min(rules.size(), scroll + visibleRows());
        for (int i = scroll; i < end; i++) {
            if (!rules.get(i).isJsonObject()) {
                continue;
            }
            JsonObject rule = rules.get(i).getAsJsonObject();
            int ry = rowY(i);
            graphics.fill(x, ry - 2, x + w, ry + ROW_H - 6, i == dragging ? 0x30D4AF37 : 0x20000000);

            // line 1: handle, When [trigger] [value] → Then [action]  ✖
            graphics.text(font, "≡", x + 2, ry + 4, inside(mouseX, mouseY, x, ry, HANDLE_W, LINE * 2) ? NpcUiTheme.ACCENT : NpcUiTheme.BORDER_DIM, false);
            graphics.text(font, "When", whenLabelX(), ry + 4, 0xFFBFB3A0, false);
            String trigger = str(rule, "when", "always");
            chip(graphics, triggerX(), ry, TRIGGER_W, labelOf(TRIGGERS, trigger), mouseX, mouseY);
            String vk = valueKind(trigger);
            if (vk.equals("radius") || vk.equals("percent")) {
                frame(graphics, valueX(), ry, VALUE_W);
                String unit = vk.equals("radius") ? "m" : "%";
                graphics.text(font, unit, valueX() + VALUE_W - font.width(unit) - 2, ry + 4, 0xFF6E6578, false);
            } else if (vk.equals("time") || vk.equals("weather")) {
                String value = str(rule, "value", vk.equals("time") ? "day" : "clear");
                chip(graphics, valueX(), ry, VALUE_W, value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1), mouseX, mouseY);
            }
            graphics.text(font, "→ Then", thenLabelX(), ry + 4, 0xFFBFB3A0, false);
            chip(graphics, actionX(), ry, ACTION_W, labelOf(ACTIONS, str(rule, "then", "attack")), mouseX, mouseY);
            boolean delHover = inside(mouseX, mouseY, deleteX(), ry, 10, LINE);
            graphics.text(font, "✖", deleteX() + 1, ry + 4, delHover ? 0xFFE04848 : 0xFF8F8A80, false);

            // line 2: If [condition] [extra] every [n] s
            int ly = ry + LINE + 2;
            graphics.text(font, "If", ifLabelX(), ly + 4, 0xFFBFB3A0, false);
            String cond = conditionChoice(rule);
            chip(graphics, conditionX(), ly, COND_W, cond.equals("custom") ? "Custom (from file)" : labelOf(CONDITIONS, cond), mouseX, mouseY);
            String extra = extraKind(str(rule, "then", "attack"));
            if (extra.equals("animation")) {
                String animId = str(rule, "animation", "");
                com.npcmaker.data.Emote emote = com.npcmaker.data.Emote.byId(animId);
                chip(graphics, extraX(), ly, extraW(), animId.isEmpty() ? "Pick an animation…" : emote != null ? emote.label()
                        : "★ " + animId.substring(animId.indexOf(':') + 1), mouseX, mouseY);
            } else if (extra.equals("sound") || extra.equals("particle")) {
                String id = extraText(rule, extra);
                String shown = id.isEmpty() ? (extra.equals("sound") ? "Pick a sound…" : "Pick particles…")
                        : (extra.equals("sound") ? "♪ " : "✦ ") + id.replace("minecraft:", "");
                chip(graphics, extraX(), ly, extraW(), shown, mouseX, mouseY);
            } else if (extra.equals("item")) {
                String itemId = str(rule, "item", "");
                Identifier key = Identifier.tryParse(itemId);
                Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
                String label = item == null ? "Pick an item…" : item.getDefaultInstance().getHoverName().getString();
                chip(graphics, extraX(), ly, extraW(), label, mouseX, mouseY);
                if (item != null) {
                    graphics.item(item.getDefaultInstance(), extraX() + extraW() - 17, ly);
                }
            } else if (!extra.isEmpty()) {
                frame(graphics, extraX(), ly, extraW());
            }
            graphics.text(font, "every", everyLabelX(), ly + 4, 0xFFBFB3A0, false);
            frame(graphics, cooldownX(), ly, COOLDOWN_W);
            graphics.text(font, "s", cooldownX() + COOLDOWN_W + 2, ly + 4, 0xFFBFB3A0, false);
        }
        if (dragging >= 0) {
            int lineY = y + (dropIndex() - scroll) * ROW_H - 4;
            graphics.fill(x, lineY, x + w, lineY + 2, NpcUiTheme.BORDER);
        }

        // add button and help
        int ay = addButtonY();
        boolean addHover = inside(mouseX, mouseY, x, ay, w, LINE);
        graphics.fill(x, ay, x + w, ay + LINE, NpcUiTheme.PANEL_LIGHT);
        graphics.outline(x, ay, w, LINE, addHover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        String add = rules.isEmpty() ? "+ Add the first rule" : "+ Add rule";
        graphics.text(font, add, x + (w - font.width(add)) / 2, ay + 4, addHover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT, false);
        String help = rules.size() > visibleRows()
                ? "Scroll for more · drag ≡ to reorder · rules run top to bottom"
                : "Click a choice to change it (right-click goes back) · drag ≡ to reorder";
        graphics.text(font, font.plainSubstrByWidth(help, w), x, Math.min(ay + LINE + 4, y + h - 9), 0xFF6E6578, false);
    }

    private void frame(GuiGraphicsExtractor graphics, int fx, int fy, int fw) {
        graphics.fill(fx, fy, fx + fw, fy + LINE, NpcUiTheme.PANEL_INSET);
        graphics.outline(fx, fy, fw, LINE, NpcUiTheme.BORDER_DIM);
    }

    private void chip(GuiGraphicsExtractor graphics, int cx, int cy, int cw, String label, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, cx, cy, cw, LINE);
        graphics.fill(cx, cy, cx + cw, cy + LINE, NpcUiTheme.PANEL_LIGHT);
        graphics.outline(cx, cy, cw, LINE, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        String shown = font().plainSubstrByWidth(label, cw - 6);
        graphics.text(font(), shown, cx + 3, cy + 4, hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT, false);
    }
}
