package com.npcmaker.client;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.jspecify.annotations.Nullable;

/** The render state of an NPC drawn as a custom (Blockbench) model, with the renderer that draws it. */
public class CustomModelState extends LivingEntityRenderState {
    public @Nullable CustomModelRenderer renderer;
    /** The animation playing (an id like "custom:animation.baker.wave"), its age and length in ticks. */
    public @Nullable String animation;
    public float animationAge;
    public float animationLength;
    /** A custom idle pose animation, while standing still. */
    public @Nullable String idlePose;
}
