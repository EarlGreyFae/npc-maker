package com.npcmaker.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.npcmaker.network.ContentPayloads;
import com.npcmaker.network.NpcNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Creates or edits a faction file: name, colour, visibility, how it treats players, which mobs count as members, and
 * (right column) how it regards every other faction and which ones a player can't join at the same time. Factions are
 * world-wide, so Save just saves. Reputation amounts and standing tiers stay in the file. Uses NpcUiTheme.
 */
public class FactionEditorScreen extends Screen implements ClientContent.Listener {

    private static final int PAD = 8;
    private static final int ROW = 18;
    private static final int BUTTON_H = 14;
    private static final int HEADER = 26;
    private static final int FOOTER = 26;
    private static final long CONFIRM_MS = 3000L;
    private static final List<String> RELATIONS = List.of("neutral", "allied", "hostile");

    private final Screen parent;
    private final @Nullable Consumer<String> onCreated;
    private String factionId;
    private final JsonObject faction;
    private boolean dirty;
    private boolean badColor;
    private int relScroll;
    private long discardArmedAt = -1;
    private Component status = Component.empty();
    private int statusColor = NpcUiTheme.BORDER_DIM;
    /** Hover tooltips and the (?) help mode. */
    private final HelpLayer help = new HelpLayer();

    private FactionEditorScreen(Screen parent, String factionId, JsonObject faction, @Nullable Consumer<String> onCreated) {
        super(Component.literal("Faction"));
        this.parent = parent;
        this.factionId = factionId;
        this.faction = faction;
        this.onCreated = onCreated;
    }

    /** A new faction; {@code onCreated} gets its id once it's saved. */
    public static void create(Screen parent, @Nullable Consumer<String> onCreated) {
        JsonObject f = new JsonObject();
        f.addProperty("name", "New faction");
        f.addProperty("color", "#C8A24A");
        FactionEditorScreen screen = new FactionEditorScreen(parent, "", f, onCreated);
        screen.dirty = true;
        screen.status = Component.literal("A new faction. Name it, then Create.");
        screen.statusColor = NpcUiTheme.ACCENT;
        Minecraft.getInstance().gui.setScreen(screen);
    }

