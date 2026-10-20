package com.npcmaker.client;

import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcEntityTypes;
import com.npcmaker.mixin.client.WalkAnimationStateAccessor;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Mob looks: an NPC with {@code "model": "minecraft:spider"} is drawn as a spider. Each such NPC gets a client-only
 * stand-in mob that copies its position, turning, walking, hurt flash, name and gear every frame, and the stand-in
 * is what gets drawn (see EntityRenderDispatcherMixin). The NPC itself, its hitbox and all its behaviour stay as
 * they are.
 */
public final class MobDisguises {

    private static final Map<NpcEntity, LivingEntity> STAND_INS = new WeakHashMap<>();
    /** Types that couldn't be made into a living mob, so they aren't retried every frame. */
    private static final Set<String> UNUSABLE = new HashSet<>();
    private static final int ID_BASE = -0x4E50_0000;

    private MobDisguises() {
    }

    /** One choice in the editor's mob list. {@code id} is "" for the normal player model. */
    public record Option(String id, Component name) {
    }

    /** Every living mob in the game and modpack (anything with mob attributes), by name, after "Player". */
    public static List<Option> options() {
        List<Option> mobs = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type == EntityTypes.PLAYER || type == NpcEntityTypes.npc() || !DefaultAttributes.hasSupplier(type)) {
                continue;
            }
            mobs.add(new Option(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), type.getDescription()));
        }
        mobs.sort(Comparator.comparing(o -> o.name().getString()));
        List<Option> all = new ArrayList<>();
        all.add(new Option("", Component.literal("Player (uses the skin)")));
        for (String model : CustomModels.modelNames()) {
            all.add(new Option(com.npcmaker.custom.CustomContent.NAMESPACE + ":" + model,
                    Component.literal("★ " + model + " (custom model)")));
        }
        all.addAll(mobs);
        return all;
    }

    /** A fresh mob of this type for previews, or null if the id isn't a living mob. */
    public static @Nullable LivingEntity create(Level level, String typeId, int entityId) {
        if (UNUSABLE.contains(typeId)) {
            return null;
        }
        Identifier key = Identifier.tryParse(typeId);
        Optional<EntityType<?>> type = key == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(key);
        Entity made = null;
        try {
            made = type.map(t -> (Entity) t.create(level, EntitySpawnReason.LOAD)).orElse(null);
        } catch (Exception ignored) {
            // some modded mobs can't be made outside a real world; they just keep the player look
        }
        if (!(made instanceof LivingEntity living)) {
            UNUSABLE.add(typeId);
            return null;
        }
        living.setId(entityId);
        return living;
    }

    /** The stand-in to draw for this NPC this frame (brought up to date), or null to draw the NPC itself. */
    public static @Nullable LivingEntity standInFor(NpcEntity npc) {
        String typeId = npc.modelId();
        if (typeId.isEmpty()) {
            return null;
        }
        LivingEntity standIn = STAND_INS.get(npc);
        if (standIn == null || !typeId.equals(BuiltInRegistries.ENTITY_TYPE.getKey(standIn.getType()).toString())) {
            standIn = create(npc.level(), typeId, ID_BASE - npc.getId());
            if (standIn == null) {
                STAND_INS.remove(npc);
                return null;
            }
            STAND_INS.put(npc, standIn);
        }
        copy(npc, standIn);
        return standIn;
    }

    private static void copy(NpcEntity npc, LivingEntity to) {
        to.setPos(npc.getX(), npc.getY(), npc.getZ());
        to.xo = npc.xo;
        to.yo = npc.yo;
        to.zo = npc.zo;
        to.xOld = npc.xOld;
        to.yOld = npc.yOld;
        to.zOld = npc.zOld;
        to.setYRot(npc.getYRot());
        to.yRotO = npc.yRotO;
        to.setXRot(npc.getXRot());
        to.xRotO = npc.xRotO;
        to.yBodyRot = npc.yBodyRot;
        to.yBodyRotO = npc.yBodyRotO;
        to.yHeadRot = npc.yHeadRot;
        to.yHeadRotO = npc.yHeadRotO;
        to.tickCount = npc.tickCount;
        to.hurtTime = npc.hurtTime;
        to.hurtDuration = npc.hurtDuration;
        to.deathTime = npc.deathTime;
        to.setOnGround(npc.onGround());
        to.setInvisible(npc.isInvisible());
        to.setPose(npc.getPose());
        to.setHealth(npc.isDeadOrDying() ? 0.0F : to.getMaxHealth());

        WalkAnimationStateAccessor from = (WalkAnimationStateAccessor) npc.walkAnimation;
        WalkAnimationStateAccessor walk = (WalkAnimationStateAccessor) to.walkAnimation;
        walk.npcmaker$setSpeedOld(from.npcmaker$getSpeedOld());
        walk.npcmaker$setSpeed(from.npcmaker$getSpeed());
        walk.npcmaker$setPosition(from.npcmaker$getPosition());

        Component name = npc.getCustomName();
        if (name == null ? to.getCustomName() != null : !name.equals(to.getCustomName())) {
            to.setCustomName(name);
        }
        to.setCustomNameVisible(npc.isCustomNameVisible());

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack wanted = npc.getItemBySlot(slot);
            if (!ItemStack.matches(wanted, to.getItemBySlot(slot))) {
                to.setItemSlot(slot, wanted.copy());
            }
        }
        AttributeInstance fromScale = npc.getAttribute(Attributes.SCALE);
        AttributeInstance toScale = to.getAttribute(Attributes.SCALE);
        if (fromScale != null && toScale != null && toScale.getBaseValue() != fromScale.getBaseValue()) {
            toScale.setBaseValue(fromScale.getBaseValue());
        }
    }

    /** Speech bubbles and quest markers for a stand-in's render state, as the NPC would have shown them. */
    public static void decorate(NpcEntity npc, LivingEntity standIn, EntityRenderState state, float partialTicks) {
        Component speech = npc.speech();
        boolean speaking = speech != null && NpcClientSettings.showSpeechBubbles()
                && state.distanceToCameraSq <= NpcRenderer.SPEECH_DISTANCE * NpcRenderer.SPEECH_DISTANCE;
        Component marker = NpcRenderer.markerFor(npc, state.distanceToCameraSq);
        if ((speaking || marker != null) && state.nameTagAttachment == null) {
            state.nameTagAttachment = standIn.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, standIn.getYRot(partialTicks));
        }
        if (speaking) {
            state.scoreText = state.nameTag;
            state.nameTag = speech;
        }
        QuestMarkerRenderer.setMarker(state, marker);
    }
}
