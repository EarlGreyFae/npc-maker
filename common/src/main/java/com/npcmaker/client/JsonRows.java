package com.npcmaker.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.DynamicOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
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
import java.util.function.Supplier;

/**
 * An editable list of typed JSON entries ({@code {"type": "kill", "entity": …, "count": 5}}): quest objectives,
 * rewards and conditions. Each entry is a block: a type chip (click steps through the types, right-click steps back)
 * with ✖, then its fields, up to three per line. Text and number fields are edit boxes; toggles and choices are chips;
 * items, mobs, NPCs, factions and quests open a picker. Uses NpcUiTheme.
 */
final class JsonRows {

    interface Host {
        Font font();

        /** The screen pickers return to. */
        Screen screen();

        void addBox(EditBox box);

        void rebuild();

        void changed();

        DynamicOps<JsonElement> ops();

        /** Rows above / below these y values are scrolled out of view. */
        int clipTop();

        int clipBottom();

        /** Where the rows register their help spots. */
        HelpLayer help();
    }

    /** "objective", "reward" or "condition": picks the help text for the rows (quest.row.objective…). */
    private String helpGroup = "objective";

    void setHelpGroup(String group) {
        this.helpGroup = group;
    }

    private void helpSpot(int sx, int sy, int sw, int sh, String key, @Nullable String valueKey) {
        if (visible(sy, sh)) {
            host.help().spot(sx, sy, sw, sh, key, valueKey);
        }
    }

    /**
     * @param kind 't' text, 'n' whole number, 'D' number, 'd' 0–1, 'b' toggle, 'c' choice, 'i' item id, 'I' item stack,
     *             'N' NPC template, 'F' faction, 'Q' quest, 'E' mob type, 'P' position (x y z)
     * @param def  value for a new entry, or null to leave the key out
     */
    record Field(String key, String label, char kind, List<String> options, @Nullable String def) {
        static Field text(String key, String label) {
            return new Field(key, label, 't', List.of(), null);
        }

        static Field whole(String key, String label, @Nullable String def) {
            return new Field(key, label, 'n', List.of(), def);
        }

        static Field number(String key, String label, @Nullable String def) {
            return new Field(key, label, 'D', List.of(), def);
        }

        static Field chance(String key, String label) {
            return new Field(key, label, 'd', List.of(), "0.5");
        }

        static Field toggle(String key, String label) {
            return new Field(key, label, 'b', List.of(), "true");
        }

        static Field choice(String key, String label, String... options) {
            return new Field(key, label, 'c', List.of(options), options[0]);
        }

        static Field of(char kind, String key, String label) {
            return new Field(key, label, kind, List.of(), null);
        }
    }

    /**
     * @param marker for types with variants (a kill objective by mob, faction or NPC): the key whose presence picks
     *               this variant, or null
     */
    record Kind(String type, String label, List<Field> fields, @Nullable String marker) {
        Kind(String type, String label, List<Field> fields) {
            this(type, label, fields, null);
        }
    }

    static final int ROW = 16;
    private static final int BUTTON_H = 14;
    private static final int PER_LINE = 3;

    private final Host host;
    private final List<Kind> kinds;
    private final String addLabel;
    private final int max;
    private Supplier<JsonArray> source = JsonArray::new;
    private int x;
    private int y;
    private int w;

    JsonRows(Host host, List<Kind> kinds, String addLabel, int max) {
        this.host = host;
        this.kinds = kinds;
        this.addLabel = addLabel;
        this.max = max;
    }

    void setSource(Supplier<JsonArray> source) {
        this.source = source;
    }

    void layout(int x, int y, int w) {
        this.x = x;
        this.y = y;
        this.w = w;
    }

    private JsonArray list() {
        return source.get();
    }

