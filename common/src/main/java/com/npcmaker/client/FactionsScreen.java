package com.npcmaker.client;

import com.npcmaker.network.FactionStandingsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * The player's Factions screen (J by default, or /factions): every faction they can see, with membership, a
 * reputation bar from -100 to 100, their standing tier, and how the faction regards them. Uses NpcUiTheme.
 */
public class FactionsScreen extends Screen {

    private static final int PANEL_WIDTH = 300;
    private static final int ROW_HEIGHT = 32;
    private static final int ROW_GAP = 4;
    private static final int HEADER = 30;
    private static final int FOOTER = 18;
    private static final int PADDING = 10;
    private static final int BAR_HEIGHT = 5;
    private static final int BADGE_WIDTH = 56;

    private List<FactionStandingsPayload.Row> rows;
    private int scroll;

    private FactionsScreen(List<FactionStandingsPayload.Row> rows) {
        super(Component.literal("Factions"));
        this.rows = rows;
    }

    /** Opens the screen, or refreshes it if it's already open. */
    public static void open(FactionStandingsPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof FactionsScreen screen) {
            screen.rows = payload.rows();
            screen.clampScroll();
        } else {
            mc.gui.setScreen(new FactionsScreen(payload.rows()));
        }
    }

    // ---- layout -------------------------------------------------------------------------------------------

    private int visibleRows() {
        int space = height - 40 - HEADER - FOOTER;
        return Math.max(1, Math.min(Math.max(rows.size(), 1), space / (ROW_HEIGHT + ROW_GAP)));
    }

    private int panelHeight() {
        return HEADER + visibleRows() * (ROW_HEIGHT + ROW_GAP) + FOOTER;
    }

    private int panelX() {
        return (width - PANEL_WIDTH) / 2;
    }

    private int panelY() {
        return (height - panelHeight()) / 2;
    }

    private void clampScroll() {
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - visibleRows()));
    }

    // ---- input --------------------------------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY);
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (NpcKeys.FACTIONS.matches(event)) {
            onClose();
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
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int x = panelX();
        int y = panelY();
        int h = panelHeight();

        graphics.fill(x, y, x + PANEL_WIDTH, y + h, NpcUiTheme.PANEL);
        graphics.outline(x, y, PANEL_WIDTH, h, NpcUiTheme.BORDER);
        graphics.centeredText(font, title, x + PANEL_WIDTH / 2, y + 10, NpcUiTheme.ACCENT);

        int rowY = y + HEADER;
        if (rows.isEmpty()) {
            graphics.centeredText(font, Component.literal("You don't know any factions yet."), x + PANEL_WIDTH / 2,
                    rowY + ROW_HEIGHT / 2 - 4, NpcUiTheme.TEXT);
        }
        int end = Math.min(rows.size(), scroll + visibleRows());
        for (int i = scroll; i < end; i++) {
            drawRow(graphics, rows.get(i), x + PADDING, rowY, PANEL_WIDTH - PADDING * 2);
            rowY += ROW_HEIGHT + ROW_GAP;
        }

        String hint = rows.size() > visibleRows() ? "Scroll for more  ·  J or Esc to close" : "J or Esc to close";
        graphics.centeredText(font, Component.literal(hint), x + PANEL_WIDTH / 2, y + h - 13, NpcUiTheme.BORDER_DIM);
    }

    private void drawRow(GuiGraphicsExtractor graphics, FactionStandingsPayload.Row row, int x, int y, int w) {
        graphics.fill(x, y, x + w, y + ROW_HEIGHT, NpcUiTheme.PANEL_INSET);
        graphics.outline(x, y, w, ROW_HEIGHT, row.member() ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);

        // line 1: name on the left, standing + number on the right
        graphics.text(font, row.name(), x + 6, y + 5, 0xFFFFFFFF, true);
        Component standing = row.standing().copy().append(Component.literal("  " + row.reputation()).withColor(0xAAAAAA));
        graphics.text(font, standing, x + w - 6 - font.width(standing), y + 5, 0xFFFFFFFF, false);

        // line 2: reputation bar from -100 (left) to 100 (right), filled from the middle
        int barX = x + 6;
        int barW = w - 12 - BADGE_WIDTH - 6;
        int barY = y + 19;
        int mid = barX + barW / 2;
        graphics.fill(barX, barY, barX + barW, barY + BAR_HEIGHT, 0xFF2A2236);
        int filled = Math.round(barW / 2.0F * Mth.clamp(row.reputation(), -100, 100) / 100.0F);
        int barColor = 0xFF000000 | row.tierColor();
        if (filled > 0) {
            graphics.fill(mid, barY, mid + filled, barY + BAR_HEIGHT, barColor);
        } else if (filled < 0) {
            graphics.fill(mid + filled, barY, mid, barY + BAR_HEIGHT, barColor);
        }
        graphics.fill(mid, barY - 1, mid + 1, barY + BAR_HEIGHT + 1, NpcUiTheme.BORDER);

        // badge: Member, or how the faction sees you
        Component badge;
        int badgeColor;
        if (row.member()) {
            badge = Component.literal("Member");
            badgeColor = NpcUiTheme.ACCENT;
        } else {
            switch (row.relation()) {
                case "allied" -> { badge = Component.literal("Ally"); badgeColor = 0xFF7FD36A; }
                case "hostile" -> { badge = Component.literal("Enemy"); badgeColor = 0xFFE04848; }
                default -> { badge = Component.literal("Neutral"); badgeColor = 0xFFBBBBBB; }
            }
        }
        int badgeX = x + w - 6 - BADGE_WIDTH;
        graphics.centeredText(font, badge, badgeX + BADGE_WIDTH / 2, y + 18, badgeColor);
    }
}
