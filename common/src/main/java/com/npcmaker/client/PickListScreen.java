package com.npcmaker.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A searchable list to pick from: sounds, mobs, NPC templates, factions, quests. Pick one, or tick several (multi
 * mode). With a preview action (sounds), each row gets a ▶ that plays it without picking. Returns to the screen
 * that opened it. Uses NpcUiTheme.
 */
public class PickListScreen extends Screen {

    /** @param detail shown dimmed after the label (e.g. the id), may be empty */
    public record Option(String id, String label, String detail) {
    }

    private static final int PAD = 8;
    private static final int ROW_H = 12;
    private static final int BUTTON_H = 14;

    private final Screen parent;
    private final List<Option> options;
    private final @Nullable String current;
    private final @Nullable Consumer<String> preview;
    private final @Nullable String noneLabel;
    private final @Nullable Consumer<String> onPick;
    private final @Nullable Consumer<Set<String>> onDone;
    private final Set<String> ticked = new LinkedHashSet<>();
    private String search = "";
    private int scroll;
    private List<Option> shown = List.of();
    /** Hover tooltips and the (?) help mode. */
    private final HelpLayer help = new HelpLayer();

    private PickListScreen(Screen parent, Component title, List<Option> options, @Nullable String current,
                           @Nullable Consumer<String> preview, @Nullable String noneLabel, @Nullable Consumer<String> onPick,
                           @Nullable Consumer<Set<String>> onDone, Set<String> ticked) {
        super(title);
        this.parent = parent;
        this.options = options;
        this.current = current;
        this.preview = preview;
        this.noneLabel = noneLabel;
        this.onPick = onPick;
        this.onDone = onDone;
        this.ticked.addAll(ticked);
    }

    /**
     * Pick one option.
     *
     * @param noneLabel a button that picks "" (e.g. "No sound"), or null for none
     * @param preview   plays an option without picking it (the ▶ on each row), or null
     */
    public static PickListScreen single(Screen parent, String title, List<Option> options, @Nullable String current,
                                        @Nullable String noneLabel, @Nullable Consumer<String> preview, Consumer<String> onPick) {
        return new PickListScreen(parent, Component.literal(title), options, current, preview, noneLabel, onPick, null, Set.of());
    }

    /** Tick any number of options; Done returns the ticked ids. */
    public static PickListScreen multi(Screen parent, String title, List<Option> options, Set<String> ticked,
                                       Consumer<Set<String>> onDone) {
        return new PickListScreen(parent, Component.literal(title), options, null, null, null, null, onDone, ticked);
    }

    // ---- layout -------------------------------------------------------------------------------------------

    private int panelW() {
        return Math.min(380, width - 16);
    }

