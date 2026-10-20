package com.npcmaker.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.npcmaker.data.Voice;
import com.npcmaker.network.DialogueChoicePayload;
import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.OpenDialoguePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The on-screen dialogue box: a bottom RPG-style panel with the NPC's name in its colour, text that types out
 * with soft blips, and the player's replies as compact rows above the box's right edge. While it's open the camera
 * eases round to face the NPC, framing its face in the upper middle of the screen, clear of the box and replies.
 */
public class DialogueScreen extends Screen {

    private static final int BOX_HEIGHT = 84;
    private static final int PADDING = 10;
    private static final int REPLY_HEIGHT = 14;
    private static final int REPLY_GAP = 2;
    private static final int REPLY_PADDING = 6;
    /** How far below the NPC's eyes the camera aims, as a fraction of its height (0.28 ≈ half a block). */
    private static final float FRAME_DROP = 0.28F;
    private static final float CHARS_PER_TICK = 1.5F;
    private static final int BLIP_EVERY = 3;

    private static final int COLOR_BOX = NpcUiTheme.PANEL;
    private static final int COLOR_BORDER = NpcUiTheme.BORDER;
    private static final int COLOR_TEXT = NpcUiTheme.TEXT;
    private static final int COLOR_HINT = NpcUiTheme.ACCENT;
    private static final int COLOR_REPLY = NpcUiTheme.PANEL_LIGHT;
    private static final int COLOR_REPLY_BORDER = NpcUiTheme.BORDER_DIM;
    private static final int COLOR_REPLY_HOVER_TEXT = NpcUiTheme.TEXT_HOVER;

    private OpenDialoguePayload content;
    private int page;
    private float revealed;
    private int lastBlipAt;
    private boolean waitingForServer;
    private long lastFrameMillis = Util.getMillis();
    private final List<ReplyRow> replyRows = new ArrayList<>();

    private DialogueScreen(OpenDialoguePayload content) {
        super(content.name());
        this.content = content;
    }

