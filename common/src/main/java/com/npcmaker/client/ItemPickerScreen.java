package com.npcmaker.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The item picker: a purple/gold take on the creative menu. Every creative tab (vanilla and modded) across the top,
 * an item grid with tooltips, a search box that looks through everything, and "Use item in my hand" for enchanted
 * or renamed items. Picking (or clearing) returns to the screen that opened it.
 */
public class ItemPickerScreen extends Screen {

    private static final int COLS = 12;
    private static final int SLOT = 18;
    private static final int PAD = 8;
    private static final int TAB = 20;
    private static final int BUTTON_H = 14;

    private final Screen parent;
    private final Consumer<ItemStack> onPick;
    private final List<CreativeModeTab> tabs = new ArrayList<>();
    /** Registered items that aren't in any creative tab (some mods hide items there). */
    private List<ItemStack> otherItems = List.of();
    /** -1 = everything (also used while searching). */
    private int tabIndex = -1;
    private int tabScroll;
    private int scroll;
    private String search = "";
    private List<ItemStack> shown = List.of();
    /** Hover tooltips and the (?) help mode. */
    private final HelpLayer help = new HelpLayer();

    /** @param onPick gets the chosen stack (count 1), or {@link ItemStack#EMPTY} for "Clear slot" */
    public ItemPickerScreen(Screen parent, Component title, Consumer<ItemStack> onPick) {
        super(title);
        this.parent = parent;
        this.onPick = onPick;
    }

    // ---- layout -------------------------------------------------------------------------------------------

    private static final List<String> BUTTON_LABELS = List.of("Use item in my hand", "Clear slot", "Cancel");
    private static final int BUTTON_GAP = 5;

    private int buttonsWidth() {
        int total = 0;
        for (String label : BUTTON_LABELS) {
            total += font.width(label) + 14 + BUTTON_GAP;
        }
        return total - BUTTON_GAP;
    }

    /** Wide enough for the grid and for the buttons at any GUI scale. */
    private int panelW() {
        return Math.max(COLS * SLOT, buttonsWidth()) + PAD * 2;
    }

    private int visibleRows() {
        return Math.max(3, Math.min(8, (height - 120) / SLOT));
    }

