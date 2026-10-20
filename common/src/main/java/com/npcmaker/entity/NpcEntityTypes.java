package com.npcmaker.entity;

import com.npcmaker.Constants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Entity types. Each loader registers what {@link #create()} builds, at the time its registry system allows. */
public final class NpcEntityTypes {

    public static final ResourceKey<EntityType<?>> NPC_KEY = ResourceKey.create(Registries.ENTITY_TYPE, Constants.id("npc"));

    private static EntityType<NpcEntity> npc;

    private NpcEntityTypes() {
    }

    public static EntityType<NpcEntity> create() {
        npc = EntityType.Builder.<NpcEntity>of(NpcEntity::new, MobCategory.MISC)
                .sized(0.6F, 1.8F)
                .eyeHeight(1.62F)
                .clientTrackingRange(10)
                .build(NPC_KEY);
        return npc;
    }

    public static EntityType<NpcEntity> npc() {
        return npc;
    }
}
