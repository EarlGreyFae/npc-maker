package com.npcmaker.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The Live list on its own (I with no NPC in sight): every NPC loaded right now, to find, go to, edit or remove.
 * The same list is the editor's Live tab. Uses NpcUiTheme.
 */
public class LiveNpcsScreen extends Screen implements ClientContent.Listener {

    private static final int MAX_W = 400;
    private static final int MAX_H = 270;
    private static final int HEADER = 26;
    private static final int FOOTER = 26;
    private static final int PAD = 8;
    private static final int BUTTON_H = 14;

    private final HelpLayer help = new HelpLayer();
    private final LiveList list = new LiveList(new LiveList.Host() {
        @Override
        public Font font() {
            return font;
        }

        @Override
        public String currentTemplate() {
            return "";
        }

        @Override
        public boolean mayLeave() {
            return true;
        }

        @Override
        public void status(String message, int color) {
            status = message;
            statusColor = color;
        }
    });

    private String status = "";
    private int statusColor = NpcUiTheme.BORDER_DIM;

    public LiveNpcsScreen() {
        super(Component.literal("Live NPCs"));
    }

    private int panelW() {
        return Math.min(MAX_W, width - 16);
    }

    private int panelH() {
        return Math.min(MAX_H, height - 16);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelY() {
        return (height - panelH()) / 2;
    }

    private int footerY() {
        return panelY() + panelH() - FOOTER + 6;
    }

    @Override
    protected void init() {
        int top = panelY() + HEADER + 4;
        int bottom = panelY() + panelH() - FOOTER - 4;
        addRenderableWidget(list.layout(panelX() + PAD, top, panelW() - PAD * 2, bottom - top));
    }

    @Override
    public void tick() {
        super.tick();
        list.tick();
    }

    @Override
    public void showResult(boolean ok, String message) {
        status = message;
        statusColor = ok ? 0xFF7FD36A : 0xFFE04848;
    }

    private record Button(int x, int y, int w, String label) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + BUTTON_H;
        }
    }

    private Button closeButton() {
        int w = font.width("Close") + 16;
        return new Button(panelX() + panelW() - PAD - w, footerY(), w, "Close");
    }

    private Button refreshButton() {
        Button close = closeButton();
        int w = font.width("Refresh") + 16;
        return new Button(close.x() - 6 - w, footerY(), w, "Refresh");
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (help.click(mx, my)) {
            return true;
        }
        if (list.click(mx, my)) {
            return true;
        }
        if (closeButton().contains(mx, my)) {
            onClose();
            return true;
        }
        if (refreshButton().contains(mx, my)) {
            LiveList.request();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (list.contains(x, y)) {
            return list.scroll(scrollY);
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int x = panelX();
        int y = panelY();
        int w = panelW();
        int h = panelH();
        help.begin();
        graphics.fill(x, y, x + w, y + h, NpcUiTheme.PANEL);
        graphics.outline(x, y, w, h, NpcUiTheme.BORDER);
        graphics.text(font, Component.literal("Live NPCs"), x + PAD, y + 9, NpcUiTheme.ACCENT, false);
        String hint = "every NPC loaded right now";
        graphics.text(font, hint, x + w - PAD - 18 - font.width(hint), y + 9, NpcUiTheme.BORDER_DIM, false);
        help.button(x + w - PAD - 12, y + 7);
        graphics.fill(x + 4, y + HEADER - 2, x + w - 4, y + HEADER - 1, NpcUiTheme.BORDER_DIM);

        list.draw(graphics, help, mouseX, mouseY);

        int footerTop = y + h - FOOTER;
        graphics.fill(x + 4, footerTop + 1, x + w - 4, footerTop + 2, NpcUiTheme.BORDER_DIM);
        Button refresh = refreshButton();
        for (Button b : new Button[]{refresh, closeButton()}) {
            boolean hover = b.contains(mouseX, mouseY);
            graphics.fill(b.x(), b.y(), b.x() + b.w(), b.y() + BUTTON_H, NpcUiTheme.PANEL_LIGHT);
            graphics.outline(b.x(), b.y(), b.w(), BUTTON_H, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            graphics.centeredText(font, Component.literal(b.label()), b.x() + b.w() / 2, b.y() + 3,
                    hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT);
        }
        help.spot(refresh.x(), refresh.y(), refresh.w(), BUTTON_H, "live.refresh");
        String shown = font.plainSubstrByWidth(status, Math.max(0, refresh.x() - x - PAD * 2));
        graphics.text(font, shown, x + PAD, footerY() + 3, statusColor, false);

        super.extractRenderState(graphics, mouseX, mouseY, a);
        help.render(graphics, font, mouseX, mouseY, width, height);
    }
}
