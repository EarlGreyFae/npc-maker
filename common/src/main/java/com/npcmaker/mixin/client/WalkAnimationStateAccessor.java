package com.npcmaker.mixin.client;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets a mob-look stand-in copy its NPC's walking animation exactly (legs in step with the NPC's movement). */
@Mixin(WalkAnimationState.class)
public interface WalkAnimationStateAccessor {

    @Accessor("speedOld")
    float npcmaker$getSpeedOld();

    @Accessor("speedOld")
    void npcmaker$setSpeedOld(float value);

    @Accessor("speed")
    float npcmaker$getSpeed();

    @Accessor("speed")
    void npcmaker$setSpeed(float value);

    @Accessor("position")
    float npcmaker$getPosition();

    @Accessor("position")
    void npcmaker$setPosition(float value);
}
