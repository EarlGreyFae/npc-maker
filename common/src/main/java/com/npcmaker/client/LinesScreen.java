package com.npcmaker.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Edits a list of lines an NPC says (idle lines, lines on approach): one box per line, ✖ to remove, + to add. Done
 * hands the lines back (blank ones dropped); Cancel leaves them as they were. Uses NpcUiTheme.
 */
public class LinesScreen extends Screen {

    private static final int PAD = 8;
    private static final int ROW = 18;
    private static final int BUTTON_H = 14;

    private final Screen parent;
    private final Consumer<List<String>> onDone;
    private final List<String> lines;
    /** Hover tooltips and the (?) help mode. */
    private final HelpLayer help = new HelpLayer();

    public LinesScreen(Screen parent, String title, List<String> lines, Consumer<List<String>> onDone) {
        super(Component.literal(title));
        this.parent = parent;
        this.onDone = onDone;
        this.lines = new ArrayList<>(lines);
    }

    private int maxLines() {
        return Math.max(1, Math.min(12, (height - 16 - 70) / ROW));
    }

    private int panelW() {
        return Math.min(340, width - 16);
    }

    private int panelH() {
        return 26 + maxLines() * ROW + 4 + BUTTON_H + 12 + BUTTON_H + PAD;
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelY() {
        return (height - panelH()) / 2;
    }

    private int lineY(int i) {
        return panelY() + 26 + i * ROW;
    }

    private int addY() {
        return lineY(lines.size()) + 2;
    }

    private int buttonsY() {
        return panelY() + panelH() - PAD - BUTTON_H;
    }

    @Override
    protected void init() {
        while (lines.size() > maxLines()) {
            lines.remove(lines.size() - 1);
        }
        int x = panelX() + PAD;
        int w = panelW() - PAD * 2 - 16;
        for (int i = 0; i < lines.size(); i++) {
            int index = i;
            EditBox box = new EditBox(font, x + 3, lineY(i) + 4, w - 6, 10, Component.empty());
            box.setBordered(false);
            box.setMaxLength(256);
            box.setTextColor(NpcUiTheme.TEXT);
            box.setHint(Component.literal("Something the NPC says…").withColor(0xFF6E6578));
            box.setValue(lines.get(i));
            box.setResponder(s -> lines.set(index, s));
            addRenderableWidget(box);
        }
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int doneW() {
        return font.width("Done") + 18;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (help.click(mx, my)) {
            return true;
        }
        int x = panelX() + PAD;
        int w = panelW() - PAD * 2;
        for (int i = 0; i < lines.size(); i++) {
            if (inside(mx, my, x + w - 12, lineY(i) + 2, 12, 14)) {
                lines.remove(i);
                rebuildWidgets();
                return true;
            }
        }
        if (lines.size() < maxLines() && inside(mx, my, x, addY(), w, BUTTON_H)) {
            lines.add("");
            rebuildWidgets();
            return true;
        }
        if (inside(mx, my, x, buttonsY(), doneW(), BUTTON_H)) {
            List<String> kept = lines.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
            minecraft.gui.setScreen(parent);
            onDone.accept(kept);
            return true;
        }
        int cancelX = x + doneW() + 5;
        if (inside(mx, my, cancelX, buttonsY(), font.width("Cancel") + 14, BUTTON_H)) {
            onClose();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int x = panelX();
        int y = panelY();
        int w = panelW();
        graphics.fill(x, y, x + w, y + panelH(), NpcUiTheme.PANEL);
        graphics.outline(x, y, w, panelH(), NpcUiTheme.BORDER);
        help.begin();
        help.button(x + w - PAD - 12, y + 7);
        graphics.text(font, font.plainSubstrByWidth(title.getString(), w - PAD * 2 - 16), x + PAD, y + 9, NpcUiTheme.ACCENT, false);
        int lx = x + PAD;
        int lw = w - PAD * 2;
        help.spot(lx, addY(), lw, BUTTON_H, "lines.add");
        for (int i = 0; i < lines.size(); i++) {
            help.spot(lx, lineY(i), lw - 16, ROW - 2, "lines.line");
            help.spot(lx + lw - 12, lineY(i) + 2, 12, 14, "lines.delete");
            graphics.fill(lx, lineY(i), lx + lw - 16, lineY(i) + ROW - 2, NpcUiTheme.PANEL_INSET);
            graphics.outline(lx, lineY(i), lw - 16, ROW - 2, NpcUiTheme.BORDER_DIM);
            boolean overX = inside(mouseX, mouseY, lx + lw - 12, lineY(i) + 2, 12, 14);
            graphics.text(font, "✖", lx + lw - 10, lineY(i) + 4, overX ? 0xFFE04848 : 0xFF8F8A80, false);
        }
        boolean canAdd = lines.size() < maxLines();
        boolean hoverAdd = canAdd && inside(mouseX, mouseY, lx, addY(), lw, BUTTON_H);
        graphics.fill(lx, addY(), lx + lw, addY() + BUTTON_H, NpcUiTheme.PANEL_LIGHT);
        graphics.outline(lx, addY(), lw, BUTTON_H, hoverAdd ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        graphics.centeredText(font, Component.literal("+ Add a line"), lx + lw / 2, addY() + 3,
                !canAdd ? 0xFF6E6578 : hoverAdd ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT);
        String tip = "One is picked at random. <i>italic</i> and other tags work.";
        graphics.text(font, font.plainSubstrByWidth(tip, lw), lx, addY() + BUTTON_H + 3, 0xFF6E6578, false);

        drawButton(graphics, lx, doneW(), "Done", mouseX, mouseY);
        drawButton(graphics, lx + doneW() + 5, font.width("Cancel") + 14, "Cancel", mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, a);
        help.render(graphics, font, mouseX, mouseY, width, height);
    }

    private void drawButton(GuiGraphicsExtractor graphics, int bx, int bw, String label, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, bx, buttonsY(), bw, BUTTON_H);
        graphics.fill(bx, buttonsY(), bx + bw, buttonsY() + BUTTON_H, NpcUiTheme.PANEL_LIGHT);
        graphics.outline(bx, buttonsY(), bw, BUTTON_H, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        graphics.centeredText(font, Component.literal(label), bx + bw / 2, buttonsY() + 3, hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT);
    }
}
