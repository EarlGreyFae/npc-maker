package com.npcmaker.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.NativeImage;
import com.npcmaker.content.SkinLibrary;
import com.npcmaker.network.SkinPayloads;
import com.npcmaker.network.ContentPayloads;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import com.npcmaker.data.Appearance;
import com.npcmaker.data.ArmStyle;
import com.npcmaker.entity.NpcEntity;
import com.npcmaker.entity.NpcEntityTypes;
import com.npcmaker.network.EditorResultPayload;
import com.npcmaker.network.NpcNetworking;
import com.npcmaker.network.OpenEditorPayload;
import com.npcmaker.network.SaveNpcPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The in-game NPC editor (sneak-right-click an NPC, or {@code /npcmaker edit}): section tabs down the left, the
 * section's fields in the middle, a turning preview of the NPC on the right. Saving always asks whether to update the
 * template (every NPC using it) or give only this NPC its own copy, and that choice takes two clicks.
 *
 * <p>Fields edit the template's JSON. Only fields that are changed get written into the file, so a short file that
 * leans on an AI template stays short. Uses NpcUiTheme.
 */
public class NpcEditorScreen extends Screen {

    // ---- layout -------------------------------------------------------------------------------------------

    private static final int MAX_W = 470;
    private static final int MAX_H = 270;
    private static final int TABS_W = 82;
    /** Most space a side tab gets; tabs squeeze together on short screens so all of them fit. */
    private static final int TAB_STEP = 16;
    private static final int PREVIEW_W = 104;
    private static final int HEADER = 26;
    private static final int FOOTER = 26;
    private static final int ROW = 18;
    private static final int PAD = 8;
    private static final int BUTTON_H = 14;
    private static final long CONFIRM_MS = 3000L;
    /** An id no real entity uses (real ids count up from 1). */
    private static final int PREVIEW_ENTITY_ID = -0x4E50_4300;

    // ---- sections and fields ------------------------------------------------------------------------------

    private enum Tab {
        LOOK("Look"), STATS("Stats"), BEHAVIOUR("Behaviour"), RULES("AI rules"), GEAR("Gear"), INVENTORY("Inventory"), FACTIONS("Factions"),
        SOUNDS("Sounds"), ANIMATION("Animation"), DIALOGUE("Dialogue"), QUESTS("Quests"), LOCATION("Location"), LIBRARY("Library");

        final String label;

        Tab(String label) {
            this.label = label;
        }

        /** Every section is built now; kept so a future section can show greyed out until it's ready. */
        boolean ready() {
            return true;
        }
    }

    private sealed interface Field permits TextField, NumberField, ToggleField, ChoiceField, SkinFileField, ModelField,
            ButtonField, GearSlotField, SoundField, LinesField, ParticleField, EmotesField, AnimationField {
        String label();

        String path();
    }

    /** @param optional blank means "not set" (the key is removed) */
    private record TextField(String label, String path, String fallback, boolean optional, boolean color) implements Field {
    }

    private record NumberField(String label, String path, double fallback, double min, double max, boolean whole) implements Field {
    }

    private record ToggleField(String label, String path, boolean fallback) implements Field {
    }

    /** @param noneValue an option that removes the key instead of writing it, or null */
    private record ChoiceField(String label, String path, String fallback, List<String> options, @Nullable String noneValue)
            implements Field {
    }

    /** A skin from the world's shared library; click to cycle through it (or "none"). */
    private record SkinFileField(String label, String path) implements Field {
    }

    /** Which mob the NPC looks like; click to open the searchable mob list. */
    private record ModelField(String label, String path) implements Field {
    }

    /** A button filling the row; {@code action} says what it does. */
    private record ButtonField(String label, String button, String action) implements Field {
        public String path() {
            return "";
        }
    }

    /** One worn or held item: click to pick from the item picker, right-click to clear. */
    private record GearSlotField(String label, String path, EquipmentSlot slot) implements Field {
    }

    /** A sound: click to open the sound list (with ▶ previews), right-click to clear. */
    private record SoundField(String label, String path) implements Field {
    }

    /** A list of lines the NPC says; click to edit them. */
    private record LinesField(String label, String path, String title) implements Field {
    }

    /** Particles: click to pick from every particle (any mod), right-click to clear. */
    private record ParticleField(String label, String path) implements Field {
    }

    /** Several emotes: click to tick them in a list. */
    private record EmotesField(String label, String path) implements Field {
    }

    /** One animation: a built-in pose or emote, or a custom animation. Click to pick, right-click to clear. */
    private record AnimationField(String label, String path, boolean poses) implements Field {
    }

    private static List<Field> fieldsFor(Tab tab) {
        return switch (tab) {
            case ANIMATION -> List.of(
                    new AnimationField("Idle pose", "effects.idle_pose", true),
                    new EmotesField("Idle emotes", "effects.idle_emotes"),
                    new NumberField("Emote every (s)", "effects.emote_interval_seconds", 40, 5, 3600, true),
                    new AnimationField("Greeting emote", "effects.greet_emote", false),
                    new ParticleField("Aura", "effects.aura"),
                    new ParticleField("On greet", "effects.on_greet"),
                    new ParticleField("On hurt", "effects.on_hurt"),
                    new ParticleField("On death", "effects.on_death"),
                    new ParticleField("Quest given", "effects.on_quest_given"),
                    new ParticleField("Quest done", "effects.on_quest_done"),
                    new ButtonField("", "▶ Preview the next emote on the model", "preview_emote"));
            case SOUNDS -> List.of(
                    new SoundField("Idle sound", "sounds.ambient"),
                    new SoundField("Greeting sound", "sounds.greet"),
                    new SoundField("Hurt sound", "sounds.hurt"),
                    new SoundField("Death sound", "sounds.death"),
                    new SoundField("Talking blips", "sounds.voice.sound"),
                    new NumberField("Voice pitch (0.5–2)", "sounds.voice.pitch", 1.0, 0.5, 2.0, false),
                    new ToggleField("Speech bubbles", "speech.bubbles", true),
                    new LinesField("Idle lines", "speech.idle", "Idle lines (said now and then near players)"),
                    new LinesField("Lines on approach", "speech.approach", "Said when a player comes near"),
                    new NumberField("Idle line every (s)", "speech.interval_seconds", 30, 5, 3600, true));
            case GEAR -> List.of(
                    new GearSlotField("Main hand", "equipment.main_hand", EquipmentSlot.MAINHAND),
                    new GearSlotField("Off hand", "equipment.off_hand", EquipmentSlot.OFFHAND),
                    new GearSlotField("Head", "equipment.head", EquipmentSlot.HEAD),
                    new GearSlotField("Chest", "equipment.chest", EquipmentSlot.CHEST),
                    new GearSlotField("Legs", "equipment.legs", EquipmentSlot.LEGS),
                    new GearSlotField("Feet", "equipment.feet", EquipmentSlot.FEET),
                    new NumberField("Drop chance (0–1)", "equipment.drop_chance", 0.0, 0.0, 1.0, false));
            case LOOK -> List.of(
                    new TextField("Name", "name", "", false, false),
                    new TextField("Name colour", "appearance.name_color", "#FFFFFF", false, true),
                    new ToggleField("Show name", "appearance.show_name", true),
                    new ModelField("Looks like", "appearance.model"),
                    new SkinFileField("Skin file", "appearance.skin_file"),
                    new ButtonField("", "Upload skin file…  (or drop a PNG here)", "upload"),
                    new TextField("Skin (player name)", "appearance.skin_username", "", true, false),
                    new ChoiceField("Arms", "appearance.arms", "auto", List.of("auto", "wide", "slim"), null),
                    new NumberField("Size", "appearance.scale", 1.0, 0.25, 4.0, false));
            case STATS -> List.of(
                    new NumberField("Health", "stats.health", 20.0, 1.0, 1024.0, false),
                    new NumberField("Strength", "stats.strength", 3.0, 0.0, 2048.0, false),
                    new NumberField("Armor", "stats.armor", 0.0, 0.0, 30.0, false),
                    new NumberField("Speed", "stats.speed", 0.3, 0.0, 1.0, false),
                    new NumberField("Knockback resist.", "stats.knockback_resistance", 0.0, 0.0, 1.0, false));
            case BEHAVIOUR -> List.of(
                    new ChoiceField("AI template", "ai_template", "none",
                            List.of("none", "guard", "townsfolk", "bandit", "companion"), "none"),
                    new ChoiceField("Movement", "behavior.movement", "stationary",
                            List.of("stationary", "wander", "patrol", "follow"), null),
                    new NumberField("Wander radius", "behavior.wander_radius", 8, 1, 128, true),
                    new ChoiceField("Combat", "behavior.combat", "passive",
                            List.of("passive", "defensive", "guard", "aggressive"), null),
                    new ToggleField("Flees from danger", "behavior.flee", true),
                    new ToggleField("Can't be hurt", "behavior.invulnerable", true),
                    new ToggleField("Looks at players", "behavior.look_at_players", true),
                    new ToggleField("Respawns", "behavior.respawn", true),
                    new NumberField("Respawn after (s)", "behavior.respawn_seconds", 60, 1, 86400, true));
            default -> List.of();
        };
    }

    // ---- state --------------------------------------------------------------------------------------------

    private OpenEditorPayload data;
    private JsonObject raw;
    private JsonObject effective;
    private Tab tab = Tab.LOOK;
    private boolean dirty;
    private final Set<String> invalid = new HashSet<>();
    private final List<EditBox> boxes = new ArrayList<>();

    // location (always per-NPC)
    private double homeX;
    private double homeY;
    private double homeZ;
    private float yaw;
    private float pitch;

