package com.npcmaker.entity;

import com.npcmaker.Constants;
import com.npcmaker.NpcPermissions;
import com.npcmaker.content.NpcContent;
import com.npcmaker.data.Appearance;
import com.npcmaker.data.ArmStyle;
import com.npcmaker.data.Behavior;
import com.npcmaker.data.CombatStance;
import com.npcmaker.data.Effects;
import com.npcmaker.data.Emote;
import com.npcmaker.data.Equipment;
import com.npcmaker.data.ParticleSpec;
import com.npcmaker.data.Speech;
import com.npcmaker.data.MovementMode;
import com.npcmaker.data.NpcDefinition;
import com.npcmaker.data.SoundSpec;
import com.npcmaker.data.Stats;
import com.npcmaker.entity.ai.DefendAlliedPlayersGoal;
import com.npcmaker.entity.ai.DefendOwnerGoal;
import com.npcmaker.entity.ai.FaceHomeGoal;
import com.npcmaker.entity.ai.FollowOwnerGoal;
import com.npcmaker.entity.ai.ReturnHomeGoal;
import com.npcmaker.entity.ai.TalkToPlayerGoal;
import com.npcmaker.dialogue.DialogueManager;
import com.npcmaker.faction.Factions;
import com.npcmaker.faction.PlayerFactions;
import com.npcmaker.faction.Relation;
import com.npcmaker.text.TextTags;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import com.npcmaker.data.ItemUse;
import com.npcmaker.entity.ai.NpcMeleeAttackGoal;
import com.npcmaker.entity.ai.NpcRangedAttackGoal;
import com.npcmaker.entity.ai.PatrolGoal;
import com.npcmaker.ai.AiRule;
import com.npcmaker.ai.AiRules;
import com.npcmaker.ai.Threats;
import com.npcmaker.quest.QuestManager;
import com.npcmaker.editor.NpcEditing;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import com.npcmaker.entity.ai.FleeAndHideGoal;
import com.npcmaker.entity.ai.RulesGoal;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.Item;
import com.npcmaker.item.NpcWandItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A placed NPC. Its look, stats and behaviour come from its template ({@link NpcDefinition}); what is specific to
 * this one NPC (home, rotation, companion owner) is its {@link NpcInstance}.
 */
public class NpcEntity extends PathfinderMob {

