package com.npcmaker.client;

import com.npcmaker.network.QuestNoticePayload;
import com.npcmaker.network.QuestSyncPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Quest overlays: the tracker (the one pinned quest, on the right edge below the reputation card) and the banner
 * ("Quest started", "Quest complete" with rewards…) near the top centre. Banners queue up and show one at a time.
 */
public final class QuestHud {

    /** Widest an objective line may get before it wraps, in pixels (about the size of a scoreboard). */
    private static final int TRACKER_MAX_TEXT = 110;
    private static final int INDENT = 8;
    private static final int PAD = 3;
    private static final int LINE = 9;
    /** Widest a banner line may get before it wraps, in pixels. */
    private static final int BANNER_MAX_TEXT = 150;
    private static final long BANNER_IN_MS = 250L;
    private static final long BANNER_MS = 4500L;
    private static final long BANNER_OUT_MS = 400L;

    private static final ArrayDeque<QuestNoticePayload> QUEUE = new ArrayDeque<>();
    private static QuestNoticePayload banner;
    private static long bannerStart;

    private QuestHud() {
    }

    public static void notice(QuestNoticePayload payload) {
        QUEUE.add(payload);
    }

    /** Drawn every frame by each loader's HUD layer. */
    public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!npcMakerScreenOpen(mc)) {
            ClientQuests.tracked().ifPresent(quest -> drawTracker(graphics, mc.font, quest));
        }
        drawBanner(graphics, mc.font);
    }

    /** The tracker steps aside while a conversation or another NPC Maker screen is open, so nothing overlaps. */
    private static boolean npcMakerScreenOpen(Minecraft mc) {
        var screen = mc.gui.screen();
        return screen instanceof DialogueScreen || screen instanceof QuestOfferScreen || screen instanceof QuestJournalScreen
                || screen instanceof RewardChoiceScreen || screen instanceof FactionsScreen;
    }

    // ---- tracker ------------------------------------------------------------------------------------------

    /**
     * Scoreboard-sized: a title row and one short line per objective (the step description stays in the journal),
     * only as wide as its text needs, up to {@link #TRACKER_MAX_TEXT} pixels.
     */
    private static void drawTracker(GuiGraphicsExtractor graphics, Font font, QuestSyncPayload.Quest quest) {
        List<FormattedCharSequence> title = font.split(quest.title(), TRACKER_MAX_TEXT + INDENT);
        List<Row> rows = new ArrayList<>();
        int textWidth = 0;
        for (FormattedCharSequence line : title) {
            textWidth = Math.max(textWidth, font.width(line));
        }
        for (QuestSyncPayload.Objective objective : quest.objectives()) {
            List<FormattedCharSequence> lines = font.split(objectiveText(objective), TRACKER_MAX_TEXT);
            rows.add(new Row(objective, lines));
            for (FormattedCharSequence line : lines) {
                textWidth = Math.max(textWidth, INDENT + font.width(line));
            }
        }

        int width = textWidth + PAD * 2;
        int titleHeight = title.size() * LINE + 2;
        int height = titleHeight + PAD;
        for (Row row : rows) {
            height += row.lines.size() * LINE;
        }
        height += PAD;

        int x = graphics.guiWidth() - width - 2;
        int y = Math.max(graphics.guiHeight() / 4, ReputationCard.bottom() + 4);
        graphics.fill(x, y, x + width, y + height, NpcUiTheme.PANEL_LIGHT);
        graphics.fill(x, y, x + width, y + titleHeight + 1, NpcUiTheme.PANEL);
        graphics.outline(x, y, width, height, NpcUiTheme.BORDER_DIM);

        int ty = y + 2;
        for (FormattedCharSequence line : title) {
            graphics.text(font, line, x + (width - font.width(line)) / 2, ty, NpcUiTheme.ACCENT, false);
            ty += LINE;
        }
        ty = y + titleHeight + PAD;
        for (Row row : rows) {
            boolean done = row.objective.done();
            graphics.text(font, done ? "✔" : "•", x + PAD, ty, done ? 0xFF7FD36A : NpcUiTheme.ACCENT, false);
            for (FormattedCharSequence line : row.lines) {
                graphics.text(font, line, x + PAD + INDENT, ty, done ? 0xFF8F8A80 : NpcUiTheme.TEXT, false);
                ty += LINE;
            }
        }
    }

    private record Row(QuestSyncPayload.Objective objective, List<FormattedCharSequence> lines) {
    }

    /** "Slay undead 2/5" (no counter for one-off objectives). */
    static Component objectiveText(QuestSyncPayload.Objective objective) {
        Component label = objective.label();
        if (objective.needed() <= 1) {
            return label;
        }
        return label.copy().append(Component.literal("  " + Math.min(objective.progress(), objective.needed()) + "/" + objective.needed()));
    }

    // ---- banner -------------------------------------------------------------------------------------------

    private static void drawBanner(GuiGraphicsExtractor graphics, Font font) {
        long now = Util.getMillis();
        if (banner != null && now - bannerStart > BANNER_MS + BANNER_OUT_MS) {
            banner = null;
        }
        if (banner == null) {
            banner = QUEUE.poll();
            if (banner == null) {
                return;
            }
            bannerStart = now;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(soundFor(banner.kind()), 1.0F, 0.9F));
        }
        float in = Mth.clamp((now - bannerStart) / (float) BANNER_IN_MS, 0.0F, 1.0F);
        float out = Mth.clamp((now - bannerStart - BANNER_MS) / (float) BANNER_OUT_MS, 0.0F, 1.0F);
        float shown = Math.min(in, 1.0F - out);
        if (shown <= 0.0F) {
            return;
        }

        Component heading = Component.literal(switch (banner.kind()) {
            case QuestNoticePayload.STARTED -> "Quest Started";
            case QuestNoticePayload.NEXT_STAGE -> "Quest Updated";
            case QuestNoticePayload.COMPLETED -> "Quest Complete";
            default -> "Quest Abandoned";
        });
        // Narrow: long text wraps onto more lines and the banner grows downward instead of sideways.
        int maxText = Math.min(BANNER_MAX_TEXT, graphics.guiWidth() - 40);
        List<FormattedCharSequence> title = font.split(banner.title(), maxText);
        List<FormattedCharSequence> body = new ArrayList<>();
        for (Component line : banner.lines()) {
            body.addAll(font.split(line, maxText));
        }
        int textWidth = font.width(heading);
        for (FormattedCharSequence line : title) {
            textWidth = Math.max(textWidth, font.width(line));
        }
        for (FormattedCharSequence line : body) {
            textWidth = Math.max(textWidth, font.width(line));
        }
        int width = textWidth + 30;
        int height = 8 + LINE + 2 + title.size() * LINE + (body.isEmpty() ? 0 : 4 + body.size() * LINE) + 7;
        int x = (graphics.guiWidth() - width) / 2;
        int y = Math.round(graphics.guiHeight() * 0.16F) - Math.round((1.0F - shown) * 12);

        int alpha = Math.round(shown * 255);
        if (alpha < 8) {
            return; // nearly transparent text can render fully opaque
        }
        graphics.fill(x, y, x + width, y + height, withAlpha(NpcUiTheme.PANEL, shown));
        graphics.outline(x, y, width, height, withAlpha(NpcUiTheme.BORDER, shown));
        int ty = y + 8;
        graphics.centeredText(font, heading, x + width / 2, ty, (alpha << 24) | (NpcUiTheme.ACCENT & 0xFFFFFF));
        ty += LINE + 2;
        for (FormattedCharSequence line : title) {
            graphics.text(font, line, x + (width - font.width(line)) / 2, ty, (alpha << 24) | 0xFFFFFF, true);
            ty += LINE;
        }
        if (!body.isEmpty()) {
            ty += 4;
            for (FormattedCharSequence line : body) {
                graphics.text(font, line, x + (width - font.width(line)) / 2, ty, (alpha << 24) | (NpcUiTheme.TEXT & 0xFFFFFF), true);
                ty += LINE;
            }
        }
    }

    private static int withAlpha(int argb, float factor) {
        int a = Math.round(((argb >>> 24) & 0xFF) * factor);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static SoundEvent soundFor(int kind) {
        return switch (kind) {
            case QuestNoticePayload.STARTED -> SoundEvents.BOOK_PAGE_TURN;
            case QuestNoticePayload.COMPLETED -> SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
            case QuestNoticePayload.NEXT_STAGE -> SoundEvents.PLAYER_LEVELUP;
            default -> SoundEvents.UI_TOAST_OUT;
        };
    }
}
