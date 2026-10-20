package com.npcmaker.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.npcmaker.client.MobDisguises;
import com.npcmaker.client.QuestMarkerRenderer;
import com.npcmaker.entity.NpcEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NPCs use the player render state, which 26.3 always hands to vanilla's player renderer, so NPC Maker's own renderer
 * never gets to draw. This hook runs after any entity is drawn and adds the quest marker for NPCs that have one.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    /** NPCs with a mob look are drawn as their stand-in mob (with the NPC's speech bubble and quest marker). */
    @Inject(method = "extractEntity", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void npcmaker$extractMobLook(E entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        if (entity instanceof NpcEntity npc) {
            EntityRenderState custom = com.npcmaker.client.CustomModels.extract(npc, partialTicks);
            if (custom != null) {
                cir.setReturnValue(custom);
                return;
            }
            LivingEntity standIn = MobDisguises.standInFor(npc);
            if (standIn != null) {
                EntityRenderState state = ((EntityRenderDispatcher) (Object) this).extractEntity(standIn, partialTicks);
                MobDisguises.decorate(npc, standIn, state, partialTicks);
                cir.setReturnValue(state);
            }
        }
    }

    /**
     * Some screens (like the editor's preview) ask for an entity's renderer directly instead of going through
     * extractEntity, so NPCs wearing a custom model get that model's renderer here too.
     */
    @Inject(method = "getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
            at = @At("HEAD"), cancellable = true)
    private void npcmaker$customModelRendererForEntity(Entity entity,
                                                       CallbackInfoReturnable<net.minecraft.client.renderer.entity.EntityRenderer<?, ?>> cir) {
        if (entity instanceof NpcEntity npc) {
            com.npcmaker.client.CustomModelRenderer custom = com.npcmaker.client.CustomModels.rendererFor(npc);
            if (custom != null) {
                cir.setReturnValue(custom);
            }
        }
    }

    /** NPCs wearing a custom Blockbench model are drawn by that model's own renderer. */
    @Inject(method = "getRenderer(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
            at = @At("HEAD"), cancellable = true)
    private void npcmaker$customModelRenderer(EntityRenderState state,
                                              CallbackInfoReturnable<net.minecraft.client.renderer.entity.EntityRenderer<?, ?>> cir) {
        if (state instanceof com.npcmaker.client.CustomModelState custom && custom.renderer != null) {
            cir.setReturnValue(custom.renderer);
        }
    }

    @Inject(method = "submit", at = @At("TAIL"))
    private <S extends EntityRenderState> void npcmaker$submitQuestMarker(S state, CameraRenderState camera, double x, double y,
                                                                          double z, PoseStack poseStack, SubmitNodeCollector collector,
                                                                          CallbackInfo ci) {
        QuestMarkerRenderer.submit(state, camera, x, y, z, poseStack, collector);
    }
}
