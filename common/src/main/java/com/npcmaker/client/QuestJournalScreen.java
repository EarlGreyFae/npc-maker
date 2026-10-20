package com.npcmaker.client;

import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.QuestActionPayload;
import com.npcmaker.network.QuestSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.List;
import java.util.Optional;

/**
 * The quest journal (K by default): active quests on the left (◆ marks the one on the HUD) with finished ones
 * below, and the chosen quest's details on the right: who gave it, the story, the current step and its objectives,
 * plus Track / Abandon buttons. Uses NpcUiTheme.
 */
public class QuestJournalScreen extends Screen {

    private static final int MAX_WIDTH = 400;
    private static final int MAX_HEIGHT = 250;
    private static final int LIST_WIDTH = 132;
    private static final int ROW = 14;
    private static final int PAD = 10;
    private static final int LINE = 10;
    private static final int BUTTON_HEIGHT = 16;
    private static final long CONFIRM_MS = 3000L;

    private String selected = "";
    private int listScroll;
    private int detailScroll;
    private long abandonArmedAt = -1L;

    private QuestJournalScreen() {
        super(Component.literal("Quest Journal"));
    }

    public static void open() {
        Minecraft.getInstance().gui.setScreen(new QuestJournalScreen());
    }

    @Override
    protected void init() {
        if (selectedQuest().isEmpty()) {
            selected = ClientQuests.tracked().or(() -> ClientQuests.get().active().stream().findFirst())
                    .map(QuestSyncPayload.Quest::id).orElse("");
        }
    }

    /** The server sent new quest data while the journal is open. */
    void refresh() {
        if (selectedQuest().isEmpty()) {
            selected = ClientQuests.get().active().stream().findFirst().map(QuestSyncPayload.Quest::id).orElse("");
            detailScroll = 0;
        }
        abandonArmedAt = -1L;
    }

    private Optional<QuestSyncPayload.Quest> selectedQuest() {
        return ClientQuests.find(selected);
    }

    // ---- layout -------------------------------------------------------------------------------------------

    private int panelWidth() {
        return Math.min(MAX_WIDTH, width - 20);
    }

    private int panelHeight() {
        return Math.min(MAX_HEIGHT, height - 20);
    }

    private int panelX() {
        return (width - panelWidth()) / 2;
    }

    private int panelY() {
        return (height - panelHeight()) / 2;
    }

    private int listTop() {
        return panelY() + 28;
    }

    private int listBottom() {
        return panelY() + panelHeight() - PAD;
    }

    private int detailX() {
        return panelX() + PAD + LIST_WIDTH + PAD;
    }

    private int detailWidth() {
        return panelX() + panelWidth() - PAD - detailX();
    }

    private int buttonY() {
        return panelY() + panelHeight() - PAD - BUTTON_HEIGHT;
    }

    /** Rows in the left column: active quests, then a "Finished" heading and finished titles. */
    private List<Object> listRows() {
        QuestSyncPayload data = ClientQuests.get();
        List<Object> rows = new java.util.ArrayList<>(data.active());
        if (!data.completed().isEmpty()) {
            rows.add("Finished (" + data.completed().size() + ")");
            rows.addAll(data.completed());
        }
        return rows;
    }

    private int visibleListRows() {
        return Math.max(1, (listBottom() - listTop()) / ROW);
    }

    // ---- buttons ------------------------------------------------------------------------------------------

