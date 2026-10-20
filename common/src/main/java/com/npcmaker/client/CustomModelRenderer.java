package com.npcmaker.client;

import com.npcmaker.custom.CustomContent;
import com.npcmaker.entity.NpcEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityAttachment;

/**
 * Draws NPCs that look like a custom Blockbench model: vanilla's living-entity drawing (lighting, hurt flash, name,
 * shadow) with the model's own texture, plus the NPC's speech bubble and quest marker. One renderer per model.
 */
public class CustomModelRenderer extends LivingEntityRenderer<NpcEntity, CustomModelState, BedrockModel> {

    private final Identifier texture;

    public CustomModelRenderer(EntityRendererProvider.Context context, BedrockModel model, String name) {
        super(context, model, 0.5F);
        this.texture = Identifier.fromNamespaceAndPath(CustomContent.NAMESPACE, "textures/entity/" + name + ".png");
    }

    @Override
    public CustomModelState createRenderState() {
        return new CustomModelState();
    }

    @Override
    public void extractRenderState(NpcEntity npc, CustomModelState state, float partialTicks) {
        super.extractRenderState(npc, state, partialTicks);
        state.renderer = this;
        NpcRenderer.previewClock(npc, state);
        // Face where the NPC looks (e.g. the player talking to it) with the whole model, not just a head bone.
        float headYaw = state.bodyRot + state.yRot;
        state.bodyRot = npc.facingYaw(partialTicks);
        state.yRot = net.minecraft.util.Mth.wrapDegrees(headYaw - state.bodyRot);
        String playing = npc.currentAnimation();
        boolean custom = playing != null && playing.startsWith(CustomContent.ANIMATION_PREFIX);
        state.animation = custom ? playing : null;
        state.animationAge = custom ? npc.emoteAge(partialTicks) : 0;
        state.animationLength = custom ? Math.max(1, CustomModels.lengthTicks(playing)) : 0;
        String pose = npc.idlePoseId();
        state.idlePose = pose.startsWith(CustomContent.ANIMATION_PREFIX) ? pose : null;

        Component speech = npc.speech();
        boolean speaking = speech != null && NpcClientSettings.showSpeechBubbles()
                && state.distanceToCameraSq <= NpcRenderer.SPEECH_DISTANCE * NpcRenderer.SPEECH_DISTANCE;
        Component marker = NpcRenderer.markerFor(npc, state.distanceToCameraSq);
        if ((speaking || marker != null) && state.nameTagAttachment == null) {
            state.nameTagAttachment = npc.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, npc.getYRot(partialTicks));
        }
        if (speaking) {
            state.scoreText = state.nameTag;
            state.nameTag = speech;
        }
        QuestMarkerRenderer.setMarker(state, marker);
    }

    @Override
    public Identifier getTextureLocation(CustomModelState state) {
        return texture;
    }
}