    private int panelH() {
        return 22 + TAB + 6 + visibleRows() * SLOT + 8 + BUTTON_H + PAD;
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelY() {
        return (height - panelH()) / 2;
    }

    private int tabsY() {
        return panelY() + 22;
    }

    private int gridX() {
        return panelX() + (panelW() - COLS * SLOT) / 2;
    }

    private int gridY() {
        return tabsY() + TAB + 6;
    }

    private int buttonsY() {
        return gridY() + visibleRows() * SLOT + 8;
    }

    /** Tabs that fit across, after the "All" tab. */
    private int tabsAcross() {
        return (panelW() - PAD * 2 - TAB - 24) / TAB;
    }

    // ---- data ---------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null) {
            // The same rebuild the creative menu does, so modded tabs and items are all filled in.
            CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), true, mc.level.registryAccess());
        }
        tabs.clear();
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            if (tab.getType() == CreativeModeTab.Type.CATEGORY && tab.shouldDisplay() && tab.hasAnyItems()) {
                tabs.add(tab);
            }
        }
        // Items a mod registered but left out of every creative tab still get listed, under "Other".
        LinkedHashSet<String> inTabs = new LinkedHashSet<>();
        CreativeModeTabs.searchTab().getDisplayItems().forEach(s -> inTabs.add(itemId(s)));
        tabs.forEach(t -> t.getDisplayItems().forEach(s -> inTabs.add(itemId(s))));
        otherItems = new ArrayList<>();
        for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            ItemStack stack = item.getDefaultInstance();
            if (!stack.isEmpty() && !inTabs.contains(itemId(stack))) {
                otherItems.add(stack);
            }
        }
        EditBox box = new EditBox(font, panelX() + panelW() - PAD - 100, panelY() + 7, 96, 10, Component.empty());
        box.setBordered(false);
        box.setMaxLength(50);
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
    }

    private void refresh() {
        String q = search.toLowerCase(Locale.ROOT).trim();
        List<ItemStack> source = new ArrayList<>();
        if (tabIndex < 0 || !q.isEmpty()) {
            // Everything: the search tab's list, plus anything a mod put in a tab but not in search.
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (ItemStack stack : CreativeModeTabs.searchTab().getDisplayItems()) {
                if (seen.add(key(stack))) {
                    source.add(stack);
                }
            }
            for (CreativeModeTab tab : tabs) {
                for (ItemStack stack : tab.getDisplayItems()) {
                    if (seen.add(key(stack))) {
                        source.add(stack);
                    }
                }
            }
            source.addAll(otherItems);
        } else if (tabIndex >= tabs.size()) {
            source.addAll(otherItems);
        } else {
            source.addAll(tabs.get(tabIndex).getDisplayItems());
        }
        if (!q.isEmpty()) {
            source.removeIf(stack -> !stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)
                    && !net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains(q));
        }
        shown = source;
        clampScroll();
    }

    private static String itemId(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Tabs shown in the scrolling row: every creative tab, then "Other" if there's anything in it. */
    private int tabCount() {
        return tabs.size() + (otherItems.isEmpty() ? 0 : 1);
    }

    /** Variants (potions, enchanted books…) are different entries; plain duplicates aren't. */
    private static String key(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + stack.getComponentsPatch();
    }

    private void clampScroll() {
        int rows = (shown.size() + COLS - 1) / COLS;
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows - visibleRows()));
    }

    // ---- input --------------------------------------------------------------------------------------------

    private int slotAt(double mx, double my) {
        if (mx < gridX() || mx >= gridX() + COLS * SLOT || my < gridY() || my >= gridY() + visibleRows() * SLOT) {
            return -1;
        }
        int index = (scroll + (int) ((my - gridY()) / SLOT)) * COLS + (int) ((mx - gridX()) / SLOT);
        return index < shown.size() ? index : -1;
    }

    private record Button(int x, int w, String label) {
    }

    private List<Button> buttons() {
        List<Button> list = new ArrayList<>();
        int x = panelX() + PAD;
        for (String label : BUTTON_LABELS) {
            int w = font.width(label) + 14;
            list.add(new Button(x, w, label));
            x += w + BUTTON_GAP;
        }
        return list;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (help.click(mx, my)) {
            return true;
        }
        int slot = slotAt(mx, my);
        if (slot >= 0) {
            pick(shown.get(slot).copyWithCount(1));
            return true;
        }
        // tabs: [All] [‹] tabs… [›]
        if (my >= tabsY() && my < tabsY() + TAB) {
            int x = panelX() + PAD;
            if (mx >= x && mx < x + TAB) {
                selectTab(-1);
                return true;
            }
            int arrowsX = x + TAB + 2;
            if (mx >= arrowsX && mx < arrowsX + 10) {
                tabScroll = Math.max(0, tabScroll - 1);
                return true;
            }
            int first = arrowsX + 12;
            for (int i = 0; i < tabsAcross() && tabScroll + i < tabCount(); i++) {
                int tx = first + i * TAB;
                if (mx >= tx && mx < tx + TAB) {
                    selectTab(tabScroll + i);
                    return true;
                }
            }
            int rightArrow = first + tabsAcross() * TAB;
            if (mx >= rightArrow && mx < rightArrow + 10) {
                tabScroll = Math.min(Math.max(0, tabCount() - tabsAcross()), tabScroll + 1);
                return true;
            }
        }
        for (Button b : buttons()) {
            if (mx >= b.x() && mx < b.x() + b.w() && my >= buttonsY() && my < buttonsY() + BUTTON_H) {
                switch (b.label()) {
                    case "Use item in my hand" -> {
                        ItemStack held = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem();
                        if (!held.isEmpty()) {
                            pick(held.copy());
                        }
                    }
                    case "Clear slot" -> pick(ItemStack.EMPTY);
                    default -> onClose();
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void selectTab(int index) {
        tabIndex = index;
        scroll = 0;
        refresh();
    }

    private void pick(ItemStack stack) {
        minecraft.gui.setScreen(parent);
        onPick.accept(stack);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (y >= tabsY() && y < tabsY() + TAB) {
            tabScroll = Mth.clamp(tabScroll - (int) Math.signum(scrollY), 0, Math.max(0, tabCount() - tabsAcross()));
        } else {
            scroll -= (int) Math.signum(scrollY);
            clampScroll();
        }
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
        graphics.text(font, title, x + PAD, y + 8, NpcUiTheme.ACCENT, false);
        int boxX = x + w - PAD - 104;
        help.begin();
        help.button(boxX - 16, y + 5);
        help.spot(boxX, y + 4, x + w - PAD - boxX, 14, "picklist.search");
        help.spot(x + PAD, tabsY(), w - PAD * 2, TAB, "itempicker.tabs");
        help.spot(gridX(), gridY(), COLS * SLOT, visibleRows() * SLOT, "itempicker.grid");
        for (Button b : buttons()) {
            if (b.label().startsWith("Use")) {
                help.spot(b.x(), buttonsY(), b.w(), BUTTON_H, "itempicker.hand");
            } else if (b.label().startsWith("Clear")) {
                help.spot(b.x(), buttonsY(), b.w(), BUTTON_H, "itempicker.clear");
            }
        }
        graphics.fill(boxX, y + 4, x + w - PAD, y + 18, NpcUiTheme.PANEL_INSET);
        graphics.outline(boxX, y + 4, x + w - PAD - boxX, 14, NpcUiTheme.BORDER_DIM);

        Component tooltip = null;
        // tabs
        int tx = x + PAD;
        drawTab(graphics, tx, tabIndex < 0 && search.isBlank(), null, "All", mouseX, mouseY);
        if (inside(mouseX, mouseY, tx, tabsY(), TAB, TAB)) {
            tooltip = Component.literal("All items");
        }
        int arrowsX = tx + TAB + 2;
        graphics.text(font, "‹", arrowsX + 2, tabsY() + 6, tabScroll > 0 ? NpcUiTheme.ACCENT : 0xFF4A4256, false);
        int first = arrowsX + 12;
        for (int i = 0; i < tabsAcross() && tabScroll + i < tabCount(); i++) {
            int index = tabScroll + i;
            int cx = first + i * TAB;
            boolean selected = tabIndex == index && search.isBlank();
            if (index < tabs.size()) {
                CreativeModeTab tab = tabs.get(index);
                drawTab(graphics, cx, selected, tab.getIconItem(), null, mouseX, mouseY);
                if (inside(mouseX, mouseY, cx, tabsY(), TAB, TAB)) {
                    tooltip = tab.getDisplayName();
                }
            } else {
                drawTab(graphics, cx, selected, null, "…", mouseX, mouseY);
                if (inside(mouseX, mouseY, cx, tabsY(), TAB, TAB)) {
                    tooltip = Component.literal("Other (items no creative tab shows)");
                }
            }
        }
        int rightArrow = first + tabsAcross() * TAB;
        boolean more = tabScroll + tabsAcross() < tabCount();
        graphics.text(font, "›", rightArrow + 2, tabsY() + 6, more ? NpcUiTheme.ACCENT : 0xFF4A4256, false);

        // grid
        graphics.fill(gridX() - 1, gridY() - 1, gridX() + COLS * SLOT + 1, gridY() + visibleRows() * SLOT + 1, NpcUiTheme.PANEL_INSET);
        int hovered = slotAt(mouseX, mouseY);
        for (int row = 0; row < visibleRows(); row++) {
            for (int col = 0; col < COLS; col++) {
                int index = (scroll + row) * COLS + col;
                if (index >= shown.size()) {
                    break;
                }
                int sx = gridX() + col * SLOT;
                int sy = gridY() + row * SLOT;
                if (index == hovered) {
                    graphics.fill(sx, sy, sx + SLOT, sy + SLOT, 0x40D4AF37);
                }
                graphics.item(shown.get(index), sx + 1, sy + 1);
            }
        }
        if (shown.isEmpty()) {
            graphics.text(font, "Nothing matches.", gridX() + 4, gridY() + 4, NpcUiTheme.BORDER_DIM, false);
        }

        // buttons
        for (Button b : buttons()) {
            boolean hover = inside(mouseX, mouseY, b.x(), buttonsY(), b.w(), BUTTON_H);
            graphics.fill(b.x(), buttonsY(), b.x() + b.w(), buttonsY() + BUTTON_H, NpcUiTheme.PANEL_LIGHT);
            graphics.outline(b.x(), buttonsY(), b.w(), BUTTON_H, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            graphics.centeredText(font, Component.literal(b.label()), b.x() + b.w() / 2, buttonsY() + 3,
                    hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT);
        }
        // The count sits in the title row (between the title and the search box), clear of the buttons.
        String count = shown.size() + " items";
        int countX = boxX - 22 - font.width(count);
        if (countX > x + PAD + font.width(title) + 6) {
            graphics.text(font, count, countX, y + 8, NpcUiTheme.BORDER_DIM, false);
        }

        super.extractRenderState(graphics, mouseX, mouseY, a); // search box

        if (hovered >= 0) {
            graphics.setTooltipForNextFrame(font, shown.get(hovered), mouseX, mouseY);
        } else if (tooltip != null) {
            graphics.setTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        }
        if (help.active()) {
            help.render(graphics, font, mouseX, mouseY, width, height);
        } else {
            // Item and tab names already show on hover here, so only the (?) circle is drawn outside help mode.
            help.renderButtonOnly(graphics, font, mouseX, mouseY);
        }
    }

    private void drawTab(GuiGraphicsExtractor graphics, int x, boolean selected, ItemStack icon, String label, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, tabsY(), TAB, TAB);
        graphics.fill(x, tabsY(), x + TAB - 1, tabsY() + TAB, selected ? NpcUiTheme.PANEL_INSET : NpcUiTheme.PANEL_LIGHT);
        graphics.outline(x, tabsY(), TAB - 1, TAB, selected ? NpcUiTheme.BORDER : hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.BORDER_DIM);
        if (icon != null) {
            graphics.item(icon, x + 1, tabsY() + 2);
        } else if (label != null) {
            graphics.centeredText(font, Component.literal(label), x + TAB / 2, tabsY() + 6, NpcUiTheme.TEXT);
        }
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
