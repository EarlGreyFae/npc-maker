package com.npcmaker.client;

import com.npcmaker.custom.AnimationClip;
import com.npcmaker.custom.Molang;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

import java.util.Map;

/**
 * Applies a Bedrock animation to model parts, on top of their current pose: rotations (degrees) add, positions move
 * the part (Bedrock's up is the model's minus y), scales multiply. Used for custom models (by bone name) and for
 * player-shaped NPCs (Bedrock humanoid bone names: head, body, rightArm, leftArm, rightLeg, leftLeg).
 */
public final class CustomAnimator {

    private CustomAnimator() {
    }

    /**
     * @param seconds time since the animation started
     * @param weight  0–1, for easing in and out
     */
    public static void apply(AnimationClip clip, Map<String, ModelPart> bones, double seconds, double groundSpeed,
                             double lifeSeconds, float weight) {
        double time = clip.timeAt(seconds);
        Molang.Context ctx = new Molang.Context(time, lifeSeconds, groundSpeed);
        for (Map.Entry<String, AnimationClip.Bone> e : clip.bones().entrySet()) {
            ModelPart part = bones.get(e.getKey());
            if (part != null) {
                applyBone(part, e.getValue(), time, ctx, weight);
            }
        }
    }

    private static void applyBone(ModelPart part, AnimationClip.Bone bone, double time, Molang.Context ctx, float weight) {
        if (bone.rotation() != null) {
            double[] r = bone.rotation().sample(time, ctx);
            part.xRot += (float) Math.toRadians(r[0]) * weight;
            part.yRot += (float) Math.toRadians(r[1]) * weight;
            part.zRot += (float) Math.toRadians(r[2]) * weight;
        }
        if (bone.position() != null) {
            double[] p = bone.position().sample(time, ctx);
            part.x += (float) p[0] * weight;
            part.y -= (float) p[1] * weight;
            part.z += (float) p[2] * weight;
        }
        if (bone.scale() != null) {
            double[] s = bone.scale().sample(time, ctx);
            part.xScale *= Mth.lerp(weight, 1.0F, (float) s[0]);
            part.yScale *= Mth.lerp(weight, 1.0F, (float) s[1]);
            part.zScale *= Mth.lerp(weight, 1.0F, (float) s[2]);
        }
    }

    /** The player model's parts under their Bedrock humanoid names (matched loosely, see AnimationClip.key). */
    public static Map<String, ModelPart> humanoid(HumanoidModel<?> model) {
        return Map.of(
                AnimationClip.key("head"), model.head,
                AnimationClip.key("body"), model.body,
                AnimationClip.key("rightArm"), model.rightArm,
                AnimationClip.key("leftArm"), model.leftArm,
                AnimationClip.key("rightLeg"), model.rightLeg,
                AnimationClip.key("leftLeg"), model.leftLeg);
    }
}