    private record Button(int x, int y, int w, Component label) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + BUTTON_HEIGHT;
        }
    }

    private Optional<Button> trackButton(QuestSyncPayload.Quest quest) {
        boolean tracked = quest.id().equals(ClientQuests.get().tracked());
        Component label = Component.literal(tracked ? "Untrack" : "Track on screen");
        return Optional.of(new Button(detailX(), buttonY(), font.width(label) + 16, label));
    }

    private Optional<Button> abandonButton(QuestSyncPayload.Quest quest) {
        if (!quest.abandonable()) {
            return Optional.empty();
        }
        Component label = Component.literal(abandonArmed() ? "Click again to abandon" : "Abandon");
        int w = font.width(label) + 16;
        return Optional.of(new Button(detailX() + detailWidth() - w, buttonY(), w, label));
    }

    private boolean abandonArmed() {
        return abandonArmedAt >= 0 && Util.getMillis() - abandonArmedAt < CONFIRM_MS;
    }

    // ---- input --------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        Optional<QuestSyncPayload.Quest> quest = selectedQuest();
        if (quest.isPresent()) {
            Optional<Button> track = trackButton(quest.get());
            if (track.isPresent() && track.get().contains(mx, my)) {
                boolean tracked = quest.get().id().equals(ClientQuests.get().tracked());
                NpcNetworking.sendToServer(new QuestActionPayload(quest.get().id(),
                        tracked ? QuestActionPayload.UNTRACK : QuestActionPayload.TRACK));
                return true;
            }
            Optional<Button> abandon = abandonButton(quest.get());
            if (abandon.isPresent() && abandon.get().contains(mx, my)) {
                if (abandonArmed()) {
                    NpcNetworking.sendToServer(new QuestActionPayload(quest.get().id(), QuestActionPayload.ABANDON));
                    abandonArmedAt = -1L;
                } else {
                    abandonArmedAt = Util.getMillis();
                }
                return true;
            }
        }
        int listX = panelX() + PAD;
        if (mx >= listX && mx < listX + LIST_WIDTH && my >= listTop() && my < listBottom()) {
            int index = listScroll + (int) ((my - listTop()) / ROW);
            List<Object> rows = listRows();
            if (index < rows.size() && rows.get(index) instanceof QuestSyncPayload.Quest q) {
                selected = q.id();
                detailScroll = 0;
                abandonArmedAt = -1L;
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (x < detailX()) {
            listScroll = Mth.clamp(listScroll - (int) Math.signum(scrollY), 0, Math.max(0, listRows().size() - visibleListRows()));
        } else {
            detailScroll = Math.max(0, detailScroll - (int) Math.signum(scrollY) * LINE * 2);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (NpcKeys.QUESTS.matches(event)) {
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
        int w = panelWidth();
        int h = panelHeight();
        graphics.fill(x, y, x + w, y + h, NpcUiTheme.PANEL);
        graphics.outline(x, y, w, h, NpcUiTheme.BORDER);
        graphics.centeredText(font, title, x + w / 2, y + 10, NpcUiTheme.ACCENT);
        // divider between the list and the details
        graphics.fill(detailX() - PAD / 2 - 1, listTop(), detailX() - PAD / 2, listBottom(), NpcUiTheme.BORDER_DIM);

        drawList(graphics, mouseX, mouseY);
        Optional<QuestSyncPayload.Quest> quest = selectedQuest();
        if (quest.isPresent()) {
            drawDetails(graphics, quest.get(), mouseX, mouseY);
        } else {
            int cx = detailX() + detailWidth() / 2;
            graphics.centeredText(font, Component.literal("No active quests."), cx, listTop() + 30, NpcUiTheme.TEXT);
            graphics.centeredText(font, Component.literal("Talk to people to find work."), cx, listTop() + 44, NpcUiTheme.BORDER_DIM);
        }
        graphics.text(font, "K or Esc to close", x + w - PAD - font.width("K or Esc to close"), y + 10, NpcUiTheme.BORDER_DIM, false);
    }

    private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<Object> rows = listRows();
        int x = panelX() + PAD;
        int ry = listTop();
        String tracked = ClientQuests.get().tracked();
        int end = Math.min(rows.size(), listScroll + visibleListRows());
        for (int i = listScroll; i < end; i++) {
            Object row = rows.get(i);
            if (row instanceof QuestSyncPayload.Quest quest) {
                boolean isSelected = quest.id().equals(selected);
                boolean hover = mouseX >= x && mouseX < x + LIST_WIDTH && mouseY >= ry && mouseY < ry + ROW;
                if (isSelected || hover) {
                    graphics.fill(x, ry, x + LIST_WIDTH, ry + ROW - 1, NpcUiTheme.PANEL_INSET);
                    graphics.outline(x, ry, LIST_WIDTH, ROW - 1, isSelected ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
                }
                String marker = quest.id().equals(tracked) ? "◆ " : "  ";
                graphics.text(font, marker, x + 3, ry + 3, NpcUiTheme.ACCENT, false);
                FormattedCharSequence name = fit(quest.title(), LIST_WIDTH - 16);
                graphics.text(font, name, x + 13, ry + 3, isSelected ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT, false);
            } else if (row instanceof String heading) {
                graphics.text(font, heading, x + 3, ry + 4, NpcUiTheme.ACCENT, false);
            } else if (row instanceof Component finished) {
                graphics.text(font, fit(Component.literal("✔ ").append(finished.getString()), LIST_WIDTH - 6), x + 3, ry + 3, 0xFF8F8A80, false);
            }
            ry += ROW;
        }
        if (rows.isEmpty()) {
            graphics.text(font, "Nothing yet.", x + 3, ry + 3, NpcUiTheme.BORDER_DIM, false);
        }
    }

    private FormattedCharSequence fit(Component text, int width) {
        List<FormattedCharSequence> lines = font.split(text, width);
        return lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.get(0);
    }

    private void drawDetails(GuiGraphicsExtractor graphics, QuestSyncPayload.Quest quest, int mouseX, int mouseY) {
        int x = detailX();
        int w = detailWidth();
        int top = listTop();
        int bottom = buttonY() - 6;
        // Everything above the buttons scrolls; clip by skipping lines outside the area.
        int ty = top - detailScroll;
        ty = lines(graphics, font.split(quest.title(), w), x, ty, top, bottom, NpcUiTheme.ACCENT);
        if (!quest.giver().getString().isEmpty()) {
            ty = lines(graphics, font.split(Component.literal("From ").append(quest.giver()), w), x, ty, top, bottom, 0xFFBFB3A0);
        }
        ty += 4;
        if (!quest.description().getString().isEmpty()) {
            ty = lines(graphics, font.split(quest.description(), w), x, ty, top, bottom, NpcUiTheme.TEXT);
            ty += 6;
        }
        String step = quest.stages() > 1 ? "Step " + (quest.stage() + 1) + " of " + quest.stages() : "Objectives";
        ty = lines(graphics, font.split(Component.literal(step), w), x, ty, top, bottom, NpcUiTheme.ACCENT);
        if (!quest.stageText().getString().isEmpty()) {
            ty = lines(graphics, font.split(quest.stageText(), w), x, ty, top, bottom, 0xFFBFB3A0);
        }
        ty += 2;
        for (QuestSyncPayload.Objective objective : quest.objectives()) {
            boolean done = objective.done();
            if (ty >= top && ty + LINE <= bottom) {
                graphics.text(font, done ? "✔" : "•", x, ty, done ? 0xFF7FD36A : NpcUiTheme.ACCENT, false);
            }
            ty = lines(graphics, font.split(QuestHud.objectiveText(objective), w - 10), x + 10, ty, top, bottom,
                    done ? 0xFF8F8A80 : NpcUiTheme.TEXT);
        }
        // keep the scroll from running past the end
        int contentHeight = ty + detailScroll - top;
        detailScroll = Mth.clamp(detailScroll, 0, Math.max(0, contentHeight - (bottom - top)));

        trackButton(quest).ifPresent(b -> drawButton(graphics, b, mouseX, mouseY));
        abandonButton(quest).ifPresent(b -> drawButton(graphics, b, mouseX, mouseY));
    }

    private int lines(GuiGraphicsExtractor graphics, List<FormattedCharSequence> lines, int x, int ty, int top, int bottom, int color) {
        for (FormattedCharSequence line : lines) {
            if (ty >= top && ty + LINE <= bottom) {
                graphics.text(font, line, x, ty, color, false);
            }
            ty += LINE;
        }
        return ty;
    }

    private void drawButton(GuiGraphicsExtractor graphics, Button b, int mouseX, int mouseY) {
        boolean hover = b.contains(mouseX, mouseY);
        graphics.fill(b.x, b.y, b.x + b.w, b.y + BUTTON_HEIGHT, NpcUiTheme.PANEL_LIGHT);
        graphics.outline(b.x, b.y, b.w, BUTTON_HEIGHT, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        graphics.centeredText(font, b.label, b.x + b.w / 2, b.y + 4, hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT);
    }
}
