package com.npcmaker.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Help for the editor screens. Every part of a screen registers a "spot" (its box and a help key) while it draws:
 * <ul>
 *   <li>Hovering a spot for a moment shows a short tooltip (and what the current choice does, for choices).</li>
 *   <li>The (?) circle in the screen's corner turns on help mode: every spot gets a gold outline, and clicking one
 *       opens a card with the full explanation. Click (?) again or press Esc to leave.</li>
 * </ul>
 * Text lives in the language file: {@code npcmaker.help.<key>} (the short line) and {@code npcmaker.help.<key>.more}
 * (the card's explanation; optional). A choice's current value adds {@code npcmaker.help.<valueKey>} below. Uses
 * NpcUiTheme.
 */
public final class HelpLayer {

    private static final int BUTTON = 12;
    private static final long HOVER_DELAY_MS = 350L;
    private static final int TIP_W = 200;
    private static final int CARD_W = 240;

    private record Spot(int x, int y, int w, int h, String key, @Nullable String valueKey) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Spot> spots = new ArrayList<>();
    private boolean active;
    private @Nullable Spot card;
    private @Nullable Spot hovered;
    private long hoverSince;
    private int buttonX = -100;
    private int buttonY = -100;

    private boolean quiet;

    /** Call at the start of each frame, before the screen registers its spots. */
    public void begin() {
        spots.clear();
        quiet = false;
    }

    /** Skip the hover tooltip this frame (another tooltip, like an item's, is showing). */
    public void quiet() {
        quiet = true;
    }

    /** Just the (?) circle, for screens whose hover tooltips are already their own (item names). */
    public void renderButtonOnly(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        graphics.nextStratum();
        drawButton(graphics, font, mouseX, mouseY);
    }

    /** A part of the screen with help text. */
    public void spot(int x, int y, int w, int h, String key) {
        spot(x, y, w, h, key, null);
    }

    /** @param valueKey help for the choice currently shown (e.g. {@code field.movement.wander}), or null */
    public void spot(int x, int y, int w, int h, String key, @Nullable String valueKey) {
        if (w > 0 && h > 0 && has("npcmaker.help." + key)) {
            spots.add(new Spot(x, y, w, h, key, valueKey));
        }
    }

    /** Where the (?) circle goes (its top-left corner). */
    public void button(int x, int y) {
        buttonX = x;
        buttonY = y;
    }

    public boolean active() {
        return active;
    }

    private boolean onButton(double mx, double my) {
        return mx >= buttonX && mx < buttonX + BUTTON && my >= buttonY && my < buttonY + BUTTON;
    }

    /** The smallest spot under the mouse (so a field inside a panel wins over the panel). */
    private @Nullable Spot spotAt(double mx, double my) {
        Spot best = null;
        for (Spot s : spots) {
            if (s.contains(mx, my) && (best == null || s.w() * s.h() < best.w() * best.h())) {
                best = s;
            }
        }
        return best;
    }

    /**
     * Call first in the screen's mouseClicked. The (?) toggles help mode; in help mode every click is used (to open
     * or close a card) so nothing gets changed by accident.
     */
    public boolean click(double mx, double my) {
        if (onButton(mx, my)) {
            active = !active;
            card = null;
            return true;
        }
        if (!active) {
            return false;
        }
        Spot s = spotAt(mx, my);
        card = s != null && !s.equals(card) ? s : null;
        return true;
    }

    /** Call first in the screen's keyPressed with whether the key was Escape: closes the card, then help mode. */
    public boolean escape() {
        if (card != null) {
            card = null;
            return true;
        }
        if (active) {
            active = false;
            return true;
        }
        return false;
    }

    private static boolean has(String key) {
        return Language.getInstance().has(key);
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    /** Call last in the screen's render, after everything else (it draws above it all). */
    public void render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, int screenW, int screenH) {
        graphics.nextStratum();
        drawButton(graphics, font, mouseX, mouseY);
        Spot under = spotAt(mouseX, mouseY);
        if (active) {
            for (Spot s : spots) {
                int color = s.equals(card) ? 0xFFFFE08A : s.equals(under) ? NpcUiTheme.BORDER : 0x80D4AF37;
                graphics.outline(s.x() - 1, s.y() - 1, s.w() + 2, s.h() + 2, color);
            }
            // No banner (it covered the screen's header); the hints follow the mouse instead.
            if (card != null) {
                drawCard(graphics, font, card, screenW, screenH);
            } else if (onButton(mouseX, mouseY)) {
                drawTip(graphics, font, List.of(Component.literal("Help mode is on: click any outlined part to learn about it."),
                        Component.literal("Click (?) or press Esc to leave.").withColor(0xFF6E6578)), mouseX, mouseY, screenW, screenH);
            } else if (under != null) {
                drawTip(graphics, font, List.of(Component.translatable("npcmaker.help." + under.key()),
                        Component.literal("Click for the full explanation").withColor(0xFF6E6578)), mouseX, mouseY, screenW, screenH);
            }
            return;
        }
        // Spots are rebuilt every frame, so compare by value (records), not by identity.
        if (!java.util.Objects.equals(under, hovered)) {
            hovered = under;
            hoverSince = Util.getMillis();
        }
        if (quiet) {
            return;
        }
        if (onButton(mouseX, mouseY)) {
            drawTip(graphics, font, List.of(Component.literal("Help: click, then click any part of this screen to learn what it does.")),
                    mouseX, mouseY, screenW, screenH);
        } else if (hovered != null && Util.getMillis() - hoverSince >= HOVER_DELAY_MS) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("npcmaker.help." + hovered.key()));
            if (hovered.valueKey() != null && has("npcmaker.help." + hovered.valueKey())) {
                lines.add(Component.translatable("npcmaker.help." + hovered.valueKey()).withColor(0xFFE8C46A));
            }
            if (has("npcmaker.help." + hovered.key() + ".more")) {
                lines.add(Component.literal("(?) for more").withColor(0xFF6E6578));
            }
            drawTip(graphics, font, lines, mouseX, mouseY, screenW, screenH);
        }
    }

    private void drawButton(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        boolean hover = onButton(mouseX, mouseY);
        int x = buttonX;
        int y = buttonY;
        int fill = active ? 0xFF3A2A12 : NpcUiTheme.PANEL_LIGHT;
        int edge = active || hover ? 0xFFFFE08A : NpcUiTheme.BORDER;
        // a small circle: rows trimmed at the corners
        graphics.fill(x + 3, y, x + BUTTON - 3, y + BUTTON, edge);
        graphics.fill(x + 1, y + 1, x + BUTTON - 1, y + BUTTON - 1, edge);
        graphics.fill(x, y + 3, x + BUTTON, y + BUTTON - 3, edge);
        graphics.fill(x + 3, y + 1, x + BUTTON - 3, y + BUTTON - 1, fill);
        graphics.fill(x + 2, y + 2, x + BUTTON - 2, y + BUTTON - 2, fill);
        graphics.fill(x + 1, y + 3, x + BUTTON - 1, y + BUTTON - 3, fill);
        graphics.text(font, "?", x + (BUTTON - font.width("?")) / 2 + 1, y + 2, edge, false);
    }

    private static void drawTip(GuiGraphicsExtractor graphics, Font font, List<Component> lines, int mouseX, int mouseY,
                                int screenW, int screenH) {
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component line : lines) {
            wrapped.addAll(font.split(line, TIP_W));
        }
        int w = 0;
        for (FormattedCharSequence s : wrapped) {
            w = Math.max(w, font.width(s));
        }
        int h = wrapped.size() * 10;
        int x = mouseX + 10;
        int y = mouseY + 10;
        if (x + w + 8 > screenW) {
            x = Math.max(2, mouseX - w - 14);
        }
        if (y + h + 8 > screenH) {
            y = Math.max(2, mouseY - h - 12);
        }
        panel(graphics, x, y, w + 8, h + 6, NpcUiTheme.BORDER);
        for (int i = 0; i < wrapped.size(); i++) {
            graphics.text(font, wrapped.get(i), x + 4, y + 4 + i * 10, NpcUiTheme.TEXT, false);
        }
    }

    private static void drawCard(GuiGraphicsExtractor graphics, Font font, Spot spot, int screenW, int screenH) {
        List<FormattedCharSequence> heading = font.split(Component.translatable("npcmaker.help." + spot.key()), CARD_W);
        List<FormattedCharSequence> body = new ArrayList<>();
        String moreKey = "npcmaker.help." + spot.key() + ".more";
        if (has(moreKey)) {
            for (String para : I18n.get(moreKey).split("\n")) {
                body.addAll(para.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : font.split(Component.literal(para), CARD_W));
            }
        }
        List<FormattedCharSequence> value = spot.valueKey() != null && has("npcmaker.help." + spot.valueKey())
                ? font.split(Component.literal("Now: ").append(Component.translatable("npcmaker.help." + spot.valueKey())), CARD_W)
                : List.of();
        int lines = heading.size() + body.size() + value.size() + (body.isEmpty() ? 0 : 1) + (value.isEmpty() ? 0 : 1);
        int w = CARD_W + 12;
        int h = lines * 10 + 10;
        int x = Math.min(Math.max(4, spot.x()), screenW - w - 4);
        int y = spot.y() + spot.h() + 4;
        if (y + h > screenH - 4) {
            y = Math.max(4, spot.y() - h - 4);
        }
        panel(graphics, x, y, w, h, 0xFFFFE08A);
        int ty = y + 6;
        for (FormattedCharSequence s : heading) {
            graphics.text(font, s, x + 6, ty, NpcUiTheme.ACCENT, false);
            ty += 10;
        }
        if (!value.isEmpty()) {
            ty += 4;
            for (FormattedCharSequence s : value) {
                graphics.text(font, s, x + 6, ty, 0xFFE8C46A, false);
                ty += 10;
            }
        }
        if (!body.isEmpty()) {
            ty += 4;
            for (FormattedCharSequence s : body) {
                graphics.text(font, s, x + 6, ty, NpcUiTheme.TEXT, false);
                ty += 10;
            }
        }
    }

    private static void panel(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int border) {
        graphics.fill(x, y, x + w, y + h, 0xF8140A1E);
        graphics.outline(x, y, w, h, border);
    }
}
