package com.npcmaker.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws the quest marker (gold !, gold ?, grey ?) over an NPC: large, facing the camera, no background box, gently
 * bobbing just above the speech-bubble line (at the same height whether or not the NPC is talking). Positioned the same way vanilla positions name tags.
 */
public final class QuestMarkerRenderer {

    /** Vanilla's name-tag scale is 0.025; the marker is 2.5 times bigger. */
    private static final float SCALE = 0.025F * 2.5F;
    /** Height of one vanilla name-tag line in world units. */
    private static final float NAME_LINE = 9.0F * 1.15F * 0.025F;
    /** Space between the top of the speech-bubble line and the marker's middle bob position, in world units. */
    private static final float GAP = 0.08F;
    /** How far the marker bobs up and down, in world units. */
    private static final float BOB = 0.05F;
    private static final int FULL_BRIGHT = 0xF000F0;

    private QuestMarkerRenderer() {
    }

    /** Markers for NPCs drawn as another mob (their render state isn't ours to add a field to). */
    private static final Map<EntityRenderState, Component> STAND_IN_MARKERS = new WeakHashMap<>();

    public static void setMarker(EntityRenderState state, @Nullable Component marker) {
        if (marker == null) {
            STAND_IN_MARKERS.remove(state);
        } else {
            STAND_IN_MARKERS.put(state, marker);
        }
    }

    public static void submit(EntityRenderState state, CameraRenderState camera, double x, double y, double z,
                              PoseStack poseStack, SubmitNodeCollector collector) {
        Component marker = state instanceof NpcRenderer.NpcAvatarState npc ? npc.questMarker : STAND_IN_MARKERS.get(state);
        if (marker == null || state.nameTagAttachment == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        FormattedCharSequence text = marker.getVisualOrderText();
        Vec3 attach = state.nameTagAttachment;
        float bob = (float) Math.sin(Util.getMillis() / 400.0) * BOB;

        poseStack.pushPose();
        // Vanilla name lines hang below their anchor: the name line ends at +0.5 and the speech-bubble line one
        // line higher. The marker always sits just above where the speech line would end, so it never jumps when
        // the NPC starts talking, and its lowest bob still clears the bubble.
        poseStack.translate(x + attach.x, y + attach.y + 0.5 + NAME_LINE + GAP + bob, z + attach.z);
        poseStack.rotate(camera.orientation);
        poseStack.scale(SCALE, -SCALE, SCALE);
        // Text is drawn downward from y; start one line up so the marker's bottom sits at the anchor.
        collector.submitText(poseStack, -font.width(text) / 2.0F, -font.lineHeight, text, true,
                Font.DisplayMode.NORMAL, FULL_BRIGHT, 0xFFFFFFFF, 0, 0);
        poseStack.popPose();
    }
}
