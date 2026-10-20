package com.npcmaker.client;

/**
 * NPC Maker's one look for every screen and overlay (dialogue box, reply rows, reputation card, editor):
 * very dark purple panels with gold outlines and warm cream text. Use these instead of picking colours per screen.
 * All values are ARGB.
 */
public final class NpcUiTheme {

    /** Main panel background: very dark purple (as dark as a near-black navy), slightly see-through. */
    public static final int PANEL = 0xE8180D24;
    /** Smaller elements on top of the world (reply rows, tabs): the same dark purple, a touch more see-through. */
    public static final int PANEL_LIGHT = 0xD81E102C;
    /** Inset areas inside a panel (input fields, lists): even darker. */
    public static final int PANEL_INSET = 0xF0100818;

    /** Gold outline around panels and the hovered/selected element. */
    public static final int BORDER = 0xFFD4AF37;
    /** Dimmer gold for outlines of elements that aren't hovered or selected. */
    public static final int BORDER_DIM = 0x99D4AF37;

    /** Body text. */
    public static final int TEXT = 0xFFF5ECD7;
    /** Hovered or selected text. */
    public static final int TEXT_HOVER = 0xFFFFE08A;
    /** Titles, hints, counters and markers. */
    public static final int ACCENT = 0xFFE8C46A;

    private NpcUiTheme() {
    }

    /**
     * The first (longest) of several wordings that fits {@code maxWidth}, so hint lines never run off the screen at
     * big GUI scales; the last one is cut to fit if even it is too long.
     */
    public static String fitting(net.minecraft.client.gui.Font font, int maxWidth, String... longestFirst) {
        for (String option : longestFirst) {
            if (font.width(option) <= maxWidth) {
                return option;
            }
        }
        return font.plainSubstrByWidth(longestFirst[longestFirst.length - 1], maxWidth);
    }
}
