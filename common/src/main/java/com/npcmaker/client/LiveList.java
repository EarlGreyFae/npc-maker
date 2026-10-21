package com.npcmaker.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.npcmaker.entity.LocateGlow;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.network.LivePayloads;
import com.npcmaker.network.NpcNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Live list: every NPC loaded right now, with Find, Go, Edit and Remove. Drawn inside the editor's Live tab and
 * the stand-alone Live screen (the I key with no NPC in sight). Works like Enemy Maker's Live list: your world first,
 * nearest first, refreshed every 2 seconds, and Remove asks "Sure?" first. Uses NpcUiTheme.
 */
final class LiveList {

    /** What the screen showing the list provides. */
    interface Host {
        Font font();

        /** The template being edited (rows using it are gold), or "" outside the editor. */
        String currentTemplate();

        /** False to stop Edit (the editor has unsaved changes and wants a second click). */
        boolean mayLeave();

        void status(String message, int color);
    }

    private static final int ROW_H = 24;
    private static final int BUTTON_H = 12;
    private static final long CONFIRM_MS = 3000L;
    private static final long GLOW_MS = 30_000L;
    private static final int RED = 0xFFE07070;

    /** The last list from the server, shared so switching screens doesn't flash "Loading". */
    private static JsonArray latest = new JsonArray();
    private static boolean loaded;
    /** When the first unanswered request went out (0 = none), to spot a server without the Live list. */
    private static long askedAt;

    private final Host host;
    private String search = "";
    private int scroll;
    private int ticks;
    private String confirmRemove = "";
    private long confirmAt;

    private int x;
    private int y;
    private int w;
    private int h;

    LiveList(Host host) {
        this.host = host;
    }

    // ---- data ---------------------------------------------------------------------------------------------

    static void request() {
        if (!loaded && askedAt == 0) {
            askedAt = Util.getMillis();
        }
        NpcNetworking.sendToServer(new LivePayloads.Action(LivePayloads.LIST, ""));
    }

    /** A list from the server: store it, and open the Live screen if the I key asked for it. */
    static void onSnapshot(LivePayloads.Snapshot payload) {
        try {
            latest = JsonParser.parseString(payload.json()).getAsJsonArray();
        } catch (RuntimeException e) {
            latest = new JsonArray();
        }
        loaded = true;
        askedAt = 0;
        Minecraft mc = Minecraft.getInstance();
        if (payload.open() && mc.gui.screen() == null) {
            mc.gui.setScreen(new LiveNpcsScreen());
        }
    }