    private @Nullable Kind kindOf(JsonObject entry) {
        JsonElement t = entry.get("type");
        String type = t != null && t.isJsonPrimitive() ? t.getAsString() : "";
        return kinds.stream().filter(k -> k.type().equals(type) && (k.marker() == null || entry.has(k.marker())))
                .findFirst().orElse(null);
    }

    private static int lines(@Nullable Kind kind) {
        return kind == null ? 0 : (kind.fields().size() + PER_LINE - 1) / PER_LINE;
    }

    private int blockHeight(JsonElement e) {
        Kind kind = e.isJsonObject() ? kindOf(e.getAsJsonObject()) : null;
        return ROW + 2 + lines(kind) * (ROW + 2) + 6;
    }

    private int blockY(int index) {
        int by = y;
        JsonArray list = list();
        for (int i = 0; i < index && i < list.size(); i++) {
            by += blockHeight(list.get(i));
        }
        return by;
    }

    private int addY() {
        return blockY(list().size());
    }

    /** Total height, including the add button. */
    int height() {
        return addY() + BUTTON_H - y;
    }

    private boolean visible(int ry, int h) {
        return ry >= host.clipTop() && ry + h <= host.clipBottom();
    }

    /** The field's box: x, y, width. */
    private int[] fieldBox(int blockY, int fieldIndex, int fieldCount) {
        int line = fieldIndex / PER_LINE;
        int inLine = Math.min(PER_LINE, fieldCount - line * PER_LINE);
        int col = fieldIndex % PER_LINE;
        int fw = (w - 14) / inLine;
        return new int[]{x + col * fw, blockY + ROW + 2 + line * (ROW + 2), fw - 3};
    }

    // ---- widgets ------------------------------------------------------------------------------------------