    private static final EntityDataAccessor<String> DATA_TEMPLATE = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<ResolvableProfile> DATA_PROFILE = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.RESOLVABLE_PROFILE);
    private static final EntityDataAccessor<Byte> DATA_ARMS = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<String> DATA_SKIN_FILE = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_MODEL = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    /** The emote playing now ("" for none) and the game time it started, so clients can time it. */
    private static final EntityDataAccessor<String> DATA_EMOTE = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_EMOTE_TICK = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    /** How long the current animation plays, in ticks (custom animations have their own length). */
    private static final EntityDataAccessor<Integer> DATA_EMOTE_LENGTH = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    /** Pose held while standing still ("" for none). */
    private static final EntityDataAccessor<String> DATA_IDLE_POSE = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Optional<Component>> DATA_SPEECH = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.OPTIONAL_COMPONENT);

    /** How close a hostile has to be to a guard's home before the guard engages. */
    private static final double GUARD_RADIUS = 16.0;

    private @Nullable NpcInstance instance;

    public NpcEntity(EntityType<? extends NpcEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, Stats.DEFAULT.health())
                .add(Attributes.ATTACK_DAMAGE, Stats.DEFAULT.strength())
                .add(Attributes.MOVEMENT_SPEED, Stats.DEFAULT.speed())
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    /** Creates an NPC from a template and adds it to the world. Returns null if the template doesn't exist. */
    public static @Nullable NpcEntity place(ServerLevel level, String templateId, Vec3 pos, float yaw, float pitch) {
        if (NpcContent.NPCS.get(templateId).isEmpty()) {
            return null;
        }
        return place(level, NpcInstance.placed(templateId, pos, yaw, pitch));
    }

    public static @Nullable NpcEntity place(ServerLevel level, NpcInstance instance) {
        NpcEntity npc = NpcEntityTypes.npc().create(level, EntitySpawnReason.COMMAND);
        if (npc == null) {
            return null;
        }
        Vec3 pos = instance.home();
        npc.snapTo(pos.x, pos.y, pos.z, instance.yaw(), instance.pitch());
        npc.setYHeadRot(instance.yaw());
        npc.setYBodyRot(instance.yaw());
        npc.instance = instance;
        npc.applyTemplate(true);
        npc.restock();
        npc.setHealth(npc.getMaxHealth());
        level.addFreshEntity(npc);
        return npc;
    }

    /**
     * Re-reads every loaded NPC's template, e.g. after {@code /npcmaker reload}, including fresh gear and a restocked
     * inventory. Returns how many were updated.
     */
    public static int refreshAll(MinecraftServer server) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (NpcEntity npc : level.getEntities(NpcEntityTypes.npc(), e -> true)) {
                npc.applyTemplate(true);
                npc.restock();
                count++;
            }
        }
        return count;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_TEMPLATE, "");
        builder.define(DATA_PROFILE, ResolvableProfile.Static.EMPTY);
        builder.define(DATA_ARMS, (byte) ArmStyle.AUTO.ordinal());
        builder.define(DATA_SKIN_FILE, "");
        builder.define(DATA_MODEL, "");
        builder.define(DATA_EMOTE, "");
        builder.define(DATA_EMOTE_TICK, 0);
        builder.define(DATA_EMOTE_LENGTH, 0);
        builder.define(DATA_IDLE_POSE, "");
        builder.define(DATA_SPEECH, Optional.empty());
    }

    // ---- template -----------------------------------------------------------------------------------------

    public String templateId() {
        return entityData.get(DATA_TEMPLATE);
    }

    public Optional<NpcDefinition> definition() {
        return NpcContent.NPCS.get(templateId());
    }

    public @Nullable NpcInstance instance() {
        return instance;
    }

    public ResolvableProfile profile() {
        return entityData.get(DATA_PROFILE);
    }

    public ArmStyle arms() {
        byte b = entityData.get(DATA_ARMS);
        return b >= 0 && b < ArmStyle.values().length ? ArmStyle.values()[b] : ArmStyle.AUTO;
    }

    /** Switches this NPC to another template (e.g. its own copy from the editor) and applies it. */
    public void setTemplate(String template) {
        if (instance == null) {
            return;
        }
        instance = instance.withTemplate(template);
        applyTemplate(false);
    }

    /**
     * Client-side only, for the editor's preview copy: shows a look without a template on this side (skin, arms,
     * name and colour, scale), plus the template id used to pick a default skin.
     */
    public void previewLook(String template, Appearance look, String name) {
        entityData.set(DATA_TEMPLATE, template);
        applyLook(look, name);
        setBase(Attributes.SCALE, look.scale());
    }

    /** Skin, skin file, mob model, arms, and the name in its colour; all synced to clients. */
    private void applyLook(Appearance look, String name) {
        entityData.set(DATA_PROFILE, look.skinUsername().filter(n -> !n.isBlank())
                .map(ResolvableProfile::createUnresolved)
                .orElse(ResolvableProfile.Static.EMPTY));
        entityData.set(DATA_SKIN_FILE, look.skinFile().orElse(""));
        entityData.set(DATA_MODEL, look.model().orElse(""));
        entityData.set(DATA_ARMS, (byte) look.arms().ordinal());
        setCustomName(Component.literal(name).withColor(look.nameColorRgb()));
        setCustomNameVisible(look.showName());
    }

    /** Name of a skin in the world's skin library, or "" for none. */
    public String skinFile() {
        return entityData.get(DATA_SKIN_FILE);
    }

    /** Entity type id of the mob this NPC looks like, or "" for the player model. */
    public String modelId() {
        return entityData.get(DATA_MODEL);
    }

    // ---- animations and particles -------------------------------------------------------------------------

    /** Plays an emote for everyone nearby (replacing one that's playing). */
    public void playEmote(Emote emote) {
        startAnimation(emote.id(), emote.ticks());
    }

    /**
     * Plays a built-in emote ("wave") or a custom animation ("custom:animation.baker.wave") for everyone nearby. A
     * custom animation plays for its own length (a looping one for at least 5 seconds); unknown ids do nothing.
     */
    public void playAnimation(String id) {
        Emote emote = Emote.byId(id);
        if (emote != null) {
            playEmote(emote);
            return;
        }
        com.npcmaker.custom.AnimationClip clip = com.npcmaker.custom.CustomContent.animation(id);
        if (clip != null) {
            int ticks = (int) Math.ceil(clip.length() * 20);
            startAnimation(id, clip.loop() == com.npcmaker.custom.AnimationClip.Loop.LOOP ? Math.max(100, ticks) : Math.max(10, ticks));
        }
    }

    private void startAnimation(String id, int ticks) {
        entityData.set(DATA_EMOTE, id);
        entityData.set(DATA_EMOTE_TICK, (int) level().getGameTime());
        entityData.set(DATA_EMOTE_LENGTH, ticks);
    }

    /** The animation playing (built-in or custom id), if it hasn't run out yet. */
    public @Nullable String currentAnimation() {
        String id = entityData.get(DATA_EMOTE);
        if (id.isEmpty() || emoteAge(0) > entityData.get(DATA_EMOTE_LENGTH)) {
            return null;
        }
        return id;
    }

    /** The built-in emote playing, if any (custom animations are drawn by their own code). */
    public @Nullable Emote currentEmote() {
        String id = currentAnimation();
        return id == null ? null : Emote.byId(id);
    }

    /** The idle pose's id as written ("read", or a custom animation), or "". */
    public String idlePoseId() {
        return entityData.get(DATA_IDLE_POSE);
    }

    /** Ticks since the current emote started. */
    public float emoteAge(float partialTicks) {
        return (int) level().getGameTime() - entityData.get(DATA_EMOTE_TICK) + partialTicks;
    }

    public @Nullable Emote idlePose() {
        return Emote.byId(entityData.get(DATA_IDLE_POSE));
    }

    // Client side: which way a custom model's whole body faces. Custom models may have no head (or a small one), so
    // while standing still the body turns smoothly to where the NPC looks (a player talking to it), and while
    // walking it faces the way it moves.
    private float facingYaw;
    private float facingYawO;
    private boolean facingStarted;

    private void tickFacing() {
        float target = getDeltaMovement().horizontalDistanceSqr() > 0.0004 ? yBodyRot : yHeadRot;
        if (!facingStarted) {
            facingYaw = target;
            facingStarted = true;
        }
        facingYawO = facingYaw;
        facingYaw = net.minecraft.util.Mth.approachDegrees(facingYaw, target, 15.0F);
    }

    /** The body yaw a custom model is drawn with (client side). */
    public float facingYaw(float partialTicks) {
        return facingStarted ? net.minecraft.util.Mth.rotLerp(partialTicks, facingYawO, facingYaw) : yBodyRot;
    }

    /** Client-side only, for the editor's preview copy: shows an idle pose without a template. */
    public void previewIdlePose(String pose) {
        entityData.set(DATA_IDLE_POSE, pose);
    }

    private int emoteCheck;
    private int emoteCooldown = 200;

    /**
     * The aura (while a player is within 32 blocks), and an idle emote now and then while a player is near and the
     * NPC is standing still with nothing else to do.
     */
    private void tickAnimationsAndAura() {
        Optional<Effects> found = definition().map(NpcDefinition::effects);
        if (found.isEmpty() || !isAlive() || !(level() instanceof ServerLevel level)) {
            return;
        }
        Effects effects = found.get();
        effects.aura().ifPresent(aura -> {
            if (tickCount % aura.interval() == 0 && level.getNearestPlayer(this, 32) != null) {
                aura.spawn(level, this);
            }
        });
        if (emoteCooldown > 0) {
            emoteCooldown--;
        }
        if (++emoteCheck < 20 || effects.idleEmotes().isEmpty()) {
            return;
        }
        emoteCheck = 0;
        boolean busy = conversationPartner != null || getTarget() != null || getDeltaMovement().horizontalDistanceSqr() > 0.0004
                || currentAnimation() != null;
        if (emoteCooldown > 0 || busy || level.getNearestPlayer(this, 8) == null) {
            return;
        }
        playAnimation(pick(effects.idleEmotes()));
        float jitter = 0.75F + getRandom().nextFloat() * 0.5F;
        emoteCooldown = (int) (effects.emoteIntervalSeconds() * 20 * jitter);
    }

    /** One of the template's event effects (greet, hurt, death, quest given / done), if it has one. */
    public void showEffect(java.util.function.Function<Effects, Optional<ParticleSpec>> which) {
        if (level() instanceof ServerLevel level) {
            definition().flatMap(d -> which.apply(d.effects())).ifPresent(spec -> spec.spawn(level, this));
        }
    }

    /** Copies the template onto this entity (look, name, stats, AI) without touching its current gear. */
    public void applyTemplate() {
        applyTemplate(false);
    }

    /**
     * Copies the template onto this entity: look, name, stats and AI, and with {@code resetGear} also its worn and
     * held items. Gear is only reset on placement, reload and restock, so weapons the NPC swapped mid-fight
     * aren't duplicated when other settings change.
     */
    public void applyTemplate(boolean resetGear) {
        if (instance == null || level().isClientSide()) {
            return;
        }
        entityData.set(DATA_TEMPLATE, instance.template());
        Optional<NpcDefinition> found = definition();
        if (found.isEmpty()) {
            setCustomName(Component.literal("Missing template: " + instance.template()).withStyle(ChatFormatting.RED));
            setCustomNameVisible(true);
            Constants.LOG.warn("NPC at {} uses missing template '{}'", blockPosition(), instance.template());
            return;
        }
        NpcDefinition def = found.get();

        Appearance look = def.appearance();
        applyLook(look, def.displayName());
        entityData.set(DATA_IDLE_POSE, def.effects().idlePose().orElse(""));

        Stats stats = def.stats();
        setBase(Attributes.MAX_HEALTH, stats.health());
        setBase(Attributes.ATTACK_DAMAGE, stats.strength());
        setBase(Attributes.ARMOR, stats.armor());
        setBase(Attributes.MOVEMENT_SPEED, stats.speed());
        setBase(Attributes.KNOCKBACK_RESISTANCE, stats.knockbackResistance());
        setBase(Attributes.SCALE, look.scale());
        if (getHealth() > getMaxHealth()) {
            setHealth(getMaxHealth());
        }

        setPermanentlyInvulnerable(def.behavior().invulnerable());

        Equipment gear = def.equipment();
        for (EquipmentSlot slot : Equipment.SLOTS) {
            if (resetGear) {
                setItemSlot(slot, gear.get(slot).copy());
            }
            setDropChance(slot, gear.dropChance());
        }

        rebuildGoals(def.behavior());
    }

    private void setBase(Holder<Attribute> attribute, double value) {
        AttributeInstance inst = getAttribute(attribute);
        if (inst != null) {
            inst.setBaseValue(value);
        }
    }

    // ---- AI ---------------------------------------------------------------------------------------------

    @Override
    protected void registerGoals() {
        // Goals depend on the template, which isn't known yet; see rebuildGoals.
        goalSelector.addGoal(0, new FloatGoal(this));
    }

    private void rebuildGoals(Behavior behavior) {
        goalSelector.removeAllGoals(goal -> true);
        targetSelector.removeAllGoals(goal -> true);
        clearHome();

        boolean fights = behavior.combat() != CombatStance.PASSIVE;
        boolean companion = instance != null && instance.isCompanion();

        boolean patrolling = isPatrolActive();

        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(0, new TalkToPlayerGoal(this));
        goalSelector.addGoal(0, new RulesGoal(this));
        if (!fights && behavior.flee()) {
            goalSelector.addGoal(1, new FleeAndHideGoal(this));
        }
        if (fights) {
            // Exactly one of these runs, depending on whether a ranged weapon is held and can be fired.
            goalSelector.addGoal(1, new NpcRangedAttackGoal(this));
            goalSelector.addGoal(1, new NpcMeleeAttackGoal(this));
        }
        if (companion) {
            goalSelector.addGoal(2, new FollowOwnerGoal(this));
        } else if (patrolling) {
            goalSelector.addGoal(3, new PatrolGoal(this));
        } else if (behavior.movement() == MovementMode.WANDER && instance != null) {
            setHomeTo(BlockPos.containing(instance.home()), behavior.wanderRadius());
            goalSelector.addGoal(3, new MoveTowardsRestrictionGoal(this, 1.0));
            goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
        } else {
            // Stationary (and "patrol" templates whose NPC has no waypoints yet).
            goalSelector.addGoal(3, new ReturnHomeGoal(this));
        }
        if (behavior.lookAtPlayers()) {
            goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        }
        if (companion || (behavior.movement() == MovementMode.WANDER && !patrolling)) {
            goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        } else if (!patrolling) {
            goalSelector.addGoal(9, new FaceHomeGoal(this));
        }

        if (fights) {
            targetSelector.addGoal(1, new HurtByTargetGoal(this));
            if (companion) {
                targetSelector.addGoal(2, new DefendOwnerGoal(this));
            }
            if (behavior.combat() == CombatStance.GUARD || behavior.combat() == CombatStance.AGGRESSIVE) {
                boolean guard = behavior.combat() == CombatStance.GUARD;
                targetSelector.addGoal(2, new DefendAlliedPlayersGoal(this, () -> guard ? guardPost() : position()));
                targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, LivingEntity.class, true,
                        (target, level) -> Factions.isHostile(this, target) && (!guard || isNearGuardPost(target))));
            }
        }
    }

    /** Whether this NPC joins fights at all (anything but a passive stance). */
    public boolean fightsForAllies() {
        return definition().map(d -> d.behavior().combat() != CombatStance.PASSIVE).orElse(false);
    }

    /** Where a guard guards from: its home when standing guard, wherever it is when patrolling or following. */
    private Vec3 guardPost() {
        return instance != null && !instance.isCompanion() && !isPatrolActive() ? instance.home() : position();
    }

    private boolean isNearGuardPost(LivingEntity target) {
        return target.distanceToSqr(guardPost()) < GUARD_RADIUS * GUARD_RADIUS;
    }

    // ---- AI rules ---------------------------------------------------------------------------------------

    private int rulesCheck;
    private @Nullable AiRule activeMovementRule;
    private @Nullable LivingEntity activeRuleTarget;
    private final Map<Integer, Integer> ruleLastFired = new HashMap<>();

    /** The first matching rule with a movement action (flee, go_home, go_to, follow, stop), if any. */
    public @Nullable AiRule activeMovementRule() {
        return activeMovementRule;
    }

    public @Nullable LivingEntity activeRuleTarget() {
        return activeRuleTarget;
    }

    /** Twice a second, top to bottom: the first matching movement rule takes over movement; other matches fire. */
    private void tickRules() {
        if (++rulesCheck < 10) {
            return;
        }
        rulesCheck = 0;
        List<AiRule> rules = definition().map(NpcDefinition::aiRules).orElse(List.of());
        AiRule movement = null;
        LivingEntity movementTarget = null;
        if (isAlive() && instance != null) {
            for (int i = 0; i < rules.size(); i++) {
                AiRule rule = rules.get(i);
                if (rule.then().isMovement() && movement != null) {
                    continue;
                }
                AiRules.Match match = AiRules.check(this, rule);
                if (!match.matched()) {
                    continue;
                }
                if (rule.then().isMovement()) {
                    movement = rule;
                    movementTarget = match.target();
                } else {
                    Integer last = ruleLastFired.get(i);
                    if (last == null || tickCount - last >= rule.cooldownTicks()) {
                        ruleLastFired.put(i, tickCount);
                        AiRules.fire(this, rule, match.target());
                    }
                }
            }
        }
        activeMovementRule = movement;
        activeRuleTarget = movementTarget;
    }

    /** What the AI is doing right now, for {@code /npcmaker debug}: template settings, goals, rules and danger. */
    public List<String> debugLines() {
        List<String> lines = new ArrayList<>();
        definition().ifPresent(def -> {
            Behavior b = def.behavior();
            lines.add("Template: movement " + b.movement().getSerializedName() + ", combat " + b.combat().getSerializedName()
                    + ", flee " + b.flee() + ", " + def.aiRules().size() + " rule(s)");
        });
        for (WrappedGoal goal : goalSelector.getAvailableGoals()) {
            lines.add((goal.isRunning() ? "> " : "   ") + goal.getPriority() + " " + goal.getGoal().getClass().getSimpleName());
        }
        for (WrappedGoal goal : targetSelector.getAvailableGoals()) {
            lines.add((goal.isRunning() ? "> " : "   ") + "target " + goal.getPriority() + " " + goal.getGoal().getClass().getSimpleName());
        }
        lines.add("Rule in control: " + (activeMovementRule == null ? "none"
                : activeMovementRule.when().getSerializedName() + " -> " + activeMovementRule.then().getSerializedName()));
        LivingEntity danger = Threats.nearestDanger(this, 10.0);
        lines.add("Danger nearby: " + (danger == null ? "none" : danger.getName().getString()));
        lines.add("Target: " + (getTarget() == null ? "none" : getTarget().getName().getString()));
        lines.add("Idle ticks: " + getNoActionTime() + (getNoActionTime() >= 100 ? " (no player near, so it won't wander)" : ""));
        return lines;
    }

    /** Speech bubble line from a rule, unless this NPC's bubbles are switched off. */
    public void sayIfAllowed(String line) {
        if (definition().map(d -> d.speech().bubbles()).orElse(true)) {
            sayLine(line);
        }
    }

    /** Uses one of {@code item} from the inventory the vanilla way (for "use_item" rules). */
    public void useInventoryItem(Item item) {
        int slot = findSlot(s -> s.is(item));
        if (slot >= 0) {
            consumeSlot(slot);
        }
    }

    /**
     * Places a block from the inventory the vanilla way (sounds, block states, torches on walls…). Uses {@code item}
     * or else the first placeable item; puts it at {@code pos}, or else beside the NPC (at its feet for blocks it can
     * walk through, like torches). Returns whether something was placed.
     */
    public boolean placeBlock(Optional<Item> item, Optional<BlockPos> pos) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        int slot = findSlot(s -> s.getItem() instanceof BlockItem && item.map(s::is).orElse(true));
        if (slot < 0) {
            return false;
        }
        ItemStack stack = inventory.getItem(slot);
        BlockItem blockItem = (BlockItem) stack.getItem();
        List<BlockPos> spots = new ArrayList<>();
        if (pos.isPresent()) {
            spots.add(pos.get());
        } else {
            BlockPos feet = blockPosition();
            if (blockItem.getBlock().defaultBlockState().getCollisionShape(serverLevel, feet).isEmpty()) {
                spots.add(feet);
            }
            spots.add(feet.relative(getDirection()));
            for (Direction side : Direction.Plane.HORIZONTAL) {
                spots.add(feet.relative(side));
            }
        }
        for (BlockPos spot : spots) {
            if (!serverLevel.getBlockState(spot).canBeReplaced() || spot.distSqr(blockPosition()) > 6 * 6) {
                continue;
            }
            // The dispenser's placing context: no player needed; "looking down" makes torches stand on the floor
            // when they can and go on a wall otherwise.
            var context = new DirectionalPlaceContext(serverLevel, spot, Direction.DOWN, stack, Direction.UP);
            if (blockItem.place(context).consumesAction()) {
                inventory.setChanged();
                return true;
            }
        }
        return false;
    }

    /** Runs a server command as this NPC, with full permissions (for "run_command" rules). */
    public void runCommandAsNpc(String command) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        var source = serverLevel.getServer().createCommandSourceStack()
                .withEntity(this)
                .withLevel(serverLevel)
                .withPosition(position())
                .withPermission(PermissionSet.ALL_PERMISSIONS)
                .withSuppressedOutput();
        try {
            serverLevel.getServer().getCommands().performPrefixedCommand(source, command);
        } catch (Exception e) {
            Constants.LOG.error("AI rule command failed: {}", command, e);
        }
    }

    // ---- route editing (NPC Wand) -----------------------------------------------------------------------

    /** Editing ends on its own if the editor hasn't held the wand near this NPC for this long (1 minute). */
    private static final int EDIT_TIMEOUT_TICKS = 1200;

    private @Nullable UUID routeEditor;
    private int lastEditTouch;

    /** Patrols only with a finished route: while someone is editing it, the NPC stays at home. */
    public boolean isPatrolActive() {
        return instance != null && instance.isPatrolling() && routeEditor == null;
    }

    public boolean isBeingEditedBy(Player player) {
        return player.getUUID().equals(routeEditor);
    }

    /** A player picked this NPC with the wand: hold still until they finish. */
    public void startRouteEdit(Player player) {
        routeEditor = player.getUUID();
        lastEditTouch = tickCount;
        getNavigation().stop();
        applyTemplate();
    }

    /** Keeps an edit alive while the editor is still holding the wand. */
    public void touchRouteEdit(Player player) {
        if (isBeingEditedBy(player)) {
            lastEditTouch = tickCount;
        }
    }

    /** The route is finished (or abandoned): start walking it. */
    public void finishRouteEdit() {
        if (routeEditor != null) {
            routeEditor = null;
            applyTemplate();
        }
    }

    private void tickRouteEdit() {
        if (routeEditor != null && tickCount - lastEditTouch > EDIT_TIMEOUT_TICKS) {
            finishRouteEdit();
        }
    }

    /** Replaces this NPC's patrol route (empty stops patrolling) and restarts its AI with it. */
    public void setPatrol(PatrolRoute route) {
        if (instance == null) {
            return;
        }
        instance = instance.withPatrol(route);
        getNavigation().stop();
        applyTemplate();
    }

    // ---- inventory and item use -------------------------------------------------------------------------

    private final SimpleContainer inventory = new SimpleContainer(NpcDefinition.INVENTORY_SIZE);
    private boolean inventoryStocked;
    private int itemUseCheck;
    private int lastAteTick = -1000;
    private int lastCombatTick = -1000;

    public SimpleContainer inventory() {
        return inventory;
    }

    /** Refills the inventory and gear from the template (on placement, respawn, reload or {@code /npcmaker restock}). */
    public void restock() {
        inventory.clearContent();
        definition().ifPresent(def -> {
            for (EquipmentSlot slot : Equipment.SLOTS) {
                setItemSlot(slot, def.equipment().get(slot).copy());
            }
            List<ItemStack> items = def.inventory();
            for (int i = 0; i < items.size() && i < inventory.getContainerSize(); i++) {
                inventory.setItem(i, items.get(i).copy());
            }
        });
        inventoryStocked = true;
    }

    private Optional<ItemUse> itemUse() {
        return definition().map(d -> d.behavior().itemUse());
    }

    private static boolean isRangedWeapon(ItemStack stack) {
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem;
    }

    private static boolean isMeleeWeapon(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(Items.TRIDENT) || stack.is(Items.MACE)
                || stack.is(ItemTags.SPEARS);
    }

    private int findSlot(java.util.function.Predicate<ItemStack> test) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && test.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasAmmo() {
        return itemUse().map(u -> !u.useAmmo()).orElse(true) || findSlot(s -> s.is(ItemTags.ARROWS)) >= 0;
    }

    /** Holding a bow or crossbow with something to fire (used to pick between ranged and melee attacks). */
    public boolean canFireRangedWeapon() {
        return isRangedWeapon(getMainHandItem()) && hasAmmo();
    }

    /** Fires one arrow at the target, using ammo from the inventory unless the template gives unlimited arrows. */
    public void shootAt(LivingEntity target, float power) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        ItemStack weapon = getMainHandItem();
        ItemStack ammo;
        boolean useAmmo = itemUse().map(ItemUse::useAmmo).orElse(true);
        int slot = findSlot(s -> s.is(ItemTags.ARROWS));
        if (useAmmo) {
            if (slot < 0) {
                return;
            }
            ammo = inventory.getItem(slot).split(1);
            inventory.setChanged();
        } else {
            ammo = slot >= 0 ? inventory.getItem(slot).copyWithCount(1) : new ItemStack(Items.ARROW);
        }
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, ammo, power, weapon);
        double dx = target.getX() - getX();
        double dy = target.getY(1.0 / 3.0) - arrow.getY();
        double dz = target.getZ() - getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float uncertainty = 14 - serverLevel.getDifficulty().getId() * 4;
        Projectile.spawnProjectileUsingShoot(arrow, serverLevel, ammo, dx, dy + horizontal * 0.2, dz, 1.6F, uncertainty);
        playSound(weapon.getItem() instanceof CrossbowItem ? SoundEvents.CROSSBOW_SHOOT : SoundEvents.ARROW_SHOOT,
                1.0F, 1.0F / (getRandom().nextFloat() * 0.4F + 0.8F));
    }

    /** Twice a second: drink, eat or swap weapons as the template allows. */
    private void tickItemUse() {
        if (getTarget() != null) {
            lastCombatTick = tickCount;
        }
        if (++itemUseCheck < 10 || !isAlive() || isUsingItem()) {
            return;
        }
        itemUseCheck = 0;
        Optional<ItemUse> found = itemUse();
        if (found.isEmpty()) {
            return;
        }
        ItemUse use = found.get();
        if (use.cure() && hasHarmfulEffect() && consumeFirst(s -> s.is(Items.MILK_BUCKET))) {
            return;
        }
        if (use.heal() && getHealth() < getMaxHealth() * 0.4F && consumeFirst(NpcEntity::isHealingItem)) {
            return;
        }
        boolean outOfCombat = tickCount - lastCombatTick > 100;
        if (use.eat() && outOfCombat && getHealth() < getMaxHealth() && tickCount - lastAteTick > 60) {
            int slot = findSlot(s -> s.has(DataComponents.FOOD) && !isHealingItem(s));
            if (slot >= 0) {
                FoodProperties food = inventory.getItem(slot).get(DataComponents.FOOD);
                consumeSlot(slot);
                heal(food == null ? 1.0F : Math.max(1.0F, food.nutrition()));
                lastAteTick = tickCount;
                return;
            }
        }
        if (use.switchWeapons()) {
            switchWeapons();
        }
        if (use.lightUp() && outOfCombat && --lightCheck <= 0) {
            lightCheck = 4;
            lightUpDarkSpot();
        }
    }

    private int lightCheck;

    /**
     * Torch on the spot it's standing on if that's dark (light below 8: night outdoors, or a cave), it carries
     * torches, and it's near home, or anywhere for a companion lighting the way.
     */
    private void lightUpDarkSpot() {
        if (instance == null || level().getMaxLocalRawBrightness(blockPosition()) >= 8) {
            return;
        }
        if (!instance.isCompanion() && distanceToSqr(instance.home()) > 16 * 16) {
            return;
        }
        int slot = findSlot(s -> s.is(Items.TORCH) || s.is(Items.SOUL_TORCH) || s.is(Items.COPPER_TORCH));
        if (slot >= 0) {
            placeBlock(Optional.of(inventory.getItem(slot).getItem()), Optional.of(blockPosition()));
        }
    }

    private boolean hasHarmfulEffect() {
        return getActiveEffects().stream().anyMatch(e -> e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL);
    }

    private static boolean isHealingItem(ItemStack stack) {
        if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
            return true;
        }
        if (!stack.is(Items.POTION)) {
            return false; // splash and lingering potions are thrown, not drunk
        }
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return false;
        }
        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (effect.getEffect().is(MobEffects.INSTANT_HEALTH) || effect.getEffect().is(MobEffects.REGENERATION)) {
                return true;
            }
        }
        return false;
    }

    private boolean consumeFirst(java.util.function.Predicate<ItemStack> test) {
        int slot = findSlot(test);
        if (slot < 0) {
            return false;
        }
        consumeSlot(slot);
        return true;
    }

    /** Uses one item the vanilla way (effects, sounds, particles); leftovers like bottles and buckets go back in. */
    private void consumeSlot(int slot) {
        ItemStack single = inventory.getItem(slot).split(1);
        ItemStack remainder = single.finishUsingItem(level(), this);
        if (!remainder.isEmpty()) {
            ItemStack leftover = inventory.addItem(remainder);
            if (!leftover.isEmpty()) {
                spawnAtLocation((ServerLevel) level(), leftover);
            }
        }
        inventory.setChanged();
    }

    /**
     * Bow or crossbow for targets beyond 7 blocks (if there's ammo), back to a melee weapon inside 4 blocks or when
     * out of arrows, and back to the template's own weapon once the fight is over.
     */
    private void switchWeapons() {
        ItemStack held = getMainHandItem();
        LivingEntity target = getTarget();
        if (target == null) {
            ItemStack usual = definition().map(d -> d.equipment().mainHand()).orElse(ItemStack.EMPTY);
            if (!usual.isEmpty() && !ItemStack.isSameItem(held, usual) && tickCount - lastCombatTick > 100) {
                swapInto(findSlot(s -> ItemStack.isSameItem(s, usual)));
            }
            return;
        }
        double distanceSqr = distanceToSqr(target);
        if (!isRangedWeapon(held) && distanceSqr > 7 * 7 && hasAmmo()) {
            swapInto(findSlot(NpcEntity::isRangedWeapon));
        } else if (isRangedWeapon(held) && (distanceSqr < 4 * 4 || !hasAmmo())) {
            swapInto(findSlot(NpcEntity::isMeleeWeapon));
        }
    }

    private void swapInto(int slot) {
        if (slot < 0) {
            return;
        }
        ItemStack next = inventory.getItem(slot);
        ItemStack previous = getMainHandItem();
        stopUsingItem();
        setItemSlot(EquipmentSlot.MAINHAND, next);
        inventory.setItem(slot, previous);
    }

    // ---- companions -------------------------------------------------------------------------------------

    public @Nullable Player companionOwner() {
        if (instance == null || instance.owner().isEmpty()) {
            return null;
        }
        return level().getPlayerByUUID(instance.owner().get());
    }

    public boolean isCompanionOf(Player player) {
        return instance != null && instance.owner().map(id -> id.equals(player.getUUID())).orElse(false);
    }

    /** Makes this NPC a companion of {@code owner}, or a normal NPC again when empty (it then stays where it is). */
    public void setOwner(Optional<UUID> owner) {
        if (instance == null) {
            return;
        }
        instance = instance.withOwner(owner);
        if (owner.isEmpty()) {
            instance = instance.withHome(position(), getYRot(), getXRot());
        }
        setTarget(null);
        applyTemplate();
    }

    public void moveHome(Vec3 pos, float yaw, float pitch) {
        if (instance == null) {
            return;
        }
        instance = instance.withHome(pos, yaw, pitch);
        snapTo(pos.x, pos.y, pos.z, yaw, pitch);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
        getNavigation().stop();
        applyTemplate();
    }

    // ---- interaction ------------------------------------------------------------------------------------

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || player.getItemInHand(hand).getItem() instanceof NpcWandItem) {
            return InteractionResult.PASS; // the wand handles its own clicks on NPCs
        }
        if (level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        Optional<NpcDefinition> found = definition();
        if (instance == null || found.isEmpty()) {
            return InteractionResult.PASS;
        }
        NpcDefinition def = found.get();

        if (player.isShiftKeyDown() && isCompanionOf(player)) {
            instance = instance.withWaiting(!instance.waiting());
            getNavigation().stop();
            player.sendSystemMessage(getDisplayName().copy().append(Component.literal(
                    instance.waiting() ? " will wait here." : " is following you.").withStyle(ChatFormatting.GRAY)));
            return InteractionResult.SUCCESS_SERVER;
        }
        if (player.isShiftKeyDown() && NpcPermissions.canEdit(player) && player instanceof ServerPlayer editor) {
            NpcEditing.open(editor, this);
            return InteractionResult.SUCCESS_SERVER;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        // Quest "talk to" / "deliver" goals first, so the conversation already sees the new quest state.
        boolean questProgress = QuestManager.onTalk(serverPlayer, this);
        if (!DialogueManager.start(serverPlayer, this)) {
            return questProgress ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS;
        }
        def.sounds().greet().ifPresent(this::playSpec);
        def.effects().greetEmote().ifPresent(this::playAnimation);
        showEffect(Effects::onGreet);
        return InteractionResult.SUCCESS_SERVER;
    }

    // ---- conversations ----------------------------------------------------------------------------------

    private @Nullable UUID conversationPartner;

    public @Nullable Player conversationPartner() {
        if (conversationPartner == null) {
            return null;
        }
        Player player = level().getPlayerByUUID(conversationPartner);
        if (player == null) {
            conversationPartner = null;
        }
        return player;
    }

    public void startConversation(Player player) {
        conversationPartner = player.getUUID();
        getNavigation().stop();
        clearSpeech();
    }

    /** @param player who left; null clears any conversation */
    public void endConversation(@Nullable Player player) {
        if (player == null || player.getUUID().equals(conversationPartner)) {
            conversationPartner = null;
        }
    }

    // ---- sounds -----------------------------------------------------------------------------------------

    private void playSpec(SoundSpec spec) {
        playSound(spec.event(), spec.volume(), spec.pitch());
    }

    @Override
    public void playAmbientSound() {
        definition().flatMap(d -> d.sounds().ambient()).ifPresent(this::playSpec);
    }

    @Override
    protected @Nullable SoundEvent getHurtSound(DamageSource source) {
        return definition().flatMap(d -> d.sounds().hurt()).map(SoundSpec::event).orElse(SoundEvents.PLAYER_HURT);
    }

    @Override
    protected @Nullable SoundEvent getDeathSound() {
        return definition().flatMap(d -> d.sounds().death()).map(SoundSpec::event).orElse(SoundEvents.PLAYER_DEATH);
    }

    // ---- death and persistence --------------------------------------------------------------------------

    /** NPCs sharing a faction are allies: vanilla targeting (including revenge) then leaves them alone. */
    @Override
    protected boolean considersEntityAsAlly(Entity other) {
        return super.considersEntityAsAlly(other)
                || other instanceof NpcEntity npc && Factions.relation(this, npc) == Relation.ALLIED;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (source.getEntity() instanceof NpcEntity attacker && attacker != this && isAlliedTo(attacker)) {
            // No friendly fire, e.g. an archer's stray arrow hitting the guard next to it.
            return false;
        }
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) {
            showEffect(Effects::onHurt);
        }
        if (hurt && source.getEntity() instanceof ServerPlayer player && PlayerFactions.wasWarningHit(player, this)) {
            // A first, possibly accidental hit from someone who isn't an enemy: don't hold a grudge yet.
            setLastHurtByMob(null);
            if (getTarget() == player) {
                setTarget(null);
            }
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        boolean wasDead = dead;
        super.die(source);
        if (!wasDead && dead) {
            showEffect(Effects::onDeath);
        }
        if (!wasDead && dead && instance != null && level() instanceof ServerLevel serverLevel) {
            definition().filter(d -> d.behavior().respawn())
                    .ifPresent(d -> NpcRespawns.schedule(serverLevel, instance, d.behavior().respawnSeconds()));
        }
    }

    /** Glows (gold, only for the player who asked) while "Find" in the Live list is lighting it up. */
    @Override
    public boolean isCurrentlyGlowing() {
        return (level().isClientSide() && LocateGlow.isLit(getId())) || super.isCurrentlyGlowing();
    }

    @Override
    public int getTeamColor() {
        return level().isClientSide() && LocateGlow.isLit(getId()) ? LocateGlow.COLOR : super.getTeamColor();
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            tickFacing();
            return;
        }
        // Safety net: an NPC whose chunk loaded before its template was available picks it up now.
        if (instance != null && templateId().isEmpty()) {
            applyTemplate();
        }
        if (instance != null && !inventoryStocked && definition().isPresent()) {
            // NPCs saved before inventories existed get their template's starting items once.
            restock();
        }
        tickSpeech();
        tickAnimationsAndAura();
        tickItemUse();
        tickRouteEdit();
        tickRules();
    }

    // ---- speech bubbles ---------------------------------------------------------------------------------

    private int speechTicks;
    private int idleCooldown = 100;
    private int speechCheck;
    private final Set<UUID> playersNear = new HashSet<>();
    private final Map<UUID, Long> lastApproachLine = new HashMap<>();

    /** The line currently shown above the NPC's head, if any (synced to clients). */
    public @Nullable Component speech() {
        return entityData.get(DATA_SPEECH).orElse(null);
    }

    /**
     * Once a second: greet players who just came within range (approach lines, once per player per cooldown),
     * otherwise say an idle line now and then while someone is near. Never during a conversation.
     */
    private void tickSpeech() {
        if (speechTicks > 0 && --speechTicks == 0) {
            entityData.set(DATA_SPEECH, Optional.empty());
        }
        if (idleCooldown > 0) {
            idleCooldown--;
        }
        if (++speechCheck < 20) {
            return;
        }
        speechCheck = 0;
        Optional<Speech> found = definition().map(NpcDefinition::speech);
        if (found.isEmpty() || !found.get().bubbles() || !isAlive()) {
            playersNear.clear();
            return;
        }
        Speech speech = found.get();
        long now = level().getGameTime();
        double radiusSqr = speech.radius() * (double) speech.radius();
        Set<UUID> nearNow = new HashSet<>();
        Player newcomer = null;
        for (Player player : level().players()) {
            if (player.isSpectator() || player.distanceToSqr(this) > radiusSqr) {
                continue;
            }
            nearNow.add(player.getUUID());
            if (newcomer == null && !playersNear.contains(player.getUUID())) {
                Long last = lastApproachLine.get(player.getUUID());
                if (last == null || now - last >= speech.approachCooldownSeconds() * 20L) {
                    newcomer = player;
                }
            }
        }
        playersNear.clear();
        playersNear.addAll(nearNow);
        lastApproachLine.values().removeIf(t -> now - t > speech.approachCooldownSeconds() * 20L);
        if (conversationPartner != null) {
            return;
        }
        if (newcomer != null && !speech.approach().isEmpty()) {
            lastApproachLine.put(newcomer.getUUID(), now);
            sayLine(pick(speech.approach()));
            idleCooldown = Math.max(idleCooldown, nextIdleDelay(speech));
            return;
        }
        if (nearNow.isEmpty() || speechTicks > 0 || idleCooldown > 0 || speech.idle().isEmpty()) {
            return;
        }
        sayLine(pick(speech.idle()));
        idleCooldown = nextIdleDelay(speech);
    }

    /** The first, possibly accidental hit from a non-hostile player (two-hit rule), if this NPC warns at all. */
    public void sayHitWarning() {
        definition().map(NpcDefinition::speech)
                .filter(s -> s.bubbles() && s.warnOnHit() && !s.hitWarning().isEmpty())
                .ifPresent(s -> sayLine(pick(s.hitWarning())));
    }

    private void sayLine(String line) {
        Component text = TextTags.parse(line);
        // Long enough to read: about 3 s plus a little per character, at most 10 s.
        say(text, Math.min(200, 60 + text.getString().length() * 2));
    }

    private String pick(List<String> lines) {
        return lines.get(getRandom().nextInt(lines.size()));
    }

    private int nextIdleDelay(Speech speech) {
        float jitter = 0.75F + getRandom().nextFloat() * 0.5F;
        return (int) (speech.intervalSeconds() * 20 * jitter);
    }

    /** Shows a line in the speech bubble for {@code ticks}, replacing any idle line. */
    public void say(Component text, int ticks) {
        entityData.set(DATA_SPEECH, Optional.of(text));
        speechTicks = ticks;
    }

    private void clearSpeech() {
        speechTicks = 0;
        entityData.set(DATA_SPEECH, Optional.empty());
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (instance != null) {
            output.store(Constants.MOD_ID, NpcInstance.CODEC, instance);
        }
        if (inventoryStocked) {
            List<ItemStack> items = new java.util.ArrayList<>();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                items.add(inventory.getItem(i));
            }
            output.store(INVENTORY_KEY, ItemStack.OPTIONAL_CODEC.listOf(), items);
        }
    }

    private static final String INVENTORY_KEY = Constants.MOD_ID + "_inventory";

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read(Constants.MOD_ID, NpcInstance.CODEC).ifPresent(loaded -> {
            instance = loaded;
            applyTemplate();
        });
        input.read(INVENTORY_KEY, ItemStack.OPTIONAL_CODEC.listOf()).ifPresent(items -> {
            inventory.clearContent();
            for (int i = 0; i < items.size() && i < inventory.getContainerSize(); i++) {
                inventory.setItem(i, items.get(i));
            }
            inventoryStocked = true;
        });
    }

    /** Convenience for chat output: the NPC's coloured name, or its template id while unnamed. */
    public Component label() {
        return hasCustomName() ? getDisplayName() : Component.literal(templateId());
    }

    public static List<NpcEntity> only(Collection<? extends Entity> entities) {
        return entities.stream().filter(NpcEntity.class::isInstance).map(NpcEntity.class::cast).toList();
    }
}