    /** Opens the box, or moves an already open conversation with the same NPC on to the new node. */
    public static void open(OpenDialoguePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof DialogueScreen screen && screen.content.entityId() == payload.entityId()) {
            screen.show(payload);
        } else {
            mc.gui.setScreen(new DialogueScreen(payload));
        }
    }

    public static void closeFromServer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof DialogueScreen) {
            mc.gui.setScreen(null);
        }
    }

    private void show(OpenDialoguePayload payload) {
        content = payload;
        page = 0;
        revealed = 0;
        lastBlipAt = 0;
        waitingForServer = false;
        rebuildWidgets();
    }

    // ---- layout -------------------------------------------------------------------------------------------

    private int boxWidth() {
        return Math.min(width - 40, 460);
    }

    private int boxX() {
        return (width - boxWidth()) / 2;
    }

    private int boxY() {
        return height - BOX_HEIGHT - 14;
    }

    private int textX() {
        return boxX() + PADDING;
    }

    private int textWidth() {
        return boxWidth() - PADDING * 2;
    }

    /** One reply row: where it sits on screen and the (possibly shortened) text it shows. */
    private record ReplyRow(int x, int y, int width, FormattedCharSequence label) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + width && my >= y && my < y + REPLY_HEIGHT;
        }
    }

    /**
     * Replies are compact rows sized to their text, stacked against the box's right edge so they stay clear of
     * the middle of the screen where the NPC's face is.
     */
    @Override
    protected void init() {
        replyRows.clear();
        List<Component> replies = content.replies();
        if (replies.isEmpty()) {
            return;
        }
        List<Component> labels = new ArrayList<>();
        int rowWidth = 0;
        for (int i = 0; i < replies.size(); i++) {
            Component label = Component.literal((i + 1) + ". ").withColor(COLOR_HINT & 0xFFFFFF).append(replies.get(i));
            labels.add(label);
            rowWidth = Math.max(rowWidth, font.width(label) + REPLY_PADDING * 2);
        }
        rowWidth = Math.min(rowWidth, boxWidth() / 2);
        int x = boxX() + boxWidth() - rowWidth;
        for (int i = 0; i < labels.size(); i++) {
            int y = boxY() - 4 - (labels.size() - i) * (REPLY_HEIGHT + REPLY_GAP);
            List<FormattedCharSequence> fitted = font.split(labels.get(i), rowWidth - REPLY_PADDING * 2);
            replyRows.add(new ReplyRow(x, y, rowWidth, fitted.isEmpty() ? FormattedCharSequence.EMPTY : fitted.get(0)));
        }
    }

    private boolean repliesShown() {
        return !replyRows.isEmpty() && onLastPage() && pageDone() && !waitingForServer;
    }

    // ---- typing -------------------------------------------------------------------------------------------

    private Component currentPageText() {
        return content.pages().isEmpty() ? Component.empty() : content.pages().get(page);
    }

    /** The page as plain characters, for counting what has been typed so far. */
    private String currentPage() {
        return currentPageText().getString();
    }

    /** The first {@code count} characters of the page, keeping their colours and styles. */
    private Component revealedText(int count) {
        MutableComponent out = Component.empty();
        int[] remaining = {count};
        currentPageText().visit((style, part) -> {
            if (remaining[0] <= 0) {
                return Optional.of(Boolean.TRUE);
            }
            String piece = part.length() > remaining[0] ? part.substring(0, remaining[0]) : part;
            out.append(Component.literal(piece).setStyle(style));
            remaining[0] -= piece.length();
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    private boolean pageDone() {
        return revealed >= currentPage().length();
    }

    private boolean onLastPage() {
        return page >= content.pages().size() - 1;
    }

    @Override
    public void tick() {
        super.tick();
        if (!pageDone()) {
            revealed = Math.min(currentPage().length(), revealed + CHARS_PER_TICK);
            int shown = (int) revealed;
            if (shown - lastBlipAt >= BLIP_EVERY && shown > 0 && !Character.isWhitespace(currentPage().charAt(shown - 1))) {
                lastBlipAt = shown;
                playBlip();
            }
        }
        Entity npc = npc();
        if (npc == null || !npc.isAlive()) {
            onClose();
        }
    }

    /** One "syllable" in the NPC's voice, with a little random wobble so it sounds like speech. */
    private void playBlip() {
        Voice voice = content.voice();
        if (!voice.enabled() || voice.volume() <= 0.0F) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(voice.sound())
                .orElseGet(() -> SoundEvent.createVariableRangeEvent(voice.sound()));
        float wobble = minecraft.player != null ? 0.92F + minecraft.player.getRandom().nextFloat() * 0.16F : 1.0F;
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, voice.pitch() * wobble, voice.volume()));
    }

    /** Click / Space / Enter: finish the line, then go to the next page, then close if there's nothing to pick. */
    private void advance() {
        if (waitingForServer) {
            return;
        }
        if (!pageDone()) {
            revealed = currentPage().length();
        } else if (!onLastPage()) {
            page++;
            revealed = 0;
            lastBlipAt = 0;
        } else if (content.replies().isEmpty()) {
            onClose();
        }
    }

    private void choose(int index) {
        if (waitingForServer || index >= content.replies().size()) {
            return;
        }
        waitingForServer = true;
        NpcNetworking.sendToServer(new DialogueChoicePayload(content.entityId(), index));
    }

    // ---- input --------------------------------------------------------------------------------------------

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_SPACE || key == InputConstants.KEY_RETURN) {
            advance();
            return true;
        }
        if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_9 && onLastPage() && pageDone()) {
            choose(key - InputConstants.KEY_1);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (repliesShown()) {
            for (int i = 0; i < replyRows.size(); i++) {
                if (replyRows.get(i).contains(event.x(), event.y())) {
                    choose(i);
                    return true;
                }
            }
        }
        advance();
        return true;
    }

    @Override
    public void onClose() {
        NpcNetworking.sendToServer(new DialogueChoicePayload(content.entityId(), DialogueChoicePayload.CLOSE));
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        // Keep the world (and the NPC) alive behind the box, even in singleplayer.
        return false;
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    private Entity npc() {
        return minecraft.level == null ? null : minecraft.level.getEntity(content.entityId());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // No dimming or blur: the world stays visible behind the box.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        easeCameraToNpc();

        int x = boxX();
        int y = boxY();
        int w = boxWidth();

        // box
        graphics.fill(x, y, x + w, y + BOX_HEIGHT, COLOR_BOX);
        graphics.outline(x, y, w, BOX_HEIGHT, COLOR_BORDER);

        // name tab, in the NPC's own colour
        Component name = content.name();
        int nameWidth = font.width(name.getString()) + 12;
        graphics.fill(x + 8, y - 13, x + 8 + nameWidth, y, COLOR_BOX);
        graphics.outline(x + 8, y - 13, nameWidth, 14, COLOR_BORDER);
        graphics.text(font, name, x + 14, y - 10, 0xFFFFFFFF, true);

        // text, typed out so far
        Component visible = revealedText(Math.min((int) revealed, currentPage().length()));
        List<FormattedCharSequence> lines = font.split(visible, textWidth());
        int ty = y + PADDING;
        for (FormattedCharSequence line : lines) {
            graphics.text(font, line, textX(), ty, COLOR_TEXT, false);
            ty += font.lineHeight + 2;
        }

        // "more" marker
        if (pageDone() && (!onLastPage() || content.replies().isEmpty())) {
            boolean blink = (Util.getMillis() / 400) % 2 == 0;
            if (blink) {
                graphics.text(font, onLastPage() ? "■" : "▼", x + w - 14, y + BOX_HEIGHT - 14, COLOR_HINT, false);
            }
        }
        if (content.pages().size() > 1) {
            String counter = (page + 1) + "/" + content.pages().size();
            graphics.text(font, counter, x + w - font.width(counter) - 22, y + BOX_HEIGHT - 14, COLOR_HINT, false);
        }

        if (repliesShown()) {
            for (ReplyRow row : replyRows) {
                boolean hovered = row.contains(mouseX, mouseY);
                graphics.fill(row.x(), row.y(), row.x() + row.width(), row.y() + REPLY_HEIGHT, COLOR_REPLY);
                graphics.outline(row.x(), row.y(), row.width(), REPLY_HEIGHT, hovered ? COLOR_BORDER : COLOR_REPLY_BORDER);
                graphics.text(font, row.label(), row.x() + REPLY_PADDING, row.y() + (REPLY_HEIGHT - font.lineHeight) / 2 + 1,
                        hovered ? COLOR_REPLY_HOVER_TEXT : COLOR_TEXT, false);
            }
        }

        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    /**
     * Smoothly turns the player's view toward the NPC, frame-rate independent. The camera aims a little below the
     * eyes (about chest height) so the face lands in the upper middle of the screen, above the dialogue box.
     */
    private void easeCameraToNpc() {
        long now = Util.getMillis();
        float seconds = Math.min(0.1F, (now - lastFrameMillis) / 1000.0F);
        lastFrameMillis = now;
        LocalPlayer player = minecraft.player;
        Entity npc = npc();
        if (player == null || npc == null) {
            return;
        }
        Vec3 aim = npc.getEyePosition().subtract(0.0, npc.getBbHeight() * FRAME_DROP, 0.0);
        Vec3 delta = aim.subtract(player.getEyePosition());
        double horizontal = delta.horizontalDistance();
        float targetYaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0F;
        float targetPitch = (float) -(Mth.atan2(delta.y, horizontal) * Mth.RAD_TO_DEG);
        float t = 1.0F - (float) Math.exp(-6.0F * seconds);
        float yaw = player.getYRot() + Mth.wrapDegrees(targetYaw - player.getYRot()) * t;
        float pitch = player.getXRot() + (targetPitch - player.getXRot()) * t;
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
    }
}
