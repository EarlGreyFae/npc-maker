package com.npcmaker.mixin.client;

import com.npcmaker.client.NpcPoses;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NPCs are drawn with vanilla's player model, so their animations (wave, bow, sit…) are applied here, after vanilla
 * has posed the model. Players are untouched: only NPC render states carry an animation.
 */
@Mixin(PlayerModel.class)
public class PlayerModelMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void npcmaker$poseNpc(AvatarRenderState state, CallbackInfo ci) {
        NpcPoses.apply((PlayerModel) (Object) this, state);
    }
}