    private int panelH() {
        return Math.min(320, height - 16);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelY() {
        return (height - panelH()) / 2;
    }

    private int listY() {
        return panelY() + 24;
    }

    private int buttonsY() {
        return panelY() + panelH() - PAD - BUTTON_H;
    }

    private int visibleRows() {
        return Math.max(1, (buttonsY() - 6 - listY()) / ROW_H);
    }

    // ---- data ---------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        EditBox box = new EditBox(font, panelX() + panelW() - PAD - 120, panelY() + 7, 116, 10, Component.empty());
        box.setBordered(false);
        box.setMaxLength(60);
        box.setTextColor(NpcUiTheme.TEXT);
        box.setHint(Component.literal("Search…").withColor(0xFF6E6578));
        box.setValue(search);
        box.setResponder(s -> {
            search = s;
            scroll = 0;
            refresh();
        });
        addRenderableWidget(box);
        setInitialFocus(box);
        refresh();
        if (current != null && search.isEmpty()) {
            int at = -1;
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).id().equals(current)) {
                    at = i;
                }
            }
            if (at >= 0) {
                scroll = Mth.clamp(at - visibleRows() / 2, 0, Math.max(0, shown.size() - visibleRows()));
            }
        }
    }

    private void refresh() {
        String q = search.toLowerCase(Locale.ROOT).trim();
        List<Option> list = new ArrayList<>();
        for (Option o : options) {
            if (q.isEmpty() || o.label().toLowerCase(Locale.ROOT).contains(q) || o.id().toLowerCase(Locale.ROOT).contains(q)) {
                list.add(o);
            }
        }
        shown = list;
        scroll = Mth.clamp(scroll, 0, Math.max(0, shown.size() - visibleRows()));
    }

    private record Button(int x, int w, String label) {
    }

    private List<Button> buttons() {
        List<String> labels = new ArrayList<>();
        if (onDone != null) {
            labels.add("Done (" + ticked.size() + ")");
        }
        if (noneLabel != null) {
            labels.add(noneLabel);
        }
        labels.add("Cancel");
        List<Button> list = new ArrayList<>();
        int x = panelX() + PAD;
        for (String label : labels) {
            int w = font.width(label) + 14;
            list.add(new Button(x, w, label));
            x += w + 5;
        }
        return list;
    }

    // ---- input --------------------------------------------------------------------------------------------

    private int rowAt(double mx, double my) {
        if (mx < panelX() + PAD || mx >= panelX() + panelW() - PAD || my < listY() || my >= listY() + visibleRows() * ROW_H) {
            return -1;
        }
        int index = scroll + (int) ((my - listY()) / ROW_H);
        return index < shown.size() ? index : -1;
    }

    private int previewX() {
        return panelX() + panelW() - PAD - 14;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (help.click(mx, my)) {
            return true;
        }
        int row = rowAt(mx, my);
        if (row >= 0) {
            Option o = shown.get(row);
            if (preview != null && mx >= previewX()) {
                preview.accept(o.id());
                return true;
            }
            if (onDone != null) {
                if (!ticked.remove(o.id())) {
                    ticked.add(o.id());
                }
                return true;
            }
            finish(o.id());
            return true;
        }
        for (Button b : buttons()) {
            if (mx >= b.x() && mx < b.x() + b.w() && my >= buttonsY() && my < buttonsY() + BUTTON_H) {
                if (b.label().startsWith("Done")) {
                    minecraft.gui.setScreen(parent);
                    onDone.accept(Set.copyOf(ticked));
                } else if (b.label().equals(noneLabel)) {
                    finish("");
                } else {
                    onClose();
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void finish(String id) {
        minecraft.gui.setScreen(parent);
        if (onPick != null) {
            onPick.accept(id);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY) * 3, 0, Math.max(0, shown.size() - visibleRows()));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() && help.escape()) {
            return true;
        }
        if (event.isEscape()) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
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
        graphics.fill(x, y, x + w, y + h, NpcUiTheme.PANEL);
        graphics.outline(x, y, w, h, NpcUiTheme.BORDER);
        int boxX = x + w - PAD - 124;
        help.begin();
        help.button(boxX - 16, y + 5);
        graphics.text(font, font.plainSubstrByWidth(title.getString(), boxX - x - PAD - 22), x + PAD, y + 8, NpcUiTheme.ACCENT, false);
        graphics.fill(boxX, y + 4, x + w - PAD, y + 18, NpcUiTheme.PANEL_INSET);
        graphics.outline(boxX, y + 4, x + w - PAD - boxX, 14, NpcUiTheme.BORDER_DIM);
        help.spot(boxX, y + 4, x + w - PAD - boxX, 14, "picklist.search");

        int listX = x + PAD;
        int listW = w - PAD * 2;
        help.spot(listX, listY(), listW - (preview != null ? 18 : 0), visibleRows() * ROW_H, onDone != null ? "picklist.row_multi" : "picklist.row");
        if (preview != null) {
            help.spot(previewX(), listY(), 16, visibleRows() * ROW_H, "picklist.preview");
        }
        graphics.fill(listX, listY() - 1, listX + listW, listY() + visibleRows() * ROW_H + 1, NpcUiTheme.PANEL_INSET);
        int hovered = rowAt(mouseX, mouseY);
        int end = Math.min(shown.size(), scroll + visibleRows());
        for (int i = scroll; i < end; i++) {
            Option o = shown.get(i);
            int ry = listY() + (i - scroll) * ROW_H;
            boolean selected = o.id().equals(current) || ticked.contains(o.id());
            if (i == hovered || selected) {
                graphics.fill(listX, ry, listX + listW, ry + ROW_H, i == hovered ? 0x40D4AF37 : 0x20D4AF37);
            }
            int tx = listX + 3;
            if (onDone != null) {
                graphics.text(font, ticked.contains(o.id()) ? "☑" : "☐", tx, ry + 2, NpcUiTheme.ACCENT, false);
                tx += 12;
            }
            int textRoom = listW - (tx - listX) - (preview != null ? 20 : 4);
            String label = font.plainSubstrByWidth(o.label(), textRoom);
            graphics.text(font, label, tx, ry + 2, selected ? NpcUiTheme.ACCENT : i == hovered ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT, false);
            int used = font.width(label) + 6;
            if (!o.detail().isEmpty() && used < textRoom - 20) {
                graphics.text(font, font.plainSubstrByWidth(o.detail(), textRoom - used), tx + used, ry + 2, 0xFF6E6578, false);
            }
            if (preview != null) {
                boolean overPlay = i == hovered && mouseX >= previewX();
                graphics.text(font, "▶", previewX() + 3, ry + 2, overPlay ? 0xFFFFE08A : NpcUiTheme.BORDER_DIM, false);
            }
        }
        if (shown.isEmpty()) {
            graphics.text(font, "Nothing matches.", listX + 4, listY() + 3, NpcUiTheme.BORDER_DIM, false);
        }

        for (Button b : buttons()) {
            boolean hover = mouseX >= b.x() && mouseX < b.x() + b.w() && mouseY >= buttonsY() && mouseY < buttonsY() + BUTTON_H;
            graphics.fill(b.x(), buttonsY(), b.x() + b.w(), buttonsY() + BUTTON_H, NpcUiTheme.PANEL_LIGHT);
            graphics.outline(b.x(), buttonsY(), b.w(), BUTTON_H, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            graphics.centeredText(font, Component.literal(b.label()), b.x() + b.w() / 2, buttonsY() + 3,
                    hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT);
        }
        String count = shown.size() + " shown" + (preview != null ? " · ▶ plays" : "") + (onDone != null ? " · click to tick" : "");
        graphics.text(font, count, x + w - PAD - font.width(count), buttonsY() + 3, 0xFF6E6578, false);

        super.extractRenderState(graphics, mouseX, mouseY, a);
        help.render(graphics, font, mouseX, mouseY, width, height);
    }

    // ---- ready-made lists ---------------------------------------------------------------------------------

    /** Every sound event: registered ones (vanilla and mods) plus resource-pack sounds the client knows. */
    public static List<Option> soundOptions() {
        Set<String> ids = new java.util.TreeSet<>();
        net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.keySet().forEach(id -> ids.add(id.toString()));
        net.minecraft.client.Minecraft.getInstance().getSoundManager().getAvailableSounds().forEach(id -> ids.add(id.toString()));
        List<Option> list = new ArrayList<>();
        for (String id : ids) {
            list.add(new Option(id, id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id, ""));
        }
        return list;
    }

    /** Plays a sound by id in the menu, for previews. */
    public static void playSound(String id) {
        net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(id);
        if (key == null) {
            return;
        }
        net.minecraft.sounds.SoundEvent event = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.getOptional(key)
                .orElseGet(() -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(key));
        net.minecraft.client.Minecraft.getInstance().getSoundManager()
                .play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(event, 1.0F, 1.0F));
    }

    /**
     * Built-in poses (sit, sleep, read, work) or one-shot emotes (wave, bow…), followed by every custom animation
     * uploaded to the server.
     */
    public static List<Option> animationOptions(boolean poses) {
        List<Option> list = new ArrayList<>();
        for (com.npcmaker.data.Emote emote : poses ? com.npcmaker.data.Emote.poses() : com.npcmaker.data.Emote.oneShots()) {
            list.add(new Option(emote.id(), emote.label(), "built in"));
        }
        for (String name : CustomModels.animationNames()) {
            com.npcmaker.custom.AnimationClip clip = CustomModels.clip(name);
            String detail = clip == null ? "custom" : "custom · " + (clip.loop() == com.npcmaker.custom.AnimationClip.Loop.LOOP ? "loops" : String.format(Locale.ROOT, "%.1f s", clip.length()));
            list.add(new Option(com.npcmaker.custom.CustomContent.ANIMATION_PREFIX + name, name, detail));
        }
        return list;
    }

    /** Every particle that needs no extra settings (vanilla and modded), which is what NPC effects can show. */
    public static List<Option> particleOptions() {
        List<Option> list = new ArrayList<>();
        for (String name : ClientCustom.names("particle")) {
            list.add(new Option(com.npcmaker.custom.CustomContent.NAMESPACE + ":" + name, "★ " + name, "custom"));
        }
        for (var type : net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE) {
            if (type instanceof net.minecraft.core.particles.SimpleParticleType) {
                String id = net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getKey(type).toString();
                list.add(new Option(id, id.startsWith("minecraft:") ? id.substring(10) : id, ""));
            }
        }
        // custom ones first (they start with ★), then the rest by name
        list.sort((p, q) -> p.label().startsWith("★") != q.label().startsWith("★") ? (p.label().startsWith("★") ? -1 : 1)
                : p.label().compareToIgnoreCase(q.label()));
        return list;
    }

    /** Every living mob type (any mod), by name. */
    public static List<Option> mobOptions() {
        List<Option> list = new ArrayList<>();
        for (MobDisguises.Option mob : MobDisguises.options()) {
            if (!mob.id().isEmpty()) {
                list.add(new Option(mob.id(), mob.name().getString(), mob.id()));
            }
        }
        return list;
    }

    /** Options from the content index (NPC templates, factions, quests): label with the id after it. */
    public static List<Option> fromIndex(List<com.npcmaker.network.ContentPayloads.Entry> entries) {
        List<Option> list = new ArrayList<>();
        for (var e : entries) {
            list.add(new Option(e.id(), e.label(), e.id()));
        }
        list.sort((p, q) -> p.label().compareToIgnoreCase(q.label()));
        return list;
    }
}
