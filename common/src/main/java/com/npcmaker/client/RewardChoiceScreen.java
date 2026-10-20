package com.npcmaker.client;

import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.QuestActionPayload;
import com.npcmaker.network.RewardChoicePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/**
 * "Choose your reward": a finished quest's choose-one items as slots with tooltips. Click one, then Claim. It can't
 * be dismissed without choosing (the choice also waits across logins), so a reward is never lost by accident.
 */
public class RewardChoiceScreen extends Screen {

    private static final int SLOT = 24;
    private static final int GAP = 6;
    private static final int PAD = 12;
    private static final int BUTTON_W = 80;
    private static final int BUTTON_H = 16;

    private final RewardChoicePayload choice;
    private int selected = -1;

    private RewardChoiceScreen(RewardChoicePayload choice) {
        super(Component.literal("Choose Your Reward"));
        this.choice = choice;
    }

    public static void open(RewardChoicePayload choice) {
        Minecraft.getInstance().gui.setScreen(new RewardChoiceScreen(choice));
    }

    private int panelWidth() {
        int slots = choice.items().size() * (SLOT + GAP) - GAP;
        return Math.max(Math.max(220, slots + PAD * 2), font.width(choice.title()) + PAD * 2);
    }

    private int panelHeight() {
        return 12 + 12 + 12 + 10 + SLOT + 12 + BUTTON_H + PAD;
    }

    private int panelX() {
        return (width - panelWidth()) / 2;
    }

    private int panelY() {
        return (height - panelHeight()) / 2;
    }

    private int slotsY() {
        return panelY() + 12 + 12 + 12 + 10;
    }

    private int slotX(int index) {
        int slots = choice.items().size() * (SLOT + GAP) - GAP;
        return (width - slots) / 2 + index * (SLOT + GAP);
    }

    private int buttonX() {
        return (width - BUTTON_W) / 2;
    }

    private int buttonY() {
        return slotsY() + SLOT + 12;
    }

    private int slotAt(double mx, double my) {
        for (int i = 0; i < choice.items().size(); i++) {
            if (mx >= slotX(i) && mx < slotX(i) + SLOT && my >= slotsY() && my < slotsY() + SLOT) {
                return i;
            }
        }
        return -1;
    }

    private boolean overButton(double mx, double my) {
        return mx >= buttonX() && mx < buttonX() + BUTTON_W && my >= buttonY() && my < buttonY() + BUTTON_H;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int slot = slotAt(event.x(), event.y());
        if (slot >= 0) {
            selected = slot;
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            return true;
        }
        if (selected >= 0 && overButton(event.x(), event.y())) {
            NpcNetworking.sendToServer(new QuestActionPayload(choice.quest(), QuestActionPayload.CHOOSE_REWARD, selected));
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.0F));
            super.onClose();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int x = panelX();
        int y = panelY();
        int w = panelWidth();
        int h = panelHeight();
        graphics.fill(x, y, x + w, y + h, NpcUiTheme.PANEL);
        graphics.outline(x, y, w, h, NpcUiTheme.BORDER);
        graphics.centeredText(font, title, x + w / 2, y + 12, NpcUiTheme.ACCENT);
        graphics.centeredText(font, choice.title(), x + w / 2, y + 26, 0xFFFFFFFF);

        int hovered = slotAt(mouseX, mouseY);
        for (int i = 0; i < choice.items().size(); i++) {
            int sx = slotX(i);
            int sy = slotsY();
            ItemStack stack = choice.items().get(i);
            graphics.fill(sx, sy, sx + SLOT, sy + SLOT, NpcUiTheme.PANEL_INSET);
            int border = i == selected ? NpcUiTheme.BORDER : i == hovered ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.BORDER_DIM;
            graphics.outline(sx, sy, SLOT, SLOT, border);
            if (i == selected) {
                graphics.outline(sx - 1, sy - 1, SLOT + 2, SLOT + 2, NpcUiTheme.BORDER);
            }
            graphics.item(stack, sx + 4, sy + 4);
            graphics.itemDecorations(font, stack, sx + 4, sy + 4);
        }
        if (hovered >= 0) {
            graphics.setTooltipForNextFrame(font, choice.items().get(hovered), mouseX, mouseY);
        }

        boolean ready = selected >= 0;
        boolean hover = ready && overButton(mouseX, mouseY);
        graphics.fill(buttonX(), buttonY(), buttonX() + BUTTON_W, buttonY() + BUTTON_H, NpcUiTheme.PANEL_LIGHT);
        graphics.outline(buttonX(), buttonY(), BUTTON_W, BUTTON_H, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        graphics.centeredText(font, Component.literal(ready ? "Claim" : "Pick one"), buttonX() + BUTTON_W / 2, buttonY() + 4,
                !ready ? NpcUiTheme.BORDER_DIM : hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT);
    }
}
