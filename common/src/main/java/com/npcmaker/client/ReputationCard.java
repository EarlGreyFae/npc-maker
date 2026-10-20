package com.npcmaker.client;

import com.npcmaker.network.ReputationNoticePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * The reputation card: a small panel pinned to the top-right corner listing reputation changes, e.g.
 * "+2 Dawnguard  12". It's NPC Maker's own overlay rather than a vanilla toast, so it always appears in exactly the
 * same place (vanilla toasts get pushed down by recipe or tutorial pop-ups). New changes while it's up are added
 * to it (same faction lines merge) and keep it open a little longer.
 */
public final class ReputationCard {

    private static final long SLIDE_IN_MS = 200L;
    private static final long DISPLAY_MS = 4000L;
    private static final long SLIDE_OUT_MS = 300L;
    private static final int MARGIN = 4;
    private static final int TEXT_X = 10;
    private static final int LINE_HEIGHT = 11;
    private static final int MAX_LINES = 5;
    private static final int MIN_WIDTH = 160;

    private static final class Line {
        final Component faction;
        int delta;
        int now;
        Component standing;

        Line(ReputationNoticePayload.Entry entry) {
            this.faction = entry.faction();
            this.delta = entry.delta();
            this.now = entry.now();
            this.standing = entry.standing();
        }

        /** "+2 Dawnguard  Friendly 24" */
        Component text() {
            MutableComponent out = Component.literal((delta > 0 ? "+" : "") + delta + " ")
                    .withStyle(delta > 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
            out.append(faction.copy());
            out.append("  ");
            out.append(standing.copy());
            out.append(Component.literal(" " + now).withStyle(ChatFormatting.GRAY));
            return out;
        }
    }

    private static final List<Line> LINES = new ArrayList<>();
    private static long appearedAt;
    private static long lastChangedAt = -1L;

    private ReputationCard() {
    }

    public static void show(ReputationNoticePayload payload) {
        long now = Util.getMillis();
        if (!isShowing(now)) {
            LINES.clear();
            appearedAt = now;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, 1.0F, 0.6F));
        }
        for (ReputationNoticePayload.Entry entry : payload.entries()) {
            Line match = LINES.stream().filter(l -> l.faction.getString().equals(entry.faction().getString())).findFirst().orElse(null);
            if (match != null) {
                match.delta += entry.delta();
                match.now = entry.now();
                match.standing = entry.standing();
                LINES.remove(match);
                LINES.add(0, match);
            } else {
                LINES.add(0, new Line(entry));
            }
        }
        while (LINES.size() > MAX_LINES) {
            LINES.remove(LINES.size() - 1);
        }
        lastChangedAt = now;
    }

    /** Lowest screen row the card covers right now (0 when hidden), so the quest tracker can sit below it. */
    public static int bottom() {
        return isShowing(Util.getMillis()) && !LINES.isEmpty() ? MARGIN + 8 + LINE_HEIGHT * (LINES.size() + 1) + 4 : 0;
    }

    private static boolean isShowing(long now) {
        return lastChangedAt >= 0 && now - lastChangedAt < DISPLAY_MS + SLIDE_OUT_MS;
    }

    /** Drawn every frame by each loader's HUD layer. */
    public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        long now = Util.getMillis();
        if (!isShowing(now) || LINES.isEmpty()) {
            return;
        }
        float in = Mth.clamp((now - appearedAt) / (float) SLIDE_IN_MS, 0.0F, 1.0F);
        float out = Mth.clamp((now - lastChangedAt - DISPLAY_MS) / (float) SLIDE_OUT_MS, 0.0F, 1.0F);
        float shown = easeOut(Math.min(in, 1.0F - out));
        if (shown <= 0.0F) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int width = MIN_WIDTH;
        width = Math.max(width, font.width("Reputation") + TEXT_X * 2);
        for (Line line : LINES) {
            width = Math.max(width, font.width(line.text()) + TEXT_X * 2);
        }
        int height = 8 + LINE_HEIGHT * (LINES.size() + 1) + 4;
        int x = graphics.guiWidth() - Math.round((width + MARGIN) * shown);
        int y = MARGIN;

        graphics.fill(x, y, x + width, y + height, NpcUiTheme.PANEL);
        graphics.outline(x, y, width, height, NpcUiTheme.BORDER);
        graphics.text(font, Component.literal("Reputation"), x + TEXT_X, y + 7, NpcUiTheme.ACCENT, false);
        int ty = y + 7 + LINE_HEIGHT;
        for (Line line : LINES) {
            graphics.text(font, line.text(), x + TEXT_X, ty, 0xFFFFFFFF, false);
            ty += LINE_HEIGHT;
        }
    }

    private static float easeOut(float t) {
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv;
    }
}