    /** Opens an existing faction (fetched from the server first). */
    public static void edit(Screen parent, String factionId) {
        ClientContent.request(ContentPayloads.FACTION, factionId, raw -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui.screen() != parent) {
                return;
            }
            try {
                JsonElement e = JsonParser.parseString(raw);
                if (e.isJsonObject()) {
                    mc.gui.setScreen(new FactionEditorScreen(parent, factionId, e.getAsJsonObject(), null));
                }
            } catch (Exception ignored) {
                // a broken file can't be edited here
            }
        });
    }

    // ---- model --------------------------------------------------------------------------------------------

    private String str(String key, String fallback) {
        JsonElement e = faction.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    private JsonObject relations() {
        if (!faction.has("relations") || !faction.get("relations").isJsonObject()) {
            faction.add("relations", new JsonObject());
        }
        return faction.getAsJsonObject("relations");
    }

    private Set<String> list(String key) {
        Set<String> set = new LinkedHashSet<>();
        JsonElement e = faction.get(key);
        if (e != null && e.isJsonArray()) {
            e.getAsJsonArray().forEach(v -> set.add(v.getAsString()));
        }
        return set;
    }

    private void setList(String key, Set<String> values) {
        if (values.isEmpty()) {
            faction.remove(key);
        } else {
            JsonArray array = new JsonArray();
            values.forEach(array::add);
            faction.add(key, array);
        }
        changed();
    }

    private void changed() {
        dirty = true;
        discardArmedAt = -1;
    }

    /** Every other faction, for the relations column. */
    private List<ContentPayloads.Entry> others() {
        return ClientContent.factions().stream().filter(f -> !f.id().equals(factionId)).toList();
    }

    // ---- layout -------------------------------------------------------------------------------------------

    private int panelW() {
        return Math.min(470, width - 16);
    }

    private int panelH() {
        return Math.min(270, height - 16);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelY() {
        return (height - panelH()) / 2;
    }

    private int bodyTop() {
        return panelY() + HEADER;
    }

    private int bodyBottom() {
        return panelY() + panelH() - FOOTER;
    }

    private int leftW() {
        return panelW() / 2 - PAD;
    }

    private int labelW() {
        return 104;
    }

    private int controlX() {
        return panelX() + PAD + labelW();
    }

    private int controlW() {
        return leftW() - labelW() - 4;
    }

    private int rowY(int i) {
        return bodyTop() + 4 + i * ROW;
    }

    private int rightX() {
        return panelX() + panelW() / 2 + 4;
    }

    private int rightW() {
        return panelW() / 2 - PAD - 4;
    }

    private int relRowY(int i) {
        return bodyTop() + 18 + i * ROW;
    }

    private int relRows() {
        return Math.max(1, (bodyBottom() - relRowY(0)) / ROW);
    }

    private int footerY() {
        return panelY() + panelH() - FOOTER + 6;
    }

    // ---- widgets ------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        box(rowY(0), str("name", ""), 48, s -> {
            faction.addProperty("name", s.trim());
            changed();
        });
        box(rowY(1), str("color", "#FFFFFF"), 7, s -> {
            String v = s.trim();
            badColor = !v.matches("#[0-9a-fA-F]{6}");
            if (!badColor) {
                faction.addProperty("color", v.toUpperCase(java.util.Locale.ROOT));
                changed();
            }
        });
        box(rowY(4), str("hostile_below", "-50"), 4, s -> number("hostile_below", s, -50));
        box(rowY(5), str("allied_above", "50"), 4, s -> number("allied_above", s, 50));
    }

    private void number(String key, String s, int fallback) {
        try {
            int v = Mth.clamp(Integer.parseInt(s.trim()), -100, 100);
            if (v == fallback) {
                faction.remove(key);
            } else {
                faction.addProperty(key, v);
            }
            changed();
        } catch (NumberFormatException ignored) {
        }
    }

    private void box(int y, String value, int max, Consumer<String> responder) {
        EditBox box = new EditBox(font, controlX() + 4, y + 4, controlW() - 8 - (max == 7 ? 14 : 0), 10, Component.empty());
        box.setBordered(false);
        box.setMaxLength(max);
        box.setTextColor(NpcUiTheme.TEXT);
        box.setValue(value);
        box.setResponder(responder);
        addRenderableWidget(box);
    }

    // ---- buttons ------------------------------------------------------------------------------------------

    private record Button(int x, int y, int w, String label, boolean enabled, Runnable action) {
        boolean contains(double mx, double my) {
            return enabled && mx >= x && mx < x + w && my >= y && my < y + BUTTON_H;
        }
    }

    private boolean discardArmed() {
        return discardArmedAt >= 0 && Util.getMillis() - discardArmedAt < CONFIRM_MS;
    }

    private List<Button> footer() {
        List<Button> list = new ArrayList<>();
        int right = panelX() + panelW() - PAD;
        String back = discardArmed() ? "Discard changes?" : "Back";
        int bw = font.width(back) + 14;
        list.add(new Button(right - bw, footerY(), bw, back, true, this::tryBack));
        String save = factionId.isEmpty() ? "Create" : "Save";
        int sw = font.width(save) + 18;
        list.add(new Button(right - bw - 5 - sw, footerY(), sw, save, dirty, this::save));
        return list;
    }

    private void save() {
        if (str("name", "").isBlank()) {
            showResult(false, "Give the faction a name first.");
            return;
        }
        if (badColor) {
            showResult(false, "The colour must look like #C8A24A.");
            return;
        }
        if (relations().isEmpty()) {
            faction.remove("relations");
        }
        NpcNetworking.sendToServer(new ContentPayloads.Save(ContentPayloads.FACTION,
                factionId.isEmpty() ? str("name", "faction") : factionId, faction.toString(), factionId.isEmpty()));
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

    @Override
    public void showResult(boolean ok, String message) {
        status = Component.literal(message);
        statusColor = ok ? 0xFF7FD36A : 0xFFE04848;
    }

    @Override
    public void contentSaved(String kind, String id) {
        if (!kind.equals(ContentPayloads.FACTION)) {
            return;
        }
        boolean created = factionId.isEmpty();
        factionId = id;
        dirty = false;
        if (created && onCreated != null) {
            onCreated.accept(id);
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
        boolean back = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
        if (help.click(mx, my)) {
            return true;
        }
        for (Button b : footer()) {
            if (b.contains(mx, my)) {
                b.action().run();
                return true;
            }
        }
        // left column chips
        if (inside(mx, my, controlX(), rowY(2), controlW(), ROW - 2)) {
            faction.addProperty("hidden", !Boolean.parseBoolean(str("hidden", "false")));
            changed();
            return true;
        }
        if (inside(mx, my, controlX(), rowY(3), controlW(), ROW - 2)) {
            int at = RELATIONS.indexOf(str("default_player_relation", "neutral"));
            String next = RELATIONS.get(Math.floorMod(at + (back ? -1 : 1), RELATIONS.size()));
            if (next.equals("neutral")) {
                faction.remove("default_player_relation");
            } else {
                faction.addProperty("default_player_relation", next);
            }
            changed();
            return true;
        }
        if (inside(mx, my, controlX(), rowY(6), controlW(), ROW - 2)) {
            minecraft.gui.setScreen(PickListScreen.multi(this, "Mobs that count as members", PickListScreen.mobOptions(),
                    list("entity_types"), picked -> setList("entity_types", new LinkedHashSet<>(picked))));
            return true;
        }
        // right column: relation and "can't join both" per faction
        List<ContentPayloads.Entry> others = others();
        int relW = 62;
        int exW = 58;
        for (int i = 0; i < Math.min(relRows(), others.size() - relScroll); i++) {
            String other = others.get(relScroll + i).id();
            int y = relRowY(i);
            int relX = rightX() + rightW() - relW - exW - 4;
            if (inside(mx, my, relX, y, relW, ROW - 2)) {
                String current = relations().has(other) ? relations().get(other).getAsString() : "neutral";
                int at = RELATIONS.indexOf(current);
                String next = RELATIONS.get(Math.floorMod(at + (back ? -1 : 1), RELATIONS.size()));
                if (next.equals("neutral")) {
                    relations().remove(other);
                } else {
                    relations().addProperty(other, next);
                }
                changed();
                return true;
            }
            if (inside(mx, my, rightX() + rightW() - exW, y, exW, ROW - 2)) {
                Set<String> excludes = list("excludes");
                if (!excludes.remove(other)) {
                    excludes.add(other);
                }
                setList("excludes", excludes);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (x >= rightX()) {
            relScroll = Mth.clamp(relScroll - (int) Math.signum(scrollY), 0, Math.max(0, others().size() - relRows()));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
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
        int x = panelX();
        int y = panelY();
        int w = panelW();
        int h = panelH();
        help.begin();
        graphics.fill(x, y, x + w, y + h, NpcUiTheme.PANEL);
        graphics.outline(x, y, w, h, NpcUiTheme.BORDER);
        graphics.text(font, factionId.isEmpty() ? "New faction" : "Faction '" + factionId + "'", x + PAD, y + 9, NpcUiTheme.ACCENT, false);
        help.button(x + w - PAD - 12, y + 7);
        String note = "World-wide: every NPC in it sees the change";
        graphics.text(font, note, x + w - PAD - 18 - font.width(note), y + 9, NpcUiTheme.BORDER_DIM, false);
        graphics.fill(x + 4, bodyTop() - 2, x + w - 4, bodyTop() - 1, NpcUiTheme.BORDER_DIM);

        // left column
        String[] labels = {"Name", "Colour", "Hidden", "Players start as", "Hostile at rep ≤", "Allied at rep ≥", "Mob members"};
        String[] keys = {"name", "color", "hidden", "player_relation", "hostile_below", "allied_above", "mobs"};
        for (int i = 0; i < labels.length; i++) {
            graphics.text(font, labels[i], x + PAD, rowY(i) + 4, 0xFFBFB3A0, false);
            help.spot(x + PAD, rowY(i), leftW(), ROW - 2, "faction." + keys[i],
                    i == 3 ? "faction.relation." + str("default_player_relation", "neutral") : null);
        }
        frame(graphics, rowY(0), false);
        frame(graphics, rowY(1), badColor);
        if (!badColor) {
            int rgb;
            try {
                rgb = Integer.parseInt(str("color", "#FFFFFF").substring(1), 16);
            } catch (Exception e) {
                rgb = 0xFFFFFF;
            }
            graphics.fill(controlX() + controlW() - 12, rowY(1) + 3, controlX() + controlW() - 3, rowY(1) + 12, 0xFF000000 | rgb);
        }
        boolean hidden = Boolean.parseBoolean(str("hidden", "false"));
        chip(graphics, controlX(), rowY(2), controlW(), hidden ? "✔ Hidden from players" : "✖ Shown to players", mouseX, mouseY, NpcUiTheme.TEXT);
        String rel = str("default_player_relation", "neutral");
        chip(graphics, controlX(), rowY(3), controlW(), capital(rel) + "  ▸", mouseX, mouseY, relColor(rel));
        frame(graphics, rowY(4), false);
        frame(graphics, rowY(5), false);
        int mobs = list("entity_types").size();
        int tags = list("entity_tags").size();
        chip(graphics, controlX(), rowY(6), controlW(), (mobs == 0 ? "None" : mobs + " mob" + (mobs == 1 ? "" : "s"))
                + (tags > 0 ? " +" + tags + " tag" + (tags == 1 ? "" : "s") : "") + "  …", mouseX, mouseY, NpcUiTheme.TEXT);
        int ty = rowY(7) + 4;
        for (String line : List.of("Players count as enemies at or below the", "hostile reputation, as allies at or above", "the allied one.")) {
            graphics.text(font, line, x + PAD, ty, 0xFF6E6578, false);
            ty += 10;
        }

        // right column
        graphics.fill(rightX() - 6, bodyTop() + 2, rightX() - 5, bodyBottom() - 2, NpcUiTheme.BORDER_DIM);
        List<ContentPayloads.Entry> others = others();
        graphics.text(font, others.isEmpty() ? "No other factions yet." : "How it sees other factions:", rightX(), bodyTop() + 5, 0xFFBFB3A0, false);
        int relW = 62;
        int exW = 58;
        Set<String> excludes = list("excludes");
        for (int i = 0; i < Math.min(relRows(), others.size() - relScroll); i++) {
            ContentPayloads.Entry other = others.get(relScroll + i);
            int ry = relRowY(i);
            int relX = rightX() + rightW() - relW - exW - 4;
            graphics.text(font, font.plainSubstrByWidth(other.label(), relX - rightX() - 4), rightX(), ry + 4, NpcUiTheme.TEXT, false);
            String r = relations().has(other.id()) ? relations().get(other.id()).getAsString() : "neutral";
            chip(graphics, relX, ry, relW, capital(r), mouseX, mouseY, relColor(r));
            boolean ex = excludes.contains(other.id());
            chip(graphics, rightX() + rightW() - exW, ry, exW, ex ? "✔ Rival" : "Rival?", mouseX, mouseY, ex ? 0xFFE0A048 : 0xFF8F8A80);
            help.spot(relX, ry, relW, ROW - 2, "faction.relation", "faction.relation." + r);
            help.spot(rightX() + rightW() - exW, ry, exW, ROW - 2, "faction.rival");
        }

        // footer
        graphics.fill(x + 4, bodyBottom() + 1, x + w - 4, bodyBottom() + 2, NpcUiTheme.BORDER_DIM);
        List<Button> buttons = footer();
        int leftmost = buttons.stream().mapToInt(Button::x).min().orElse(x + w);
        graphics.text(font, font.plainSubstrByWidth(status.getString(), Math.max(0, leftmost - x - PAD * 2)), x + PAD, footerY() + 3, statusColor, false);
        for (Button b : buttons) {
            boolean hover = b.contains(mouseX, mouseY);
            boolean armedLook = b.label().startsWith("Discard");
            graphics.fill(b.x(), b.y(), b.x() + b.w(), b.y() + BUTTON_H, NpcUiTheme.PANEL_LIGHT);
            graphics.outline(b.x(), b.y(), b.w(), BUTTON_H, armedLook || hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            int color = !b.enabled() ? 0xFF6E6578 : armedLook ? NpcUiTheme.ACCENT : hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT;
            graphics.centeredText(font, Component.literal(b.label()), b.x() + b.w() / 2, b.y() + 3, color);
            help.spot(b.x(), b.y(), b.w(), BUTTON_H, b.label().equals("Back") || b.label().startsWith("Discard") ? "faction.back" : "faction.save");
        }
        super.extractRenderState(graphics, mouseX, mouseY, a);
        help.render(graphics, font, mouseX, mouseY, width, height);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static int relColor(String relation) {
        return switch (relation) {
            case "allied" -> 0xFF7FD36A;
            case "hostile" -> 0xFFE04848;
            default -> NpcUiTheme.TEXT;
        };
    }

    private void frame(GuiGraphicsExtractor graphics, int y, boolean bad) {
        graphics.fill(controlX(), y, controlX() + controlW(), y + ROW - 2, NpcUiTheme.PANEL_INSET);
        graphics.outline(controlX(), y, controlW(), ROW - 2, bad ? 0xFFE04848 : NpcUiTheme.BORDER_DIM);
    }

    private void chip(GuiGraphicsExtractor graphics, int cx, int cy, int cw, String text, int mouseX, int mouseY, int color) {
        boolean hover = inside(mouseX, mouseY, cx, cy, cw, ROW - 2);
        graphics.fill(cx, cy, cx + cw, cy + ROW - 2, NpcUiTheme.PANEL_LIGHT);
        graphics.outline(cx, cy, cw, ROW - 2, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        graphics.text(font, font.plainSubstrByWidth(text, cw - 6), cx + 3, cy + 4, hover ? NpcUiTheme.TEXT_HOVER : color, false);
    }
}
