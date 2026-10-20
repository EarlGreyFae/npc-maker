package com.npcmaker.client;

import com.npcmaker.data.Emote;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;

/**
 * Poses the player model for NPC animations, after vanilla has set up walking, looking and held items. Rotations are
 * in radians in model space (negative xRot lifts an arm forward and up; positive zRot swings the right arm out).
 * One-shot emotes ease in and out over a few ticks so they blend with vanilla's pose. Armour and held items follow,
 * because they hang off the same parts.
 */
public final class NpcPoses {

    private static final int EASE_TICKS = 6;

    private NpcPoses() {
    }

    public static void apply(HumanoidModel<?> model, AvatarRenderState state) {
        if (!(state instanceof NpcRenderer.NpcAvatarState npc)) {
            return;
        }
        // Custom (Bedrock) animations, by humanoid bone names.
        String customId = npc.customAnimation != null ? npc.customAnimation : npc.customIdle;
        com.npcmaker.custom.AnimationClip clip = customId != null ? CustomModels.animation(customId) : null;
        if (clip != null) {
            boolean oneShot = npc.customAnimation != null;
            float age = oneShot ? npc.customAge : state.ageInTicks;
            float weight = oneShot ? Math.max(0, Math.min(1.0F, Math.min(age / 4.0F, (npc.customLength - age) / 4.0F))) : 1.0F;
            CustomAnimator.apply(clip, CustomAnimator.humanoid(model), age / 20.0, state.walkAnimationSpeed, state.ageInTicks / 20.0, weight);
            return;
        }
        Emote emote = npc.emote != null ? npc.emote : npc.idlePose;
        if (emote == null) {
            return;
        }
        boolean oneShot = npc.emote != null;
        float t = oneShot ? npc.emoteAge : state.ageInTicks;
        float k = oneShot ? ease(t, emote.ticks()) : 1.0F;
        switch (emote) {
            case WAVE -> {
                blend(model.rightArm, -2.9F, 0.0F, 0.3F + Mth.sin(t * 0.8F) * 0.35F, k);
            }
            case BOW -> {
                bend(model, 0.65F * k);
                model.rightArm.xRot += 0.25F * k;
                model.leftArm.xRot += 0.25F * k;
            }
            case CHEER -> {
                blend(model.rightArm, -2.8F, 0.0F, 0.35F, k);
                blend(model.leftArm, -2.8F, 0.0F, -0.35F, k);
                model.root().y -= Math.abs(Mth.sin(t * 0.45F)) * 2.0F * k;
            }
            case POINT -> blend(model.rightArm, -1.55F, model.head.yRot, 0.0F, k);
            case SALUTE -> blend(model.rightArm, -2.3F, -0.55F, 0.0F, k);
            case SIT -> {
                blend(model.rightLeg, -1.4137F, 0.3142F, 0.0F, k);
                blend(model.leftLeg, -1.4137F, -0.3142F, 0.0F, k);
                model.root().y += 10.0F * k;
            }
            case SLEEP -> {
                model.head.xRot = 0.0F;
                model.head.yRot = 0.0F;
            }
            case READ -> {
                blend(model.rightArm, -0.95F, -0.35F, 0.0F, k);
                blend(model.leftArm, -0.95F, 0.35F, Mth.sin(t * 0.05F) * 0.05F, k);
                model.head.xRot = Mth.lerp(k, model.head.xRot, 0.45F);
            }
            case WORK -> {
                float swing = (Mth.sin(t * 0.4F) + 1.0F) * 0.5F;
                blend(model.rightArm, -2.4F + swing * 1.6F, 0.0F, 0.0F, k);
                bend(model, 0.15F * k);
                model.head.xRot = Mth.lerp(k, model.head.xRot, 0.35F);
            }
        }
    }

    /** 0 → 1 over the first few ticks, 1 → 0 over the last few. */
    private static float ease(float t, int length) {
        float in = Mth.clamp(t / EASE_TICKS, 0.0F, 1.0F);
        float out = Mth.clamp((length - t) / EASE_TICKS, 0.0F, 1.0F);
        float v = Math.min(in, out);
        return v * v * (3.0F - 2.0F * v);
    }

    private static void blend(ModelPart part, float x, float y, float z, float k) {
        part.xRot = Mth.lerp(k, part.xRot, x);
        part.yRot = Mth.lerp(k, part.yRot, y);
        part.zRot = Mth.lerp(k, part.zRot, z);
    }

    /** Leans the upper body forward by {@code angle}, hinging at the hips (12 px down from the neck). */
    private static void bend(HumanoidModel<?> model, float angle) {
        float cos = Mth.cos(angle);
        float sin = Mth.sin(angle);
        for (ModelPart part : new ModelPart[]{model.head, model.body, model.rightArm, model.leftArm}) {
            float fromHip = 12.0F - part.y;
            part.y = 12.0F - fromHip * cos;
            part.z = part.z - fromHip * sin;
            part.xRot += angle;
        }
    }
}