    void init() {
        JsonArray list = list();
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).isJsonObject()) {
                continue;
            }
            JsonObject entry = list.get(i).getAsJsonObject();
            Kind kind = kindOf(entry);
            if (kind == null) {
                continue;
            }
            int by = blockY(i);
            for (int f = 0; f < kind.fields().size(); f++) {
                Field field = kind.fields().get(f);
                int[] box = fieldBox(by, f, kind.fields().size());
                switch (field.kind()) {
                    case 't', 'n', 'D', 'd' -> addBox(box[0], box[1], box[2], value(entry, field.key()), field.label(),
                            s -> write(entry, field, s));
                    case 'P' -> addBox(box[0], box[1], box[2] - 30, positionText(entry, field.key()), "x y z",
                            s -> writePosition(entry, field.key(), s));
                    default -> {
                    }
                }
            }
        }
    }

    private void addBox(int bx, int by, int bw, String value, String hint, Consumer<String> responder) {
        EditBox box = new EditBox(host.font(), bx + 3, by + 4, bw - 6, 10, Component.empty());
        box.setBordered(false);
        box.setMaxLength(256);
        box.setTextColor(NpcUiTheme.TEXT);
        box.setHint(Component.literal(hint).withColor(0xFF6E6578));
        box.setValue(value);
        box.setResponder(responder);
        box.visible = visible(by, ROW);
        host.addBox(box);
    }

    private static String value(JsonObject entry, String key) {
        JsonElement e = entry.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static String positionText(JsonObject entry, String key) {
        JsonElement e = entry.get(key);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        e.getAsJsonArray().forEach(n -> parts.add(trim(n.getAsDouble())));
        return String.join(" ", parts);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    private void write(JsonObject entry, Field field, String raw) {
        String v = raw.trim();
        if (v.isEmpty()) {
            entry.remove(field.key());
        } else {
            try {
                switch (field.kind()) {
                    case 'n' -> entry.addProperty(field.key(), Integer.parseInt(v));
                    case 'D' -> entry.addProperty(field.key(), Double.parseDouble(v));
                    case 'd' -> entry.addProperty(field.key(), Mth.clamp(Double.parseDouble(v), 0, 1));
                    default -> entry.addProperty(field.key(), v);
                }
            } catch (NumberFormatException e) {
                return;
            }
        }
        host.changed();
    }

    private void writePosition(JsonObject entry, String key, String raw) {
        String[] parts = raw.trim().split("[\\s,]+");
        if (parts.length != 3) {
            return;
        }
        JsonArray pos = new JsonArray();
        try {
            for (String p : parts) {
                pos.add(Double.parseDouble(p));
            }
        } catch (NumberFormatException e) {
            return;
        }
        entry.add(key, pos);
        host.changed();
    }

    JsonObject newEntry(Kind kind) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", kind.type());
        for (Field field : kind.fields()) {
            if (field.def() == null) {
                continue;
            }
            switch (field.kind()) {
                case 'b' -> entry.addProperty(field.key(), Boolean.parseBoolean(field.def()));
                case 'n' -> entry.addProperty(field.key(), Integer.parseInt(field.def()));
                case 'D', 'd' -> entry.addProperty(field.key(), Double.parseDouble(field.def()));
                default -> entry.addProperty(field.key(), field.def());
            }
        }
        return entry;
    }

    // ---- input --------------------------------------------------------------------------------------------

    private static boolean inside(double mx, double my, int bx, int by, int bw, int bh) {
        return mx >= bx && mx < bx + bw && my >= by && my < by + bh;
    }

    boolean click(double mx, double my, int button) {
        boolean back = button == InputConstants.MOUSE_BUTTON_RIGHT;
        JsonArray list = list();
        for (int i = 0; i < list.size(); i++) {
            int by = blockY(i);
            if (!visible(by, ROW)) {
                continue;
            }
            if (inside(mx, my, x + w - 12, by + 2, 12, 12)) {
                list.remove(i);
                host.changed();
                host.rebuild();
                return true;
            }
            if (!list.get(i).isJsonObject()) {
                continue;
            }
            JsonObject entry = list.get(i).getAsJsonObject();
            if (inside(mx, my, x, by, w - 14, ROW)) {
                Kind current = kindOf(entry);
                int at = current == null ? -1 : kinds.indexOf(current);
                Kind next = kinds.get(Math.floorMod(at + (back ? -1 : 1), kinds.size()));
                list.set(i, newEntry(next));
                host.changed();
                host.rebuild();
                return true;
            }
            Kind kind = kindOf(entry);
            if (kind == null) {
                continue;
            }
            for (int f = 0; f < kind.fields().size(); f++) {
                Field field = kind.fields().get(f);
                int[] box = fieldBox(by, f, kind.fields().size());
                if (field.kind() == 'P' && inside(mx, my, box[0] + box[2] - 28, box[1], 28, ROW)) {
                    var player = Minecraft.getInstance().player;
                    if (player != null) {
                        JsonArray pos = new JsonArray();
                        pos.add(player.getBlockX() + 0.5);
                        pos.add(player.getBlockY());
                        pos.add(player.getBlockZ() + 0.5);
                        entry.add(field.key(), pos);
                        host.changed();
                        host.rebuild();
                    }
                    return true;
                }
                if (inside(mx, my, box[0], box[1], box[2], ROW) && clickField(entry, field, back)) {
                    return true;
                }
            }
        }
        if (list.size() < max && inside(mx, my, x, addY(), w, BUTTON_H) && visible(addY(), BUTTON_H)) {
            list.add(newEntry(kinds.get(0)));
            host.changed();
            host.rebuild();
            return true;
        }
        return false;
    }

    private boolean clickField(JsonObject entry, Field field, boolean back) {
        Screen screen = host.screen();
        switch (field.kind()) {
            case 'b' -> {
                JsonElement e = entry.get(field.key());
                entry.addProperty(field.key(), !(e != null && e.isJsonPrimitive() && e.getAsBoolean()));
                host.changed();
                return true;
            }
            case 'c' -> {
                int at = field.options().indexOf(value(entry, field.key()));
                entry.addProperty(field.key(), field.options().get(Math.floorMod(at + (back ? -1 : 1), field.options().size())));
                host.changed();
                return true;
            }
            case 'i', 'I' -> {
                if (back) {
                    entry.remove(field.key());
                    host.changed();
                    return true;
                }
                Minecraft.getInstance().gui.setScreen(new ItemPickerScreen(screen, Component.literal("Pick an item"), stack -> {
                    if (stack.isEmpty()) {
                        entry.remove(field.key());
                    } else if (field.kind() == 'i') {
                        entry.addProperty(field.key(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    } else {
                        ItemStack.CODEC.encodeStart(host.ops(), stack).result().ifPresent(json -> entry.add(field.key(), json));
                    }
                    host.changed();
                }));
                return true;
            }
            case 'N', 'F', 'Q', 'E' -> {
                if (back) {
                    if (field.def() != null) {
                        entry.addProperty(field.key(), field.def()); // keeps a variant's marker key
                    } else {
                        entry.remove(field.key());
                    }
                    host.changed();
                    return true;
                }
                List<PickListScreen.Option> options = switch (field.kind()) {
                    case 'N' -> PickListScreen.fromIndex(ClientContent.npcs());
                    case 'F' -> PickListScreen.fromIndex(ClientContent.factions());
                    case 'Q' -> PickListScreen.fromIndex(ClientContent.quests());
                    default -> PickListScreen.mobOptions();
                };
                String title = switch (field.kind()) {
                    case 'N' -> "Pick an NPC";
                    case 'F' -> "Pick a faction";
                    case 'Q' -> "Pick a quest";
                    default -> "Pick a mob";
                };
                Minecraft.getInstance().gui.setScreen(PickListScreen.single(screen, title, options, value(entry, field.key()), null, null,
                        id -> {
                            entry.addProperty(field.key(), id);
                            host.changed();
                        }));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Font font = host.font();
        JsonArray list = list();
        for (int i = 0; i < list.size(); i++) {
            int by = blockY(i);
            JsonObject entry = list.get(i).isJsonObject() ? list.get(i).getAsJsonObject() : new JsonObject();
            Kind kind = kindOf(entry);
            String typeLabel = kind == null ? value(entry, "type") + " (from file)" : kind.label();
            chip(graphics, x, by, w - 14, typeLabel + "  ▸", mouseX, mouseY, NpcUiTheme.ACCENT);
            helpSpot(x, by, w - 14, ROW, "quest.row." + helpGroup, "quest." + helpGroup + "." + value(entry, "type"));
            helpSpot(x + w - 12, by + 2, 12, 12, "quest.row.delete", null);
            if (visible(by, ROW)) {
                boolean overX = inside(mouseX, mouseY, x + w - 12, by + 2, 12, 12);
                graphics.text(font, "✖", x + w - 10, by + 4, overX ? 0xFFE04848 : 0xFF8F8A80, false);
            }
            if (kind == null) {
                continue;
            }
            for (int f = 0; f < kind.fields().size(); f++) {
                Field field = kind.fields().get(f);
                int[] box = fieldBox(by, f, kind.fields().size());
                helpSpot(box[0], box[1], box[2], ROW, "quest.row.field", null);
                switch (field.kind()) {
                    case 'b' -> {
                        JsonElement e = entry.get(field.key());
                        boolean on = e != null && e.isJsonPrimitive() && e.getAsBoolean();
                        chip(graphics, box[0], box[1], box[2], (on ? "✔ " : "✖ ") + field.label(), mouseX, mouseY, NpcUiTheme.TEXT);
                    }
                    case 'c' -> chip(graphics, box[0], box[1], box[2], field.label() + ": " + value(entry, field.key()), mouseX, mouseY, NpcUiTheme.TEXT);
                    case 'i', 'I' -> {
                        ItemStack stack = itemIn(entry, field);
                        chip(graphics, box[0], box[1], box[2], stack.isEmpty() ? "Pick item…" : "     " + stack.getHoverName().getString(),
                                mouseX, mouseY, stack.isEmpty() ? 0xFF8F8A80 : NpcUiTheme.TEXT);
                        if (!stack.isEmpty() && visible(box[1], ROW)) {
                            graphics.item(stack, box[0] + 1, box[1]);
                        }
                    }
                    case 'N', 'F', 'Q', 'E' -> {
                        String id = value(entry, field.key());
                        String shown = id.isEmpty() ? "Pick " + field.label() + "…" : switch (field.kind()) {
                            case 'N' -> ClientContent.label(ClientContent.npcs(), id);
                            case 'F' -> ClientContent.label(ClientContent.factions(), id);
                            case 'Q' -> ClientContent.label(ClientContent.quests(), id);
                            default -> mobName(id);
                        };
                        chip(graphics, box[0], box[1], box[2], shown, mouseX, mouseY, id.isEmpty() ? 0xFF8F8A80 : NpcUiTheme.TEXT);
                    }
                    case 'P' -> {
                        frame(graphics, box[0], box[1], box[2] - 30);
                        chip(graphics, box[0] + box[2] - 28, box[1], 28, "Here", mouseX, mouseY, NpcUiTheme.TEXT);
                    }
                    default -> frame(graphics, box[0], box[1], box[2]);
                }
            }
        }
        int ay = addY();
        helpSpot(x, ay, w, BUTTON_H, "quest.row.add", null);
        if (visible(ay, BUTTON_H)) {
            boolean can = list.size() < max;
            boolean hover = can && inside(mouseX, mouseY, x, ay, w, BUTTON_H);
            graphics.fill(x, ay, x + w, ay + BUTTON_H, NpcUiTheme.PANEL_LIGHT);
            graphics.outline(x, ay, w, BUTTON_H, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            graphics.centeredText(font, Component.literal(addLabel), x + w / 2, ay + 3,
                    !can ? 0xFF6E6578 : hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT);
        }
    }

    private static String mobName(String id) {
        if (id.startsWith("#")) {
            return id;
        }
        Identifier key = Identifier.tryParse(id);
        return key == null ? id : BuiltInRegistries.ENTITY_TYPE.getOptional(key).map(t -> t.getDescription().getString()).orElse(id);
    }

    private ItemStack itemIn(JsonObject entry, Field field) {
        JsonElement e = entry.get(field.key());
        if (e == null) {
            return ItemStack.EMPTY;
        }
        if (field.kind() == 'I') {
            return ItemStack.OPTIONAL_CODEC.parse(host.ops(), e).result().orElse(ItemStack.EMPTY);
        }
        if (!e.isJsonPrimitive()) {
            return ItemStack.EMPTY;
        }
        Identifier key = Identifier.tryParse(e.getAsString());
        Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        return item == null ? ItemStack.EMPTY : item.getDefaultInstance();
    }

    private void frame(GuiGraphicsExtractor graphics, int fx, int fy, int fw) {
        if (!visible(fy, ROW)) {
            return;
        }
        graphics.fill(fx, fy, fx + fw, fy + ROW, NpcUiTheme.PANEL_INSET);
        graphics.outline(fx, fy, fw, ROW, NpcUiTheme.BORDER_DIM);
    }

    private void chip(GuiGraphicsExtractor graphics, int cx, int cy, int cw, String text, int mouseX, int mouseY, int color) {
        if (!visible(cy, ROW)) {
            return;
        }
        Font font = host.font();
        boolean hover = inside(mouseX, mouseY, cx, cy, cw, ROW);
        graphics.fill(cx, cy, cx + cw, cy + ROW, NpcUiTheme.PANEL_LIGHT);
        graphics.outline(cx, cy, cw, ROW, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        graphics.text(font, font.plainSubstrByWidth(text, cw - 6), cx + 3, cy + 4, hover ? NpcUiTheme.TEXT_HOVER : color, false);
    }
}
