package com.npcmaker.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.npcmaker.data.ArmStyle;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.network.QuestMarkersPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.ResolvableProfile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Fills in the player render state for NPCs: skin, arm width, poses, name, speech bubble and quest marker. In 26.3
 * vanilla's player renderer draws every {@link AvatarRenderState}, so the drawing itself (model, armour, held items,
 * name lines) is vanilla's; this class only decides what goes into the state. Skins come from the same cache vanilla
 * uses for mannequins and player heads; NPCs without a skin username get a vanilla default skin chosen from their
 * template id.
 */
public class NpcRenderer extends HumanoidMobRenderer<NpcEntity, AvatarRenderState, PlayerModel> {

    private final PlayerSkinRenderCache skinCache;

    public NpcRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.skinCache = context.getPlayerSkinRenderCache();
        CustomModels.context = context;
        addLayer(new HumanoidArmorLayer<>(this,
                ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), part -> new PlayerModel(part, false)),
                context.getEquipmentRenderer()));
    }

    /**
     * The player render state plus the local player's quest marker (drawn by {@link QuestMarkerRenderer}) and the
     * animation to pose (applied by {@link NpcPoses}).
     */
    public static class NpcAvatarState extends AvatarRenderState {
        public @org.jspecify.annotations.Nullable Component questMarker;
        /** A one-shot emote playing now, and its age in ticks. */
        public com.npcmaker.data.@org.jspecify.annotations.Nullable Emote emote;
        public float emoteAge;
        /** The idle pose, while standing still with no emote playing. */
        public com.npcmaker.data.@org.jspecify.annotations.Nullable Emote idlePose;
        /** A custom (Bedrock) animation playing now, its age and length in ticks. */
        public @org.jspecify.annotations.Nullable String customAnimation;
        public float customAge;
        public float customLength;
        /** A custom idle pose animation, while standing still. */
        public @org.jspecify.annotations.Nullable String customIdle;
    }

    @Override
    public AvatarRenderState createRenderState() {
        // Still an AvatarRenderState, so vanilla's player renderer keeps drawing the NPC itself.
        return new NpcAvatarState();
    }

    @Override
    public void extractRenderState(NpcEntity npc, AvatarRenderState state, float partialTicks) {
        super.extractRenderState(npc, state, partialTicks);
        state.id = npc.getId();
        PlayerSkin skin = skinFor(npc);
        ArmStyle arms = npc.arms();
        if (arms != ArmStyle.AUTO) {
            PlayerModelType forced = arms == ArmStyle.SLIM ? PlayerModelType.SLIM : PlayerModelType.WIDE;
            skin = new PlayerSkin(skin.body(), skin.cape(), skin.elytra(), forced, skin.secure());
        }
        state.skin = skin;
        extractSpeech(npc, state, partialTicks);
        extractQuestMarker(npc, state, partialTicks);
        extractAnimation(npc, state, partialTicks);
        previewClock(npc, state);
    }

    /**
     * The editor's preview copy is never added to the world, so it never ticks and its idle animations would stand
     * still. It gets a smooth real-time clock instead (world NPCs have positive ids; the preview's is negative).
     */
    static void previewClock(NpcEntity npc, net.minecraft.client.renderer.entity.state.LivingEntityRenderState state) {
        if (npc.getId() < 0) {
            state.ageInTicks = (net.minecraft.util.Util.getMillis() % 3_600_000L) / 50.0F;
        }
    }

    /** The emote or idle pose for {@link NpcPoses}; sitting and sleeping also use vanilla's riding and lying poses. */
    private void extractAnimation(NpcEntity npc, AvatarRenderState state, float partialTicks) {
        if (!(state instanceof NpcAvatarState npcState)) {
            return;
        }
        com.npcmaker.data.Emote emote = npc.currentEmote();
        npcState.emote = emote;
        npcState.emoteAge = emote != null ? npc.emoteAge(partialTicks) : 0;
        String playing = npc.currentAnimation();
        boolean custom = playing != null && playing.startsWith(com.npcmaker.custom.CustomContent.ANIMATION_PREFIX);
        npcState.customAnimation = custom ? playing : null;
        npcState.customAge = custom ? npc.emoteAge(partialTicks) : 0;
        npcState.customLength = custom ? Math.max(1, CustomModels.lengthTicks(playing)) : 0;
        boolean still = state.walkAnimationSpeed < 0.05F;
        boolean free = playing == null && still && npc.getTarget() == null;
        npcState.idlePose = free ? npc.idlePose() : null;
        String poseId = npc.idlePoseId();
        npcState.customIdle = free && poseId.startsWith(com.npcmaker.custom.CustomContent.ANIMATION_PREFIX) ? poseId : null;
        com.npcmaker.data.Emote showing = emote != null ? emote : npcState.idlePose;
        if (showing == com.npcmaker.data.Emote.SIT) {
            state.isPassenger = true;
        } else if (showing == com.npcmaker.data.Emote.SLEEP) {
            state.pose = net.minecraft.world.entity.Pose.SLEEPING;
            state.bedOrientation = net.minecraft.core.Direction.fromYRot(npc.getYRot(partialTicks));
        }
    }

    /** Quest markers show from this far away, in blocks. */
    private static final double MARKER_DISTANCE = 32.0;

    /** The local player's marker text for this NPC (gold !, gold ?, grey ?), or null for none or too far away. */
    static @org.jspecify.annotations.Nullable Component markerFor(NpcEntity npc, double distanceToCameraSq) {
        int marker = ClientQuests.marker(npc.getId());
        if (marker == 0 || distanceToCameraSq > MARKER_DISTANCE * MARKER_DISTANCE) {
            return null;
        }
        return switch (marker) {
            case QuestMarkersPayload.AVAILABLE -> Component.literal("!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
            case QuestMarkersPayload.TURN_IN -> Component.literal("?").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
            default -> Component.literal("?").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD);
        };
    }

    /** The local player's quest marker (gold !, gold ?, grey ?) for {@link QuestMarkerRenderer} to draw. */
    private void extractQuestMarker(NpcEntity npc, AvatarRenderState state, float partialTicks) {
        if (!(state instanceof NpcAvatarState npcState)) {
            return;
        }
        npcState.questMarker = markerFor(npc, state.distanceToCameraSq);
        if (npcState.questMarker != null && state.nameTagAttachment == null) {
            state.nameTagAttachment = npc.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, npc.getYRot(partialTicks));
        }
    }

    /** How far away an idle line can be read, in blocks. */
    static final double SPEECH_DISTANCE = 16.0;

    /**
     * Idle lines reuse vanilla's two-line name display: the name moves to the lower line (where scoreboard
     * numbers go for players) and the speech sits above it. If the name is hidden, only the speech shows.
     */
    private void extractSpeech(NpcEntity npc, AvatarRenderState state, float partialTicks) {
        Component speech = npc.speech();
        if (speech == null || state.distanceToCameraSq > SPEECH_DISTANCE * SPEECH_DISTANCE
                || !NpcClientSettings.showSpeechBubbles()) {
            return;
        }
        state.scoreText = state.nameTag;
        state.nameTag = speech;
        if (state.nameTagAttachment == null) {
            state.nameTagAttachment = npc.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, npc.getYRot(partialTicks));
        }
    }

    private PlayerSkin skinFor(NpcEntity npc) {
        if (!npc.skinFile().isEmpty()) {
            // Library skins have no arm-width info of their own: wide unless the template says slim.
            PlayerSkin fromLibrary = ClientSkins.get(npc.skinFile(),
                    npc.arms() == ArmStyle.SLIM ? PlayerModelType.SLIM : PlayerModelType.WIDE);
            if (fromLibrary != null) {
                return fromLibrary;
            }
        }
        ResolvableProfile profile = npc.profile();
        if (profile.equals(ResolvableProfile.Static.EMPTY)) {
            UUID seed = UUID.nameUUIDFromBytes(("npcmaker:" + npc.templateId()).getBytes(StandardCharsets.UTF_8));
            return DefaultPlayerSkin.get(seed);
        }
        return skinCache.getOrDefault(profile).playerSkin();
    }

    @Override
    protected HumanoidModel.ArmPose getArmPose(NpcEntity npc, HumanoidArm arm) {
        HumanoidModel.ArmPose pose = super.getArmPose(npc, arm);
        if (pose != HumanoidModel.ArmPose.EMPTY) {
            return pose;
        }
        ItemStack held = npc.getItemHeldByArm(arm);
        if (held.isEmpty()) {
            return HumanoidModel.ArmPose.EMPTY;
        }
        boolean usingThisHand = npc.isUsingItem() && npc.getUsedItemHand().asArm(npc.getMainArm()) == arm;
        if (usingThisHand) {
            ItemUseAnimation animation = held.getUseAnimation();
            if (animation == ItemUseAnimation.BOW) {
                return HumanoidModel.ArmPose.BOW_AND_ARROW;
            }
            if (animation == ItemUseAnimation.CROSSBOW) {
                return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
            }
        }
        if (held.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(held)) {
            return HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }
        return HumanoidModel.ArmPose.ITEM;
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return state.skin.body().texturePath();
    }

    @Override
    protected void scale(AvatarRenderState state, PoseStack poseStack) {
        // Same as players, so NPCs line up with player-sized armour and items.
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }
}
