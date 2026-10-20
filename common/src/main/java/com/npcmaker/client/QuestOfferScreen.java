package com.npcmaker.client;

import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.QuestActionPayload;
import com.npcmaker.network.QuestOfferPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The quest offer card: title, who's asking, the story, the first step's objectives and the rewards (items as icons
 * with tooltips), with Accept and Decline. Esc declines. Sits on the left with the world undimmed, so the NPC stays in view.
 * Uses NpcUiTheme.
 */
public class QuestOfferScreen extends Screen {

    /** Narrow and on the left, so the NPC stays visible in the middle of the screen. */
    private static final int WIDTH = 190;
    private static final int MARGIN = 10;
    private static final int PAD = 8;
    private static final int LINE = 9;
    private static final int SLOT = 18;
    private static final int BUTTON_W = 76;
    private static final int BUTTON_H = 14;

    private final QuestOfferPayload offer;
    private boolean answered;

    /** Laid out in init() so the height fits the text. */
    private final List<Element> elements = new ArrayList<>();
    private int panelHeight;

    private sealed interface Element permits TextLine, ItemRow, Gap {
    }

    private record TextLine(FormattedCharSequence text, int color, boolean centered) implements Element {
    }

    private record ItemRow(List<ItemStack> items) implements Element {
    }

    private record Gap(int height) implements Element {
    }

    private QuestOfferScreen(QuestOfferPayload offer) {
        super(Component.literal("Quest Offered"));
        this.offer = offer;
    }

    public static void open(QuestOfferPayload offer) {
        Minecraft.getInstance().gui.setScreen(new QuestOfferScreen(offer));
    }

    @Override
    protected void init() {
        elements.clear();
        int w = WIDTH - PAD * 2;
        add(title, NpcUiTheme.ACCENT, true, w);
        elements.add(new Gap(2));
        add(offer.title(), 0xFFFFFFFF, true, w);
        if (!offer.giver().getString().isEmpty()) {
            add(Component.literal("From ").append(offer.giver()), 0xFFBFB3A0, true, w);
        }
        elements.add(new Gap(4));
        if (!offer.description().getString().isEmpty()) {
            add(offer.description(), NpcUiTheme.TEXT, false, w);
            elements.add(new Gap(4));
        }
        add(Component.literal(offer.stages() > 1 ? "First of " + offer.stages() + " steps" : "Objectives"), NpcUiTheme.ACCENT, false, w);
        if (!offer.stepText().getString().isEmpty()) {
            add(offer.stepText(), 0xFFBFB3A0, false, w);
        }
        for (Component objective : offer.objectives()) {
            add(Component.literal("• ").append(objective), NpcUiTheme.TEXT, false, w);
        }
        if (!offer.rewardItems().isEmpty() || !offer.rewardLines().isEmpty()) {
            elements.add(new Gap(4));
            add(Component.literal("Rewards"), NpcUiTheme.ACCENT, false, w);
            if (!offer.rewardItems().isEmpty()) {
                elements.add(new ItemRow(offer.rewardItems()));
            }
            for (Component line : offer.rewardLines()) {
                add(line, NpcUiTheme.TEXT, false, w);
            }
        }
        if (!offer.chooseOne().isEmpty()) {
            elements.add(new Gap(4));
            add(Component.literal("Choose one of"), NpcUiTheme.ACCENT, false, w);
            elements.add(new ItemRow(offer.chooseOne()));
        }
        panelHeight = PAD + elements.stream().mapToInt(QuestOfferScreen::heightOf).sum() + 6 + BUTTON_H + PAD;
    }

    private void add(Component text, int color, boolean centered, int width) {
        for (FormattedCharSequence line : font.split(text, width)) {
            elements.add(new TextLine(line, color, centered));
        }
    }

    private static int heightOf(Element element) {
        return switch (element) {
            case TextLine t -> LINE;
            case ItemRow r -> SLOT + 2;
            case Gap g -> g.height();
        };
    }

    private int panelX() {
        return MARGIN;
    }

    private int panelY() {
        return Math.max(4, (height - panelHeight) / 2);
    }

    private int buttonY() {
        return panelY() + panelHeight - PAD - BUTTON_H;
    }

    private int acceptX() {
        return panelX() + PAD;
    }

    private int declineX() {
        return panelX() + WIDTH - PAD - BUTTON_W;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void answer(boolean accept) {
        if (!answered) {
            answered = true;
            NpcNetworking.sendToServer(new QuestActionPayload(offer.quest(), accept ? QuestActionPayload.ACCEPT : QuestActionPayload.DECLINE));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (inside(event.x(), event.y(), acceptX(), buttonY(), BUTTON_W, BUTTON_H)) {
            answer(true);
            super.onClose();
            return true;
        }
        if (inside(event.x(), event.y(), declineX(), buttonY(), BUTTON_W, BUTTON_H)) {
            onClose();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        answer(false);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // No dimming or blur, so the NPC offering the quest stays visible next to the card.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int x = panelX();
        int y = panelY();
        graphics.fill(x, y, x + WIDTH, y + panelHeight, NpcUiTheme.PANEL);
        graphics.outline(x, y, WIDTH, panelHeight, NpcUiTheme.BORDER);

        int ty = y + PAD;
        ItemStack hovered = null;
        for (Element element : elements) {
            switch (element) {
                case TextLine t -> {
                    int tx = t.centered() ? x + (WIDTH - font.width(t.text())) / 2 : x + PAD;
                    graphics.text(font, t.text(), tx, ty, t.color(), false);
                }
                case ItemRow r -> {
                    for (int i = 0; i < r.items().size(); i++) {
                        int sx = x + PAD + i * (SLOT + 3);
                        ItemStack stack = r.items().get(i);
                        graphics.fill(sx, ty, sx + SLOT, ty + SLOT, NpcUiTheme.PANEL_INSET);
                        graphics.outline(sx, ty, SLOT, SLOT, NpcUiTheme.BORDER_DIM);
                        graphics.item(stack, sx + 1, ty + 1);
                        graphics.itemDecorations(font, stack, sx + 1, ty + 1);
                        if (inside(mouseX, mouseY, sx, ty, SLOT, SLOT)) {
                            hovered = stack;
                        }
                    }
                }
                case Gap g -> {
                }
            }
            ty += heightOf(element);
        }

        drawButton(graphics, acceptX(), "Accept", mouseX, mouseY);
        drawButton(graphics, declineX(), "Decline", mouseX, mouseY);
        if (hovered != null) {
            graphics.setTooltipForNextFrame(font, hovered, mouseX, mouseY);
        }
    }

    private void drawButton(GuiGraphicsExtractor graphics, int bx, String label, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, bx, buttonY(), BUTTON_W, BUTTON_H);
        graphics.fill(bx, buttonY(), bx + BUTTON_W, buttonY() + BUTTON_H, NpcUiTheme.PANEL_LIGHT);
        graphics.outline(bx, buttonY(), BUTTON_W, BUTTON_H, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        graphics.centeredText(font, Component.literal(label), bx + BUTTON_W / 2, buttonY() + 3,
                hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT);
    }
}