    // save choice: -1 none, else SaveNpcPayload.ALL / ONLY_THIS; armed = waiting for the confirming click
    private boolean choosingScope;
    private int armedScope = -1;
    private long armedAt;
    private long discardArmedAt = -1L;

    private Component status = Component.empty();
    private int statusColor = NpcUiTheme.BORDER_DIM;

    private @Nullable NpcEntity preview;

    /** Hover tooltips and the (?) help mode. */
    private final HelpLayer help = new HelpLayer();

    private final RulesPanel rulesPanel = new RulesPanel(new RulesPanel.Host() {
        @Override
        public net.minecraft.client.gui.Font font() {
            return font;
        }

        @Override
        public com.google.gson.JsonArray rules() {
            JsonElement e = get(effective, "ai_rules");
            return e != null && e.isJsonArray() ? e.getAsJsonArray() : new com.google.gson.JsonArray();
        }

        @Override
        public void setRules(com.google.gson.JsonArray rules) {
            // Written even when empty, so deleting every rule also drops an AI template's rules.
            set("ai_rules", rules);
        }

        @Override
        public void addBox(EditBox box) {
            boxes.add(box);
            addRenderableWidget(box);
        }

        @Override
        public void rebuild() {
            rebuildWidgets();
        }

        @Override
        public void pickItem(String title, java.util.function.Consumer<ItemStack> onPick) {
            openPicker(title, onPick);
        }

        @Override
        public void pickFrom(String title, List<PickListScreen.Option> options, String current,
                             java.util.function.@Nullable Consumer<String> preview, java.util.function.Consumer<String> onPick) {
            minecraft.gui.setScreen(PickListScreen.single(NpcEditorScreen.this, title, options, current, "Clear", preview, onPick));
        }
    });

    private NpcEditorScreen(OpenEditorPayload data) {
        super(Component.literal("Edit NPC"));
        load(data);
    }