    private List<JsonObject> shown() {
        String q = search.trim().toLowerCase(Locale.ROOT);
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : latest) {
            JsonObject o = e.getAsJsonObject();
            if (q.isEmpty() || o.get("name").getAsString().toLowerCase(Locale.ROOT).contains(q)
                    || o.get("template").getAsString().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(o);
            }
        }
        return out;
    }

    /** Called every client tick while the list is showing; asks for a fresh list every 2 seconds. */
    void tick() {
        if (ticks++ % 40 == 0) {
            request();
        }
    }

    // ---- layout -------------------------------------------------------------------------------------------

    /** The search box goes at the top of the area, the rows under it. */
    EditBox layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y + 18;
        this.w = w;
        this.h = h - 18;
        EditBox box = new EditBox(host.font(), x + 4, y + 3, w - 8, 10, Component.empty());
        box.setBordered(false);
        box.setMaxLength(48);
        box.setTextColor(NpcUiTheme.TEXT);
        box.setHint(Component.literal("Search names or templates…").withColor(0xFF6E6578));
        box.setValue(search);
        box.setResponder(s -> {
            search = s;
            scroll = 0;
        });
        scroll = Mth.clamp(scroll, 0, maxScroll());
        return box;
    }

    private int rows() {
        return Math.max(1, h / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, shown().size() - rows());
    }

    private record RowButton(String action, int x, int y, int w, String label, int color) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + BUTTON_H;
        }
    }

    private List<RowButton> buttons(JsonObject o, int ry) {
        Font font = host.font();
        String uuid = o.get("uuid").getAsString();
        boolean confirming = confirmRemove.equals(uuid) && Util.getMillis() - confirmAt < CONFIRM_MS;
        String[][] specs = {{"remove", confirming ? "Sure?" : "Remove"}, {"edit", "Edit"}, {"tp", "Go"}, {"find", "Find"}};
        List<RowButton> out = new ArrayList<>();
        int right = x + w - 2;
        int by = ry + (ROW_H - BUTTON_H) / 2 - 1;
        for (String[] spec : specs) {
            int bw = font.width(spec[1]) + 8;
            right -= bw;
            out.add(new RowButton(spec[0], right, by, bw, spec[1], spec[0].equals("remove") ? RED : NpcUiTheme.TEXT));
            right -= 3;
        }
        return out;
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    void draw(GuiGraphicsExtractor graphics, HelpLayer help, int mouseX, int mouseY) {
        Font font = host.font();
        // search box frame (the box itself is a widget the screen added)
        graphics.fill(x, y - 18, x + w, y - 3, NpcUiTheme.PANEL_INSET);
        graphics.outline(x, y - 18, w, 15, NpcUiTheme.BORDER_DIM);
        help.spot(x, y - 18, w, 15, "live.search");
        List<JsonObject> shown = shown();
        if (!loaded || shown.isEmpty()) {
            boolean noAnswer = !loaded && askedAt != 0 && Util.getMillis() - askedAt > 4000L;
            String text = noAnswer ? "No answer from the server." : !loaded ? "Loading…" : latest.isEmpty()
                    ? "No NPCs are loaded right now." : "No NPC matches.";
            graphics.text(font, text, x + 4, y + 4, NpcUiTheme.BORDER_DIM, false);
            if (noAnswer) {
                graphics.text(font, "It may be running an older NPC Maker without the Live list.", x + 4, y + 16, 0xFF8F8A80, false);
            }
            if (loaded && latest.isEmpty()) {
                graphics.text(font, "NPCs only count while someone is near them.", x + 4, y + 16, 0xFF8F8A80, false);
            }
            return;
        }
        String current = host.currentTemplate();
        int end = Math.min(shown.size(), scroll + rows());
        for (int i = scroll; i < end; i++) {
            JsonObject o = shown.get(i);
            int ry = y + (i - scroll) * ROW_H;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H - 2;
            graphics.fill(x, ry, x + w, ry + ROW_H - 2, hover ? NpcUiTheme.PANEL_INSET : NpcUiTheme.PANEL_LIGHT);
            graphics.outline(x, ry, w, ROW_H - 2, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            List<RowButton> buttons = buttons(o, ry);
            int textW = buttons.get(buttons.size() - 1).x() - x - 8;
            String template = o.get("template").getAsString();
            boolean same = !current.isEmpty() && template.equals(current);
            graphics.text(font, font.plainSubstrByWidth(o.get("name").getAsString(), textW), x + 4, ry + 2,
                    same ? NpcUiTheme.ACCENT : NpcUiTheme.TEXT, false);
            graphics.text(font, font.plainSubstrByWidth(detail(o), textW), x + 4, ry + 12, 0xFF8F8A80, false);
            help.spot(x, ry, textW + 4, ROW_H - 2, "live.row");
            for (RowButton b : buttons) {
                boolean over = b.contains(mouseX, mouseY);
                graphics.fill(b.x(), b.y(), b.x() + b.w(), b.y() + BUTTON_H, NpcUiTheme.PANEL);
                graphics.outline(b.x(), b.y(), b.w(), BUTTON_H, over ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
                graphics.centeredText(font, Component.literal(b.label()), b.x() + b.w() / 2, b.y() + 2,
                        over && b.color() != RED ? NpcUiTheme.TEXT_HOVER : b.color());
                help.spot(b.x(), b.y(), b.w(), BUTTON_H, "live." + (b.action().equals("tp") ? "go" : b.action()));
            }
        }
        String count = shown.size() + " NPC" + (shown.size() == 1 ? "" : "s") + (shown.size() > rows() ? " · scroll for more" : "");
        graphics.text(font, count, x + w - font.width(count), y + rows() * ROW_H + 1, 0xFF6E6578, false);
    }

    private static String detail(JsonObject o) {
        int distance = o.get("distance").getAsInt();
        String at = o.get("x").getAsInt() + " " + o.get("y").getAsInt() + " " + o.get("z").getAsInt();
        String where = distance >= 0 ? distance + " blocks away (" + at + ")"
                : o.get("dimension").getAsString().replace("minecraft:", "").replace('_', ' ') + " (" + at + ")";
        return o.get("health").getAsInt() + "/" + o.get("max_health").getAsInt() + " hp · " + where + " · " + o.get("template").getAsString();
    }

    // ---- input --------------------------------------------------------------------------------------------

    boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y - 18 && my < y + h;
    }

    boolean click(double mx, double my) {
        List<JsonObject> shown = shown();
        int end = Math.min(shown.size(), scroll + rows());
        for (int i = scroll; i < end; i++) {
            JsonObject o = shown.get(i);
            for (RowButton b : buttons(o, y + (i - scroll) * ROW_H)) {
                if (b.contains(mx, my)) {
                    press(b.action(), o);
                    return true;
                }
            }
        }
        return false;
    }

    boolean scroll(double amount) {
        scroll = Mth.clamp(scroll - (int) Math.signum(amount), 0, maxScroll());
        return true;
    }

    private void press(String action, JsonObject o) {
        String uuid = o.get("uuid").getAsString();
        String name = o.get("name").getAsString();
        switch (action) {
            case "find" -> find(o, name);
            case "tp" -> NpcNetworking.sendToServer(new LivePayloads.Action(LivePayloads.TELEPORT, uuid));
            case "edit" -> {
                if (host.mayLeave()) {
                    NpcNetworking.sendToServer(new LivePayloads.Action(LivePayloads.EDIT, uuid));
                }
            }
            case "remove" -> {
                if (confirmRemove.equals(uuid) && Util.getMillis() - confirmAt < CONFIRM_MS) {
                    confirmRemove = "";
                    NpcNetworking.sendToServer(new LivePayloads.Action(LivePayloads.REMOVE, uuid));
                } else {
                    confirmRemove = uuid;
                    confirmAt = Util.getMillis();
                    host.status("Click Sure? to remove " + name + " for good.", 0xFFE0A048);
                }
            }
            default -> {
            }
        }
    }

    /** Lights the NPC up for 30 s (for this player only) and says which way it is. */
    private void find(JsonObject o, String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        int distance = o.get("distance").getAsInt();
        String at = o.get("x").getAsInt() + " " + o.get("y").getAsInt() + " " + o.get("z").getAsInt();
        if (distance < 0) {
            String world = o.get("dimension").getAsString().replace("minecraft:", "").replace('_', ' ');
            host.status(name + " is in " + world + " at " + at + ". Use Go to get there.", NpcUiTheme.ACCENT);
            return;
        }
        LocateGlow.light(o.get("id").getAsInt(), System.currentTimeMillis() + GLOW_MS);
        double dx = o.get("x").getAsInt() + 0.5 - mc.player.getX();
        double dz = o.get("z").getAsInt() + 0.5 - mc.player.getZ();
        boolean visible = mc.level.getEntity(o.get("id").getAsInt()) instanceof NpcEntity;
        String way = distance < 3 ? "right here" : distance + " blocks " + compass(dx, dz);
        host.status(name + " is " + way + " (" + at + ")" + (visible ? ", glowing for 30 s." : ". It glows once you're closer."),
                NpcUiTheme.ACCENT);
    }

    /** "north", "south-east"… from the player toward a point (Minecraft: -Z is north, +X is east). */
    private static String compass(double dx, double dz) {
        String[] names = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        double angle = Math.toDegrees(Math.atan2(-dx, dz)); // 0 = south, 90 = west
        int index = Math.floorMod((int) Math.round(angle / 45.0), 8);
        return names[index];
    }
}