    public static void open(OpenEditorPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof NpcEditorScreen screen && screen.data.entityId() == payload.entityId()) {
            screen.load(payload);
            screen.rebuildWidgets();
        } else if (mc.gui.screen() instanceof DialogueGraphScreen graph && graph.parentEditor() != null
                && graph.parentEditor().data.entityId() == payload.entityId()) {
            // A conversation save refreshes the editor behind the graph; stay on the graph.
            graph.parentEditor().load(payload);
        } else {
            mc.gui.setScreen(new NpcEditorScreen(payload));
        }
    }

    /** How many loaded NPCs share this NPC's template. */
    int templateUsers() {
        return data.users();
    }

    /** The template this NPC uses. */
    String templateId() {
        return data.template();
    }

    public static void result(EditorResultPayload payload) {
        if (Minecraft.getInstance().gui.screen() instanceof ClientContent.Listener listener) {
            listener.showResult(payload.ok(), payload.message());
        } else if (Minecraft.getInstance().gui.screen() instanceof DialogueGraphScreen) {
            DialogueGraphScreen.result(payload.ok(), payload.message());
        } else if (Minecraft.getInstance().gui.screen() instanceof NpcEditorScreen screen) {
            screen.status = Component.literal(payload.message());
            screen.statusColor = payload.ok() ? 0xFF7FD36A : 0xFFE04848;
        }
    }

    private void load(OpenEditorPayload payload) {
        this.data = payload;
        this.raw = parseObject(payload.rawJson());
        this.effective = parseObject(payload.effectiveJson());
        this.homeX = payload.home().x;
        this.homeY = payload.home().y;
        this.homeZ = payload.home().z;
        this.yaw = payload.yaw();
        this.pitch = payload.pitch();
        this.dirty = false;
        this.gearChanged = false;
        this.invalid.clear();
        this.choosingScope = false;
        this.armedScope = -1;
        this.discardArmedAt = -1L;
    }

    private static JsonObject parseObject(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    // ---- JSON model ---------------------------------------------------------------------------------------

    private static @Nullable JsonElement get(JsonObject root, String path) {
        JsonElement cur = root;
        for (String key : path.split("\\.")) {
            if (cur == null || !cur.isJsonObject()) {
                return null;
            }
            cur = cur.getAsJsonObject().get(key);
        }
        return cur;
    }

    /** Sets (or with null removes) a value in both the file JSON and the shown values. */
    private void set(String path, @Nullable JsonElement value) {
        put(raw, path, value);
        put(effective, path, value);
        dirty = true;
        armedScope = -1;
        updatePreview();
    }

    private static void put(JsonObject root, String path, @Nullable JsonElement value) {
        String[] keys = path.split("\\.");
        JsonObject cur = root;
        for (int i = 0; i < keys.length - 1; i++) {
            JsonElement next = cur.get(keys[i]);
            if (next == null || !next.isJsonObject()) {
                if (value == null) {
                    return;
                }
                next = new JsonObject();
                cur.add(keys[i], next);
            }
            cur = next.getAsJsonObject();
        }
        if (value == null || value instanceof JsonNull) {
            cur.remove(keys[keys.length - 1]);
        } else {
            cur.add(keys[keys.length - 1], value);
        }
    }

    private String text(Field f, String fallback) {
        JsonElement e = get(effective, f.path());
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    private boolean bool(ToggleField f) {
        JsonElement e = get(effective, f.path());
        return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : f.fallback();
    }

    private static String formatNumber(double value, boolean whole) {
        if (whole || value == Math.rint(value)) {
            return String.valueOf((long) Math.rint(value));
        }
        String s = String.format(Locale.ROOT, "%.3f", value);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    // ---- layout helpers -----------------------------------------------------------------------------------

    private int panelW() {
        return Math.min(MAX_W, width - 16);
    }

    private int panelH() {
        return Math.min(MAX_H, height - 16);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelY() {
        return (height - panelH()) / 2;
    }

    /** Vertical step between side tabs: 16 px, or less so every tab fits above the footer divider. */
    private int tabStep() {
        return Math.max(10, Math.min(TAB_STEP, (bodyBottom() - bodyTop() - 4) / Tab.values().length));
    }

    private int tabH() {
        return tabStep() - 1;
    }

    private int bodyTop() {
        return panelY() + HEADER;
    }

    private int bodyBottom() {
        return panelY() + panelH() - FOOTER;
    }

    private int fieldsX() {
        return panelX() + TABS_W + PAD;
    }

    private int fieldsW() {
        return panelW() - TABS_W - PREVIEW_W - PAD * 2;
    }

    /** Controls take the right half of the fields column. */
    private int controlX() {
        return fieldsX() + fieldsW() / 2;
    }

    private int controlW() {
        return fieldsW() - fieldsW() / 2;
    }

    private int rowY(int index) {
        return bodyTop() + 4 + index * ROW;
    }

    private int previewX() {
        return panelX() + panelW() - PREVIEW_W;
    }

    private int footerY() {
        return panelY() + panelH() - FOOTER + 6;
    }

    // ---- widgets ------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        boxes.clear();
        if (preview == null && minecraft.level != null) {
            preview = NpcEntityTypes.npc().create(minecraft.level, EntitySpawnReason.LOAD);
            if (preview != null) {
                // Never added to the world, so it never gets an id, and 26.3 won't draw an entity without one.
                preview.setId(PREVIEW_ENTITY_ID);
            }
            if (preview != null && minecraft.level.getEntity(data.entityId()) instanceof NpcEntity real) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    preview.setItemSlot(slot, real.getItemBySlot(slot).copy());
                }
            }
        }
        updatePreview();

        if (pickingMob) {
            EditBox search = new EditBox(font, fieldsX() + 4, bodyTop() + 8, fieldsW() - 8, 10, Component.empty());
            search.setBordered(false);
            search.setMaxLength(48);
            search.setTextColor(NpcUiTheme.TEXT);
            search.setHint(Component.literal("Search mobs…").withColor(0xFF6E6578));
            search.setValue(mobSearch);
            search.setResponder(s -> {
                mobSearch = s;
                mobScroll = 0;
            });
            addRenderableWidget(search);
            setInitialFocus(search);
            return;
        }
        if (tab == Tab.LOCATION) {
            addLocationBoxes();
            return;
        }
        if (tab == Tab.RULES) {
            // Rules use the preview's space too: the rows need the width.
            rulesPanel.layout(fieldsX(), bodyTop() + 6, panelW() - TABS_W - PAD * 2, bodyBottom() - bodyTop() - 10);
            rulesPanel.init();
            return;
        }
        List<Field> fields = fieldsFor(tab);
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            int y = rowY(i);
            if (field instanceof TextField t) {
                EditBox box = box(y, text(t, t.fallback()));
                box.setResponder(value -> {
                    String v = value.trim();
                    if (t.color() && !v.matches("#[0-9a-fA-F]{6}")) {
                        invalid.add(t.path());
                        return;
                    }
                    if (t.path().equals("name") && v.isEmpty()) {
                        invalid.add(t.path());
                        return;
                    }
                    invalid.remove(t.path());
                    set(t.path(), t.optional() && v.isEmpty() ? null : new JsonPrimitive(v));
                });
            } else if (field instanceof NumberField n) {
                JsonElement e = get(effective, n.path());
                double value = e != null && e.isJsonPrimitive() ? e.getAsDouble() : n.fallback();
                EditBox box = box(y, formatNumber(value, n.whole()));
                box.setResponder(s -> {
                    try {
                        double v = Double.parseDouble(s.trim());
                        if (v < n.min() || v > n.max() || (n.whole() && v != Math.rint(v))) {
                            invalid.add(n.path());
                            return;
                        }
                        invalid.remove(n.path());
                        set(n.path(), n.whole() ? new JsonPrimitive((long) v) : new JsonPrimitive(v));
                    } catch (NumberFormatException ex) {
                        invalid.add(n.path());
                    }
                });
            }
        }
    }

    private EditBox box(int y, String value) {
        EditBox box = new EditBox(font, controlX() + 4, y + 4, controlW() - 8, 10, Component.empty());
        box.setBordered(false);
        box.setMaxLength(64);
        box.setTextColor(NpcUiTheme.TEXT);
        box.setValue(value);
        boxes.add(box);
        addRenderableWidget(box);
        return box;
    }

    private void addLocationBoxes() {
        String[] labels = {"X", "Y", "Z", "Facing (yaw)", "Head tilt (pitch)"};
        double[] values = {homeX, homeY, homeZ, yaw, pitch};
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            EditBox box = box(rowY(i), formatNumber(values[i], false));
            box.setResponder(s -> {
                try {
                    double v = Double.parseDouble(s.trim());
                    invalid.remove("location." + index);
                    switch (index) {
                        case 0 -> homeX = v;
                        case 1 -> homeY = v;
                        case 2 -> homeZ = v;
                        case 3 -> yaw = (float) Mth.wrapDegrees(v);
                        default -> pitch = (float) Mth.clamp(v, -90, 90);
                    }
                    dirty = true;
                    armedScope = -1;
                } catch (NumberFormatException ex) {
                    invalid.add("location." + index);
                }
            });
        }
    }

    private void updatePreview() {
        if (preview == null) {
            return;
        }
        String skin = textAt("appearance.skin_username", "");
        ArmStyle arms = switch (textAt("appearance.arms", "auto")) {
            case "wide" -> ArmStyle.WIDE;
            case "slim" -> ArmStyle.SLIM;
            default -> ArmStyle.AUTO;
        };
        String color = textAt("appearance.name_color", "#FFFFFF");
        JsonElement show = get(effective, "appearance.show_name");
        JsonElement scale = get(effective, "appearance.scale");
        String skinFile = textAt("appearance.skin_file", "");
        String model = textAt("appearance.model", "");
        Appearance look = new Appearance(skin.isBlank() ? Optional.empty() : Optional.of(skin),
                skinFile.isBlank() ? Optional.empty() : Optional.of(skinFile),
                model.isBlank() ? Optional.empty() : Optional.of(model), arms,
                color.matches("#[0-9a-fA-F]{6}") ? color : "#FFFFFF",
                show == null || show.getAsBoolean(), scale == null ? 1.0F : Mth.clamp(scale.getAsFloat(), 0.25F, 4.0F));
        preview.previewLook(data.template(), look, textAt("name", data.template()));
        preview.previewIdlePose(textAt("effects.idle_pose", ""));

        // A mob look shows that mob in the preview instead of the player model.
        if (model.isEmpty()) {
            previewMob = null;
            previewMobId = "";
        } else if (!model.equals(previewMobId) && minecraft.level != null) {
            previewMobId = model;
            previewMob = MobDisguises.create(minecraft.level, model, PREVIEW_ENTITY_ID - 1);
        }
    }

    private @Nullable LivingEntity previewMob;
    private String previewMobId = "";

    private String textAt(String path, String fallback) {
        JsonElement e = get(effective, path);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    // ---- items (gear and inventory) -----------------------------------------------------------------------

    private static final int INV_COLS = 9;
    private static final int INV_SLOTS = 27;
    private static final int ISLOT = 18;
    /** Gear or inventory changed, so saving re-equips and restocks the NPCs. */
    private boolean gearChanged;

    private com.mojang.serialization.DynamicOps<JsonElement> itemOps() {
        return minecraft.level.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
    }

    private ItemStack stackAt(String path) {
        JsonElement e = get(effective, path);
        if (e == null || minecraft.level == null) {
            return ItemStack.EMPTY;
        }
        return ItemStack.OPTIONAL_CODEC.parse(itemOps(), e).result().orElse(ItemStack.EMPTY);
    }

    private @Nullable JsonElement encode(ItemStack stack) {
        return stack.isEmpty() ? null : ItemStack.CODEC.encodeStart(itemOps(), stack).result().orElse(null);
    }

    private void setGear(GearSlotField field, ItemStack stack) {
        set(field.path(), encode(stack));
        gearChanged = true;
        if (preview != null) {
            preview.setItemSlot(field.slot(), stack.copy());
        }
    }

    private List<ItemStack> inventoryStacks() {
        List<ItemStack> stacks = new ArrayList<>();
        JsonElement e = get(effective, "inventory");
        if (e != null && e.isJsonArray() && minecraft.level != null) {
            for (JsonElement item : e.getAsJsonArray()) {
                ItemStack.OPTIONAL_CODEC.parse(itemOps(), item).result().filter(s -> !s.isEmpty()).ifPresent(stacks::add);
            }
        }
        return stacks;
    }

    private void setInventory(List<ItemStack> stacks) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (ItemStack stack : stacks) {
            JsonElement json = encode(stack);
            if (json != null) {
                array.add(json);
            }
        }
        set("inventory", array.isEmpty() ? null : array);
        gearChanged = true;
    }

    private int inventoryX() {
        return fieldsX();
    }

    private int inventoryY() {
        return rowY(0) + 2;
    }

    private int inventorySlotAt(double mx, double my) {
        if (mx < inventoryX() || mx >= inventoryX() + INV_COLS * ISLOT || my < inventoryY() || my >= inventoryY() + 3 * ISLOT) {
            return -1;
        }
        return (int) ((my - inventoryY()) / ISLOT) * INV_COLS + (int) ((mx - inventoryX()) / ISLOT);
    }

    private void openPicker(String title, java.util.function.Consumer<ItemStack> onPick) {
        minecraft.gui.setScreen(new ItemPickerScreen(this, Component.literal(title), onPick));
    }

    /** Inventory slot clicked: pick an item for it (keeping the amount when replacing), or remove with right-click. */
    private void clickInventory(int slot, boolean rightClick) {
        List<ItemStack> stacks = inventoryStacks();
        if (rightClick) {
            if (slot < stacks.size()) {
                stacks.remove(slot);
                setInventory(stacks);
            }
            return;
        }
        int index = Math.min(slot, stacks.size());
        if (index >= INV_SLOTS) {
            return;
        }
        int keepCount = index < stacks.size() ? stacks.get(index).getCount() : 1;
        openPicker("Inventory slot " + (index + 1), picked -> {
            List<ItemStack> now = inventoryStacks();
            if (picked.isEmpty()) {
                if (index < now.size()) {
                    now.remove(index);
                }
            } else {
                ItemStack stack = picked.copyWithCount(Math.min(keepCount, picked.getMaxStackSize()));
                if (index < now.size()) {
                    now.set(index, stack);
                } else {
                    now.add(stack);
                }
            }
            setInventory(now);
        });
    }

    // ---- buttons ------------------------------------------------------------------------------------------

    private record Button(int x, int y, int w, Component label, boolean enabled) {
        boolean contains(double mx, double my) {
            return enabled && mx >= x && mx < x + w && my >= y && my < y + BUTTON_H;
        }
    }

    private List<Button> footerButtons() {
        List<Button> buttons = new ArrayList<>();
        int right = panelX() + panelW() - PAD;
        int y = footerY();
        if (!choosingScope) {
            Component close = Component.literal(discardArmed() ? "Discard changes?" : "Close");
            int cw = font.width(close) + 16;
            buttons.add(new Button(right - cw, y, cw, close, true));
            Component save = Component.literal("Save…");
            int sw = font.width(save) + 20;
            buttons.add(new Button(right - cw - 6 - sw, y, sw, save, dirty));
            return buttons;
        }
        Component cancel = Component.literal("Cancel");
        int cw = font.width(cancel) + 16;
        buttons.add(new Button(right - cw, y, cw, cancel, true));
        boolean armedOnly = armed(SaveNpcPayload.ONLY_THIS);
        Component only = Component.literal(armedOnly ? "Confirm: only this NPC" : "Only this NPC");
        int ow = font.width(only) + 16;
        buttons.add(new Button(right - cw - 6 - ow, y, ow, only, true));
        boolean armedAll = armed(SaveNpcPayload.ALL);
        int users = Math.max(1, data.users());
        Component all = Component.literal(armedAll ? "Confirm: update " + users + " NPC" + (users == 1 ? "" : "s")
                : "All " + users + " using '" + data.template() + "'");
        int aw = font.width(all) + 16;
        buttons.add(new Button(right - cw - 6 - ow - 6 - aw, y, aw, all, true));
        return buttons;
    }

    private boolean armed(int scope) {
        return armedScope == scope && Util.getMillis() - armedAt < CONFIRM_MS;
    }

    private boolean discardArmed() {
        return discardArmedAt >= 0 && Util.getMillis() - discardArmedAt < CONFIRM_MS;
    }

    /** Toggle / choice / location-button hit areas for the current tab. */
    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private List<Hit> hits() {
        List<Hit> hits = new ArrayList<>();
        if (tab == Tab.LOCATION) {
            int y = rowY(5) + 4;
            hits.add(new Hit(fieldsX(), y, fieldsW() / 2 - 3, BUTTON_H, this::moveToPlayer));
            hits.add(new Hit(fieldsX() + fieldsW() / 2 + 3, y, fieldsW() / 2 - 3, BUTTON_H, this::faceThePlayer));
            return hits;
        }
        if (tab == Tab.DIALOGUE) {
            hits.add(new Hit(fieldsX(), rowY(3), fieldsW(), BUTTON_H + 2, this::openConversation));
            return hits;
        }
        if (tab == Tab.LIBRARY) {
            hits.add(new Hit(fieldsX(), rowY(5), fieldsW(), BUTTON_H + 2, () -> minecraft.gui.setScreen(new LibraryScreen(this))));
            return hits;
        }
        if (tab == Tab.QUESTS) {
            List<ContentPayloads.Entry> given = questsGiven();
            for (int i = 0; i < given.size(); i++) {
                String id = given.get(i).id();
                hits.add(new Hit(fieldsX(), rowY(i + 1), fieldsW(), ROW - 2, () -> QuestBuilderScreen.edit(this, id, null)));
            }
            int after = given.size() + 1;
            hits.add(new Hit(fieldsX(), rowY(after), fieldsW() / 2 - 3, ROW - 2,
                    () -> QuestBuilderScreen.create(this, data.template(), null)));
            hits.add(new Hit(fieldsX() + fieldsW() / 2 + 3, rowY(after), fieldsW() / 2 - 3, ROW - 2,
                    () -> minecraft.gui.setScreen(PickListScreen.single(this, "Edit a quest", PickListScreen.fromIndex(ClientContent.quests()),
                            null, null, null, id -> QuestBuilderScreen.edit(this, id, null)))));
            return hits;
        }
        if (tab == Tab.FACTIONS) {
            List<ContentPayloads.Entry> all = ClientContent.factions();
            int shown = Math.min(all.size() - factionScroll, factionRows());
            for (int i = 0; i < shown; i++) {
                String id = all.get(factionScroll + i).id();
                int y = rowY(i + 1);
                hits.add(new Hit(fieldsX(), y, fieldsW() - 40, ROW - 2, () -> toggleFaction(id)));
                hits.add(new Hit(fieldsX() + fieldsW() - 36, y, 36, ROW - 2, () -> FactionEditorScreen.edit(this, id)));
            }
            hits.add(new Hit(fieldsX(), rowY(shown + 1) + 2, fieldsW(), ROW - 2,
                    () -> FactionEditorScreen.create(this, id -> {
                        if (!memberOf().contains(id)) {
                            toggleFaction(id);
                        }
                    })));
            return hits;
        }
        List<Field> fields = fieldsFor(tab);
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            int y = rowY(i);
            if (field instanceof ToggleField t) {
                hits.add(new Hit(controlX(), y, controlW(), ROW - 2, () -> set(t.path(), new JsonPrimitive(!bool(t)))));
            } else if (field instanceof ChoiceField c) {
                hits.add(new Hit(controlX(), y, controlW(), ROW - 2, () -> cycle(c)));
            } else if (field instanceof SkinFileField s) {
                hits.add(new Hit(controlX(), y, controlW(), ROW - 2, () -> cycleSkinFile(s)));
            } else if (field instanceof ModelField) {
                hits.add(new Hit(controlX(), y, controlW(), ROW - 2, this::openMobPicker));
            } else if (field instanceof ButtonField b && b.action().equals("upload")) {
                hits.add(new Hit(fieldsX(), y, fieldsW(), ROW - 2, this::browseForSkin));
            } else if (field instanceof ButtonField b && b.action().equals("preview_emote")) {
                hits.add(new Hit(fieldsX(), y, fieldsW(), ROW - 2, this::previewNextEmote));
            } else if (field instanceof ParticleField p) {
                hits.add(new Hit(controlX(), y, controlW(), ROW - 2, () -> minecraft.gui.setScreen(PickListScreen.single(this,
                        p.label() + " particles", PickListScreen.particleOptions(), particleId(p), "No particles", null,
                        id -> setParticle(p, id)))));
            } else if (field instanceof AnimationField an) {
                hits.add(new Hit(controlX(), y, controlW(), ROW - 2, () -> minecraft.gui.setScreen(PickListScreen.single(this,
                        an.label(), PickListScreen.animationOptions(an.poses()), text(an, ""), "None", null,
                        id -> set(an.path(), id.isEmpty() ? null : new JsonPrimitive(id))))));
            } else if (field instanceof EmotesField e) {
                hits.add(new Hit(controlX(), y, controlW(), ROW - 2, () -> {
                    List<PickListScreen.Option> options = PickListScreen.animationOptions(false);
                    minecraft.gui.setScreen(PickListScreen.multi(this, "Emotes played now and then", options,
                            new java.util.LinkedHashSet<>(lines(e.path())), picked -> {
                                com.google.gson.JsonArray array = new com.google.gson.JsonArray();
                                for (PickListScreen.Option o : options) {
                                    if (picked.contains(o.id())) {
                                        array.add(o.id());
                                    }
                                }
                                set(e.path(), array);
                            }));
                }));
            } else if (field instanceof GearSlotField g) {
                hits.add(new Hit(controlX(), y, controlW(), ROW - 2,
                        () -> openPicker("Gear: " + g.label(), stack -> setGear(g, stack))));
            } else if (field instanceof SoundField s) {
                hits.add(new Hit(controlX(), y, controlW(), ROW - 2, () -> minecraft.gui.setScreen(PickListScreen.single(this,
                        s.label() + " (▶ to listen)", PickListScreen.soundOptions(), soundId(s), "No sound / default",
                        PickListScreen::playSound, id -> setSound(s, id)))));
            } else if (field instanceof LinesField l) {
                hits.add(new Hit(controlX(), y, controlW(), ROW - 2, () -> minecraft.gui.setScreen(new LinesScreen(this, l.title(),
                        lines(l), lines -> {
                            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
                            lines.forEach(array::add);
                            set(l.path(), array);
                        }))));
            }
        }
        return hits;
    }

    // ---- sounds and speech --------------------------------------------------------------------------------

    /** The sound's id ("" if none): the file may hold just an id or {"sound": id, "volume": …, "pitch": …}. */
    private String soundId(SoundField field) {
        JsonElement e = get(effective, field.path());
        if (e != null && e.isJsonObject()) {
            e = e.getAsJsonObject().get("sound");
        }
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** Picks a new sound, keeping its volume and pitch if it had them. */
    private void setSound(SoundField field, String id) {
        if (id.isEmpty()) {
            set(field.path(), null);
            return;
        }
        JsonElement current = get(raw, field.path());
        if (current == null) {
            current = get(effective, field.path());
        }
        if (current != null && current.isJsonObject()) {
            JsonObject copy = current.getAsJsonObject().deepCopy();
            copy.addProperty("sound", id);
            set(field.path(), copy);
        } else {
            set(field.path(), new JsonPrimitive(id));
        }
    }

    // ---- animations and particles -------------------------------------------------------------------------

    private int previewEmoteIndex;

    /** Plays the next one-shot emote on the preview model. */
    private void previewNextEmote() {
        List<com.npcmaker.data.Emote> emotes = com.npcmaker.data.Emote.oneShots();
        com.npcmaker.data.Emote emote = emotes.get(previewEmoteIndex++ % emotes.size());
        if (preview != null) {
            preview.playEmote(emote);
        }
        status = Component.literal("Preview: " + emote.label());
        statusColor = NpcUiTheme.BORDER_DIM;
    }

    private String particleId(ParticleField field) {
        JsonElement e = get(effective, field.path());
        if (e != null && e.isJsonObject()) {
            e = e.getAsJsonObject().get("particle");
        }
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** Picks particles, keeping an existing count/spread; a new aura starts light (one particle twice a second). */
    private void setParticle(ParticleField field, String id) {
        if (id.isEmpty()) {
            set(field.path(), null);
            return;
        }
        JsonElement current = get(raw, field.path());
        if (current == null) {
            current = get(effective, field.path());
        }
        if (current != null && current.isJsonObject()) {
            JsonObject copy = current.getAsJsonObject().deepCopy();
            copy.addProperty("particle", id);
            set(field.path(), copy);
        } else if (field.path().endsWith("aura")) {
            JsonObject aura = new JsonObject();
            aura.addProperty("particle", id);
            aura.addProperty("count", 1);
            aura.addProperty("interval", 10);
            set(field.path(), aura);
        } else {
            set(field.path(), new JsonPrimitive(id));
        }
    }

    private List<String> lines(LinesField field) {
        return lines(field.path());
    }

    private List<String> lines(String path) {
        List<String> list = new ArrayList<>();
        JsonElement e = get(effective, path);
        if (e != null && e.isJsonArray()) {
            e.getAsJsonArray().forEach(line -> list.add(line.getAsString()));
        }
        return list;
    }

    // ---- quests and factions ------------------------------------------------------------------------------

    private int factionScroll;

    /** Quests this NPC's template hands out (their "giver"). */
    private List<ContentPayloads.Entry> questsGiven() {
        return ClientContent.quests().stream().filter(q -> q.extra().equals(data.template())).limit(7).toList();
    }

    private int factionRows() {
        return Math.max(1, (bodyBottom() - rowY(1)) / ROW - 2);
    }

    private List<String> memberOf() {
        List<String> list = new ArrayList<>();
        JsonElement e = get(effective, "factions");
        if (e != null && e.isJsonArray()) {
            e.getAsJsonArray().forEach(f -> list.add(f.getAsString()));
        }
        return list;
    }

    private void toggleFaction(String id) {
        List<String> now = memberOf();
        if (!now.remove(id)) {
            now.add(id);
        }
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        now.forEach(array::add);
        set("factions", array);
    }

    /** Asks the server for this NPC's conversation; the graph opens when it arrives. */
    private void openConversation() {
        if (dirty) {
            status = Component.literal("Save (or discard) your NPC changes before editing the conversation.");
            statusColor = 0xFFE0A048;
            return;
        }
        status = Component.literal("Opening the conversation…");
        statusColor = NpcUiTheme.BORDER_DIM;
        NpcNetworking.sendToServer(new com.npcmaker.network.DialoguePayloads.Request(data.entityId()));
    }

    private void cycleSkinFile(SkinFileField field) {
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(ClientSkins.library());
        int index = options.indexOf(text(field, ""));
        String next = options.get((index + 1) % options.size());
        set(field.path(), next.isEmpty() ? null : new JsonPrimitive(next));
    }

    // ---- skin uploads -------------------------------------------------------------------------------------

    /** Kept alive until SDL calls back (it's native code holding a pointer to it). */
    private static @Nullable SDL_DialogFileCallback fileCallback;

    /** The system's file picker; SDL calls back (possibly on another thread) with the chosen file. */
    private void browseForSkin() {
        if (fileCallback != null) {
            return; // a picker is already open
        }
        fileCallback = SDL_DialogFileCallback.create((userdata, fileList, filter) -> {
            String chosen = null;
            if (fileList != 0L) {
                long first = MemoryUtil.memGetAddress(fileList);
                if (first != 0L) {
                    chosen = MemoryUtil.memUTF8(first);
                }
            }
            String path = chosen;
            Minecraft.getInstance().execute(() -> {
                SDL_DialogFileCallback done = fileCallback;
                fileCallback = null;
                if (done != null) {
                    done.free();
                }
                if (path != null && Minecraft.getInstance().gui.screen() instanceof NpcEditorScreen editor) {
                    editor.uploadSkin(Path.of(path));
                }
            });
        });
        SDLDialog.SDL_ShowOpenFileDialog(fileCallback, 0L, minecraft.getWindow().handle(),
                (SDL_DialogFileFilter.Buffer) null, (CharSequence) null, false);
        status = Component.literal("Choose a 64×64 skin PNG…");
        statusColor = NpcUiTheme.BORDER_DIM;
    }

    /** A PNG dragged onto the editor window. */
    @Override
    public void onFilesDrop(List<Path> files) {
        files.stream().filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                .findFirst()
                .ifPresentOrElse(this::uploadSkin, () -> {
                    status = Component.literal("Only PNG skin files can be dropped here.");
                    statusColor = 0xFFE04848;
                });
    }

    /** Checks the file here first (size and 64×64 / 64×32), then sends it to the server's skin library. */
    private void uploadSkin(Path file) {
        try {
            byte[] png = Files.readAllBytes(file);
            if (png.length > SkinLibrary.MAX_BYTES) {
                status = Component.literal("That file is too big; skins must be PNGs under 30 KB.");
                statusColor = 0xFFE04848;
                return;
            }
            try (NativeImage image = NativeImage.read(png)) {
                if (image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
                    status = Component.literal("Skins must be 64×64 pixels (this one is " + image.getWidth() + "×" + image.getHeight() + ").");
                    statusColor = 0xFFE04848;
                    return;
                }
            }
            NpcNetworking.sendToServer(new SkinPayloads.Upload(file.getFileName().toString(), png));
            status = Component.literal("Uploading " + file.getFileName() + "…");
            statusColor = NpcUiTheme.BORDER_DIM;
        } catch (Exception e) {
            status = Component.literal("Couldn't read that file as a PNG.");
            statusColor = 0xFFE04848;
        }
    }

    /** The library changed (e.g. our upload finished): select the new skin if there is one. */
    public static void skinLibraryChanged(String added) {
        if (!added.isEmpty() && Minecraft.getInstance().gui.screen() instanceof NpcEditorScreen editor) {
            editor.set("appearance.skin_file", new JsonPrimitive(added));
            editor.rebuildWidgets();
        }
    }

    // ---- mob picker ---------------------------------------------------------------------------------------

    private boolean pickingMob;
    private @Nullable List<MobDisguises.Option> mobOptions;
    private String mobSearch = "";
    private int mobScroll;
    private static final int MOB_ROW = 12;

    private void openMobPicker() {
        // Built fresh each time, so models just uploaded in the Library show up straight away.
        mobOptions = MobDisguises.options();
        pickingMob = true;
        mobSearch = "";
        mobScroll = 0;
        rebuildWidgets();
    }

    private void closeMobPicker() {
        pickingMob = false;
        rebuildWidgets();
    }

    private List<MobDisguises.Option> filteredMobs() {
        if (mobOptions == null) {
            return List.of();
        }
        String q = mobSearch.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) {
            return mobOptions;
        }
        return mobOptions.stream().filter(o -> o.name().getString().toLowerCase(Locale.ROOT).contains(q)
                || o.id().contains(q)).toList();
    }

    private int pickerTop() {
        return bodyTop() + 24;
    }

    private int pickerRows() {
        return Math.max(1, (bodyBottom() - 4 - pickerTop()) / MOB_ROW);
    }

    private String mobName(String typeId) {
        if (typeId.isEmpty()) {
            return "Player";
        }
        if (typeId.startsWith(com.npcmaker.custom.CustomContent.NAMESPACE + ":")) {
            return "★ " + typeId.substring(typeId.indexOf(':') + 1) + " (custom model)";
        }
        Identifier key = Identifier.tryParse(typeId);
        return key == null ? typeId : BuiltInRegistries.ENTITY_TYPE.getOptional(key)
                .map(t -> t.getDescription().getString()).orElse(typeId);
    }

    private void cycle(ChoiceField c) {
        String current = text(c, c.fallback());
        int index = c.options().indexOf(current);
        String next = c.options().get((index + 1) % c.options().size());
        set(c.path(), next.equals(c.noneValue()) ? null : new JsonPrimitive(next));
    }

    private void moveToPlayer() {
        Player player = minecraft.player;
        if (player == null) {
            return;
        }
        homeX = Math.round(player.getX() * 100) / 100.0;
        homeY = Math.round(player.getY() * 100) / 100.0;
        homeZ = Math.round(player.getZ() * 100) / 100.0;
        yaw = Mth.wrapDegrees(Math.round(player.getYRot()));
        pitch = 0;
        dirty = true;
        armedScope = -1;
        rebuildWidgets();
    }

    private void faceThePlayer() {
        Player player = minecraft.player;
        if (player == null) {
            return;
        }
        double dx = player.getX() - homeX;
        double dz = player.getZ() - homeZ;
        yaw = Mth.wrapDegrees((float) Math.round(Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        dirty = true;
        armedScope = -1;
        rebuildWidgets();
    }

    // ---- input --------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (help.click(mx, my)) {
            return true;
        }
        if (pickingMob) {
            boolean inPicker = mx >= fieldsX() && mx < fieldsX() + fieldsW() && my >= bodyTop() && my < bodyBottom();
            if (!inPicker) {
                closeMobPicker();
                return true;
            }
            if (my >= pickerTop()) {
                int index = mobScroll + (int) ((my - pickerTop()) / MOB_ROW);
                List<MobDisguises.Option> shown = filteredMobs();
                if (index < shown.size()) {
                    String id = shown.get(index).id();
                    set("appearance.model", id.isEmpty() ? null : new JsonPrimitive(id));
                    closeMobPicker();
                }
                return true;
            }
            return super.mouseClicked(event, doubleClick); // the search box
        }
        // 26.3 numbers mouse buttons the SDL way (left 1, right 3), so use the constant, not GLFW's old 1 = right.
        boolean rightClick = event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;
        if (tab == Tab.INVENTORY) {
            int slot = inventorySlotAt(mx, my);
            if (slot >= 0) {
                clickInventory(slot, rightClick);
                return true;
            }
        }
        if ((tab == Tab.GEAR || tab == Tab.SOUNDS || tab == Tab.ANIMATION) && rightClick) {
            List<Field> fields = fieldsFor(tab);
            for (int i = 0; i < fields.size(); i++) {
                boolean onControl = mx >= controlX() && mx < controlX() + controlW() && my >= rowY(i) && my < rowY(i) + ROW - 2;
                if (onControl && fields.get(i) instanceof GearSlotField g) {
                    setGear(g, ItemStack.EMPTY);
                    return true;
                }
                if (onControl && fields.get(i) instanceof SoundField s) {
                    setSound(s, "");
                    return true;
                }
                if (onControl && fields.get(i) instanceof ParticleField p) {
                    setParticle(p, "");
                    return true;
                }
                if (onControl && fields.get(i) instanceof AnimationField an) {
                    set(an.path(), null);
                    return true;
                }
            }
        }
        // tabs
        for (Tab t : Tab.values()) {
            int ty = bodyTop() + 2 + t.ordinal() * tabStep();
            if (t.ready() && mx >= panelX() + 4 && mx < panelX() + TABS_W - 4 && my >= ty && my < ty + tabH()) {
                if (t != tab) {
                    tab = t;
                    rebuildWidgets();
                }
                return true;
            }
        }
        if (tab == Tab.RULES && rulesPanel.contains(mx, my) && rulesPanel.click(mx, my, event.button())) {
            return true;
        }
        for (Hit hit : hits()) {
            if (hit.contains(mx, my)) {
                hit.action().run();
                return true;
            }
        }
        for (Button b : footerButtons()) {
            if (b.contains(mx, my)) {
                pressFooter(b);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void pressFooter(Button b) {
        String label = b.label().getString();
        if (label.equals("Close") || label.equals("Discard changes?")) {
            tryClose();
        } else if (label.equals("Save…")) {
            if (!invalid.isEmpty()) {
                status = Component.literal("Fix the fields shown in red first.");
                statusColor = 0xFFE04848;
                return;
            }
            choosingScope = true;
            armedScope = -1;
            status = Component.literal("Update every NPC using this template, or only this one? Click twice to confirm.");
            statusColor = NpcUiTheme.ACCENT;
        } else if (label.equals("Cancel")) {
            choosingScope = false;
            armedScope = -1;
            status = Component.empty();
        } else {
            int scope = label.contains("only this") || label.startsWith("Only") ? SaveNpcPayload.ONLY_THIS : SaveNpcPayload.ALL;
            if (armed(scope)) {
                NpcNetworking.sendToServer(new SaveNpcPayload(data.entityId(), data.template(), raw.toString(), scope,
                        new Vec3(homeX, homeY, homeZ), yaw, pitch, gearChanged));
                choosingScope = false;
                armedScope = -1;
                status = Component.literal("Saving…");
                statusColor = NpcUiTheme.BORDER_DIM;
            } else {
                armedScope = scope;
                armedAt = Util.getMillis();
            }
        }
    }

    private void tryClose() {
        if (dirty && !discardArmed()) {
            discardArmedAt = Util.getMillis();
            status = Component.literal("Unsaved changes. Close again to discard them.");
            statusColor = 0xFFE0A048;
            return;
        }
        super.onClose();
    }

    @Override
    public void onClose() {
        tryClose();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (tab == Tab.RULES) {
            rulesPanel.drag(event.y());
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (tab == Tab.RULES && rulesPanel.release()) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() && help.escape()) {
            return true;
        }
        if (pickingMob && event.isEscape()) {
            closeMobPicker();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (pickingMob) {
            int max = Math.max(0, filteredMobs().size() - pickerRows());
            mobScroll = Mth.clamp(mobScroll - (int) Math.signum(scrollY) * 3, 0, max);
            return true;
        }
        if (tab == Tab.RULES && rulesPanel.contains(x, y)) {
            return rulesPanel.scroll(scrollY);
        }
        if (tab == Tab.FACTIONS) {
            int max = Math.max(0, ClientContent.factions().size() - factionRows());
            factionScroll = Mth.clamp(factionScroll - (int) Math.signum(scrollY), 0, max);
            return true;
        }
        if (tab == Tab.INVENTORY) {
            int slot = inventorySlotAt(x, y);
            List<ItemStack> stacks = inventoryStacks();
            if (slot >= 0 && slot < stacks.size()) {
                ItemStack stack = stacks.get(slot);
                int step = minecraft.hasShiftDown() ? 8 : 1;
                stack.setCount(Mth.clamp(stack.getCount() + (int) Math.signum(scrollY) * step, 1, stack.getMaxStackSize()));
                setInventory(stacks);
                return true;
            }
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Vanilla mob looks in the preview are drawn by vanilla, so they need their own clock to animate while idle. */
    @Override
    public void tick() {
        super.tick();
        if (previewMob != null) {
            previewMob.tickCount++;
        }
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int x = panelX();
        int y = panelY();
        int w = panelW();
        int h = panelH();
        help.begin();
        graphics.fill(x, y, x + w, y + h, NpcUiTheme.PANEL);
        graphics.outline(x, y, w, h, NpcUiTheme.BORDER);

        // header: title, template and the (?) help circle
        graphics.text(font, Component.literal("Edit NPC"), x + PAD, y + 9, NpcUiTheme.ACCENT, false);
        help.button(x + w - PAD - 12, y + 7);
        String info = "template '" + data.template() + "' · " + data.users() + " NPC" + (data.users() == 1 ? "" : "s");
        int infoX = x + w - PAD - 18 - font.width(info);
        graphics.text(font, info, infoX, y + 9, NpcUiTheme.BORDER_DIM, false);
        help.spot(infoX - 2, y + 6, font.width(info) + 4, 13, "editor.template");
        graphics.fill(x + 4, bodyTop() - 2, x + w - 4, bodyTop() - 1, NpcUiTheme.BORDER_DIM);

        drawTabs(graphics, mouseX, mouseY);
        drawFields(graphics, mouseX, mouseY);
        if (tab != Tab.RULES) {
            drawPreview(graphics, mouseX, mouseY);
        }

        // footer
        graphics.fill(x + 4, bodyBottom() + 1, x + w - 4, bodyBottom() + 2, NpcUiTheme.BORDER_DIM);
        List<Button> buttons = footerButtons();
        int leftmost = buttons.stream().mapToInt(Button::x).min().orElse(x + w);
        String shown = font.plainSubstrByWidth(status.getString(), Math.max(0, leftmost - x - PAD * 2));
        graphics.text(font, shown, x + PAD, footerY() + 3, statusColor, false);
        for (Button b : buttons) {
            boolean hover = b.contains(mouseX, mouseY);
            boolean armedLook = b.label().getString().startsWith("Confirm");
            graphics.fill(b.x(), b.y(), b.x() + b.w(), b.y() + BUTTON_H, NpcUiTheme.PANEL_LIGHT);
            graphics.outline(b.x(), b.y(), b.w(), BUTTON_H, armedLook || hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            int color = !b.enabled() ? 0xFF6E6578 : armedLook ? NpcUiTheme.ACCENT : hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT;
            graphics.centeredText(font, b.label(), b.x() + b.w() / 2, b.y() + 3, color);
            String label = b.label().getString();
            String key = label.equals("Save…") ? "editor.save" : label.equals("Close") || label.startsWith("Discard") ? "editor.close"
                    : label.equals("Cancel") ? "editor.cancel" : label.contains("nly") ? "editor.scope.only" : "editor.scope.all";
            help.spot(b.x(), b.y(), b.w(), BUTTON_H, key);
        }
        if (tab != Tab.RULES && !pickingMob) {
            help.spot(previewX(), bodyTop() + 2, PREVIEW_W - 6, bodyBottom() - bodyTop() - 4, "editor.preview");
        }
        if (tab == Tab.RULES) {
            rulesPanel.help(help);
        }

        super.extractRenderState(graphics, mouseX, mouseY, a); // the text boxes, on top of their frames
        help.render(graphics, font, mouseX, mouseY, width, height);
    }

    private void drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = panelX() + 4;
        int w = TABS_W - 8;
        for (Tab t : Tab.values()) {
            int ty = bodyTop() + 2 + t.ordinal() * tabStep();
            boolean selected = t == tab;
            boolean hover = t.ready() && mouseX >= x && mouseX < x + w && mouseY >= ty && mouseY < ty + tabH();
            if (selected || hover) {
                graphics.fill(x, ty, x + w, ty + tabH(), NpcUiTheme.PANEL_INSET);
                graphics.outline(x, ty, w, tabH(), selected ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            }
            int color = !t.ready() ? 0xFF6E6578 : selected ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT;
            graphics.text(font, t.label, x + 6, ty + (tabH() - 7) / 2, color, false);
            help.spot(x, ty, w, tabH(), "editor.tab." + t.name().toLowerCase(Locale.ROOT));
        }
        graphics.fill(panelX() + TABS_W, bodyTop() + 2, panelX() + TABS_W + 1, bodyBottom() - 2, NpcUiTheme.BORDER_DIM);
    }

    private void drawInventory(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<ItemStack> stacks = inventoryStacks();
        int x0 = inventoryX();
        int y0 = inventoryY();
        int hovered = inventorySlotAt(mouseX, mouseY);
        help.spot(x0, y0, INV_COLS * ISLOT, 3 * ISLOT, "editor.inventory.grid");
        for (int i = 0; i < INV_SLOTS; i++) {
            int sx = x0 + (i % INV_COLS) * ISLOT;
            int sy = y0 + (i / INV_COLS) * ISLOT;
            graphics.fill(sx, sy, sx + ISLOT - 1, sy + ISLOT - 1, NpcUiTheme.PANEL_INSET);
            graphics.outline(sx, sy, ISLOT - 1, ISLOT - 1, i == hovered ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
            if (i < stacks.size()) {
                graphics.item(stacks.get(i), sx, sy);
                graphics.itemDecorations(font, stacks.get(i), sx, sy);
            }
        }
        int ty = y0 + 3 * ISLOT + 6;
        for (String line : List.of("Click a slot to pick an item; right-click removes it.",
                "Scroll over an item to change the amount (Shift: by 8).",
                "NPCs drink potions and milk, eat food, swap weapons,",
                "use arrows and place torches from here.")) {
            graphics.text(font, font.plainSubstrByWidth(line, fieldsW()), x0, ty, 0xFF8F8A80, false);
            ty += 10;
        }
        if (hovered >= 0 && hovered < stacks.size()) {
            help.quiet();
            graphics.setTooltipForNextFrame(font, stacks.get(hovered), mouseX, mouseY);
        }
    }

    private void drawMobPicker(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = fieldsX();
        int w = fieldsW();
        help.spot(x, bodyTop() + 4, w, bodyBottom() - bodyTop() - 4, "editor.mobpicker");
        graphics.fill(x, bodyTop() + 4, x + w, bodyTop() + 20, NpcUiTheme.PANEL_INSET);
        graphics.outline(x, bodyTop() + 4, w, 16, NpcUiTheme.BORDER);
        List<MobDisguises.Option> shown = filteredMobs();
        String current = textAt("appearance.model", "");
        int end = Math.min(shown.size(), mobScroll + pickerRows());
        for (int i = mobScroll; i < end; i++) {
            MobDisguises.Option option = shown.get(i);
            int ry = pickerTop() + (i - mobScroll) * MOB_ROW;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + MOB_ROW;
            boolean selected = option.id().equals(current);
            if (hover || selected) {
                graphics.fill(x, ry, x + w, ry + MOB_ROW, NpcUiTheme.PANEL_INSET);
                if (selected) {
                    graphics.outline(x, ry, w, MOB_ROW, NpcUiTheme.BORDER_DIM);
                }
            }
            String name = font.plainSubstrByWidth(option.name().getString(), w - 8);
            graphics.text(font, name, x + 4, ry + 2, selected ? NpcUiTheme.ACCENT : hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT, false);
        }
        if (shown.isEmpty()) {
            graphics.text(font, "No mob matches.", x + 4, pickerTop() + 2, NpcUiTheme.BORDER_DIM, false);
        }
        String hint = shown.size() + " mobs · scroll · Esc to cancel";
        graphics.text(font, hint, x + w - font.width(hint), bodyBottom() - 10, 0xFF6E6578, false);
    }

    private void drawFields(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!tab.ready()) {
            return;
        }
        if (pickingMob) {
            drawMobPicker(graphics, mouseX, mouseY);
            return;
        }
        if (tab == Tab.INVENTORY) {
            drawInventory(graphics, mouseX, mouseY);
            return;
        }
        if (tab == Tab.RULES) {
            rulesPanel.draw(graphics, mouseX, mouseY);
            return;
        }
        if (tab == Tab.LOCATION) {
            String[] labels = {"X", "Y", "Z", "Facing (yaw)", "Head tilt (pitch)"};
            for (int i = 0; i < labels.length; i++) {
                drawLabel(graphics, labels[i], rowY(i));
                drawBoxFrame(graphics, rowY(i), invalid.contains("location." + i));
                help.spot(fieldsX(), rowY(i), fieldsW(), ROW - 2, "editor.location." + new String[]{"x", "y", "z", "yaw", "pitch"}[i]);
            }
            List<Hit> hits = hits();
            drawHitButton(graphics, hits.get(0), "Move here", mouseX, mouseY);
            drawHitButton(graphics, hits.get(1), "Face me", mouseX, mouseY);
            helpHit(hits.get(0), "editor.location.move");
            helpHit(hits.get(1), "editor.location.face");
            graphics.text(font, "Position and facing are always this NPC's own.", fieldsX(), rowY(6) + 8, NpcUiTheme.BORDER_DIM, false);
            return;
        }
        if (tab == Tab.QUESTS) {
            List<ContentPayloads.Entry> given = questsGiven();
            graphics.text(font, given.isEmpty() ? "This NPC doesn't give any quests yet." : "Quests this NPC gives (click to edit):",
                    fieldsX(), rowY(0) + 4, 0xFFBFB3A0, false);
            List<Hit> hits = hits();
            for (int i = 0; i < given.size(); i++) {
                drawHitButton(graphics, hits.get(i), "! " + given.get(i).label(), mouseX, mouseY);
                helpHit(hits.get(i), "editor.quests.row");
            }
            drawHitButton(graphics, hits.get(given.size()), "+ New quest…", mouseX, mouseY);
            drawHitButton(graphics, hits.get(given.size() + 1), "Edit another…", mouseX, mouseY);
            helpHit(hits.get(given.size()), "editor.quests.new");
            helpHit(hits.get(given.size() + 1), "editor.quests.other");
            int ty = rowY(given.size() + 2) + 6;
            for (String line : List.of("Hand quests out from the conversation: in the",
                    "dialogue graph, give a reply \"Offer quest card\"",
                    "and pick or make the quest right there.")) {
                graphics.text(font, font.plainSubstrByWidth(line, fieldsW()), fieldsX(), ty, 0xFF8F8A80, false);
                ty += 10;
            }
            return;
        }
        if (tab == Tab.FACTIONS) {
            List<ContentPayloads.Entry> all = ClientContent.factions();
            List<String> member = memberOf();
            graphics.text(font, all.isEmpty() ? "No factions yet." : "Tick the factions this NPC belongs to:",
                    fieldsX(), rowY(0) + 4, 0xFFBFB3A0, false);
            List<Hit> hits = hits();
            int shown = Math.min(all.size() - factionScroll, factionRows());
            for (int i = 0; i < shown; i++) {
                ContentPayloads.Entry f = all.get(factionScroll + i);
                Hit toggle = hits.get(i * 2);
                Hit edit = hits.get(i * 2 + 1);
                boolean on = member.contains(f.id());
                boolean hover = toggle.contains(mouseX, mouseY);
                graphics.fill(toggle.x(), toggle.y(), toggle.x() + toggle.w(), toggle.y() + toggle.h(), on ? NpcUiTheme.PANEL_INSET : NpcUiTheme.PANEL_LIGHT);
                graphics.outline(toggle.x(), toggle.y(), toggle.w(), toggle.h(), hover || on ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
                graphics.text(font, on ? "☑" : "☐", toggle.x() + 4, toggle.y() + 4, NpcUiTheme.ACCENT, false);
                int rgb = parseColor(f.extra());
                graphics.fill(toggle.x() + 16, toggle.y() + 4, toggle.x() + 23, toggle.y() + 11, 0xFF000000 | rgb);
                String name = font.plainSubstrByWidth(f.label(), toggle.w() - 32);
                graphics.text(font, name, toggle.x() + 27, toggle.y() + 4, on ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT, false);
                drawHitButton(graphics, edit, "Edit", mouseX, mouseY);
                helpHit(toggle, "editor.factions.row");
                helpHit(edit, "editor.factions.edit");
            }
            drawHitButton(graphics, hits.get(shown * 2), "+ Create faction…", mouseX, mouseY);
            helpHit(hits.get(shown * 2), "editor.factions.create");
            if (all.size() > factionRows()) {
                String more = "scroll for more (" + all.size() + ")";
                graphics.text(font, more, fieldsX() + fieldsW() - font.width(more), bodyBottom() - 10, 0xFF6E6578, false);
            }
            return;
        }
        if (tab == Tab.LIBRARY) {
            graphics.text(font, "Custom content on this server:", fieldsX(), rowY(0) + 4, 0xFFBFB3A0, false);
            String[] kinds = {"sound", "particle", "model", "animation"};
            String[] labels = {"Sounds", "Particles", "Models", "Animation files"};
            for (int i = 0; i < kinds.length; i++) {
                int count = ClientCustom.names(kinds[i]).size();
                drawLabel(graphics, labels[i], rowY(i + 1));
                String shown = kinds[i].equals("particle") ? count + " of " + com.npcmaker.custom.CustomContent.PARTICLE_SLOTS : String.valueOf(count);
                graphics.text(font, shown, controlX() + 5, rowY(i + 1) + 4, NpcUiTheme.TEXT, false);
                help.spot(fieldsX(), rowY(i + 1), fieldsW(), ROW - 2, "library.kind." + kinds[i]);
            }
            drawHitButton(graphics, hits().get(0), "Open the custom content library…", mouseX, mouseY);
            helpHit(hits().get(0), "editor.library.open");
            int ty = rowY(7);
            for (String line : List.of("Upload your own sounds, particle pictures,",
                    "Blockbench models and animations. Everyone",
                    "on the server gets them automatically.")) {
                graphics.text(font, font.plainSubstrByWidth(line, fieldsW()), fieldsX(), ty, 0xFF8F8A80, false);
                ty += 10;
            }
            return;
        }
        if (tab == Tab.DIALOGUE) {
            String conversation = textAt("dialogue", "");
            drawLabel(graphics, "Conversation", rowY(0));
            graphics.text(font, conversation.isEmpty() ? "none yet (greeting only)" : conversation,
                    controlX() + 5, rowY(0) + 4, conversation.isEmpty() ? 0xFF8F8A80 : NpcUiTheme.ACCENT, false);
            JsonElement greeting = get(effective, "greeting");
            int lines = greeting != null && greeting.isJsonArray() ? greeting.getAsJsonArray().size() : 0;
            drawLabel(graphics, "Greeting lines", rowY(1));
            graphics.text(font, String.valueOf(lines), controlX() + 5, rowY(1) + 4, NpcUiTheme.TEXT, false);
            drawHitButton(graphics, hits().get(0), conversation.isEmpty() ? "Create a conversation…" : "Open conversation graph…",
                    mouseX, mouseY);
            help.spot(fieldsX(), rowY(0), fieldsW(), ROW - 2, "editor.dialogue.conversation");
            helpHit(hits().get(0), "editor.dialogue.open");
            int ty = rowY(5);
            for (String line : List.of("Boxes are what the NPC says; arrows are the",
                    "player's replies. Set starts, ends and loops,",
                    "and give rewards from a reply's \"Do\" list.")) {
                graphics.text(font, font.plainSubstrByWidth(line, fieldsW()), fieldsX(), ty, 0xFF8F8A80, false);
                ty += 10;
            }
            return;
        }
        List<Field> fields = fieldsFor(tab);
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            int y = rowY(i);
            drawLabel(graphics, field.label(), y);
            help.spot(fieldsX(), y, fieldsW(), ROW - 2, helpKey(field),
                    field instanceof ChoiceField c ? helpKey(field) + "." + text(c, c.fallback())
                            : field instanceof AnimationField an ? helpKey(field) + "." + (text(an, "").isEmpty() ? "none" : text(an, "")) : null);
            switch (field) {
                case TextField t -> {
                    drawBoxFrame(graphics, y, invalid.contains(t.path()));
                    if (t.color() && !invalid.contains(t.path())) {
                        int rgb = Integer.parseInt(text(t, "#FFFFFF").substring(1), 16);
                        graphics.fill(controlX() + controlW() - 12, y + 3, controlX() + controlW() - 3, y + 12, 0xFF000000 | rgb);
                    }
                }
                case NumberField n -> drawBoxFrame(graphics, y, invalid.contains(n.path()));
                case ToggleField t -> {
                    boolean on = bool(t);
                    drawControl(graphics, y, mouseX, mouseY);
                    graphics.text(font, on ? "✔ Yes" : "✖ No", controlX() + 5, y + 4, on ? 0xFF7FD36A : 0xFFBBBBBB, false);
                }
                case ChoiceField c -> {
                    drawControl(graphics, y, mouseX, mouseY);
                    String value = text(c, c.fallback());
                    graphics.text(font, capitalise(value), controlX() + 5, y + 4, NpcUiTheme.TEXT, false);
                    graphics.text(font, "▸", controlX() + controlW() - 10, y + 4, NpcUiTheme.ACCENT, false);
                }
                case SkinFileField s -> {
                    drawControl(graphics, y, mouseX, mouseY);
                    String value = text(s, "");
                    String shown = value.isEmpty() ? "None" : font.plainSubstrByWidth(value, controlW() - 18);
                    graphics.text(font, shown, controlX() + 5, y + 4, value.isEmpty() ? 0xFFBBBBBB : NpcUiTheme.TEXT, false);
                    graphics.text(font, "▸", controlX() + controlW() - 10, y + 4, NpcUiTheme.ACCENT, false);
                }
                case ModelField m -> {
                    drawControl(graphics, y, mouseX, mouseY);
                    String shown = font.plainSubstrByWidth(mobName(text(m, "")), controlW() - 18);
                    graphics.text(font, shown, controlX() + 5, y + 4, NpcUiTheme.TEXT, false);
                    graphics.text(font, "…", controlX() + controlW() - 10, y + 4, NpcUiTheme.ACCENT, false);
                }
                case GearSlotField g -> {
                    drawControl(graphics, y, mouseX, mouseY);
                    ItemStack stack = stackAt(g.path());
                    if (stack.isEmpty()) {
                        graphics.text(font, "Empty", controlX() + 5, y + 4, 0xFF8F8A80, false);
                    } else {
                        graphics.item(stack, controlX() + 1, y);
                        String name = font.plainSubstrByWidth(stack.getHoverName().getString(), controlW() - 24);
                        graphics.text(font, name, controlX() + 20, y + 4, NpcUiTheme.TEXT, false);
                        if (mouseX >= controlX() && mouseX < controlX() + 18 && mouseY >= y && mouseY < y + 16) {
                            help.quiet();
                            graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
                        }
                    }
                }
                case SoundField s -> {
                    drawControl(graphics, y, mouseX, mouseY);
                    String id = soundId(s);
                    String shown = id.isEmpty() ? "Default" : id.startsWith("minecraft:") ? id.substring(10) : id;
                    graphics.text(font, font.plainSubstrByWidth(shown, controlW() - 18), controlX() + 5, y + 4,
                            id.isEmpty() ? 0xFF8F8A80 : NpcUiTheme.TEXT, false);
                    graphics.text(font, "♪", controlX() + controlW() - 10, y + 4, NpcUiTheme.ACCENT, false);
                }
                case ParticleField p -> {
                    drawControl(graphics, y, mouseX, mouseY);
                    String id = particleId(p);
                    String shown = id.isEmpty() ? "None" : id.startsWith("minecraft:") ? id.substring(10) : id;
                    graphics.text(font, font.plainSubstrByWidth(shown, controlW() - 18), controlX() + 5, y + 4,
                            id.isEmpty() ? 0xFF8F8A80 : NpcUiTheme.TEXT, false);
                    graphics.text(font, "✦", controlX() + controlW() - 10, y + 4, NpcUiTheme.ACCENT, false);
                }
                case AnimationField an -> {
                    drawControl(graphics, y, mouseX, mouseY);
                    String id = text(an, "");
                    com.npcmaker.data.Emote builtIn = com.npcmaker.data.Emote.byId(id);
                    String shown = id.isEmpty() ? "None" : builtIn != null ? builtIn.label()
                            : "★ " + id.substring(id.indexOf(':') + 1);
                    graphics.text(font, font.plainSubstrByWidth(shown, controlW() - 18), controlX() + 5, y + 4,
                            id.isEmpty() ? 0xFF8F8A80 : NpcUiTheme.TEXT, false);
                    graphics.text(font, "…", controlX() + controlW() - 10, y + 4, NpcUiTheme.ACCENT, false);
                }
                case EmotesField e -> {
                    drawControl(graphics, y, mouseX, mouseY);
                    List<String> picked = lines(e.path());
                    String shown = picked.isEmpty() ? "None" : String.join(", ", picked);
                    graphics.text(font, font.plainSubstrByWidth(shown, controlW() - 18), controlX() + 5, y + 4,
                            picked.isEmpty() ? 0xFF8F8A80 : NpcUiTheme.TEXT, false);
                    graphics.text(font, "…", controlX() + controlW() - 10, y + 4, NpcUiTheme.ACCENT, false);
                }
                case LinesField l -> {
                    drawControl(graphics, y, mouseX, mouseY);
                    int count = lines(l).size();
                    graphics.text(font, count == 0 ? "None" : count + " line" + (count == 1 ? "" : "s"), controlX() + 5, y + 4,
                            count == 0 ? 0xFF8F8A80 : NpcUiTheme.TEXT, false);
                    graphics.text(font, "✎", controlX() + controlW() - 10, y + 4, NpcUiTheme.ACCENT, false);
                }
                case ButtonField b -> {
                    boolean hover = mouseX >= fieldsX() && mouseX < fieldsX() + fieldsW() && mouseY >= y && mouseY < y + ROW - 2;
                    graphics.fill(fieldsX(), y, fieldsX() + fieldsW(), y + ROW - 2, NpcUiTheme.PANEL_LIGHT);
                    graphics.outline(fieldsX(), y, fieldsW(), ROW - 2, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
                    String label = font.plainSubstrByWidth(b.button(), fieldsW() - 8);
                    graphics.text(font, label, fieldsX() + (fieldsW() - font.width(label)) / 2, y + 4,
                            hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT, false);
                }
            }
        }
    }

    private void helpHit(Hit hit, String key) {
        help.spot(hit.x(), hit.y(), hit.w(), hit.h(), key);
    }

    /** The help text key for a field: its path, or a shared key for gear slots and buttons. */
    private static String helpKey(Field field) {
        return switch (field) {
            case GearSlotField g -> "field.gear_slot";
            case ButtonField b -> "field." + b.action();
            default -> "field." + field.path();
        };
    }

    private static int parseColor(String hex) {
        try {
            return Integer.parseInt(hex.startsWith("#") ? hex.substring(1) : hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void drawLabel(GuiGraphicsExtractor graphics, String label, int y) {
        graphics.text(font, label, fieldsX(), y + 4, 0xFFBFB3A0, false);
    }

    private void drawBoxFrame(GuiGraphicsExtractor graphics, int y, boolean bad) {
        graphics.fill(controlX(), y, controlX() + controlW(), y + ROW - 2, NpcUiTheme.PANEL_INSET);
        graphics.outline(controlX(), y, controlW(), ROW - 2, bad ? 0xFFE04848 : NpcUiTheme.BORDER_DIM);
    }

    private void drawControl(GuiGraphicsExtractor graphics, int y, int mouseX, int mouseY) {
        boolean hover = mouseX >= controlX() && mouseX < controlX() + controlW() && mouseY >= y && mouseY < y + ROW - 2;
        graphics.fill(controlX(), y, controlX() + controlW(), y + ROW - 2, NpcUiTheme.PANEL_LIGHT);
        graphics.outline(controlX(), y, controlW(), ROW - 2, hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
    }

    private void drawHitButton(GuiGraphicsExtractor graphics, Hit hit, String label, int mouseX, int mouseY) {
        boolean hover = hit.contains(mouseX, mouseY);
        graphics.fill(hit.x(), hit.y(), hit.x() + hit.w(), hit.y() + hit.h(), NpcUiTheme.PANEL_LIGHT);
        graphics.outline(hit.x(), hit.y(), hit.w(), hit.h(), hover ? NpcUiTheme.BORDER : NpcUiTheme.BORDER_DIM);
        graphics.centeredText(font, Component.literal(label), hit.x() + hit.w() / 2, hit.y() + 3,
                hover ? NpcUiTheme.TEXT_HOVER : NpcUiTheme.TEXT);
    }

    private void drawPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = previewX();
        int top = bodyTop() + 2;
        int bottom = bodyBottom() - 16;
        graphics.fill(x, top, x + PREVIEW_W - 6, bottom + 14, NpcUiTheme.PANEL_INSET);
        graphics.outline(x, top, PREVIEW_W - 6, bottom + 14 - top, NpcUiTheme.BORDER_DIM);
        LivingEntity shown3d = previewMob != null ? previewMob : preview;
        if (shown3d != null) {
            // Fit the whole mob in the box, whether it's a bat or a ghast.
            float extent = Math.max(shown3d.getBbHeight(), shown3d.getBbWidth() * 1.2F);
            int size = (int) Mth.clamp((bottom - top - 8) / Math.max(0.4F, extent) * 0.85F, 6, 200);
            // The box's own centre as the "mouse", so the model faces straight ahead and holds still for poses.
            float centreX = (x + 2 + x + PREVIEW_W - 8) / 2.0F;
            float centreY = (top + 2 + bottom) / 2.0F;
            InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x + 2, top + 2, x + PREVIEW_W - 8, bottom,
                    size, 0.0625F, centreX, centreY, shown3d);
        }
        // the name as it will show over the NPC's head
        Component name = Component.literal(textAt("name", data.template()));
        String color = textAt("appearance.name_color", "#FFFFFF");
        int rgb = color.matches("#[0-9a-fA-F]{6}") ? Integer.parseInt(color.substring(1), 16) : 0xFFFFFF;
        String shown = font.plainSubstrByWidth(name.getString(), PREVIEW_W - 12);
        graphics.text(font, shown, x + (PREVIEW_W - 6 - font.width(shown)) / 2, bottom + 2, 0xFF000000 | rgb, true);
    }
}
